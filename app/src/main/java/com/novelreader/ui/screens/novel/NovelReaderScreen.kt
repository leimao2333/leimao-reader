package com.novelreader.ui.screens.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.theme.ReaderBackgrounds

/**
 * 小说阅读页。
 *
 * 核心功能：
 * - 正文渲染（可滚动）
 * - 点击中央区域呼出设置面板
 * - 字体大小调节（A- / A+）
 * - 背景颜色切换（多套配色）
 * - 日/夜间模式切换
 * - 上一章 / 下一章
 */
@Composable
fun NovelReaderScreen(
    viewModel: NovelReaderViewModel,
    item: MediaItem,
    chapters: List<ChapterItem>,
    chapter: ChapterItem,
    onBack: () -> Unit,
) {
    val content by viewModel.content.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val bgIndex by viewModel.bgIndex.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()
    val chapterTitle by viewModel.chapterTitle.collectAsState()

    LaunchedEffect(item, chapter) {
        viewModel.init(item, chapters, chapter)
    }

    // 根据日夜间模式选择配色
    val backgroundColors = if (nightMode) ReaderBackgrounds.nightColors else ReaderBackgrounds.dayColors
    val bgIndexSafe = bgIndex.coerceIn(backgroundColors.indices)
    val backgroundColor = backgroundColors[bgIndexSafe].first
    val textColor = if (nightMode) Color(0xFFB0B0B0) else Color(0xFF1A1A1A)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        when {
            loading -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            error != null -> {
                Text(
                    text = error ?: "加载失败",
                    color = textColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }
            else -> {
                // 正文内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                        ) { viewModel.toggleSettings() }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                ) {
                    Text(
                        text = chapterTitle,
                        fontSize = (fontSize + 2).sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = content,
                        fontSize = fontSize.sp,
                        color = textColor,
                        lineHeight = (fontSize * 1.6).sp,
                        textAlign = TextAlign.Justify,
                    )
                }

                // 顶部栏：返回 + 标题 + 设置按钮
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(backgroundColor.copy(alpha = 0.9f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "‹ 返回",
                        color = textColor,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = chapterTitle,
                        color = textColor,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = viewModel::toggleSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置", tint = textColor)
                    }
                }

                // 底部：上一章 / 下一章
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(backgroundColor.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "上一章",
                        color = textColor,
                        modifier = Modifier
                            .clickable(onClick = viewModel::prevChapter)
                            .padding(8.dp),
                    )
                    Text(
                        text = "下一章",
                        color = textColor,
                        modifier = Modifier
                            .clickable(onClick = viewModel::nextChapter)
                            .padding(8.dp),
                    )
                }
            }
        }

        // 设置面板
        if (showSettings) {
            ReaderSettingsPanel(
                viewModel = viewModel,
                fontSize = fontSize,
                nightMode = nightMode,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onDismiss = viewModel::toggleSettings,
            )
        }
    }
}

/**
 * 阅读设置面板。
 */
@Composable
private fun BoxScope.ReaderSettingsPanel(
    viewModel: NovelReaderViewModel,
    fontSize: Float,
    nightMode: Boolean,
    backgroundColor: Color,
    textColor: Color,
    onDismiss: () -> Unit,
) {
    val backgroundColors = if (nightMode) ReaderBackgrounds.nightColors else ReaderBackgrounds.dayColors

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter),
        color = backgroundColor,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 字体大小
            Text("字体大小", color = textColor, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::decreaseFontSize) {
                    Text("A-", color = textColor, fontSize = 16.sp)
                }
                Text(
                    text = "${fontSize.toInt()}",
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                IconButton(onClick = viewModel::increaseFontSize) {
                    Text("A+", color = textColor, fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 背景颜色
            Text("背景颜色", color = textColor, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row {
                backgroundColors.forEachIndexed { index, (color, name) ->
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(36.dp)
                            .background(color, CircleShape)
                            .clickable { viewModel.setBackground(index) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 日夜间切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("夜间模式", color = textColor, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = nightMode,
                    onCheckedChange = { viewModel.toggleNightMode() },
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
