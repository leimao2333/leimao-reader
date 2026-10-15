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
import com.novelreader.core.util.VideoLineMemory
import com.novelreader.data.cache.VideoPrefetchCache
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
    /** 横屏全屏上下滑切换上下集（v1.4.7.0） */
    val verticalSwipeSwitch: Boolean = true,
    /** 小窗播放（PIP）开关（v1.5.1.0，默认关闭） */
    val pipEnabled: Boolean = false,
    /** 预加载集数（向后预取接下来 N 集，默认 3；v1.6.0.0） */
    val prefetchAhead: Int = 3,
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
    private val videoPrefetchCache: VideoPrefetchCache,
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

    // ==================== 记住上次用的源 + 自动优先有效源（v1.6.9.0） ====================

    /**
     * 记忆里的线路落在当前线路表的哪个下标（UI 在换源列表上打「上次」角标）。
     *
     * `null` = 这部片子还没有记忆 / 线路表还没加载出来。
     */
    private val _rememberedLineIndex = MutableStateFlow<Int?>(null)
    val rememberedLineIndex: StateFlow<Int?> = _rememberedLineIndex.asStateFlow()

    /**
     * 全部线路记忆：key = `sourceId\u0001itemId`。
     *
     * 纯逻辑（序列化 / 按名字解析下标 / 裁剪）在 [VideoLineMemory]；这里只做读写。
     */
    private var lineMemory: Map<String, VideoLineMemory.Entry> = emptyMap()

    /**
     * 本次会话里已确认播不通的线路下标（自动换源时每条最多试一次，
     * 否则两条坏线路之间会来回弹）。
     */
    private val failedLines = mutableSetOf<Int>()

    /** 自动起播选线 / 纠错任务（重新进片时取消上一次） */
    private var lineProbeJob: Job? = null

    /**
     * 探测纠线刚切走时，给「上一条线路迟到的播放错误」一次豁免（v1.6.9.0）。
     *
     * 场景：进入播放页时先按导航传进来的线路起播，随后探测发现它不通、切到另一条 ——
     * 旧线路那条 load 的错误回调可能晚一步才到。不豁免的话会出现
     * 「已经切到好线路了，却又被那条死线路的错误带着往回切」。
     * 豁免只给一次，且在新线路真正 READY 后立刻撤销。
     */
    private var staleErrorGrace = false

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

    /** 预取任务（切集时取消，避免旧集预取覆盖新集） */
    private var prefetchJob: Job? = null

    /**
     * 下一集播放地址预缓存：episode.url → (videoUrl, headers)。切集命中时秒起，无需再等解析。
     * v1.6.0.0：保留内存映射用于会话内秒命中；解析结果同时落盘（[videoPrefetchCache]），
     * 下次进入播放页 / 冷启动可从磁盘热起来。
     */
    private val prefetchCache = mutableMapOf<String, Pair<String, Map<String, String>>>()

    /** 当前正在看的视频条目（用于构造预取 key：sourceId + itemId + lineIndex + episode.index） */
    private var prefetchKeyPrefix: String = ""

    /** 缓存最近一次播放的地址与请求头（外挂字幕重载时复用） */
    private var lastVideoUrl: String? = null
    private var lastVideoHeaders: Map<String, String> = emptyMap()

    /** 常规倍速（长按加速结束后恢复到此值） */
    private var normalSpeed: Float = 1f

    init {
        viewModelScope.launch {
            // 线路记忆（v1.6.9.0）：一次性读进内存，之后写穿回 DataStore。
            // 读失败当作「没有记忆」——这是体验优化，绝不该拖累播放。
            lineMemory = runCatching {
                VideoLineMemory.decode(preferencesManager.videoLineMemoryFlow.first())
            }.getOrDefault(emptyMap())

            // 先取一次真实设置（避免 UI 拿到默认值就把「默认全屏」用错）
            val loaded = VideoPrefs(
                fullscreenDefault = preferencesManager.videoFullscreenDefaultFlow.first(),
                longPressSpeed = preferencesManager.videoLongPressSpeedFlow.first() / 10f,
                doubleTapSeekSec = preferencesManager.videoDoubleTapSeekFlow.first(),
                savedSpeed = preferencesManager.videoSpeedFlow.first() / 100f,
                autoNext = preferencesManager.videoAutoNextFlow.first(),
                verticalSwipeSwitch = preferencesManager.videoVerticalSwipeSwitchFlow.first(),
                pipEnabled = preferencesManager.videoPipEnabledFlow.first(),
                prefetchAhead = preferencesManager.videoPrefetchAheadFlow.first(),
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
                preferencesManager.videoVerticalSwipeSwitchFlow.collect { v ->
                    _prefs.update { it.copy(verticalSwipeSwitch = v) }
                }
            }
            launch {
                preferencesManager.videoPipEnabledFlow.collect { v ->
                    _prefs.update { it.copy(pipEnabled = v) }
                }
            }
            launch {
                preferencesManager.videoPrefetchAheadFlow.collect { v ->
                    _prefs.update { it.copy(prefetchAhead = v) }
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
                if (playbackState == Player.STATE_READY) staleErrorGrace = false
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                // v1.6.9.0：刚因探测纠线切走时，第一条错误多半来自被放弃的那条线路
                if (staleErrorGrace) {
                    staleErrorGrace = false
                    return
                }
                // 这条路线的流挂了就自动换下一条没试过的（每条最多试一次）；
                // 全部试完才如实把错误抛给用户
                if (tryAutoSwitchOnError()) return
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
        // 换片：清掉上一部片子留下的失败线路记录与「上次」角标
        failedLines.clear()
        _rememberedLineIndex.value = null
        lineProbeJob?.cancel()
        // 预取 key 前缀：按节目源 / 节目 / 线路切分，保证换源 / 换节目不读到别人的缓存
        prefetchKeyPrefix = "${item.sourceId}__${item.id}__${_currentLineIndex.value}__"
        loadEpisode(episode, restoreProgress = true)
        // 线路表后台加载（详情页已把首条线路的剧集通过导航传进来，所以不必阻塞起播）
        viewModelScope.launch { loadLines(item, episode) }
        // 进入播放页时把磁盘预热到内存（冷启动也能秒切）
        warmPrefetchFromDisk()
    }

    /**
     * 把磁盘上的预取条目按当前 key 前缀热到内存（v1.6.0.0）。
     *
     * 落地策略（[VideoPrefetchCache]）保证同一节目源 / 节目 / 线路下切集不重复解析；
     * 换源后旧条目仍占用磁盘，但下一次进入相同节目会被忽略、缓存管理页也能一键清。
     */
    private fun warmPrefetchFromDisk() {
        val prefix = prefetchKeyPrefix
        if (prefix.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                videoPrefetchCache.listAll().forEach { (k, entry) ->
                    if (!k.startsWith(prefix)) return@forEach
                    val episodeKey = k.removePrefix(prefix)
                    if (episodeKey.isBlank()) return@forEach
                    prefetchCache[episodeKey] = entry.videoUrl to entry.headers
                }
            }
        }
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
        // 线路表就绪后再纠一次线（v1.6.9.0）：能省掉「每次都要手动换源」的重复劳动
        applyRememberedLine()
    }

    // ==================== 记住上次的源 + 自动优先有效源（v1.6.9.0） ====================

    /** 记忆里的线路落在当前线路表的下标（线路表未加载 / 无记忆时为 null） */
    private fun rememberedIndex(): Int? {
        val item = currentItem ?: return null
        return VideoLineMemory.resolveIndex(
            lineMemory[VideoLineMemory.key(item.sourceId, item.id)],
            _lines.value.map { it.name },
        )
    }

    /** 把当前线路记进 [lineMemory] 并落盘（下次进这部片子直接用它） */
    private fun rememberCurrentLine() {
        val item = currentItem ?: return
        val index = _currentLineIndex.value
        val name = _lines.value.getOrNull(index)?.name ?: return
        val now = System.currentTimeMillis()
        val key = VideoLineMemory.key(item.sourceId, item.id)
        lineMemory = VideoLineMemory.prune(
            VideoLineMemory.put(lineMemory, key, name, index, now),
            now,
        )
        _rememberedLineIndex.value = index
        viewModelScope.launch {
            runCatching {
                preferencesManager.setVideoLineMemory(VideoLineMemory.encode(lineMemory))
            }
        }
    }

    /**
     * 进入播放页后把线路纠到「记忆的 / 可用的」那条（v1.6.9.0）。
     *
     * 策略（**只发最少的请求**，不阻塞起播）：
     * 1. 单线路源直接返回（没什么可选的，也别白测一次）；
     * 2. 先探**当前正在播的那条**：通了且就是记忆线路 → 什么都不做；
     * 3. 当前通了、但记忆线路是另一条 → 探一下记忆线路，可达就切回去（「之后还是什么源」）；
     * 4. 当前不通 → 并发探完其余线路，用 [LineSwitch.pickValid] 选
     *    「记忆线路优先、否则最快可达」的那条切过去（「自动优先有效源」）。
     *
     * 一条可达的都没有时**不乱切**，只提示，保持在原位等用户手动换。
     */
    private fun applyRememberedLine() {
        val item = currentItem ?: return
        val lines = _lines.value
        if (lines.size < 2) return

        val preferred = rememberedIndex()
        _rememberedLineIndex.value = preferred
        lineProbeJob?.cancel()
        lineProbeJob = viewModelScope.launch {
            val episodeIndex = currentEpisode?.index ?: 0
            val current = _currentLineIndex.value

            suspend fun probeOf(index: Int): Long? {
                val line = lines.getOrNull(index) ?: return null
                val url = (line.chapters.getOrNull(episodeIndex) ?: line.chapters.firstOrNull())
                    ?.url?.takeIf { it.isNotBlank() } ?: return null
                // 必须切到 IO：probe 是阻塞式 socket 调用（同 speedTest 的教训）
                return withContext(Dispatchers.IO) {
                    HttpClient.probe(url, AUTO_LINE_PROBE_TIMEOUT_MS)
                }
            }

            val collected = mutableMapOf<Int, Long?>()
            val currentMs = probeOf(current)
            collected[current] = currentMs
            _lineSpeeds.value = collected.toMap()
            // 探测已经证明这条不通 → 记进失败集合，别再让它被「出错自动换源」选回来
            if (currentMs == null) failedLines += current

            if (currentMs != null) {
                if (preferred == null || preferred == current) {
                    // 当前就能播，且没有别的偏好 → 不动
                    if (preferred == null) rememberCurrentLine()
                    return@launch
                }
                // 当前能播但用户上次用的是另一条：探一下记忆线路，可达才切回去
                collected[preferred] = probeOf(preferred)
                _lineSpeeds.value = collected.toMap()
            } else {
                // 当前不通 → 其余线路并发探（分片，别把弱站打挂）
                val others = lines.indices.filter { it != current }
                for (chunk in others.chunked(SPEED_CONCURRENCY)) {
                    coroutineScope {
                        chunk.map { i -> async { i to probeOf(i) } }.awaitAll()
                    }.forEach { (i, ms) ->
                        collected[i] = ms
                        _lineSpeeds.value = collected.toMap()
                    }
                }
            }

            val target = LineSwitch.pickValid(preferred, collected, lines.size)
            if (target == null) {
                if (_lines.value.size > 1) _message.value = "所有线路都暂时不可达，已保留当前线路"
                return@launch
            }
            if (target == current) {
                if (preferred == null) rememberCurrentLine()
                return@launch
            }
            val targetName = lines.getOrNull(target)?.name.orEmpty()
            val currentName = lines.getOrNull(current)?.name.orEmpty()
            // 给被放弃那条线路的迟到错误一次豁免（见 [staleErrorGrace]）
            staleErrorGrace = true
            switchLineInternal(
                index = target,
                userInitiated = false,
                note = if (target == preferred) {
                    "已恢复到上次用的「$targetName」"
                } else {
                    "「$currentName」不可用，已自动切换到「$targetName」"
                },
            )
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
        // 命中预缓存：直接用缓存地址起播，跳过解析（切下一集时秒起）
        prefetchCache[episode.url]?.let { (url, headers) ->
            _loading.value = false
            _error.value = null
            _videoUrl.value = url
            play(url, headers, restoreProgress, seekToMs)
            schedulePrefetchNext()
            return
        }
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
                        schedulePrefetchNext()
                    }
                }
                .onFailure { e ->
                    _error.value = e.message ?: "加载失败"
                }
            _loading.value = false
        }
    }

    /**
     * 预取接下来 [VideoPrefs.prefetchAhead] 集的播放地址（v1.6.0.0 N 集预加载）。
     *
     * 当前集起播后，后台把后续 N 集的地址解析出来放进 [prefetchCache]（同时落盘到
     * [videoPrefetchCache]），用户点「下一集」/ 连播时命中缓存直接起播，消除
     * 「切集 → 解析 → prepare」的等待；连播多集时也能享受到预加载的加速效果。
     *
     * 预取是纯增强：失败 / 超时静默，绝不影响当前播放。
     */
    private fun schedulePrefetchNext() {
        prefetchJob?.cancel()
        val ahead = _prefs.value.prefetchAhead.coerceIn(1, 6)
        // 取当前之后的 N 集（连续到末尾则少取）
        val targets = (1..ahead).mapNotNull { peekByOffset(it) }
        if (targets.isEmpty()) return
        val prefix = prefetchKeyPrefix
        prefetchJob = viewModelScope.launch {
            for (next in targets) {
                if (prefetchCache.containsKey(next.url)) continue
                val result = runCatching {
                    withContext(Dispatchers.IO) { contentRepository.content(next) }
                }.getOrNull()
                val url = result?.videoUrl
                if (!url.isNullOrEmpty()) {
                    val headers = result.videoHeaders
                    prefetchCache[next.url] = url to headers
                    // 落盘：缓存管理页能列出 / 清理
                    if (prefix.isNotBlank()) {
                        runCatching {
                            videoPrefetchCache.save(
                                prefix + next.url,
                                VideoPrefetchCache.Entry(url, headers),
                            )
                        }
                    }
                }
            }
        }
    }

    /** 当前播放位置向后偏移 [offset] 集（offset = 1 即下一集） */
    private fun peekByOffset(offset: Int): ChapterItem? {
        val i = currentIndex
        return _episodes.value.getOrNull(i + offset)
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

    /** 下一集（不切换，供横屏上下滑切集提示用，v1.4.7.0） */
    fun peekNext(): ChapterItem? {
        val i = currentIndex
        return _episodes.value.getOrNull(i + 1)
    }

    /** 上一集（不切换，供横屏上下滑切集提示用，v1.4.7.0） */
    fun peekPrev(): ChapterItem? {
        val i = currentIndex
        return if (i > 0) _episodes.value.getOrNull(i - 1) else null
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
     * 切换到指定线路（用户在「换源」Tab 手动点选）。
     *
     * 两条保位规则：
     * 1. **保集**：优先切到新线路的**同一集序号**（各线路集序基本一致）；
     *    若新线路集数不够（如只有预告），退回该线路第 1 集。
     * 2. **保位置**：把当前播放进度带到新线路继续，避免换源后从头看。
     *
     * v1.6.9.0：手动换源会**写进记忆**（下次进这部片子直接用它），
     * 并清空本次会话的「失败线路」记录，让之前失败的线路重新有机会。
     */
    fun switchLine(index: Int) = switchLineInternal(index, userInitiated = true, note = null)

    /**
     * 换源的真正实现。
     *
     * @param userInitiated 用户手动点的（清空失败记录 + 提示「已切换到…」）；
     *                      自动纠错传 false（保留失败记录，提示由 [note] 给出）
     * @param note 自定义提示语；`null` = 用默认的「已切换到「X」N集」
     */
    private fun switchLineInternal(index: Int, userInitiated: Boolean, note: String?) {
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
        // 手动换源后重新给每条线路机会（自动纠错时保留，避免坏线路被反复选中）
        if (userInitiated) failedLines.clear()
        _currentLineIndex.value = index
        _episodes.value = target.chapters
        // 换线路后集地址全变 → 清空内存 + 磁盘上的旧节目预缓存
        clearPrefetch()
        // 重新设置 key 前缀（线路变了）
        val newPrefix = "${currentItem?.sourceId ?: ""}__${currentItem?.id ?: ""}__${index}__"
        prefetchKeyPrefix = newPrefix
        // 删除磁盘上旧线路的预加载条目（按前缀匹配）
        clearDiskPrefetchWithPrefix("${currentItem?.sourceId ?: ""}__${currentItem?.id ?: ""}__")
        loadEpisode(next, restoreProgress = false, seekToMs = position)
        // v1.6.9.0：这次的选择记下来 —— 自动纠错选出的可用线路同样值得记住
        rememberCurrentLine()
        _message.value = note ?: "已切换到「${target.name}」${next.title}"
    }

    /**
     * 播放出错时自动换到下一条没试过的线路（v1.6.9.0，「自动优先有效源」的兜底）。
     *
     * @return `true` = 已经自动切走了，UI 不必再弹错误
     */
    private fun tryAutoSwitchOnError(): Boolean {
        val lines = _lines.value
        if (lines.size < 2) return false
        val current = _currentLineIndex.value
        failedLines += current
        // 没有别的线路可换（都试过 / 只剩当前这条）→ 如实把错误交给用户
        val next = LineSwitch.nextUntried(lines.size, failedLines)
            ?.takeIf { it != current } ?: return false
        val currentName = lines.getOrNull(current)?.name.orEmpty()
        val nextName = lines.getOrNull(next)?.name.orEmpty()
        switchLineInternal(
            index = next,
            userInitiated = false,
            note = "「$currentName」播放失败，已自动切换到「$nextName」",
        )
        return true
    }

    /** 清空内存预取（换线路 / 退出播放页时调用） */
    private fun clearPrefetch() {
        prefetchCache.clear()
        prefetchJob?.cancel()
        prefetchJob = null
    }

    /** 清空磁盘上指定节目源 / 节目的全部预加载（按前缀匹配） */
    private fun clearDiskPrefetchWithPrefix(prefix: String) {
        if (prefix.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                videoPrefetchCache.listAll().keys
                    .filter { it.startsWith(prefix) }
                    .forEach { videoPrefetchCache.removeKey(it) }
            }
        }
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
        prefetchJob?.cancel()
        lineProbeJob?.cancel()
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
        /** 进播放页自动纠线时的探测超时（比手动测速短，别让用户干等） */
        private const val AUTO_LINE_PROBE_TIMEOUT_MS = 4_000L
    }
}
