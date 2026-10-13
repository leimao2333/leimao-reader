package com.novelreader.ui.screens.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.novelreader.domain.model.ChapterItem

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
    onOpenEpisodes: () -> Unit,
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
            IconButton(onClick = onOpenEpisodes) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "选集", tint = Color.White)
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
                if (isBuffering) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                } else {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp),
                    )
                }
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
 * 选集浮层：点击切换，当前集高亮。
 */
@Composable
fun EpisodePickerOverlay(
    visible: Boolean,
    episodes: List<ChapterItem>,
    currentUrl: String,
    onPick: (ChapterItem) -> Unit,
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
                .padding(top = 12.dp),
        ) {
            Text(
                text = "选集（${episodes.size}）",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(episodes) { index, ep ->
                    val current = ep.url == currentUrl
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(ep) }
                            .background(
                                if (current) Color(0xFF4CAF50).copy(alpha = 0.18f) else Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = if (current) Color(0xFF7ED694) else Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            text = ep.title,
                            color = if (current) Color(0xFF7ED694) else Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
