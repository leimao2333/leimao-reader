package com.novelreader.ui.screens.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelreader.core.util.LineSwitch
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ChapterLine

/** 倍速可选档位（MoonTV 同款） */
val VIDEO_SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/** 毫秒 → `mm:ss` / `h:mm:ss` */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 播放器控制层（MoonTV / ArtPlayer 风格）。
 *
 * 顶部：返回 + 标题 + 选集入口
 * 中部：播放 / 暂停大按钮
 * 底部：当前时间 / 进度条（含缓冲）/ 总时长 + 倍速 / 上一集 / 下一集 / 全屏
 */
@Composable
fun BoxScope.VideoControlsOverlay(
    visible: Boolean,
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    speed: Float,
    isFullscreen: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleOrientation: () -> Unit,
    onOpenEpisodes: () -> Unit,
    /**
     * 打开详情页（v1.6.5.0 短剧详情页入口）。
     *
     * 短剧卡片是「红果式」一键直达播放的，之前没有详情页可达路径——
     * 简介 / 标签 / 完整选集都看不到。现在底部操作栏（菜单栏目）加「详情」按钮，
     * 点击跳视频详情页（`VideoDetailScreen` 对短剧条目同样适用）。
     */
    onOpenDetail: () -> Unit = {},
    /**
     * 是否在底部操作栏显示「详情」按钮（v1.6.5.0）。
     *
     * 默认 false（普通视频保持 5 个按钮不变）；短剧模式传 true —— 短剧没有
     * 从卡片进详情页的路径，必须给播放器一个直达详情的入口。
     */
    showDetailButton: Boolean = false,
    qualityTracks: List<QualityTrack> = emptyList(),
    selectedQuality: QualityTrack? = null,
    onSelectQuality: (QualityTrack) -> Unit = {},
    onResetQuality: () -> Unit = {},
    subtitleName: String? = null,
    subtitleEnabled: Boolean = false,
    onLoadSubtitle: () -> Unit = {},
    onToggleSubtitle: () -> Unit = {},
    onClearSubtitle: () -> Unit = {},
    /**
     * 隐藏 MoreVert 按钮（v1.6.0.0）。短剧模式下整个画面相关的菜单已经合并到底部
     * 面板（[ShortDramaEpisodeOverlay]），顶部不再保留 ⋮ 入口以免与底部「选集」按钮
     * 撞到一起、用户搞不清入口。
     */
    hideMoreMenu: Boolean = false,
    /**
     * 隐藏顶部「选集」入口图标（v1.6.3.0 引入，v1.6.4.0 调整为「仅隐藏顶部图标」）。
     *
     * 历史：v1.6.3.0 之前，短剧模式有两个「选集」入口（顶部 List 图标 + 底部红果式常驻栏的「选集」按钮），
     * 两者指向同一个底部浮层，纯属重复 → 删顶部。
     *
     * v1.6.4.0 反馈：删顶部 List 后发现底部红果式「选集」与操作栏「字幕」**视觉挤在一起**
     * （两者都锚定屏幕底部，垂直重叠）。改为：**完全删除红果式常驻栏**，
     * 「选集」按钮作为底部操作栏的一员（与「倍速 / 字幕 / 全屏」同一行）。
     *
     * 现在本参数仍保留，控制顶部 List 图标是否渲染（普通视频默认 false；v1.6.4.0 起两边都显示
     * 「选集」——顶部图标与底部操作栏图标并存，普通视频顶部按钮冗余未改，避免变动太大）。
     */
    hideEpisodeIcon: Boolean = false,
    /** 菜单式调亮度 / 音量（v1.6.0.0）。仅在 MoreVert 开启时生效 */
    currentBrightness: () -> Float = { 1f },
    onBrightnessChange: (Float) -> Unit = {},
    currentVolumeFraction: () -> Float = { 1f },
    onVolumeChange: (Float) -> Unit = {},
) {
    val duration = durationMs.coerceAtLeast(0L)

    // ===== 顶部栏 =====
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopStart),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                    )
                )
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { "视频播放" },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank() && subtitle != title) {
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!hideEpisodeIcon) {
                IconButton(onClick = onOpenEpisodes) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "选集", tint = Color.White)
                }
            }
        }
    }

    // ===== 中央播放 / 暂停 =====
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onPrev,
                enabled = hasPrev,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "上一集",
                    tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.width(28.dp))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                // 缓冲状态由播放层中央的单一加载指示器呈现，这里保持播放/暂停图标，
                // 避免控制栏与中央指示器同时出现两个圈。
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
            }
            Spacer(Modifier.width(28.dp))
            IconButton(
                onClick = onNext,
                enabled = hasNext,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "下一集",
                    tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }

    // ===== 底部进度 + 操作栏 =====
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        var dragValue by remember { mutableStateOf<Float?>(null) }
        var speedMenuOpen by remember { mutableStateOf(false) }
        var qualityMenuOpen by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                    )
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // 进度条：拖动时显示目标时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDuration(((dragValue ?: if (duration > 0) positionMs / duration.toFloat() else 0f) * duration).toLong()),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Slider(
                    value = dragValue ?: if (duration > 0) (positionMs / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { onSeek((it * duration).toLong()) }
                        dragValue = null
                    },
                    enabled = duration > 0,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF4CAF50),
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Text(
                    text = formatDuration(duration),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }

            // 操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 倍速
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { speedMenuOpen = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Speed,
                            contentDescription = "倍速",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (speed == 1f) "倍速" else "${trimSpeed(speed)}x",
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                    DropdownMenu(
                        expanded = speedMenuOpen,
                        onDismissRequest = { speedMenuOpen = false },
                    ) {
                        VIDEO_SPEED_OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${trimSpeed(option)}x" + if (option == 1f) "（正常）" else "",
                                        fontWeight = if (option == speed) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    onSpeedChange(option)
                                    speedMenuOpen = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = if (isBuffering) "缓冲中…" else "",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                )

                Spacer(Modifier.weight(1f))

                // 外挂字幕（v1.4.6.0）：加载本地 srt/vtt，开关显示
                Box {
                    var subtitleMenuOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { subtitleMenuOpen = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.ClosedCaption,
                            contentDescription = "字幕",
                            tint = if (subtitleName != null && subtitleEnabled) Color(0xFF4CAF50) else Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (subtitleName == null) "字幕" else "字幕",
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                    DropdownMenu(
                        expanded = subtitleMenuOpen,
                        onDismissRequest = { subtitleMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(subtitleName?.let { "加载新字幕" } ?: "加载字幕") },
                            onClick = {
                                onLoadSubtitle()
                                subtitleMenuOpen = false
                            },
                        )
                        if (subtitleName != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (subtitleEnabled) "关闭字幕" else "开启字幕",
                                        fontWeight = if (subtitleEnabled) FontWeight.Normal else FontWeight.Bold,
                                    )
                                },
                                onClick = {
                                    onToggleSubtitle()
                                    subtitleMenuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("移除字幕", color = Color(0xFFFF6B6B)) },
                                onClick = {
                                    onClearSubtitle()
                                    subtitleMenuOpen = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 选集入口（v1.6.4.0）：短剧模式下「选集」与底部操作栏的
                // 倍速/字幕/全屏合并到同一行（替代原来的红果式独立底栏，
                // 解决「选集」与「字幕」视觉挤在一起的体验问题）。
                // 普通视频同样显示此入口，与原来顶部 List 图标职责一致。
                IconButton(onClick = onOpenEpisodes) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = "选集",
                        tint = Color.White,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // 详情页入口（v1.6.5.0）：短剧一键直达播放、没有卡片进详情的路径，
                // 在菜单栏目里补一个「详情」按钮，跳 VideoDetailScreen
                // （简介 / 标签小栏 / 完整选集 / 收藏 / 下载）。
                if (showDetailButton) {
                    IconButton(onClick = onOpenDetail) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "详情",
                            tint = Color.White,
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 横竖切换（旋转）：全屏时在横屏 / 竖屏之间切换
                IconButton(onClick = onToggleOrientation) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        contentDescription = "横竖切换",
                        tint = Color.White,
                    )
                }

                Spacer(Modifier.width(2.dp))

                // 更多菜单：清晰度（仅多码率源）+ 亮度 + 音量（v1.6.0.0 菜单式调节）
                if (!hideMoreMenu) {
                    Box {
                        IconButton(onClick = { qualityMenuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = Color.White,
                            )
                        }
                        DropdownMenu(
                            expanded = qualityMenuOpen,
                            onDismissRequest = { qualityMenuOpen = false },
                        ) {
                            // 亮度滑块（v1.6.0.0）
                            var brightnessValue by remember {
                                mutableStateOf(currentBrightness().coerceIn(0.02f, 1f))
                            }
                            Text(
                                text = "亮度 ${(brightnessValue * 100).toInt()}%",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            Slider(
                                value = brightnessValue,
                                onValueChange = {
                                    brightnessValue = it
                                    onBrightnessChange(it)
                                },
                                valueRange = 0.02f..1f,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .width(220.dp),
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

                            // 音量滑块（v1.6.0.0）
                            var volumeValue by remember {
                                mutableStateOf(currentVolumeFraction().coerceIn(0f, 1f))
                            }
                            Text(
                                text = "音量 ${(volumeValue * 100).toInt()}%",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            Slider(
                                value = volumeValue,
                                onValueChange = {
                                    volumeValue = it
                                    onVolumeChange(it)
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .width(220.dp),
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

                            if (qualityTracks.isNotEmpty()) {
                                Text(
                                    text = "清晰度",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "自动",
                                            fontWeight = if (selectedQuality == null) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        onResetQuality()
                                        qualityMenuOpen = false
                                    },
                                )
                                qualityTracks.forEach { track ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = track.label,
                                                fontWeight = if (selectedQuality?.height == track.height) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            onSelectQuality(track)
                                            qualityMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** 1.0 → "1"，1.25 → "1.25" */
fun trimSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString()
    else speed.toString().trimEnd('0').trimEnd('.')

/**
 * 播放页底部「选集 / 换源」双 Tab 浮层（v1.3.6.0）。
 *
 * 只是把 [EpisodeLineSheet] 包一层遮罩 + 深色容器；内容与窗口态完全共用。
 */
@Composable
fun EpisodeLinePickerOverlay(
    visible: Boolean,
    tab: Int,
    onTabChange: (Int) -> Unit,
    episodes: List<ChapterItem>,
    currentUrl: String,
    lines: List<ChapterLine>,
    currentLineIndex: Int,
    /** 记忆里「上次用的线路」下标（v1.6.9.0），打「上次」角标用 */
    rememberedLineIndex: Int? = null,
    lineSpeeds: Map<Int, Long?>,
    speedTesting: Boolean,
    onPick: (ChapterItem) -> Unit,
    onPickLine: (Int) -> Unit,
    onSpeedTest: () -> Unit,
    onPickFastest: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(Color(0xFF16181C))
                .clickable(enabled = false) {}
                .padding(top = 10.dp),
        ) {
            EpisodeLineSheet(
                dark = true,
                tab = tab,
                onTabChange = onTabChange,
                episodes = episodes,
                currentUrl = currentUrl,
                lines = lines,
                currentLineIndex = currentLineIndex,
                rememberedLineIndex = rememberedLineIndex,
                lineSpeeds = lineSpeeds,
                speedTesting = speedTesting,
                onPick = onPick,
                onPickLine = onPickLine,
                onSpeedTest = onSpeedTest,
                onPickFastest = onPickFastest,
            )
        }
    }
}

/** 面板配色。深色浮层不能直接用 MaterialTheme（浅色主题下会变白底白字） */
private data class SheetPalette(
    val accent: Color,
    val text: Color,
    val subText: Color,
    val divider: Color,
    val tabTrack: Color,
    val tabSelected: Color,
    val tagBg: Color,
)

/** 深色浮层不能用 MaterialTheme 的动态色（浅色主题下会变白底白字），所以浅色分支才读主题色 */
@Composable
private fun sheetPalette(dark: Boolean): SheetPalette = if (dark) {
    SheetPalette(
        accent = Color(0xFF7ED694),
        text = Color.White,
        subText = Color.White.copy(alpha = 0.55f),
        divider = Color.White.copy(alpha = 0.10f),
        tabTrack = Color.White.copy(alpha = 0.08f),
        tabSelected = Color(0xFF4CAF50).copy(alpha = 0.30f),
        tagBg = Color(0xFF4CAF50).copy(alpha = 0.20f),
    )
} else {
    SheetPalette(
        accent = MaterialTheme.colorScheme.primary,
        text = MaterialTheme.colorScheme.onSurface,
        subText = MaterialTheme.colorScheme.onSurfaceVariant,
        divider = MaterialTheme.colorScheme.outlineVariant,
        tabTrack = MaterialTheme.colorScheme.surfaceVariant,
        tabSelected = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        tagBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    )
}

/** 面板 Tab 下标 */
object SheetTabs {
    const val EPISODES = 0
    const val LINES = 1
}

/**
 * 「选集 / 换源」双 Tab 面板内容（不含遮罩）。
 *
 * 全屏浮层（[dark] = true）与窗口态（[dark] = false）共用，避免两处各写一份。
 */
@Composable
fun EpisodeLineSheet(
    dark: Boolean,
    tab: Int,
    onTabChange: (Int) -> Unit,
    episodes: List<ChapterItem>,
    currentUrl: String,
    lines: List<ChapterLine>,
    currentLineIndex: Int,
    /** 记忆里「上次用的线路」下标（v1.6.9.0），打「上次」角标用 */
    rememberedLineIndex: Int? = null,
    lineSpeeds: Map<Int, Long?>,
    speedTesting: Boolean,
    onPick: (ChapterItem) -> Unit,
    onPickLine: (Int) -> Unit,
    onSpeedTest: () -> Unit,
    onPickFastest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = sheetPalette(dark)
    Column(modifier = modifier.fillMaxSize()) {
        // ===== Tab 行 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetTab("选集（${episodes.size}）", tab == SheetTabs.EPISODES, c) {
                onTabChange(SheetTabs.EPISODES)
            }
            Spacer(Modifier.width(8.dp))
            SheetTab("换源（${lines.size}）", tab == SheetTabs.LINES, c) {
                onTabChange(SheetTabs.LINES)
            }
            Spacer(Modifier.weight(1f))
            // 换源 Tab 才需要的两个动作放在 Tab 行右侧，省一行高度
            if (tab == SheetTabs.LINES && lines.size > 1) {
                Text(
                    text = if (speedTesting) "测速中…" else "测速",
                    color = if (speedTesting) c.subText else c.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !speedTesting) { onSpeedTest() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    text = "选最快",
                    color = c.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPickFastest() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (tab == SheetTabs.LINES) {
            LineList(
                lines = lines,
                currentLineIndex = currentLineIndex,
                rememberedLineIndex = rememberedLineIndex,
                lineSpeeds = lineSpeeds,
                speedTesting = speedTesting,
                colors = c,
                onPickLine = onPickLine,
            )
        } else {
            EpisodeList(episodes, currentUrl, c, onPick)
        }
    }
}

@Composable
private fun SheetTab(label: String, selected: Boolean, c: SheetPalette, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) c.accent else c.subText,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.tabSelected else c.tabTrack)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun EpisodeList(
    episodes: List<ChapterItem>,
    currentUrl: String,
    c: SheetPalette,
    onPick: (ChapterItem) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(episodes) { index, ep ->
            val current = ep.url == currentUrl
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(ep) }
                    .background(if (current) c.tabSelected else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    color = if (current) c.accent else c.subText,
                    fontSize = 13.sp,
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    text = ep.title,
                    color = if (current) c.accent else c.text,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * 线路列表：线路名 + 集数 + 测速结果，当前线路高亮，最快的标「最快」。
 *
 * v1.6.9.0：记忆里「上次用的线路」标「上次」——用户能一眼看出进播放页时
 * 被自动纠到了哪条（当前线路同时还有「播放中」，两个角标各说一件事，不冲突）。
 */
@Composable
private fun LineList(
    lines: List<ChapterLine>,
    currentLineIndex: Int,
    lineSpeeds: Map<Int, Long?>,
    speedTesting: Boolean,
    colors: SheetPalette,
    onPickLine: (Int) -> Unit,
    rememberedLineIndex: Int? = null,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { index, line ->
            val current = index == currentLineIndex
            val remembered = index == rememberedLineIndex
            val speed = lineSpeeds[index]
            val measured = lineSpeeds.containsKey(index)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickLine(index) }
                    .background(if (current) colors.tabSelected else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = line.name,
                            color = if (current) colors.accent else colors.text,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (remembered) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "上次",
                                color = if (current) colors.accent else colors.subText,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .background(colors.divider, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                        if (LineSwitch.isFastest(lineSpeeds, index, lines.size)) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "最快",
                                color = colors.accent,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .background(colors.tagBg, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${line.chapters.size} 集" + speedLabel(measured, speed, speedTesting),
                        color = colors.subText,
                        fontSize = 12.sp,
                    )
                }
                if (current) {
                    Text(text = "播放中", color = colors.accent, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 16.dp))
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 测速文案：未测 / 测速中 / 具体毫秒 / 不可达 */
private fun speedLabel(measured: Boolean, speed: Long?, testing: Boolean): String = when {
    !measured -> if (testing) " · 测速中…" else ""
    speed == null -> " · 不可达"
    else -> " · ${speed}ms"
}

// ==================== 红果式短剧选集浮层（v1.3.10.0） ====================

/**
 * 红果短剧风格的选集浮层：底部滑出，集数以竖版大块网格铺开。
 *
 * 与普通视频的「选集 / 换源」双 Tab 面板不同，短剧选集对标红果：
 * - 顶部标题「选集（N）」+ 换源 Tab + 关闭
 * - 每集一个竖版块（红果是 3:4 竖屏缩略图，这里用「第 N 集」大字块）
 * - 当前集高亮描边，点击即切集
 *
 * v1.6.0.0：把 MoreVert 菜单里的清晰度、亮度、音量等画面相关控制**合并**进底层面板
 * （避免短剧模式下顶部「MoreVert」与底部「选集」撞到一起分不清），底部现在有三个 Tab：
 * **选集 / 换源 / 画面**。「画面」里含清晰度选择 + 亮度 / 音量滑块。
 *
 * @param onPick 点选某一集（传入集在列表中的下标）
 * @param onPickLine 切换线路（换源 Tab 用）
 */
@Composable
fun ShortDramaEpisodeOverlay(
    visible: Boolean,
    episodes: List<ChapterItem>,
    currentUrl: String,
    lines: List<ChapterLine>,
    currentLineIndex: Int,
    /** 记忆里「上次用的线路」下标（v1.6.9.0），打「上次」角标用 */
    rememberedLineIndex: Int? = null,
    qualityTracks: List<QualityTrack> = emptyList(),
    selectedQuality: QualityTrack? = null,
    onSelectQuality: (QualityTrack) -> Unit = {},
    onResetQuality: () -> Unit = {},
    currentBrightness: () -> Float = { 1f },
    onBrightnessChange: (Float) -> Unit = {},
    currentVolumeFraction: () -> Float = { 1f },
    onVolumeChange: (Float) -> Unit = {},
    onPick: (Int) -> Unit,
    onPickLine: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(0xFF16181C))
                .clickable(enabled = false) {},
        ) {
            ShortDramaEpisodeSheet(
                episodes = episodes,
                currentUrl = currentUrl,
                lines = lines,
                currentLineIndex = currentLineIndex,
                rememberedLineIndex = rememberedLineIndex,
                qualityTracks = qualityTracks,
                selectedQuality = selectedQuality,
                onSelectQuality = onSelectQuality,
                onResetQuality = onResetQuality,
                currentBrightness = currentBrightness,
                onBrightnessChange = onBrightnessChange,
                currentVolumeFraction = currentVolumeFraction,
                onVolumeChange = onVolumeChange,
                onPick = onPick,
                onPickLine = onPickLine,
            )
        }
    }
}

/** 面板 Tab 下标（短剧用 v1.6.0.0 加 PICTURE 画面 Tab） */
object ShortDramaSheetTabs {
    const val EPISODES = 0
    const val LINES = 1
    const val PICTURE = 2
}

/**
 * 红果式选集面板内容（不含遮罩）。
 *
 * v1.6.0.0：第三个 Tab「画面」承载亮度 / 音量滑块、清晰度选择，取代原来的 MoreVert 菜单
 * （避免与底部「选集」按钮撞在一起分不清入口）。
 */
@Composable
private fun ShortDramaEpisodeSheet(
    episodes: List<ChapterItem>,
    currentUrl: String,
    lines: List<ChapterLine>,
    currentLineIndex: Int,
    rememberedLineIndex: Int? = null,
    qualityTracks: List<QualityTrack> = emptyList(),
    selectedQuality: QualityTrack? = null,
    onSelectQuality: (QualityTrack) -> Unit = {},
    onResetQuality: () -> Unit = {},
    currentBrightness: () -> Float = { 1f },
    onBrightnessChange: (Float) -> Unit = {},
    currentVolumeFraction: () -> Float = { 1f },
    onVolumeChange: (Float) -> Unit = {},
    onPick: (Int) -> Unit,
    onPickLine: (Int) -> Unit,
) {
    val darkPalette = SheetPalette(
        accent = Color(0xFF7ED694),
        text = Color.White,
        subText = Color.White.copy(alpha = 0.55f),
        divider = Color.White.copy(alpha = 0.10f),
        tabTrack = Color.White.copy(alpha = 0.08f),
        tabSelected = Color(0xFF4CAF50).copy(alpha = 0.30f),
        tagBg = Color(0xFF4CAF50).copy(alpha = 0.20f),
    )
    var tab by remember { mutableIntStateOf(ShortDramaSheetTabs.EPISODES) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：选集 / 换源 / 画面 Tab + 当前集信息
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetTab("选集（${episodes.size}）", tab == ShortDramaSheetTabs.EPISODES, darkPalette) {
                tab = ShortDramaSheetTabs.EPISODES
            }
            if (lines.size > 1) {
                Spacer(Modifier.width(8.dp))
                SheetTab("换源（${lines.size}）", tab == ShortDramaSheetTabs.LINES, darkPalette) {
                    tab = ShortDramaSheetTabs.LINES
                }
            }
            Spacer(Modifier.width(8.dp))
            SheetTab("画面", tab == ShortDramaSheetTabs.PICTURE, darkPalette) {
                tab = ShortDramaSheetTabs.PICTURE
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "第 ${episodes.indexOfFirst { it.url == currentUrl }.let { if (it >= 0) it + 1 else 1 }} 集",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }

        when (tab) {
            ShortDramaSheetTabs.LINES -> {
                LineList(
                    lines = lines,
                    currentLineIndex = currentLineIndex,
                    lineSpeeds = emptyMap(),
                    speedTesting = false,
                    colors = darkPalette,
                    onPickLine = onPickLine,
                    rememberedLineIndex = rememberedLineIndex,
                )
            }
            ShortDramaSheetTabs.PICTURE -> {
                PictureTabContent(
                    qualityTracks = qualityTracks,
                    selectedQuality = selectedQuality,
                    onSelectQuality = onSelectQuality,
                    onResetQuality = onResetQuality,
                    currentBrightness = currentBrightness,
                    onBrightnessChange = onBrightnessChange,
                    currentVolumeFraction = currentVolumeFraction,
                    onVolumeChange = onVolumeChange,
                )
            }
            else -> {
                // 选集：红果式竖版大块网格
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    gridItemsIndexed(episodes) { index, ep ->
                        val current = ep.url == currentUrl
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (current) Color(0xFF4CAF50).copy(alpha = 0.28f)
                                    else Color.White.copy(alpha = 0.08f)
                                )
                                .clickable { onPick(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = if (current) Color(0xFF7ED694) else Color.White.copy(alpha = 0.85f),
                                fontSize = 18.sp,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「画面」Tab 内容：清晰度选择 + 亮度 / 音量滑块（v1.6.0.0）。
 *
 * 短剧把原本的 MoreVert 菜单并到这里；亮度 / 音量通过滑块调节（替代左右边手势）。
 */
@Composable
private fun PictureTabContent(
    qualityTracks: List<QualityTrack>,
    selectedQuality: QualityTrack?,
    onSelectQuality: (QualityTrack) -> Unit,
    onResetQuality: () -> Unit,
    currentBrightness: () -> Float,
    onBrightnessChange: (Float) -> Unit,
    currentVolumeFraction: () -> Float,
    onVolumeChange: (Float) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 清晰度（仅多码率源）
        if (qualityTracks.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "清晰度",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScrollable(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QualityChip(
                            label = "自动",
                            selected = selectedQuality == null,
                            onClick = onResetQuality,
                        )
                        qualityTracks.forEach { track ->
                            QualityChip(
                                label = track.label,
                                selected = selectedQuality?.height == track.height,
                                onClick = { onSelectQuality(track) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            }
        }

        // 亮度
        item {
            var brightnessValue by remember {
                mutableStateOf(currentBrightness().coerceIn(0.02f, 1f))
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "亮度 ${(brightnessValue * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
                Slider(
                    value = brightnessValue,
                    onValueChange = {
                        brightnessValue = it
                        onBrightnessChange(it)
                    },
                    valueRange = 0.02f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
        }

        // 音量
        item {
            var volumeValue by remember {
                mutableStateOf(currentVolumeFraction().coerceIn(0f, 1f))
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "音量 ${(volumeValue * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
                Slider(
                    value = volumeValue,
                    onValueChange = {
                        volumeValue = it
                        onVolumeChange(it)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF4CAF50).copy(alpha = 0.30f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF7ED694) else Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * 让 Row 支持水平滚动（短剧画面 Tab 里清晰度 chips 一行不够放时用）。
 *
 * chips 数量一般不会触发滚动，仅为容错。
 */
@Composable
private fun Modifier.horizontalScrollable(): Modifier {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    return this.then(horizontalScroll(scrollState))
}
