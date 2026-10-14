package com.novelreader.ui.screens.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.novelreader.core.network.HttpClient
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.core.util.LineSwitch
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ChapterLine
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * 清晰度档位（v1.3.9.0）。
 *
 * 仅从多码率 HLS master playlist 提取，按视频轨道分辨率高度去重。
 */
data class QualityTrack(
    val height: Int,
    val label: String,
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

    // ==================== 换源（多线路，v1.3.6.0） ====================

    /** 全部线路（按集数降序，第 0 条为默认） */
    private val _lines = MutableStateFlow<List<ChapterLine>>(emptyList())
    val lines: StateFlow<List<ChapterLine>> = _lines.asStateFlow()

    /** 当前线路下标 */
    private val _currentLineIndex = MutableStateFlow(0)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex.asStateFlow()

    /**
     * 线路测速结果：线路下标 → 延迟毫秒。
     *
     * `null` 表示这条线路**不可达**（超时 / 非 2xx），UI 显示「-」。
     * 未测过的下标不出现在 map 里。
     */
    private val _lineSpeeds = MutableStateFlow<Map<Int, Long?>>(emptyMap())
    val lineSpeeds: StateFlow<Map<Int, Long?>> = _lineSpeeds.asStateFlow()

    /** 是否正在测速（UI 禁用按钮 + 显示进度） */
    private val _speedTesting = MutableStateFlow(false)
    val speedTesting: StateFlow<Boolean> = _speedTesting.asStateFlow()

    /** 当前选集标题 */
    private val _episodeTitle = MutableStateFlow("")
    val episodeTitle: StateFlow<String> = _episodeTitle.asStateFlow()

    /** 当前选集的 url（用于在选集列表中高亮） */
    private val _currentEpisodeUrl = MutableStateFlow("")
    val currentEpisodeUrl: StateFlow<String> = _currentEpisodeUrl.asStateFlow()

    /** 一次性提示（Toast 语义） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 清晰度（视频轨道）列表，来自 HLS master playlist 的多码率 variant（v1.3.9.0） */
    private val _qualityTracks = MutableStateFlow<List<QualityTrack>>(emptyList())
    val qualityTracks: StateFlow<List<QualityTrack>> = _qualityTracks.asStateFlow()

    /** 当前播放的清晰度（null = 自动） */
    private val _selectedQuality = MutableStateFlow<QualityTrack?>(null)
    val selectedQuality: StateFlow<QualityTrack?> = _selectedQuality.asStateFlow()

    // ==================== 外挂字幕（v1.4.6.0） ====================

    /** 当前加载的字幕文件（本地 SAF Uri），null = 未加载 */
    private val _subtitleUri = MutableStateFlow<Uri?>(null)
    val subtitleUri: StateFlow<Uri?> = _subtitleUri.asStateFlow()

    /** 字幕是否开启（false = 加载但关闭显示） */
    private val _subtitleEnabled = MutableStateFlow(true)
    val subtitleEnabled: StateFlow<Boolean> = _subtitleEnabled.asStateFlow()

    /** 字幕文件名（仅用于展示） */
    private val _subtitleName = MutableStateFlow<String?>(null)
    val subtitleName: StateFlow<String?> = _subtitleName.asStateFlow()

    private var currentItem: NovelMediaItem? = null
    private var currentEpisode: ChapterItem? = null
    private var tickerJob: Job? = null
    private var boostJob: Job? = null

    /** 缓存最近一次播放的地址与请求头（外挂字幕重载时复用） */
    private var lastVideoUrl: String? = null
    private var lastVideoHeaders: Map<String, String> = emptyMap()

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

            override fun onTracksChanged(tracks: Tracks) {
                collectQualityTracks()
            }
        })
        player.setPlaybackSpeed(normalSpeed)
        startTicker()
    }

    /**
     * 从当前轨道里提取视频清晰度档位（v1.3.9.0）。
     *
     * 仅当源是多码率（HLS master playlist 含多个视频 variant）时才有多个档位；
     * 大多数采集站只提供 1080p 单码率，此时列表为空，UI 不显示清晰度入口。
     */
    private fun collectQualityTracks() {
        val p = player ?: return
        val videoGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        // 只有多视频轨（含自动切换组）时才值得展示清晰度
        val formats = videoGroups.flatMap { group ->
            (0 until group.length).map { group.getTrackFormat(it) }
        }.filter { it.height != Format.NO_VALUE && it.height > 0 }

        if (formats.size <= 1) {
            _qualityTracks.value = emptyList()
            _selectedQuality.value = null
            return
        }

        val tracks = formats
            .distinctBy { it.height }
            .sortedByDescending { it.height }
            .map { QualityTrack(it.height, qualityLabel(it.height, it.bitrate)) }

        // 去重后若仍只有一档（多轨但同分辨率），也不展示
        if (tracks.size <= 1) {
            _qualityTracks.value = emptyList()
            _selectedQuality.value = null
            return
        }
        _qualityTracks.value = tracks

        // 自动模式：把当前已应用的轨道覆盖映射回档位；无显式覆盖则置 null（自动）
        val selector = p.trackSelectionParameters as? DefaultTrackSelector.Parameters
        val currentSelected = selector?.let { params ->
            val height = params.overrides.values
                .flatMap { it.trackIndices }
                .mapNotNull { idx ->
                    p.currentTracks.groups
                        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                        ?.getTrackFormat(idx)?.height
                }
                .firstOrNull()
            tracks.firstOrNull { it.height == height }
        }
        _selectedQuality.value = currentSelected
    }

    /** 清晰度标签：`1080P` / `720P` / `480P`（带码率可选，暂只显示分辨率） */
    private fun qualityLabel(height: Int, bitrate: Int): String {
        val name = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "2K"
            height >= 1080 -> "1080P"
            height >= 720 -> "720P"
            height >= 480 -> "480P"
            else -> "360P"
        }
        return name
    }

    /** 切换到指定清晰度（按分辨率高度锁定对应视频轨道） */
    fun selectQuality(track: QualityTrack) {
        val p = player ?: return
        val selector = p.trackSelectionParameters as? DefaultTrackSelector.Parameters ?: return
        val group = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        // 找到该分辨率对应的轨道下标
        val index = (0 until group.length).firstOrNull {
            group.getTrackFormat(it).height == track.height
        } ?: return
        p.trackSelectionParameters = selector.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, index),
            )
            .build()
        _selectedQuality.value = track
    }

    /** 恢复自动清晰度（清除轨道覆盖） */
    fun resetQuality() {
        val p = player ?: return
        val selector = p.trackSelectionParameters as? DefaultTrackSelector.Parameters ?: return
        p.trackSelectionParameters = selector.buildUpon()
            .clearOverrides()
            .build()
        _selectedQuality.value = null
    }

    // ==================== 外挂字幕（v1.4.6.0） ====================

    /**
     * 根据当前字幕状态构建 MediaItem。
     *
     * 有字幕时用 [MediaItem.SubtitleConfiguration] 附加一条 side-loaded 文本轨道，
     * 由 Media3 在 prepare 时解析（支持 SRT / WebVTT）。字幕 URI 是本地 SAF 地址，
     * 走 `content://` scheme，DefaultDataSource 可直接读取。
     */
    private fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val subUri = _subtitleUri.value
        if (subUri != null) {
            val mime = subtitleMimeType(subUri)
            val label = _subtitleName.value ?: "外挂字幕"
            val sub = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(mime)
                .setLanguage("und")
                .setLabel(label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(sub))
        }
        return builder.build()
    }

    /** 根据文件扩展名推断字幕 MIME 类型（srt → application/x-subrip，vtt → text/vtt） */
    private fun subtitleMimeType(uri: Uri): String {
        val path = uri.lastPathSegment?.lowercase() ?: return MimeTypes.APPLICATION_SUBRIP
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /**
     * 加载外挂字幕文件并立即生效（保留当前播放位置）。
     *
     * side-loaded 字幕必须在 MediaItem 里声明，所以需要重建媒体并重新 prepare。
     * 为不打断观看，先记下当前位置，重建后 seek 回去。
     */
    fun loadSubtitle(uri: Uri, name: String?) {
        val player = player ?: return
        val url = lastVideoUrl ?: run {
            _message.value = "请先播放视频"
            return
        }
        val position = player.currentPosition
        val wasPlaying = player.isPlaying

        _subtitleUri.value = uri
        _subtitleName.value = name
        _subtitleEnabled.value = true

        // 重建媒体（带字幕轨道）并从原位置续播
        val mediaItem = buildMediaItem(url)
        if (lastVideoHeaders.isNotEmpty()) {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("NovelReader/1.0")
                .setDefaultRequestProperties(lastVideoHeaders)
                .setAllowCrossProtocolRedirects(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.setPlaybackSpeed(normalSpeed)
        applySubtitleEnabled()
        if (position > 0) player.seekTo(position)
        if (wasPlaying) player.play()

        _message.value = "已加载字幕：${name ?: "外挂字幕"}"
    }

    /** 移除字幕（重建媒体，去掉字幕轨道，保留播放位置） */
    fun clearSubtitle() {
        val player = player ?: return
        val url = lastVideoUrl ?: return
        if (_subtitleUri.value == null) return
        val position = player.currentPosition
        val wasPlaying = player.isPlaying

        _subtitleUri.value = null
        _subtitleName.value = null
        _subtitleEnabled.value = false

        val mediaItem = buildMediaItem(url)
        if (lastVideoHeaders.isNotEmpty()) {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("NovelReader/1.0")
                .setDefaultRequestProperties(lastVideoHeaders)
                .setAllowCrossProtocolRedirects(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.setPlaybackSpeed(normalSpeed)
        if (position > 0) player.seekTo(position)
        if (wasPlaying) player.play()

        _message.value = "已移除字幕"
    }

    /** 切换字幕显示开关（不重建媒体，仅切换文本轨道启用状态） */
    fun toggleSubtitle() {
        _subtitleEnabled.value = !_subtitleEnabled.value
        applySubtitleEnabled()
        _message.value = if (_subtitleEnabled.value) "字幕已开启" else "字幕已关闭"
    }

    /**
     * 应用字幕开关到播放器：启用时选中文本轨道，关闭时禁用。
     *
     * Media3 通过 [TrackSelectionParameters] 的 disabled track types 控制。
     * 关闭文本轨道类型即隐藏字幕；重新启用则恢复默认文本轨道选择。
     */
    private fun applySubtitleEnabled() {
        val p = player ?: return
        val enabled = _subtitleEnabled.value
        val params = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        p.trackSelectionParameters = params
    }

    fun init(item: NovelMediaItem, episodes: List<ChapterItem>, episode: ChapterItem) {
        currentItem = item
        _episodes.value = episodes
        currentEpisode = episode
        _episodeTitle.value = episode.title
        _currentEpisodeUrl.value = episode.url
        normalSpeed = _prefs.value.savedSpeed.coerceIn(0.25f, 5f)
        loadEpisode(episode, restoreProgress = true)
        // 线路表后台加载（详情页已把首条线路的剧集通过导航传进来，所以不必阻塞起播）
        viewModelScope.launch { loadLines(item, episode) }
    }

    /**
     * 加载全部线路，并把当前线路定位到「当前正在播的那一集所属的线路」。
     *
     * 不覆盖 [episodes] 以外的东西：传入的 episodes 就是首选线路的剧集，
     * `loadLines` 只是补上「还有哪些线路可选」这一层信息。
     */
    private suspend fun loadLines(item: NovelMediaItem, episode: ChapterItem) {
        val lines = runCatching { contentRepository.chapterLines(item) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return
        _lines.value = lines
        val index = lines.indexOfFirst { line -> line.chapters.any { it.url == episode.url } }
        if (index >= 0) {
            _currentLineIndex.value = index
            // 首条线路的剧集与传入的一致时无需替换；不一致（如源返回顺序变了）才同步
            lines[index].chapters.takeIf { it.isNotEmpty() }?.let { _episodes.value = it }
        } else {
            _currentLineIndex.value = 0
        }
    }

    /**
     * 加载某集的播放地址。
     *
     * @param restoreProgress 是否恢复上次观看位置（进入播放页时用）
     * @param seekToMs 显式起播位置（换源时用来「原位置续播」，优先于 [restoreProgress]）
     */
    fun loadEpisode(
        episode: ChapterItem,
        restoreProgress: Boolean = false,
        seekToMs: Long? = null,
    ) {
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
                        play(url, result.videoHeaders, restoreProgress, seekToMs)
                    }
                }
                .onFailure { e ->
                    _error.value = e.message ?: "加载失败"
                }
            _loading.value = false
        }
    }

    private fun play(
        url: String,
        headers: Map<String, String>,
        restoreProgress: Boolean,
        seekToMs: Long? = null,
    ) {
        val player = player ?: return

        // 换集 / 换源时清空清晰度状态，待新流的轨道就绪后由 onTracksChanged 重建
        _qualityTracks.value = emptyList()
        _selectedQuality.value = null

        // 缓存当前播放地址与请求头（外挂字幕重载时复用，见 loadSubtitle）
        lastVideoUrl = url
        lastVideoHeaders = headers

        val mediaItem = buildMediaItem(url)

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
        applySubtitleEnabled()
        _error.value = null
        _state.update { it.copy(positionMs = 0L, durationMs = 0L, bufferedMs = 0L) }

        when {
            // 换源：在**原位置**继续（新线路的时长可能不同，交给 seekTo 夹取）
            seekToMs != null && seekToMs > 0L -> {
                player.seekTo(seekToMs)
                player.play()
            }
            // 恢复上次播放进度
            restoreProgress -> {
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
            }
            else -> player.play()
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

    // ==================== 换源 / 测速择优（v1.3.6.0） ====================

    /**
     * 切换到指定线路。
     *
     * 两条保位规则：
     * 1. **保集**：优先切到新线路的**同一集序号**（各线路集序基本一致）；
     *    若新线路集数不够（如只有预告），退回该线路第 1 集。
     * 2. **保位置**：把当前播放进度带到新线路继续，避免换源后从头看。
     */
    fun switchLine(index: Int) {
        val lines = _lines.value
        val target = lines.getOrNull(index) ?: return
        if (index == _currentLineIndex.value) return

        val position = player?.currentPosition ?: 0L
        val wantedIndex = currentEpisode?.index ?: 0
        val next = LineSwitch.pickChapter(target.chapters, wantedIndex)
            ?: run {
                _message.value = "该线路没有可用剧集"
                return
            }

        saveProgress() // 换源前先把当前线路的进度落盘
        _currentLineIndex.value = index
        _episodes.value = target.chapters
        loadEpisode(next, restoreProgress = false, seekToMs = position)
        _message.value = "已切换到「${target.name}」${next.title}"
    }

    /** 切到当前测速结果里最快的一条线路；没有可用结果时不动 */
    fun switchToFastestLine() {
        val best = LineSwitch.fastestIndex(_lineSpeeds.value) ?: run {
            _message.value = "还没有可用的测速结果"
            return
        }
        if (best == _currentLineIndex.value) {
            _message.value = "当前已是最快线路"
            return
        }
        switchLine(best)
    }

    /**
     * 对各条线路测速（首字节延迟）。
     *
     * 用**当前正在看的这一集**作为探测目标 —— 这才是真正要播放的地址，
     * 用它测出来的数字才有参考意义（换成第 1 集可能落在不同 CDN 上）。
     *
     * 分片并发 [SPEED_CONCURRENCY] 路：串行在 10 条线路时要等一分钟；
     * 全并发又容易把弱站打挂（首页分片拉取踩过同样的坑），所以折中。
     * 结果**逐条回填**，用户能实时看到先算出来的那些。
     */
    fun speedTest() {
        val lines = _lines.value
        if (lines.size < 2) {
            _message.value = "这个源只有一条线路"
            return
        }
        if (_speedTesting.value) return

        val episodeIndex = currentEpisode?.index ?: 0
        viewModelScope.launch {
            _speedTesting.value = true
            _lineSpeeds.value = emptyMap()
            val collected = mutableMapOf<Int, Long?>()
            for (chunk in lines.indices.chunked(SPEED_CONCURRENCY)) {
                coroutineScope {
                    chunk.map { i ->
                        async {
                            val line = lines[i]
                            val url = line.chapters.getOrNull(episodeIndex)?.url
                                ?: line.chapters.firstOrNull()?.url
                            // 必须切到 IO：probe 是阻塞式 socket 调用，
                            // 在 viewModelScope（Main）上直接跑会抛 NetworkOnMainThreadException，
                            // 被 runCatching 一吞就变成「所有线路都不可达」。
                            val ms = url
                                ?.takeIf { it.isNotBlank() }
                                ?.let { withContext(Dispatchers.IO) { HttpClient.probe(it, SPEED_PROBE_TIMEOUT_MS) } }
                            i to ms
                        }
                    }.awaitAll()
                }.forEach { (i, ms) ->
                    collected[i] = ms
                    _lineSpeeds.value = collected.toMap()
                }
            }
            _speedTesting.value = false
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
        /** 测速并发路数（太高容易把弱站打挂，见 speedTest 注释） */
        private const val SPEED_CONCURRENCY = 3
        /** 单条线路测速超时 */
        private const val SPEED_PROBE_TIMEOUT_MS = 5_000L
    }
}
