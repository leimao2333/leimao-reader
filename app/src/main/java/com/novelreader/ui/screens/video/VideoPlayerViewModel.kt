package com.novelreader.ui.screens.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.novelreader.domain.model.MediaItem as NovelMediaItem

/**
 * 播放器实时状态（供自定义控制层订阅）。
 */
data class VideoPlaybackState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 常规倍速（用户选择） */
    val speed: Float = 1f,
    /** 长按加速中的倍速；0 表示未加速 */
    val boostSpeed: Float = 0f,
    /** 长按方向：+1 快进，-1 快退，0 无 */
    val boostDirection: Int = 0,
)

/**
 * 播放器相关用户偏好（DataStore 映射，供 UI 直接读取）。
 */
data class VideoPrefs(
    val fullscreenDefault: Boolean = false,
    val longPressSpeed: Float = 3f,
    val doubleTapSeekSec: Int = 10,
    val savedSpeed: Float = 1f,
    val autoNext: Boolean = false,
    /** 是否已完成首次读取（UI 依据此判断能否应用"默认全屏"） */
    val loaded: Boolean = false,
)

/**
 * 视频播放 ViewModel。
 *
 * 管理 Media3 ExoPlayer 实例、播放状态、视频目录与进度，
 * 并为 v1.3.2.0 的播放器交互（全屏 / 长按倍速 / 手势 / 连播）提供状态来源。
 */
@UnstableApi
class VideoPlayerViewModel(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    /** ExoPlayer 实例（由 UI 层绑定到 PlayerView） */
    var player: ExoPlayer? = null
        private set

    private val _state = MutableStateFlow(VideoPlaybackState())
    val state: StateFlow<VideoPlaybackState> = _state.asStateFlow()

    private val _prefs = MutableStateFlow(VideoPrefs())
    val prefs: StateFlow<VideoPrefs> = _prefs.asStateFlow()

    /** 当前视频地址 */
    private val _videoUrl = MutableStateFlow<String?>(null)
    val videoUrl: StateFlow<String?> = _videoUrl.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _episodes = MutableStateFlow<List<ChapterItem>>(emptyList())
    val episodes: StateFlow<List<ChapterItem>> = _episodes.asStateFlow()

    /** 当前选集标题 */
    private val _episodeTitle = MutableStateFlow("")
    val episodeTitle: StateFlow<String> = _episodeTitle.asStateFlow()

    /** 当前选集的 url（用于在选集列表中高亮） */
    private val _currentEpisodeUrl = MutableStateFlow("")
    val currentEpisodeUrl: StateFlow<String> = _currentEpisodeUrl.asStateFlow()

    /** 一次性提示（Toast 语义） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var currentItem: NovelMediaItem? = null
    private var currentEpisode: ChapterItem? = null
    private var tickerJob: Job? = null
    private var boostJob: Job? = null

    /** 常规倍速（长按加速结束后恢复到此值） */
    private var normalSpeed: Float = 1f

    init {
        viewModelScope.launch {
            // 先取一次真实设置（避免 UI 拿到默认值就把「默认全屏」用错）
            val loaded = VideoPrefs(
                fullscreenDefault = preferencesManager.videoFullscreenDefaultFlow.first(),
                longPressSpeed = preferencesManager.videoLongPressSpeedFlow.first() / 10f,
                doubleTapSeekSec = preferencesManager.videoDoubleTapSeekFlow.first(),
                savedSpeed = preferencesManager.videoSpeedFlow.first() / 100f,
                autoNext = preferencesManager.videoAutoNextFlow.first(),
                loaded = true,
            )
            _prefs.value = loaded
            normalSpeed = loaded.savedSpeed.coerceIn(0.25f, 5f)
            player?.setPlaybackSpeed(normalSpeed)
            _state.update { it.copy(speed = normalSpeed) }

            // 之后持续跟随设置变化
            launch {
                preferencesManager.videoFullscreenDefaultFlow.collect { v ->
                    _prefs.update { it.copy(fullscreenDefault = v) }
                }
            }
            launch {
                preferencesManager.videoLongPressSpeedFlow.collect { v ->
                    _prefs.update { it.copy(longPressSpeed = v / 10f) }
                }
            }
            launch {
                preferencesManager.videoDoubleTapSeekFlow.collect { v ->
                    _prefs.update { it.copy(doubleTapSeekSec = v) }
                }
            }
            launch {
                preferencesManager.videoAutoNextFlow.collect { v ->
                    _prefs.update { it.copy(autoNext = v) }
                }
            }
            launch {
                // 记忆倍速：仅在未处于长按加速时同步到播放器
                preferencesManager.videoSpeedFlow.collect { v ->
                    val speed = (v / 100f).coerceIn(0.25f, 5f)
                    normalSpeed = speed
                    if (_state.value.boostSpeed == 0f) player?.setPlaybackSpeed(speed)
                    _state.update { it.copy(speed = speed) }
                    _prefs.update { it.copy(savedSpeed = speed) }
                }
            }
        }
    }

    /**
     * 绑定 UI 层创建的 ExoPlayer（其生命周期由 Composable 管理）。
     */
    fun attachPlayer(player: ExoPlayer) {
        this.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                _error.value = error.message ?: "播放出错"
            }
        })
        player.setPlaybackSpeed(normalSpeed)
        startTicker()
    }

    fun init(item: NovelMediaItem, episodes: List<ChapterItem>, episode: ChapterItem) {
        currentItem = item
        _episodes.value = episodes
        currentEpisode = episode
        _episodeTitle.value = episode.title
        _currentEpisodeUrl.value = episode.url
        normalSpeed = _prefs.value.savedSpeed.coerceIn(0.25f, 5f)
        loadEpisode(episode, restoreProgress = true)
    }

    /** 加载某集的播放地址 */
    fun loadEpisode(episode: ChapterItem, restoreProgress: Boolean = false) {
        currentEpisode = episode
        _episodeTitle.value = episode.title
        _currentEpisodeUrl.value = episode.url
        endSpeedBoost()
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { contentRepository.content(episode) }
                .onSuccess { result ->
                    val url = result.videoUrl
                    if (url.isNullOrEmpty()) {
                        _error.value = "未获取到播放地址"
                    } else {
                        _videoUrl.value = url
                        play(url, result.videoHeaders, restoreProgress)
                    }
                }
                .onFailure { e ->
                    _error.value = e.message ?: "加载失败"
                }
            _loading.value = false
        }
    }

    private fun play(url: String, headers: Map<String, String>, restoreProgress: Boolean) {
        val player = player ?: return

        val mediaItem = MediaItem.Builder().setUri(url).build()

        // 防盗链请求头：通过 DefaultHttpDataSource.Factory 注入
        if (headers.isNotEmpty()) {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("NovelReader/1.0")
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)
        } else {
            player.setMediaItem(mediaItem)
        }

        player.prepare()
        player.setPlaybackSpeed(normalSpeed)
        _error.value = null
        _state.update { it.copy(positionMs = 0L, durationMs = 0L, bufferedMs = 0L) }

        // 恢复上次播放进度
        if (restoreProgress) {
            viewModelScope.launch {
                val item = currentItem ?: return@launch
                val ep = currentEpisode ?: return@launch
                val progress = progressRepository.getProgress(item.sourceId, item.id)
                val position = progress?.playPositionMs ?: 0L
                // 若进度对应当前集，则 seek 到该位置（避免跨集误 seek）
                if (progress != null && progress.chapterUrl == ep.url && position > 0) {
                    player.seekTo(position)
                }
                player.play()
            }
        } else {
            player.play()
        }
    }

    // ==================== 进度 / 播放控制 ====================

    /** 跳转到指定位置 */
    fun seekTo(ms: Long) {
        val player = player ?: return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(ms.coerceIn(0L, duration))
        _state.update { it.copy(positionMs = player.currentPosition) }
    }

    /** 相对跳转（双击快进 / 快退） */
    fun seekBy(deltaMs: Long) {
        val player = player ?: return
        seekTo(player.currentPosition + deltaMs)
    }

    /** 切换播放/暂停 */
    fun togglePlay() {
        val player = player ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /** 设置常规倍速（并记忆） */
    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 5f)
        normalSpeed = s
        if (_state.value.boostSpeed == 0f) player?.setPlaybackSpeed(s)
        _state.update { it.copy(speed = s) }
        viewModelScope.launch { preferencesManager.setVideoSpeed((s * 100).toInt()) }
    }

    // ==================== 长按加速 ====================

    /**
     * 开始长按加速。[direction] > 0 为快进（倍速播放），< 0 为快退（连续回退）。
     */
    fun beginSpeedBoost(direction: Int) {
        val player = player ?: return
        if (direction < 0) {
            boostJob?.cancel()
            boostJob = viewModelScope.launch {
                while (isActive) {
                    val cur = player.currentPosition
                    if (cur <= 0L) break
                    player.seekTo((cur - REWIND_STEP_MS).coerceAtLeast(0L))
                    _state.update { it.copy(positionMs = player.currentPosition) }
                    delay(REWIND_TICK_MS)
                }
            }
            _state.update { it.copy(boostSpeed = REWIND_RATE, boostDirection = -1) }
        } else {
            val s = _prefs.value.longPressSpeed
            player.setPlaybackSpeed(s)
            _state.update { it.copy(boostSpeed = s, boostDirection = 1) }
        }
    }

    /** 结束长按加速，恢复正常倍速 */
    fun endSpeedBoost() {
        boostJob?.cancel()
        boostJob = null
        player?.setPlaybackSpeed(normalSpeed)
        _state.update { it.copy(boostSpeed = 0f, boostDirection = 0) }
    }

    // ==================== 选集 / 连播 ====================

    private val currentIndex: Int
        get() {
            val ep = currentEpisode ?: return -1
            return _episodes.value.indexOfFirst { it.url == ep.url }
        }

    fun hasPrev(): Boolean = currentIndex > 0

    fun hasNext(): Boolean {
        val i = currentIndex
        return i >= 0 && i < _episodes.value.size - 1
    }

    fun playNext() {
        val i = currentIndex
        _episodes.value.getOrNull(i + 1)?.let { playEpisode(it, auto = true) }
    }

    fun playPrev() {
        val i = currentIndex
        if (i > 0) _episodes.value.getOrNull(i - 1)?.let { playEpisode(it) }
    }

    /** 播放指定集 */
    fun playEpisode(episode: ChapterItem, auto: Boolean = false) {
        if (!auto) saveProgress() // 手动切集时先把当前进度落盘
        loadEpisode(episode, restoreProgress = false)
        if (auto) _message.value = "已自动连播：${episode.title}"
    }

    private fun onEnded() {
        saveProgress()
        if (_prefs.value.autoNext && hasNext()) {
            playNext()
        } else if (hasNext()) {
            _message.value = "本集播放完毕"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ==================== 进度落盘 ====================

    /**
     * 「退出时最后一次落盘」专用作用域。
     *
     * 不能用 [viewModelScope]：`onDispose` 之后 ViewModel 马上被 clear，
     * 作用域取消会把正在进行的写入直接掐掉——于是「看几秒就退出」
     * 什么都不会记，下次也进不了「继续观看」。
     *
     * 故意不 cancel：写入是一次性短任务，跑完即可被回收，不需要跟随 VM 结束。
     */
    private val finalSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun saveProgress(detached: Boolean = false) {
        val item = currentItem ?: return
        val episode = currentEpisode ?: return
        val player = player ?: return
        val pos = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val scope = if (detached) finalSaveScope else viewModelScope
        scope.launch {
            progressRepository.saveProgress(
                sourceId = item.sourceId,
                itemId = item.id,
                chapterUrl = episode.url,
                chapterTitle = episode.title,
                chapterIndex = episode.index,
                playPositionMs = pos,
                durationMs = duration,
                itemType = SourceType.VIDEO.name,
                itemTitle = item.title,
                itemCover = item.cover,
                itemAuthor = item.author,
                totalChapter = _episodes.value.size,
            )
        }
    }

    /** 保存当前播放进度（供退出时调用；脱离 viewModelScope 以免被取消） */
    fun saveCurrentProgress() {
        saveProgress(detached = true)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var sinceSave = 0L
            while (isActive) {
                val player = player ?: break
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                val buffered = player.bufferedPosition.let {
                    if (duration > 0L) it.coerceIn(0L, duration) else it.coerceAtLeast(0L)
                }
                _state.update {
                    it.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = duration,
                        bufferedMs = buffered,
                        isPlaying = player.isPlaying,
                        isBuffering = player.playbackState == Player.STATE_BUFFERING,
                    )
                }
                // 播放中周期性落盘（v1.3.4.0）：此前只在切集 / 退出时保存，
                // 进程被杀 / 崩溃 / 被系统回收时进度就丢了，「继续观看」会退回上一集。
                sinceSave += TICK_MS
                if (player.isPlaying && sinceSave >= SAVE_INTERVAL_MS) {
                    sinceSave = 0L
                    saveProgress()
                }
                delay(TICK_MS)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        boostJob?.cancel()
        player?.release()
        player = null
    }

    companion object {
        /** 状态轮询间隔 */
        private const val TICK_MS = 400L
        /** 播放中落盘间隔（毫秒） */
        private const val SAVE_INTERVAL_MS = 10_000L
        /** 长按快退：每步回退的毫秒数 */
        private const val REWIND_STEP_MS = 1_000L
        /** 长按快退：步进间隔 */
        private const val REWIND_TICK_MS = 250L
        /** 长按快退等效倍速（仅用于展示） */
        private const val REWIND_RATE = 4f
    }
}
