package com.novelreader.ui.screens.video

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.novelreader.domain.model.ChapterItem
import kotlin.math.abs
import kotlin.math.roundToInt
import com.novelreader.domain.model.MediaItem as NovelMediaItem

/** 双击快进 / 快退的涟漪提示 */
private data class SeekRipple(val forward: Boolean, val seconds: Int)

/** 横滑进度预览 */
private data class SeekPreview(val targetMs: Long, val deltaMs: Long)

/** 竖滑亮度 / 音量指示 */
private data class VerticalIndicator(val brightness: Boolean, val value: Float)

/** 横屏上下滑切集提示（v1.4.7.0）：next = 是否下一集 */
private data class SwitchEpisodeHint(val next: Boolean, val title: String)

/**
 * 视频播放页（v1.3.2.0 交互重做，对齐 MoonTV / ArtPlayer 的操作习惯）。
 *
 * 支持：
 * - 窗口 / 全屏双态：全屏 = 横屏 + 沉浸式（隐藏系统栏），返回键优先退全屏
 * - 长按画面：右半屏 3x 倍速快进、左半屏连续快退（松手恢复）
 * - 双击左 / 右半屏：快退 / 快进 10 秒（带涟漪提示）
 * - 手势：横滑调进度、左竖滑调亮度、右竖滑调音量
 * - 自定义控制层：点击显隐、播放中 3.5 秒自动隐藏、倍速菜单、选集浮层、上下集连播
 */
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel,
    item: NovelMediaItem,
    episodes: List<ChapterItem>,
    episode: ChapterItem,
    onBack: () -> Unit,
    // v1.6.5.0：跳视频详情页（短剧从播放器菜单栏目进详情的入口）
    onOpenDetail: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val episodeTitle by viewModel.episodeTitle.collectAsState()
    val episodesList by viewModel.episodes.collectAsState()
    val currentEpisodeUrl by viewModel.currentEpisodeUrl.collectAsState()
    val playback by viewModel.state.collectAsState()
    val prefs by viewModel.prefs.collectAsState()
    val message by viewModel.message.collectAsState()
    val lines by viewModel.lines.collectAsState()
    val currentLineIndex by viewModel.currentLineIndex.collectAsState()
    // v1.6.9.0：换源列表上标「上次」用（记忆里上次用的那条线路）
    val rememberedLineIndex by viewModel.rememberedLineIndex.collectAsState()
    val lineSpeeds by viewModel.lineSpeeds.collectAsState()
    val speedTesting by viewModel.speedTesting.collectAsState()
    val qualityTracks by viewModel.qualityTracks.collectAsState()
    val selectedQuality by viewModel.selectedQuality.collectAsState()
    val subtitleUri by viewModel.subtitleUri.collectAsState()
    val subtitleEnabled by viewModel.subtitleEnabled.collectAsState()
    val subtitleName by viewModel.subtitleName.collectAsState()

    // 外挂字幕文件选择器（v1.4.6.0）：SAF 选 srt/vtt/ass/ssa
    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            // 读取文件名用于展示；失败则用「外挂字幕」兜底
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
            }.getOrNull()
            // 取持久读权限，避免离开页面后失效
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.loadSubtitle(uri, name)
        }
    }

    // 创建并绑定 ExoPlayer（生命周期由 Composable 管理）
    val player = remember {
        ExoPlayer.Builder(context)
            // 缓冲策略（v1.7.7.0）：默认 min/max 都是 50s、起播只要 2.5s，
            // 采集站分片一慢就频繁「缓冲中→卡一下」。改成：
            // - 至少攒 30s 再开播（起播慢一点点，但换来中段不卡）
            // - 最多缓 120s（网络好的时候一口气吃满，回退/快进都在本地）
            // - 卡顿后要攒到 4s 才重新播，避免「播 0.5s 又卡」的抖动
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 120_000,
                        /* bufferForPlaybackMs = */ 2_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 4_000,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            // 拔耳机自动暂停（否则会突然外放）
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { viewModel.attachPlayer(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveCurrentProgress()
            viewModel.detachPlayer(player)
            player.release()
        }
    }

    LaunchedEffect(item, episode) {
        viewModel.init(item, episodes, episode)
    }

    // 一次性提示
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    // ===== 界面状态 =====
    // 短剧：默认竖屏沉浸（铺满 + 竖屏方向），可切横屏；普通视频：窗口 16:9 起步，可切横屏全屏
    val isShortDrama = item.extras[com.novelreader.ui.screens.common.HomeViewModel.EXTRA_SHORT_DRAMA] == "1"
    var isFullscreen by remember { mutableStateOf(isShortDrama) }
    // 短剧横屏切换（默认竖屏；点全屏按钮切横屏，再点切回竖屏）
    var shortDramaLandscape by remember { mutableStateOf(false) }
    // 普通视频全屏时的横竖方向（v1.5.1.0）：默认横屏，点「旋转」切竖屏
    var videoLandscape by remember { mutableStateOf(true) }
    var fullscreenInited by remember { mutableStateOf(false) }
    // 是否处于画中画（PIP）模式（v1.4.6.0）：PIP 中隐藏全部控制层与浮层
    var inPip by remember { mutableStateOf(false) }
    // 订阅全局 PIP 状态（由 MainActivity 的 onPictureInPictureModeChanged 广播）
    val globalInPip by com.novelreader.MainActivity.inPip.collectAsState()
    LaunchedEffect(globalInPip) {
        inPip = globalInPip
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var episodesOpen by remember { mutableStateOf(false) }
    // 「选集 / 换源」双 Tab 的当前 Tab（v1.3.6.0）；窗口态与全屏浮层共用同一个下标
    var sheetTab by remember { mutableIntStateOf(SheetTabs.EPISODES) }
    var ripple by remember { mutableStateOf<SeekRipple?>(null) }
    var seekPreview by remember { mutableStateOf<SeekPreview?>(null) }
    var verticalIndicator by remember { mutableStateOf<VerticalIndicator?>(null) }
    // 横屏上下滑切集提示（v1.4.7.0）
    var switchEpisodeHint by remember { mutableStateOf<SwitchEpisodeHint?>(null) }

    // 首帧后按设置决定是否直接全屏（短剧始终竖屏全屏，不受「默认全屏」设置影响）
    LaunchedEffect(prefs.loaded, prefs.fullscreenDefault) {
        if (prefs.loaded && !fullscreenInited) {
            if (!isShortDrama) isFullscreen = prefs.fullscreenDefault
            fullscreenInited = true
            fullscreenInited = true
        }
    }

    // ===== 画中画（PIP，v1.4.6.0；v1.5.1.0 加开关，默认关闭）=====
    // 播放中切后台（ON_STOP）→ 仅当「小窗播放」开关开启时进入小窗；否则保持普通后台。
    // 回到前台（ON_START）→ 退出小窗并回到全屏。
    // 用户点小窗上的「返回」按钮 → 系统回调 onPictureInPictureModeChanged(false)。
    val pipSupported = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity?.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) == true }
    val currentPipEnabled by rememberUpdatedState(prefs.pipEnabled)
    DisposableEffect(lifecycleOwner, pipSupported) {
        if (!pipSupported) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // 播放中 + 小窗开关开启才进 PIP（暂停/未加载/开关关闭时不打扰用户）
                    if (viewModel.state.value.isPlaying && !inPip && currentPipEnabled) {
                        enterPip(activity)
                    }
                }
                Lifecycle.Event.ON_START -> {
                    // 回到前台：如果还在 PIP 里则退出
                    if (inPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        try {
                            activity?.let { a ->
                                if (a.isInPictureInPictureMode) {
                                    // moveTaskToBack 会让系统退出 PIP 并回到正常 activity
                                    // 实际退出由系统 onPictureInPictureModeChanged 回调触发
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ===== 全屏：系统栏 =====
    DisposableEffect(isFullscreen) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (isFullscreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }

    // ===== 全屏：屏幕方向 =====
    // 短剧全屏 = 竖屏沉浸（可切横屏）；普通视频全屏 = 横屏（可切竖屏）
    LaunchedEffect(isFullscreen, isShortDrama, shortDramaLandscape, videoLandscape) {
        activity?.requestedOrientation = when {
            isShortDrama && shortDramaLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isShortDrama -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            isFullscreen && videoLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isFullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ===== 离开播放页：恢复系统栏 / 方向 / 亮度 =====
    DisposableEffect(Unit) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { w ->
                WindowCompat.getInsetsController(w, view)
                    .show(WindowInsetsCompat.Type.systemBars())
                val attrs = w.attributes
                attrs.screenBrightness = originalBrightness
                w.attributes = attrs
            }
        }
    }

    // 播放中保持屏幕常亮
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    // 返回键：先关选集浮层 → 再退全屏（短剧无窗口态，直接退出）→ 最后退出页面
    BackHandler {
        when {
            episodesOpen -> episodesOpen = false
            isFullscreen && !isShortDrama -> isFullscreen = false
            else -> onBack()
        }
    }

    // 控制层自动隐藏（播放中才隐藏；暂停 / 拖动 / 选集浮层打开时保持显示）
    LaunchedEffect(controlsVisible, playback.isPlaying, episodesOpen, seekPreview) {
        if (controlsVisible && playback.isPlaying && !episodesOpen && seekPreview == null) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }

    // 涟漪提示自动消失
    LaunchedEffect(ripple) {
        if (ripple != null) {
            kotlinx.coroutines.delay(650)
            ripple = null
        }
    }

    // 切集提示自动消失
    LaunchedEffect(switchEpisodeHint) {
        if (switchEpisodeHint != null) {
            kotlinx.coroutines.delay(900)
            switchEpisodeHint = null
        }
    }

    val seekStepMs = prefs.doubleTapSeekSec * 1000L

    // 横屏全屏判定（上下滑切上下集用）：短剧横屏，或普通视频横屏全屏（全屏但切竖屏时不算横屏）
    val isLandscapeFullscreen = if (isShortDrama) shortDramaLandscape else (isFullscreen && videoLandscape)
    // rememberUpdatedState：让 pointerInput(Unit) 的闭包读到最新值（v1.4.7.0）
    val currentIsLandscapeFullscreen by rememberUpdatedState(isLandscapeFullscreen)
    val currentVerticalSwipeSwitch by rememberUpdatedState(prefs.verticalSwipeSwitch)

    // 亮度读写
    fun currentBrightness(): Float {
        val b = activity?.window?.attributes?.screenBrightness ?: -1f
        return if (b < 0f) 1f else b
    }

    fun applyBrightness(value: Float) {
        val window = activity?.window ?: return
        val attrs = window.attributes
        attrs.screenBrightness = value
        window.attributes = attrs
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 播放区（窗口态 16:9，全屏态铺满） =====
        Box(
            modifier = Modifier
                .then(
                    if (isFullscreen) Modifier.fillMaxSize()
                    else Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false // 使用自定义控制层
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // ===== 手势层：单击显隐控制层 / 双击快进快退 / 长按倍速 / 拖动 =====
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(seekStepMs) {
                        detectTapGestures(
                            onPress = {
                                tryAwaitRelease()
                                viewModel.endSpeedBoost()
                            },
                            onLongPress = { offset ->
                                val forward = offset.x >= size.width / 2f
                                viewModel.beginSpeedBoost(if (forward) 1 else -1)
                                controlsVisible = false
                            },
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { offset ->
                                val forward = offset.x >= size.width / 2f
                                viewModel.seekBy(if (forward) seekStepMs else -seekStepMs)
                                ripple = SeekRipple(forward, (seekStepMs / 1000).toInt())
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        var mode = 0 // 0 未定 1 横滑 2 竖滑
                        var totalDx = 0f
                        var totalDy = 0f
                        var dragStartX = 0f
                        var startPosMs = 0L
                        var startBrightness = 1f
                        var startVolume = 0
                        // 横屏全屏时上下滑切集：累积位移 + 是否已触发（防止一次滑动连续切多集）
                        var switchTriggered = false
                        val slop = viewConfiguration.touchSlop

                        detectDragGestures(
                            onDragStart = { offset ->
                                mode = 0
                                totalDx = 0f
                                totalDy = 0f
                                dragStartX = offset.x
                                switchTriggered = false
                                startPosMs = viewModel.state.value.positionMs
                                startBrightness = currentBrightness()
                                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDx += dragAmount.x
                                totalDy += dragAmount.y
                                if (mode == 0 && (abs(totalDx) > slop || abs(totalDy) > slop)) {
                                    mode = if (abs(totalDx) > abs(totalDy)) 1 else 2
                                }
                                when (mode) {
                                    1 -> {
                                        val duration = viewModel.state.value.durationMs
                                        if (duration > 0L) {
                                            val delta = totalDx / size.width * duration
                                            val target = (startPosMs + delta).toLong()
                                                .coerceIn(0L, duration)
                                            seekPreview = SeekPreview(target, target - startPosMs)
                                            controlsVisible = false
                                        }
                                    }
                                    2 -> {
                                        if (currentIsLandscapeFullscreen && currentVerticalSwipeSwitch) {
                                            // v1.5.4.0：横屏全屏竖滑按起始位置三分区
                                            // 左 1/3 调亮度、右 1/3 调音量、中间 1/3 切上下集
                                            val leftZone = dragStartX < size.width / 3f
                                            val rightZone = dragStartX >= size.width * 2f / 3f
                                            if (leftZone || rightZone) {
                                                val fraction = -totalDy / size.height
                                                if (leftZone) {
                                                    val v = (startBrightness + fraction).coerceIn(0.02f, 1f)
                                                    applyBrightness(v)
                                                    verticalIndicator = VerticalIndicator(true, v)
                                                } else {
                                                    val max = audioManager
                                                        .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                                        .coerceAtLeast(1)
                                                    val v = (startVolume + fraction * max).roundToInt()
                                                        .coerceIn(0, max)
                                                    audioManager.setStreamVolume(
                                                        AudioManager.STREAM_MUSIC, v, 0
                                                    )
                                                    verticalIndicator =
                                                        VerticalIndicator(false, v / max.toFloat())
                                                }
                                                controlsVisible = false
                                            } else {
                                                val threshold = size.height * 0.22f
                                                if (!switchTriggered && totalDy < -threshold) {
                                                    // 上滑 → 下一集
                                                    switchTriggered = true
                                                    val next = viewModel.peekNext()
                                                    if (next != null) {
                                                        viewModel.playNext()
                                                        switchEpisodeHint = SwitchEpisodeHint(true, next.title)
                                                    }
                                                    controlsVisible = false
                                                } else if (!switchTriggered && totalDy > threshold) {
                                                    // 下滑 → 上一集
                                                    switchTriggered = true
                                                    val prev = viewModel.peekPrev()
                                                    if (prev != null) {
                                                        viewModel.playPrev()
                                                        switchEpisodeHint = SwitchEpisodeHint(false, prev.title)
                                                    }
                                                    controlsVisible = false
                                                }
                                            }
                                        } else {
                                            // 竖屏 / 窗口态（含短剧竖屏、横屏未开启切集手势）：
                                            // v1.6.0.0 取消左右边竖滑调亮度 / 音量手势（短剧容易误触），
                                            // 改为上下滑切上下集；亮度 / 音量改走 MoreVert 菜单式调节。
                                            val threshold = size.height * 0.22f
                                            if (!switchTriggered && totalDy < -threshold) {
                                                switchTriggered = true
                                                val next = viewModel.peekNext()
                                                if (next != null) {
                                                    viewModel.playNext()
                                                    switchEpisodeHint = SwitchEpisodeHint(true, next.title)
                                                }
                                                controlsVisible = false
                                            } else if (!switchTriggered && totalDy > threshold) {
                                                switchTriggered = true
                                                val prev = viewModel.peekPrev()
                                                if (prev != null) {
                                                    viewModel.playPrev()
                                                    switchEpisodeHint = SwitchEpisodeHint(false, prev.title)
                                                }
                                                controlsVisible = false
                                            }
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                if (mode == 1) {
                                    seekPreview?.let { viewModel.seekTo(it.targetMs) }
                                }
                                mode = 0
                                seekPreview = null
                                verticalIndicator = null
                            },
                            onDragCancel = {
                                mode = 0
                                seekPreview = null
                                verticalIndicator = null
                            },
                        )
                    },
            )

            // ===== 初次加载 / 缓冲（合并为单一加载指示器，避免两个圈重叠/闪烁） =====
            // loading = 解析地址 + prepare 阶段；isBuffering = prepare 后卡顿/切集缓冲。
            // 两者共用同一个圈，loading 优先显示「正在加载」，否则显示「缓冲中」。
            // 始终留在播放区中央；缓冲时隐藏中央播放控件，避免小窗模式下被推到状态栏附近。
            if (loading || playback.isBuffering) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(10.dp))
                    val buffered = playback.bufferedMs
                    val duration = playback.durationMs
                    val text = if (loading) {
                        "正在加载…"
                    } else if (duration > 0 && buffered > 0) {
                        "缓冲中 ${(buffered * 100 / duration).coerceIn(0, 100)}%"
                    } else {
                        "缓冲中…"
                    }
                    Text(
                        text = text,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                    )
                }
            }
            error?.let { msg ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = msg, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.loadEpisode(
                        episodesList.getOrNull(0) ?: episode,
                        restoreProgress = true,
                    ) }) {
                        Text("重试", color = Color.White)
                    }
                }
            }

            // ===== 长按加速提示 =====
            if (playback.boostSpeed > 0f) {
                val forward = playback.boostDirection >= 0
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    Row(
                        modifier = Modifier
                            .padding(top = 28.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${trimSpeed(playback.boostSpeed)}x " +
                                if (forward) "快进中" else "快退中",
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            // ===== 双击涟漪 =====
            ripple?.let { r ->
                Box(
                    modifier = Modifier
                        .align(
                            if (r.forward) Alignment.CenterEnd else Alignment.CenterStart
                        )
                        .padding(horizontal = 40.dp)
                        .size(98.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (r.forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = (if (r.forward) "+" else "-") + "${r.seconds}s",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ===== 横滑进度预览 =====
            seekPreview?.let { preview ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "${formatDuration(preview.targetMs)} / " +
                            formatDuration(playback.durationMs) +
                            "  (" + (if (preview.deltaMs >= 0) "+" else "-") +
                            formatDuration(abs(preview.deltaMs)) + ")",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }

            // ===== 竖滑亮度 / 音量 =====
            verticalIndicator?.let { ind ->
                Box(
                    modifier = Modifier
                        .align(
                            if (ind.brightness) Alignment.CenterStart else Alignment.CenterEnd
                        )
                        .padding(horizontal = 20.dp)
                        .width(52.dp)
                        .height(170.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            if (ind.brightness) Icons.Filled.Brightness6 else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(90.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.3f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .fillMaxHeight(ind.value.coerceIn(0f, 1f))
                                    .background(Color.White),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${(ind.value * 100).toInt().coerceIn(0, 100)}%",
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // ===== 横屏上下滑切集提示（v1.4.7.0） =====
            switchEpisodeHint?.let { hint ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (hint.next) Icons.Filled.SkipNext else Icons.Filled.SkipPrevious,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = (if (hint.next) "下一集 · " else "上一集 · ") + hint.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ===== 自定义控制层 =====
            // v1.6.4.0：短剧副标题加上「第 X / Y 集」信息（原红果式底栏已删除，
            // 这些信息合并到顶栏副标题里集中展示，避免分散到两处）。
            val curIdx = episodesList.indexOfFirst { it.url == currentEpisodeUrl }
                .let { if (it >= 0) it else 0 }
            val headerSubtitle = when {
                isShortDrama && episodesList.isNotEmpty() ->
                    "第 ${curIdx + 1} / ${episodesList.size} 集 · $episodeTitle"
                else -> episodeTitle
            }
            VideoControlsOverlay(
                visible = controlsVisible && !inPip,
                title = item.title,
                subtitle = headerSubtitle,
                isPlaying = playback.isPlaying,
                isBuffering = playback.isBuffering && !loading,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                bufferedMs = playback.bufferedMs,
                speed = playback.speed,
                isFullscreen = if (isShortDrama) shortDramaLandscape else isFullscreen,
                hasPrev = viewModel.hasPrev(),
                hasNext = viewModel.hasNext(),
                onBack = onBack,
                onTogglePlay = { viewModel.togglePlay() },
                onSeek = { viewModel.seekTo(it) },
                onSpeedChange = { viewModel.setSpeed(it) },
                onPrev = { viewModel.playPrev() },
                onNext = { viewModel.playNext() },
                onToggleFullscreen = {
                    if (isShortDrama) shortDramaLandscape = !shortDramaLandscape
                    else isFullscreen = !isFullscreen
                },
                onToggleOrientation = {
                    if (isShortDrama) {
                        shortDramaLandscape = !shortDramaLandscape
                    } else if (!isFullscreen) {
                        // 窗口态点旋转：先进入全屏（默认横屏）
                        isFullscreen = true
                        videoLandscape = true
                    } else {
                        videoLandscape = !videoLandscape
                    }
                },
                onOpenEpisodes = { episodesOpen = true },
                // v1.6.5.0：短剧在菜单栏目里加「详情」入口（普通视频保持 5 按钮不变）
                onOpenDetail = onOpenDetail,
                showDetailButton = isShortDrama,
                qualityTracks = qualityTracks,
                selectedQuality = selectedQuality,
                onSelectQuality = { viewModel.selectQuality(it) },
                onResetQuality = { viewModel.resetQuality() },
                subtitleName = subtitleName,
                subtitleEnabled = subtitleEnabled,
                onLoadSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
                onToggleSubtitle = { viewModel.toggleSubtitle() },
                onClearSubtitle = { viewModel.clearSubtitle() },
                // v1.6.0.0：短剧把更多菜单合并到底部面板；长视频保留 MoreVert（含亮度 / 音量滑块）
                hideMoreMenu = isShortDrama,
                // v1.6.3.0：短剧顶部「List」图标与底部红果式常驻栏的「选集」按钮重复，
                // 合并到底部「菜单栏目」，顶部不再保留该入口，避免用户搞不清。
                hideEpisodeIcon = isShortDrama,
                showCenterControls = !loading && !playback.isBuffering,
                currentBrightness = { currentBrightness() },
                onBrightnessChange = { applyBrightness(it) },
                currentVolumeFraction = {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
                },
                onVolumeChange = { fraction ->
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val v = (fraction * max).roundToInt().coerceIn(0, max)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                },
            )

            // ===== 短剧底部常驻栏（红果式：选集按钮 + 剧名 + 集数）=====
            // v1.6.4.0 删除：选集入口已合并到底部操作栏（与倍速/字幕/全屏同一行）。
            // 原来红果式底栏和操作栏都锚定屏幕底部，导致「选集」与「字幕」视觉挤在一起。
            // 集数信息已合并进 VideoControlsOverlay 顶栏副标题（见下方 subtitle 拼接）。

            // ===== 选集 / 换源 浮层（短剧用红果式，普通视频用双 Tab 面板）=====
            if (isShortDrama) {
                ShortDramaEpisodeOverlay(
                    rememberedLineIndex = rememberedLineIndex,
                    visible = episodesOpen && !inPip,
                    episodes = episodesList,
                    currentUrl = currentEpisodeUrl,
                    lines = lines,
                    currentLineIndex = currentLineIndex,
                    qualityTracks = qualityTracks,
                    selectedQuality = selectedQuality,
                    onSelectQuality = { viewModel.selectQuality(it) },
                    onResetQuality = { viewModel.resetQuality() },
                    currentBrightness = { currentBrightness() },
                    onBrightnessChange = { applyBrightness(it) },
                    currentVolumeFraction = {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
                    },
                    onVolumeChange = { fraction ->
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val v = (fraction * max).roundToInt().coerceIn(0, max)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    },
                    onPick = {
                        viewModel.playEpisode(episodesList.getOrNull(it) ?: return@ShortDramaEpisodeOverlay)
                        episodesOpen = false
                    },
                    onPickLine = { viewModel.switchLine(it) },
                    onDismiss = { episodesOpen = false },
                )
            } else {
                EpisodeLinePickerOverlay(
                    rememberedLineIndex = rememberedLineIndex,
                    visible = episodesOpen && !inPip,
                    tab = sheetTab,
                    onTabChange = { sheetTab = it },
                    episodes = episodesList,
                    currentUrl = currentEpisodeUrl,
                    lines = lines,
                    currentLineIndex = currentLineIndex,
                    lineSpeeds = lineSpeeds,
                    speedTesting = speedTesting,
                    onPick = {
                        viewModel.playEpisode(it)
                        episodesOpen = false
                    },
                    onPickLine = { viewModel.switchLine(it) },
                    onSpeedTest = { viewModel.speedTest() },
                    onPickFastest = { viewModel.switchToFastestLine() },
                    onDismiss = { episodesOpen = false },
                )
            }
        }

        if (!isFullscreen) {
            // ===== 窗口态：选集 / 换源 面板（短剧无窗口态，始终全屏）=====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = episodeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.playPrev() },
                    enabled = viewModel.hasPrev(),
                ) { Text("上一集") }
                TextButton(
                    onClick = { viewModel.playNext() },
                    enabled = viewModel.hasNext(),
                ) { Text("下一集") }
            }
            EpisodeLineSheet(
                dark = false,
                rememberedLineIndex = rememberedLineIndex,
                tab = sheetTab,
                onTabChange = { sheetTab = it },
                episodes = episodesList,
                currentUrl = currentEpisodeUrl,
                lines = lines,
                currentLineIndex = currentLineIndex,
                lineSpeeds = lineSpeeds,
                speedTesting = speedTesting,
                onPick = { viewModel.playEpisode(it) },
                onPickLine = { viewModel.switchLine(it) },
                onSpeedTest = { viewModel.speedTest() },
                onPickFastest = { viewModel.switchToFastestLine() },
            )
        }
    }
}

/** 从 Context 向上找到宿主 Activity */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 进入画中画（PIP）小窗。
 *
 * 视频比例优先取当前播放器宽高比，未知则回退 16:9。
 * Android 8.0（API 26）及以上才支持；低版本静默忽略。
 */
private fun enterPip(activity: Activity?) {
    val a = activity ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (a.isInPictureInPictureMode) return
    try {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        a.enterPictureInPictureMode(params)
    } catch (_: Throwable) {
        // 个别厂商 ROM 可能不支持，静默降级为普通后台
    }
}
