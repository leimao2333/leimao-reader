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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.theme.ReaderBackgrounds

/**
 * 小说阅读页。
 *
 * 核心功能：
 * - 正文渲染（上下滚动 / 左右翻页两种模式）
 * - 点击中央区域呼出设置面板
 * - 字体大小调节（A- / A+）
 * - 背景颜色切换（多套配色）
 * - 日/夜间模式切换
 * - 翻页方式切换
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
    val pageMode by viewModel.pageMode.collectAsState()

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
            pageMode == 1 -> {
                // 左右翻页模式
                HorizontalPagerNovel(
                    content = content,
                    chapterTitle = chapterTitle,
                    fontSize = fontSize,
                    textColor = textColor,
                    onTap = { viewModel.toggleSettings() },
                )
            }
            else -> {
                // 上下滚动模式
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

        // 顶部栏（滚动模式）：返回 + 标题 + 设置按钮
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

        // 翻页模式下的顶部/底部栏
        if (pageMode == 1 && error == null && !loading) {
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

        // 设置面板
        if (showSettings) {
            ReaderSettingsPanel(
                viewModel = viewModel,
                fontSize = fontSize,
                nightMode = nightMode,
                pageMode = pageMode,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onDismiss = viewModel::toggleSettings,
            )
        }
    }
}

/**
 * 左右翻页模式：把正文按固定字符数切片分页，HorizontalPager 逐页展示。
 */
@Composable
private fun HorizontalPagerNovel(
    content: String,
    chapterTitle: String,
    fontSize: Float,
    textColor: Color,
    onTap: () -> Unit,
) {
    val pages = remember(content) { splitContentIntoPages(content) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onTap,
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 48.dp),
            ) {
                if (page == 0) {
                    Text(
                        text = chapterTitle,
                        fontSize = (fontSize + 2).sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    text = pages[page],
                    fontSize = fontSize.sp,
                    color = textColor,
                    lineHeight = (fontSize * 1.6).sp,
                    textAlign = TextAlign.Justify,
                )
            }
        }
    }
}

/**
 * 把正文按固定字符数切成若干页（翻页模式用）。
 * 按自然段落边界优先，避免在词中间生硬断开。
 */
private fun splitContentIntoPages(content: String, pageSize: Int = 900): List<String> {
    if (content.isBlank()) return listOf("")
    val paragraphs = content.split("\n").filter { it.isNotBlank() }
    if (paragraphs.isEmpty()) return listOf(content)

    val pages = mutableListOf<String>()
    val buffer = StringBuilder()
    for (para in paragraphs) {
        if (buffer.isNotEmpty() && buffer.length + para.length + 1 > pageSize) {
            pages.add(buffer.toString())
            buffer.clear()
        }
        // 单个超长段落强制切分
        if (para.length > pageSize) {
            if (buffer.isNotEmpty()) {
                pages.add(buffer.toString())
                buffer.clear()
            }
            var start = 0
            while (start < para.length) {
                val end = (start + pageSize).coerceAtMost(para.length)
                pages.add(para.substring(start, end))
                start = end
            }
        } else {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(para)
        }
    }
    if (buffer.isNotEmpty()) pages.add(buffer.toString())
    return pages.ifEmpty { listOf("") }
}

/**
 * 阅读设置面板。
 */
@Composable
private fun BoxScope.ReaderSettingsPanel(
    viewModel: NovelReaderViewModel,
    fontSize: Float,
    nightMode: Boolean,
    pageMode: Int,
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

            // 翻页方式
            Text("翻页方式", color = textColor, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row {
                NovelPageModeButton("上下滚动", pageMode == 0, textColor) { viewModel.setPageMode(0) }
                Spacer(Modifier.width(12.dp))
                NovelPageModeButton("左右翻页", pageMode == 1, textColor) { viewModel.setPageMode(1) }
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

/** 小说翻页方式选择按钮 */
@Composable
private fun NovelPageModeButton(
    text: String,
    selected: Boolean,
    textColor: Color,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color(0xFF4A6FA5) else Color(0xFF333333)
    val fg = if (selected) Color.White else textColor
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
