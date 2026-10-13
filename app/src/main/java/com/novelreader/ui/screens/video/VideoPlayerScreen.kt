package com.novelreader.ui.screens.video

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
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
    val lineSpeeds by viewModel.lineSpeeds.collectAsState()
    val speedTesting by viewModel.speedTesting.collectAsState()

    // 创建并绑定 ExoPlayer（生命周期由 Composable 管理）
    val player = remember {
        ExoPlayer.Builder(context).build().also { viewModel.attachPlayer(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveCurrentProgress()
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
    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenInited by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var episodesOpen by remember { mutableStateOf(false) }
    // 「选集 / 换源」双 Tab 的当前 Tab（v1.3.6.0）；窗口态与全屏浮层共用同一个下标
    var sheetTab by remember { mutableIntStateOf(SheetTabs.EPISODES) }
    var ripple by remember { mutableStateOf<SeekRipple?>(null) }
    var seekPreview by remember { mutableStateOf<SeekPreview?>(null) }
    var verticalIndicator by remember { mutableStateOf<VerticalIndicator?>(null) }

    // 首帧后按设置决定是否直接全屏
    LaunchedEffect(prefs.loaded, prefs.fullscreenDefault) {
        if (prefs.loaded && !fullscreenInited) {
            isFullscreen = prefs.fullscreenDefault
            fullscreenInited = true
        }
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
    LaunchedEffect(isFullscreen) {
        activity?.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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

    // 返回键：先关选集浮层 → 再退全屏 → 最后退出页面
    BackHandler {
        when {
            episodesOpen -> episodesOpen = false
            isFullscreen -> isFullscreen = false
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

    val seekStepMs = prefs.doubleTapSeekSec * 1000L

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
                        val slop = viewConfiguration.touchSlop

                        detectDragGestures(
                            onDragStart = { offset ->
                                mode = 0
                                totalDx = 0f
                                totalDy = 0f
                                dragStartX = offset.x
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
                                        val fraction = -totalDy / size.height
                                        if (dragStartX < size.width / 2f) {
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

            // ===== 初次加载 / 错误 =====
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
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

            // ===== 自定义控制层 =====
            VideoControlsOverlay(
                visible = controlsVisible,
                title = item.title,
                subtitle = episodeTitle,
                isPlaying = playback.isPlaying,
                isBuffering = playback.isBuffering && !loading,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                bufferedMs = playback.bufferedMs,
                speed = playback.speed,
                isFullscreen = isFullscreen,
                hasPrev = viewModel.hasPrev(),
                hasNext = viewModel.hasNext(),
                onBack = onBack,
                onTogglePlay = { viewModel.togglePlay() },
                onSeek = { viewModel.seekTo(it) },
                onSpeedChange = { viewModel.setSpeed(it) },
                onPrev = { viewModel.playPrev() },
                onNext = { viewModel.playNext() },
                onToggleFullscreen = { isFullscreen = !isFullscreen },
                onOpenEpisodes = { episodesOpen = true },
            )

            // ===== 选集 / 换源 浮层 =====
            EpisodeLinePickerOverlay(
                visible = episodesOpen,
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

        if (!isFullscreen) {
            // ===== 窗口态：选集 / 换源 面板 =====
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
