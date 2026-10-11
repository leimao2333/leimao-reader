package com.novelreader.ui.screens.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.theme.ReaderBackgrounds
import kotlinx.coroutines.launch

/**
 * 快速单击检测：按下后抬手即判定为单击，不做双击/长按超时等待，
 * 消除菜单呼出的点击延迟。
 */
private fun Modifier.fastTap(onTap: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation()
        if (up != null) onTap()
    }
}

/**
 * 小说阅读页（legado 风格菜单）。
 *
 * - 单击屏幕中央：弹出/收起菜单（顶栏 + 底部菜单）
 * - 底部菜单：上一章 | 进度滑块 | 下一章，以及 目录 / 界面 / 设置 三个按钮
 * - 目录：章节列表面板，点击跳转
 * - 界面：字号 / 背景颜色 / 翻页方式
 * - 设置：夜间模式 + 隐藏栏目开关（顶部栏 / 底部进度栏 / 章节标题）
 * - 翻页方式：上下滚动 / 左右翻页
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
    val showMenu by viewModel.showMenu.collectAsState()
    val menuTab by viewModel.menuTab.collectAsState()
    val chapterTitle by viewModel.chapterTitle.collectAsState()
    val pageMode by viewModel.pageMode.collectAsState()
    val pageAnim by viewModel.pageAnim.collectAsState()
    val hideTopBar by viewModel.hideTopBar.collectAsState()
    val hideBottomBar by viewModel.hideBottomBar.collectAsState()
    val hideChapterTitle by viewModel.hideChapterTitle.collectAsState()
    val chapterIndex by viewModel.currentChapterIndex.collectAsState()

    LaunchedEffect(item, chapter) {
        viewModel.init(item, chapters, chapter)
    }

    val backgroundColors = if (nightMode) ReaderBackgrounds.nightColors else ReaderBackgrounds.dayColors
    val bgIndexSafe = bgIndex.coerceIn(backgroundColors.indices)
    val backgroundColor = backgroundColors[bgIndexSafe].background
    val textColor = backgroundColors[bgIndexSafe].text

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        when {
            loading -> {
                // 加载中也允许点按呼出菜单
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .fastTap { viewModel.toggleMenu() },
                ) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .fastTap { viewModel.toggleMenu() },
                ) {
                    Text(
                        text = error ?: "加载失败",
                        color = textColor,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                }
            }
            pageMode == 1 -> {
                HorizontalPagerNovel(
                    content = content,
                    chapterTitle = chapterTitle,
                    fontSize = fontSize,
                    textColor = textColor,
                    hideChapterTitle = hideChapterTitle,
                    pageAnim = pageAnim,
                    onTap = { viewModel.toggleMenu() },
                )
            }
            else -> {
                ScrollNovel(
                    content = content,
                    chapterTitle = chapterTitle,
                    fontSize = fontSize,
                    textColor = textColor,
                    hideChapterTitle = hideChapterTitle,
                    onTap = { viewModel.toggleMenu() },
                )
            }
        }

        // ===== 菜单层 =====
        if (showMenu) {
            // 顶栏
            if (!hideTopBar) {
                ReaderTopBar(
                    title = chapterTitle,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    onBack = onBack,
                )
            }

            // 底部菜单 + 页签面板
            ReaderBottomMenu(
                viewModel = viewModel,
                chapters = chapters,
                chapterIndex = chapterIndex,
                fontSize = fontSize,
                nightMode = nightMode,
                pageMode = pageMode,
                pageAnim = pageAnim,
                hideTopBar = hideTopBar,
                hideBottomBar = hideBottomBar,
                hideChapterTitle = hideChapterTitle,
                backgroundColor = backgroundColor,
                textColor = textColor,
                menuTab = menuTab,
            )
        }
    }
}

/** 上下滚动正文 */
@Composable
private fun ScrollNovel(
    content: String,
    chapterTitle: String,
    fontSize: Float,
    textColor: Color,
    hideChapterTitle: Boolean,
    onTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .fastTap { onTap() }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 48.dp),
    ) {
        if (!hideChapterTitle) {
            Text(
                text = chapterTitle,
                fontSize = (fontSize + 2).sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = content,
            fontSize = fontSize.sp,
            color = textColor,
            lineHeight = (fontSize * 1.6).sp,
            textAlign = TextAlign.Justify,
        )
    }
}

/** 左右翻页正文（按屏幕容量精确分页，每页铺满一屏、到屏幕底端即止，无内部滚动，支持翻页动画） */
@Composable
private fun HorizontalPagerNovel(
    content: String,
    chapterTitle: String,
    fontSize: Float,
    textColor: Color,
    hideChapterTitle: Boolean,
    pageAnim: Int,
    onTap: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .fastTap { onTap() },
    ) {
        // 可用尺寸（dp 转 px）：按字号估算每页可容纳字符数，做到「满一屏即止、无滚动」
        val density = LocalDensity.current.density
        val availWidthPx = maxWidth.value * density
        val availHeightPx = maxHeight.value * density
        val textWidthPx = availWidthPx - 40f * density        // 左右各 20dp padding
        val textHeightPx = availHeightPx - 96f * density       // 上下各 48dp padding
        val lineHeightPx = fontSize * 1.6f * density
        val charWidthPx = fontSize * density                   // 汉字近似全角宽度
        val charsPerLine = (textWidthPx / charWidthPx).toInt().coerceAtLeast(1)
        val linesPerPage = (textHeightPx / lineHeightPx).toInt().coerceAtLeast(1)
        val charsPerPage = (charsPerLine * linesPerPage).coerceAtLeast(100)

        val pages = remember(content, hideChapterTitle, charsPerPage) {
            if (hideChapterTitle) splitContentIntoPages(content, pageSize = charsPerPage)
            else splitContentIntoPages(content, pageSize = charsPerPage, firstPageTitle = chapterTitle)
        }
        val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val offset = pagerState.currentPageOffsetFraction
            val transform = when (pageAnim) {
                0 -> PageTransform.NoAnim
                2 -> PageTransform.cover(offset, page)
                3 -> PageTransform.simulate(offset, page)
                else -> PageTransform.slide
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = transform.translationX
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                        alpha = transform.alpha
                        rotationY = transform.rotationY
                        shadowElevation = transform.shadowElevation
                        cameraDistance = 12f * density
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 48.dp),
                ) {
                    if (page == 0 && !hideChapterTitle) {
                        Text(
                            text = chapterTitle,
                            fontSize = (fontSize + 2).sp,
                            fontWeight = FontWeight.Bold,
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
                        // 每页内容已按屏幕容量切分，禁止内部滚动——到屏幕底端即止
                    )
                }
            }
        }
    }
}

/**
 * 翻页动画的变换参数（作用于单个 page 的 graphicsLayer）。
 */
private data class PageTransform(
    val translationX: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 1f,
    val rotationY: Float = 0f,
    val shadowElevation: Float = 0f,
) {
    companion object {
        /** 无动画：页面不做额外变换 */
        val NoAnim = PageTransform()
        /** 滑动（默认）：HorizontalPager 原生平移，这里不额外处理 */
        val slide = PageTransform()

        /** 覆盖：翻页时当前页轻微缩放淡出，下一页从右侧覆盖 */
        fun cover(offset: Float, page: Int): PageTransform {
            // offset：当前页被拖离中心的比例（0~1，正值表示往左滑）
            // 正在离开的页（offset 使 page 往左移）做缩放淡出
            val leaving = if (page == 0) offset else offset
            val abs = kotlin.math.abs(offset).coerceIn(0f, 1f)
            val scale = 1f - abs * 0.12f
            val alpha = 1f - abs * 0.25f
            return PageTransform(
                scaleX = scale,
                scaleY = scale,
                alpha = alpha,
                shadowElevation = abs * 8f,
            )
        }

        /** 仿真翻页：页面绕 Y 轴 3D 旋转 */
        fun simulate(offset: Float, page: Int): PageTransform {
            val abs = kotlin.math.abs(offset).coerceIn(0f, 1f)
            // 旋转角度：最大约 -60°
            val rotation = -abs * 60f
            val scale = 1f - abs * 0.05f
            return PageTransform(
                rotationY = rotation,
                scaleX = scale,
                scaleY = scale,
                alpha = 1f - abs * 0.15f,
                shadowElevation = abs * 12f,
            )
        }
    }
}

/**
 * 把正文按固定字符数切成若干页。firstPageTitle 非空时第一页前留出标题空间（少放一点内容）。
 */
private fun splitContentIntoPages(
    content: String,
    pageSize: Int = 900,
    firstPageTitle: String? = null,
): List<String> {
    if (content.isBlank()) return listOf("")
    val paragraphs = content.split("\n").filter { it.isNotBlank() }
    if (paragraphs.isEmpty()) return listOf(content)

    val pages = mutableListOf<String>()
    val buffer = StringBuilder()
    // 第一页预留标题空间
    var firstPage = firstPageTitle != null
    for (para in paragraphs) {
        val limit = if (firstPage) pageSize - 120 else pageSize
        if (buffer.isNotEmpty() && buffer.length + para.length + 1 > limit) {
            pages.add(buffer.toString())
            buffer.clear()
            firstPage = false
        }
        if (para.length > limit && buffer.isEmpty()) {
            var start = 0
            while (start < para.length) {
                val end = (start + limit).coerceAtMost(para.length)
                pages.add(para.substring(start, end))
                start = end
                firstPage = false
            }
        } else {
            if (buffer.isNotEmpty() && buffer.length + para.length + 1 > limit) {
                pages.add(buffer.toString())
                buffer.clear()
                firstPage = false
            }
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(para)
        }
    }
    if (buffer.isNotEmpty()) pages.add(buffer.toString())
    return pages.ifEmpty { listOf("") }
}

/** 顶部栏 */
@Composable
private fun BoxScope.ReaderTopBar(
    title: String,
    backgroundColor: Color,
    textColor: Color,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .background(backgroundColor.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = textColor)
        }
        Text(
            text = title,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** 底部菜单（进度滑块 + 页签按钮 + 对应面板） */
@Composable
private fun BoxScope.ReaderBottomMenu(
    viewModel: NovelReaderViewModel,
    chapters: List<ChapterItem>,
    chapterIndex: Int,
    fontSize: Float,
    nightMode: Boolean,
    pageMode: Int,
    pageAnim: Int,
    hideTopBar: Boolean,
    hideBottomBar: Boolean,
    hideChapterTitle: Boolean,
    backgroundColor: Color,
    textColor: Color,
    menuTab: Int,
) {
    val backgroundColors = if (nightMode) ReaderBackgrounds.nightColors else ReaderBackgrounds.dayColors

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(),
        color = backgroundColor,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (menuTab) {
                0 -> {
                    // ===== 目录面板 =====
                    Text(
                        text = "目录（${chapters.size}章）",
                        color = textColor,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    // 打开目录时定位到当前章节
                    LaunchedEffect(chapters) {
                        if (chapters.isNotEmpty()) {
                            listState.scrollToItem(chapterIndex.coerceIn(0, chapters.size - 1))
                        }
                    }
                    // 目录列表 + 右侧快速定位滑块
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.5f),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                        ) {
                            itemsIndexed(chapters) { index, ch ->
                                val isCurrent = index == chapterIndex
                                Text(
                                    text = ch.title,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else textColor,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.switchChapter(index)
                                            viewModel.toggleMenu()
                                        }
                                        .padding(vertical = 10.dp),
                                )
                            }
                        }
                        // 快速定位滑块：竖直拖拽即时跳转到对应章节
                        if (chapters.size > 1) {
                            Column(
                                modifier = Modifier
                                    .width(40.dp)
                                    .fillMaxHeight()
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(12.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(6.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // 当前位置指示点
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                CircleShape,
                                            ),
                                    )
                                    // 拖拽条：手指按下/滑动即定位
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(chapters.size) {
                                                detectVerticalDragGestures(
                                                    onDragStart = { offset ->
                                                        val ratio = (offset.y / size.height).coerceIn(0f, 1f)
                                                        val target = (ratio * (chapters.size - 1)).toInt()
                                                        scope.launch { listState.scrollToItem(target.coerceIn(0, chapters.size - 1)) }
                                                    },
                                                    onVerticalDrag = { change, _ ->
                                                        val ratio = (change.position.y / size.height).coerceIn(0f, 1f)
                                                        val target = (ratio * (chapters.size - 1)).toInt()
                                                        scope.launch { listState.scrollToItem(target.coerceIn(0, chapters.size - 1)) }
                                                    },
                                                )
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // ===== 界面面板 =====
                    // 字号
                    Text("字号", color = textColor, style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MenuButton("A-") { viewModel.decreaseFontSize() }
                        Text(
                            text = "${fontSize.toInt()}",
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        MenuButton("A+") { viewModel.increaseFontSize() }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 背景颜色
                    Text("背景颜色", color = textColor, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        backgroundColors.forEachIndexed { index, rc ->
                            Box(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(36.dp)
                                    .background(rc.background, CircleShape)
                                    .clickable { viewModel.setBackground(index) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 翻页方式
                    Text("翻页方式", color = textColor, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        MenuButton("上下滚动", selected = pageMode == 0, textColor = textColor) {
                            viewModel.setPageMode(0)
                        }
                        Spacer(Modifier.width(12.dp))
                        MenuButton("左右翻页", selected = pageMode == 1, textColor = textColor) {
                            viewModel.setPageMode(1)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 翻页动画（仅左右翻页生效）
                    if (pageMode == 1) {
                        Text("翻页动画", color = textColor, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            MenuButton("无动画", selected = pageAnim == 0, textColor = textColor) {
                                viewModel.setPageAnim(0)
                            }
                            Spacer(Modifier.width(8.dp))
                            MenuButton("滑动", selected = pageAnim == 1, textColor = textColor) {
                                viewModel.setPageAnim(1)
                            }
                            Spacer(Modifier.width(8.dp))
                            MenuButton("覆盖", selected = pageAnim == 2, textColor = textColor) {
                                viewModel.setPageAnim(2)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            MenuButton("仿真翻页", selected = pageAnim == 3, textColor = textColor) {
                                viewModel.setPageAnim(3)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                else -> {
                    // ===== 设置面板（含隐藏栏目选项） =====
                    SettingSwitch("夜间模式", nightMode) { viewModel.toggleNightMode() }
                    SettingSwitch("隐藏顶部栏", hideTopBar) { viewModel.setHideTopBar(it) }
                    SettingSwitch("隐藏进度栏", hideBottomBar) { viewModel.setHideBottomBar(it) }
                    SettingSwitch("隐藏章节标题", hideChapterTitle) { viewModel.setHideChapterTitle(it) }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(4.dp))

            // ===== 进度滑块行 =====
            if (!hideBottomBar) {
                var sliderValue by remember(chapterIndex) {
                    androidx.compose.runtime.mutableFloatStateOf(chapterIndex.toFloat())
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "上一章",
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { viewModel.prevChapter() }
                            .padding(8.dp),
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val target = sliderValue.toInt().coerceIn(0, chapters.size - 1)
                            if (chapters.isNotEmpty() && target != chapterIndex) {
                                viewModel.switchChapter(target)
                                viewModel.toggleMenu()
                            }
                        },
                        valueRange = 0f..(if (chapters.isEmpty()) 1f else (chapters.size - 1).toFloat()),
                        steps = 0,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "下一章",
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { viewModel.nextChapter() }
                            .padding(8.dp),
                    )
                }
            }

            // ===== 页签按钮行：目录 / 界面 / 设置 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TabButton("目录", menuTab == 0, textColor) { viewModel.setMenuTab(0) }
                TabButton("界面", menuTab == 1, textColor) { viewModel.setMenuTab(1) }
                TabButton("设置", menuTab == 2, textColor) { viewModel.setMenuTab(2) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 菜单通用按钮 */
@Composable
private fun MenuButton(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 页签按钮（目录/界面/设置） */
@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    textColor: Color,
    onClick: () -> Unit,
) {
    // 选中态：蓝底白字；未选中态：浅灰底深字（夜间自动切深底浅字），避免黑底黑字看不清
    val isDark = textColor.luminance() > 0.5f
    val bg = if (selected) Color(0xFF4A6FA5) else if (isDark) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
    val fg = if (selected) Color.White else if (isDark) Color(0xFFE0E0E0) else Color(0xFF333333)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/** 翻页方式按钮（带选中态） */
@Composable
private fun MenuButton(
    text: String,
    selected: Boolean,
    textColor: Color,
    onClick: () -> Unit,
) {
    // 选中态：蓝底白字；未选中态：浅灰底深字（夜间自动切深底浅字），避免黑底黑字看不清
    val isDark = textColor.luminance() > 0.5f
    val bg = if (selected) Color(0xFF4A6FA5) else if (isDark) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
    val fg = if (selected) Color.White else if (isDark) Color(0xFFE0E0E0) else Color(0xFF333333)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 设置开关行 */
@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
