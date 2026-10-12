package com.novelreader.ui.screens.novel

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.components.BookMeta
import com.novelreader.ui.theme.ReaderBackgrounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 三区单击检测（阅读页手势核心）：
 *
 * - 左 1/3 单击：上一页 / 上一章
 * - 右 1/3 单击：下一页 / 下一章
 * - 中间 1/3 单击：呼出 / 收起菜单
 *
 * #5 手势增强：
 * - 双击（任意位置，中央优先）：呼出菜单
 * - 长按：调用 [onLongPress]（弹出文本选择 / 复制）
 *
 * 单击立即响应以保证翻页低延迟；双击/长按在各自分支内单独判定。
 */
private fun Modifier.pageTap(
    onLeft: () -> Unit,
    onCenter: () -> Unit,
    onRight: () -> Unit,
    onLongPress: (() -> Unit)? = null,
): Modifier = this.pointerInput(onLongPress != null) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downPos = down.position
        // 等待抬手或长按超时
        var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
        var longPressed = false
        withTimeoutOrNull(450L) {
            up = waitForUpOrCancellation()
        }
        if (up == null) {
            // 超时未抬手 → 长按
            longPressed = true
        }
        if (longPressed) {
            onLongPress?.invoke()
            // 长按后消费本次手势，等待抬手结束
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        val u = up ?: return@awaitEachGesture
        // 滑动距离过大视为翻页/滚动拖拽，不当作单击
        val dx = kotlin.math.abs(u.position.x - downPos.x)
        val dy = kotlin.math.abs(u.position.y - downPos.y)
        if (dx > 24f || dy > 24f) return@awaitEachGesture
        val w = size.width.toFloat()
        if (w <= 0f) return@awaitEachGesture
        val x = u.position.x
        // 单击立即响应
        when {
            x < w / 3f -> onLeft()
            x > w * 2f / 3f -> onRight()
            else -> onCenter()
        }
        // 双击判定：短窗口内若再来一次点击 → 呼出菜单
        val secondDown = withTimeoutOrNull(260L) {
            awaitFirstDown(requireUnconsumed = false)
        }
        if (secondDown != null) {
            val secondUp = waitForUpOrCancellation()
            if (secondUp != null) {
                val sdx = kotlin.math.abs(secondUp.position.x - secondDown.position.x)
                val sdy = kotlin.math.abs(secondUp.position.y - secondDown.position.y)
                if (sdx <= 24f && sdy <= 24f) onCenter()
            }
        }
    }
}

/**
 * 滚动模式单击判定：整屏分上下两半，上半=向上翻、下半=向下翻（菜单呼出走顶栏返回键）。
 * 中心竖条区域仍保留「呼出菜单」，兼顾两种习惯。
 * #5：长按 → [onLongPress]（文本选择/复制）；双击 → 呼出菜单。
 */
private fun Modifier.scrollTap(
    onUp: () -> Unit,
    onCenter: () -> Unit,
    onDown: () -> Unit,
    onLongPress: (() -> Unit)? = null,
): Modifier = this.pointerInput(onLongPress != null) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downPos = down.position
        var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
        var longPressed = false
        withTimeoutOrNull(450L) { up = waitForUpOrCancellation() }
        if (up == null) longPressed = true
        if (longPressed) {
            onLongPress?.invoke()
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        val u = up ?: return@awaitEachGesture
        val dx = kotlin.math.abs(u.position.x - downPos.x)
        val dy = kotlin.math.abs(u.position.y - downPos.y)
        if (dx > 24f || dy > 24f) return@awaitEachGesture
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (w <= 0f || h <= 0f) return@awaitEachGesture
        val x = u.position.x
        val y = u.position.y
        // 中间竖条（水平方向中间 1/3）且落在屏幕中部 → 呼出菜单
        if (x in (w / 3f)..(w * 2f / 3f) && y in (h * 0.28f)..(h * 0.72f)) {
            onCenter()
        } else if (y < h / 2f) {
            onUp()
        } else {
            onDown()
        }
        // 双击 → 呼出菜单
        val secondDown = withTimeoutOrNull(260L) { awaitFirstDown(requireUnconsumed = false) }
        if (secondDown != null) {
            val secondUp = waitForUpOrCancellation()
            if (secondUp != null) {
                val sdx = kotlin.math.abs(secondUp.position.x - secondDown.position.x)
                val sdy = kotlin.math.abs(secondUp.position.y - secondDown.position.y)
                if (sdx <= 24f && sdy <= 24f) onCenter()
            }
        }
    }
}

/** 简单全屏单击（加载中/错误页用：任意点击呼出菜单） */
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
    val lineHeight by viewModel.lineHeight.collectAsState()
    val paragraphSpacing by viewModel.paragraphSpacing.collectAsState()
    val sidePadding by viewModel.sidePadding.collectAsState()
    val autoPage by viewModel.autoPage.collectAsState()
    val autoPageInterval by viewModel.autoPageInterval.collectAsState()
    val showMenu by viewModel.showMenu.collectAsState()
    val menuTab by viewModel.menuTab.collectAsState()
    val chapterTitle by viewModel.chapterTitle.collectAsState()
    val pageMode by viewModel.pageMode.collectAsState()
    val pageAnim by viewModel.pageAnim.collectAsState()
    val hideTopBar by viewModel.hideTopBar.collectAsState()
    val hideBottomBar by viewModel.hideBottomBar.collectAsState()
    val hideChapterTitle by viewModel.hideChapterTitle.collectAsState()
    val chapterIndex by viewModel.currentChapterIndex.collectAsState()
    val initialTextPosition by viewModel.initialTextPosition.collectAsState()
    val initialPageIndex by viewModel.initialPageIndex.collectAsState()
    val changeSourceOpen by viewModel.changeSourceOpen.collectAsState()
    val changeSourceCandidates by viewModel.changeSourceCandidates.collectAsState()
    val changeSourceLoading by viewModel.changeSourceLoading.collectAsState()
    val sameAuthorOnly by viewModel.sameAuthorOnly.collectAsState()
    val changeSourceError by viewModel.changeSourceError.collectAsState()
    val changeSourceHasMore by viewModel.changeSourceHasMore.collectAsState()
    val changeSourceLoadingMore by viewModel.changeSourceLoadingMore.collectAsState()
    val fromCache by viewModel.fromCache.collectAsState()
    val cachedIndices by viewModel.cachedIndices.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val collected by viewModel.collected.collectAsState()
    val hasNextChapter by viewModel.hasNextChapter.collectAsState()
    val currentItemState by viewModel.currentItemState.collectAsState()

    // 顶栏书名 / 来源：换源后随 VM 状态刷新（不再固定为进入时的导航参数）
    val displayItem = currentItemState ?: item
    // 章节列表以 VM 状态为准：换源后 VM 会替换整份目录，UI 必须跟着切换
    val vmChapters by viewModel.chapters.collectAsState()
    val chapterList = if (vmChapters.isNotEmpty()) vmChapters else chapters
    // 当前章节在列表中的位置（目录定位 / 高亮 / 进度滑块；比源返回的 index 更可靠）
    val chapterPosition = remember(chapterList, chapterIndex) {
        chapterList.indexOfFirst { it.index == chapterIndex }.coerceAtLeast(0)
    }

    // 当前章节的页码展示（如 «1/18»），由翻页/滚动子组件回传，供顶栏统一显示进度（#10）
    var pageText by remember { androidx.compose.runtime.mutableStateOf("") }
    val chapterCount = chapterList.size

    // #5 长按弹出正文选择/复制面板
    var showTextSelect by remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(item, chapter) {
        viewModel.init(item, chapters, chapter)
    }

    // 提示条自动消失
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1800)
            viewModel.dismissToast()
        }
    }

    // 阅读时保持屏幕常亮，退出阅读页自动还原（#8）
    val readerView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(readerView) {
        val previous = readerView.keepScreenOn
        readerView.keepScreenOn = true
        onDispose { readerView.keepScreenOn = previous }
    }

    val backgroundColors = if (nightMode) ReaderBackgrounds.nightColors else ReaderBackgrounds.dayColors
    val bgIndexSafe = bgIndex.coerceIn(backgroundColors.indices)
    val backgroundColor = backgroundColors[bgIndexSafe].background
    val textColor = backgroundColors[bgIndexSafe].text

    // 音量键翻页（#8）：阅读页持有焦点并拦截音量键，上下键分别上一页/下一页
    val volumePageEnabled by viewModel.volumePage.collectAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val volumeKeyModifier = Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (!volumePageEnabled) return@onPreviewKeyEvent false
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.VolumeUp -> {
                    viewModel.volumePrevPage(); true
                }
                Key.VolumeDown -> {
                    viewModel.volumeNextPage(); true
                }
                else -> false
            }
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(volumeKeyModifier)
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = error ?: "加载失败",
                        color = textColor,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    // 失败可原地重试（旧实现只能返回再进入）
                    Text(
                        text = "重试",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(Color(0xFF4A6FA5), RoundedCornerShape(8.dp))
                            .clickable { viewModel.retry() }
                            .padding(horizontal = 28.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "也可点击屏幕呼出菜单切换书源",
                        color = textColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            pageMode == 1 -> {
                HorizontalPagerNovel(
                    content = content,
                    chapterTitle = chapterTitle,
                    chapterIndex = chapterIndex,
                    initialPage = initialPageIndex,
                    fontSize = fontSize,
                    lineHeightMultiplier = lineHeight,
                    paragraphSpacing = paragraphSpacing,
                    sidePadding = sidePadding,
                    textColor = textColor,
                    hideChapterTitle = hideChapterTitle,
                    pageAnim = pageAnim,
                    autoPage = autoPage,
                    autoPageInterval = autoPageInterval,
                    hasNextChapter = hasNextChapter,
                    onTapCenter = { viewModel.toggleMenu() },
                    onReachFirst = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPageChanged = { page -> viewModel.savePageIndex(chapterIndex, page) },
                    onPageTextChanged = { pageText = it },
                    pageTurn = viewModel.pageTurn,
                    onLongPress = { showTextSelect = true },
                )
            }
            else -> {
                ScrollNovel(
                    content = content,
                    chapterTitle = chapterTitle,
                    chapterIndex = chapterIndex,
                    initialRatio = initialTextPosition,
                    fontSize = fontSize,
                    lineHeightMultiplier = lineHeight,
                    paragraphSpacing = paragraphSpacing,
                    sidePadding = sidePadding,
                    textColor = textColor,
                    hideChapterTitle = hideChapterTitle,
                    hasNextChapter = hasNextChapter,
                    onScrollRatio = { ratio -> viewModel.saveScrollRatio(chapterIndex, ratio) },
                    autoPage = autoPage,
                    autoPageInterval = autoPageInterval,
                    onTapCenter = { viewModel.toggleMenu() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPageTextChanged = { pageText = it },
                    pageTurn = viewModel.pageTurn,
                    onLongPress = { showTextSelect = true },
                )
            }
        }

        // ===== 菜单层 =====
        if (showMenu) {
            // 顶栏
            if (!hideTopBar) {
                ReaderTopBar(
                    bookTitle = displayItem.title,
                    sourceName = displayItem.sourceName,
                    title = chapterTitle,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    chapterIndex = chapterIndex,
                    chapterCount = chapterCount,
                    pageText = pageText,
                    fromCache = fromCache,
                    collected = collected,
                    onToggleCollect = { viewModel.toggleCollect() },
                    onBack = onBack,
                )
            }

            // 底部菜单 + 页签面板
            ReaderBottomMenu(
                viewModel = viewModel,
                chapters = chapterList,
                chapterIndex = chapterIndex,
                chapterPosition = chapterPosition,
                cachedIndices = cachedIndices,
                fontSize = fontSize,
                nightMode = nightMode,
                pageMode = pageMode,
                pageAnim = pageAnim,
                hideTopBar = hideTopBar,
                hideBottomBar = hideBottomBar,
                hideChapterTitle = hideChapterTitle,
                lineHeight = lineHeight,
                paragraphSpacing = paragraphSpacing,
                sidePadding = sidePadding,
                autoPage = autoPage,
                autoPageInterval = autoPageInterval,
                backgroundColor = backgroundColor,
                textColor = textColor,
                menuTab = menuTab,
                volumePage = volumePageEnabled,
            )
        }

        // ===== 换源面板（覆盖层） =====
        if (changeSourceOpen) {
            ChangeSourcePanel(
                viewModel = viewModel,
                candidates = changeSourceCandidates,
                loading = changeSourceLoading,
                sameAuthorOnly = sameAuthorOnly,
                error = changeSourceError,
                hasMore = changeSourceHasMore,
                loadingMore = changeSourceLoadingMore,
                backgroundColor = backgroundColor,
                textColor = textColor,
            )
        }

        // ===== 长按选择/复制面板（#5） =====
        if (showTextSelect) {
            TextSelectPanel(
                content = content,
                chapterTitle = chapterTitle,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onClose = { showTextSelect = false },
            )
        }

        // ===== 轻量提示条（换源成功 / 翻页反馈 / 章节边界） =====
        toast?.let { msg ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 24.dp, end = 24.dp),
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 上下滚动正文（记住正文位置：进入恢复、滚动停止回写；单击上下半屏翻页；滚到章末显示「下一章」续读入口） */
@Composable
private fun ScrollNovel(
    content: String,
    chapterTitle: String,
    chapterIndex: Int,
    initialRatio: Int,
    fontSize: Float,
    lineHeightMultiplier: Float,
    paragraphSpacing: Float,
    sidePadding: Float,
    textColor: Color,
    hideChapterTitle: Boolean,
    hasNextChapter: Boolean,
    onScrollRatio: (Float) -> Unit,
    autoPage: Boolean,
    autoPageInterval: Int,
    onTapCenter: () -> Unit,
    onNextChapter: () -> Unit,
    onPageTextChanged: (String) -> Unit = {},
    pageTurn: kotlinx.coroutines.flow.SharedFlow<Int>? = null,
    onLongPress: (() -> Unit)? = null,
) {
    // 每章一个独立滚动状态：切章回到顶部，不沿用上一章位置
    val scrollState = remember(chapterIndex) { ScrollState(0) }
    val scope = rememberCoroutineScope()

    // 音量键翻页（#8）：-1 上翻一屏，+1 下翻一屏，到底进入下一章
    if (pageTurn != null) {
        LaunchedEffect(pageTurn, chapterIndex, content) {
            pageTurn.collect { dir ->
                val max = scrollState.maxValue
                if (max <= 0) return@collect
                if (dir > 0) {
                    if (scrollState.value >= max - 8) {
                        onNextChapter()
                    } else {
                        val target = (scrollState.value + max * 0.9f).toInt().coerceAtMost(max)
                        scrollState.animateScrollTo(target)
                    }
                } else {
                    val target = (scrollState.value - max * 0.9f).toInt().coerceAtLeast(0)
                    scrollState.animateScrollTo(target)
                }
            }
        }
    }

    // 滚动模式下的本章百分比（#10）：滚动位置 / 可滚范围
    val scrollRatio by remember {
        androidx.compose.runtime.derivedStateOf {
            val max = scrollState.maxValue
            if (max > 0) (scrollState.value.toFloat() / max).coerceIn(0f, 1f) else 0f
        }
    }
    LaunchedEffect(scrollRatio) {
        onPageTextChanged("${(scrollRatio * 100).toInt()}%")
    }

    // 恢复：待布局完成后按记录的比例滚动到上次位置（仅在本章有记录时）
    LaunchedEffect(chapterIndex, content, initialRatio) {
        if (initialRatio in 1..10000) {
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            scrollState.scrollTo((initialRatio / 10000f * scrollState.maxValue).toInt())
        }
    }

    // 回写：滚动停止约 400ms 后保存当前比例（collectLatest 天然做去抖）
    LaunchedEffect(chapterIndex, content) {
        snapshotFlow { scrollState.value }.collectLatest { v ->
            delay(400)
            val max = scrollState.maxValue
            if (max > 0) onScrollRatio(v.toFloat() / max)
        }
    }

    // 自动翻页：按设定间隔向下滚动一屏，到底进入下一章
    LaunchedEffect(autoPage, autoPageInterval, chapterIndex, content) {
        if (!autoPage) return@LaunchedEffect
        while (true) {
            delay(autoPageInterval * 1000L)
            val max = scrollState.maxValue
            if (max <= 0) continue
            if (scrollState.value >= max - 8) {
                onNextChapter()
            } else {
                scrollState.animateScrollTo((scrollState.value + max * 0.9f).toInt().coerceAtMost(max))
            }
        }
    }

    // 单击下半屏：向下翻一屏；上半屏：向上翻一屏；中部：呼出菜单
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scrollTap(
                onUp = {
                    scope.launch {
                        val target = (scrollState.value - scrollState.maxValue * 0.9f)
                            .toInt().coerceAtLeast(0)
                        scrollState.animateScrollTo(target)
                    }
                },
                onCenter = onTapCenter,
                onDown = {
                    scope.launch {
                        val max = scrollState.maxValue
                        if (scrollState.value >= max - 8) {
                            // 已到底：直接进入下一章，保证连续阅读
                            onNextChapter()
                        } else {
                            val target = (scrollState.value + max * 0.9f)
                                .toInt().coerceAtMost(max)
                            scrollState.animateScrollTo(target)
                        }
                    }
                },
                onLongPress = onLongPress,
            )
            .verticalScroll(scrollState)
            .padding(horizontal = sidePadding.dp, vertical = 48.dp),
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
        // 段距用文本段落间距实现（而非在正文底部加 padding）：
        // 旧实现把段距当作整段文本的 bottom padding，只会在正文最后留一条空白，
        // 段落之间毫无间距，视觉上像「大片空白」堆在末尾。这里改用 buildAnnotatedString
        // 在每个段落起始注入首行缩进与段间距，使段间呼吸均匀。
        Text(
            text = buildReaderText(
                content = content,
                indentEm = 2f,
                fontSize = fontSize,
                paragraphSpacingDp = paragraphSpacing,
            ),
            fontSize = fontSize.sp,
            color = textColor,
            lineHeight = (fontSize * lineHeightMultiplier).sp,
            textAlign = TextAlign.Justify,
            letterSpacing = 0.sp,
        )

        // 章末续读：滚到底部出现「下一章」入口，保证阅读连续性；已是末章则明确提示
        if (content.isNotBlank()) {
            Spacer(Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (hasNextChapter) Modifier.clickable(onClick = onNextChapter) else Modifier)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (hasNextChapter) "下一章 ›" else "已是最后一章",
                    fontSize = (fontSize + 2).sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasNextChapter) MaterialTheme.colorScheme.primary
                    else textColor.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 左右翻页正文（按屏幕容量精确分页，每页铺满一屏、到屏幕底端即止，无内部滚动，支持翻页动画；章末可续翻下一章；三区单击翻页） */
@Composable
private fun HorizontalPagerNovel(
    content: String,
    chapterTitle: String,
    chapterIndex: Int,
    initialPage: Int,
    fontSize: Float,
    lineHeightMultiplier: Float,
    paragraphSpacing: Float,
    sidePadding: Float,
    textColor: Color,
    hideChapterTitle: Boolean,
    pageAnim: Int,
    autoPage: Boolean,
    autoPageInterval: Int,
    hasNextChapter: Boolean,
    onTapCenter: () -> Unit,
    onReachFirst: () -> Unit,
    onNextChapter: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onPageTextChanged: (String) -> Unit = {},
    pageTurn: kotlinx.coroutines.flow.SharedFlow<Int>? = null,
    onLongPress: (() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        // 用真实文本排版测量分页（P0 修复）：
        // 旧实现按 fontSize*density 假设「每个字符都是全角方块」估算每行字数，
        // 忽略标点/西文/数字的实际宽度与换行规则，导致行数偏少、末页内容越过屏幕底端被裁掉
        // （表现为「字只显示一半」「大片空白」）。此处改用 TextMeasurer 真实测量。
        val density = LocalDensity.current.density
        // 可用文字区域尺寸（px）：与下面正文 Column 的 padding 严格一致（左右 sidePadding，上下 48dp）
        val availWidthPx = (maxWidth.value - sidePadding * 2f).coerceAtLeast(1f) * density
        val textHeightPx = (maxHeight.value - 96f).coerceAtLeast(1f) * density

        val textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineHeightMultiplier).sp,
            textAlign = TextAlign.Justify,
        )
        // 段距（dp → px）：按段落切分后每段之间的额外间距
        val paraSpacingPx = paragraphSpacing * density

        val pages = remember(
            content, chapterTitle, hideChapterTitle,
            availWidthPx, textHeightPx, fontSize, lineHeightMultiplier, paraSpacingPx,
        ) {
            paginateByMeasurement(
                measurer = textMeasurer,
                content = content,
                chapterTitle = chapterTitle,
                showTitle = !hideChapterTitle,
                widthPx = availWidthPx,
                heightPx = textHeightPx,
                style = textStyle,
                paragraphSpacingPx = paraSpacingPx,
            )
        }
        // 章末附加一页「下一章」引导页：正文滑完还能继续翻入下一章，
        // 否则滑到章末就卡住，无法连续阅读（空正文 / 已是末章时不加）
        val hasNextGate = content.isNotBlank() && hasNextChapter
        val pageCount = pages.size + if (hasNextGate) 1 else 0

        // 进入本章时恢复到上次记录的页码（越界自动收敛到有效范围）
        val startPage = remember(chapterIndex, initialPage) {
            initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })
        val scope = rememberCoroutineScope()

        // 音量键翻页（#8）：-1 上一页，+1 下一页，越界时跨章
        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir ->
                    if (dir > 0) {
                        val last = pagerState.pageCount - 1
                        if (pagerState.currentPage < last) {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        } else {
                            onNextChapter()
                        }
                    } else {
                        if (pagerState.currentPage > 0) {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        } else {
                            onReachFirst()
                        }
                    }
                }
            }
        }

        // 切换章节时回到第一页，避免沿用上一章的页码。
        // 注意：仅在「章号真正变化」时复位；首次进入本章要保留 initialPage（恢复上次页码），
        // 因此用 remember 记录上一次章号，首次组合时不触发。
        var lastChapterIndex by remember { androidx.compose.runtime.mutableIntStateOf(chapterIndex) }
        LaunchedEffect(chapterIndex) {
            if (lastChapterIndex != chapterIndex) {
                lastChapterIndex = chapterIndex
                if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
                // 切章后页码归零，同步回写，避免沿用上一章页码
                onPageChanged(0)
            }
        }

        // 翻到章末引导页时自动进入下一章（每章只触发一次，防切章瞬间页码未复位造成连跳）
        var gateConsumed by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
        LaunchedEffect(pagerState.currentPage, pageCount, chapterIndex) {
            if (hasNextGate && pageCount > 0 &&
                pagerState.currentPage == pageCount - 1 &&
                gateConsumed != chapterIndex
            ) {
                gateConsumed = chapterIndex
                onNextChapter()
            }
        }

        // 页码回写：停稳后记录当前页码，供退出重进恢复
        LaunchedEffect(pagerState, chapterIndex) {
            snapshotFlow { pagerState.currentPage }.collectLatest { page ->
                delay(300)
                if (!pagerState.isScrollInProgress) onPageChanged(page)
            }
        }

        // 顶栏页码展示（#10）：当前页/总页数
        LaunchedEffect(pagerState, pageCount) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                onPageTextChanged("${page + 1}/${pageCount}")
            }
        }

        // 自动翻页：按设定间隔翻到下一页，到章末进入下一章
        LaunchedEffect(autoPage, autoPageInterval, chapterIndex) {
            if (!autoPage) return@LaunchedEffect
            while (true) {
                delay(autoPageInterval * 1000L)
                val last = pagerState.pageCount - 1
                if (pagerState.currentPage < last) {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                } else {
                    onNextChapter()
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .pageTap(
                    onLeft = {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        } else {
                            // 已是本章第一页：翻到上一章
                            onReachFirst()
                        }
                    },
                    onCenter = onTapCenter,
                    onRight = {
                        val last = pagerState.pageCount - 1
                        if (pagerState.currentPage < last) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            onNextChapter()
                        }
                    },
                    onLongPress = onLongPress,
                ),
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
                if (page >= pages.size) {
                    // 章末引导页：本章已读完，继续滑动将进入下一章
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "本章已读完",
                            fontSize = fontSize.sp,
                            color = textColor.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "继续翻页进入下一章 ›",
                            fontSize = (fontSize - 2).sp,
                            color = textColor.copy(alpha = 0.4f),
                        )
                    }
                } else Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = sidePadding.dp, vertical = 48.dp),
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
                        text = buildReaderText(
                            content = pages[page],
                            indentEm = 2f,
                            fontSize = fontSize,
                            paragraphSpacingDp = paragraphSpacing,
                        ),
                        fontSize = fontSize.sp,
                        color = textColor,
                        lineHeight = (fontSize * lineHeightMultiplier).sp,
                        textAlign = TextAlign.Justify,
                        // 每页内容已按屏幕容量切分，禁止内部滚动——到屏幕底端即止
                    )
                    Spacer(Modifier.weight(1f))
                    // 底部页码指示（右对齐，弱化显示）
                    Text(
                        text = "${page + 1}/${pages.size}",
                        fontSize = (fontSize - 6).sp,
                        color = textColor.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
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
 * 构造阅读正文的 [androidx.compose.ui.text.AnnotatedString]：
 *
 * - 首行缩进：每个段落开头注入 2 个全角空格（[indentEm]，1 = 一个全角字宽）。
 *   源文本里的全角缩进常在清洗时被 trim 掉，这里统一补齐，保证段首视觉规整。
 * - 段间距：在每个段落之后追加一个换行 + 一个「占位空行」，通过给该空行加大字号
 *   来撑开段间高度（Compose 的 `ParagraphStyle.lineHeight` 无法直接做段间距）。
 *
 * 若原文已带首行缩进（如 \u3000\u3000），则不重复注入。
 */
private fun buildReaderText(
    content: String,
    indentEm: Float,
    fontSize: Float,
    paragraphSpacingDp: Float,
): androidx.compose.ui.text.AnnotatedString {
    if (content.isBlank()) return androidx.compose.ui.text.AnnotatedString("")
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()

    // 段间距用「加高字号」的占位空行实现，避免额外 Spacer 破坏整段可选/换行连续性
    val spacingEm = (paragraphSpacingDp / fontSize).coerceAtLeast(0f)
    val lines = content.split("\n")
    lines.forEachIndexed { i, raw ->
        if (i > 0) {
            builder.append("\n")
            if (spacingEm > 0.05f) {
                val spacerStyle = androidx.compose.ui.text.SpanStyle(
                    fontSize = (paragraphSpacingDp.coerceAtLeast(0f)).sp,
                    letterSpacing = 0.sp,
                )
                val start = builder.length
                builder.append("\u200B") // 零宽空格占位（不可见，仅用于撑开行高）
                builder.addStyle(spacerStyle, start, builder.length)
                builder.append("\n")
            }
        }
        val line = raw.trimStart('\u3000', ' ', '\t')
        val needIndent = line.isNotEmpty() && !raw.startsWith("\u3000\u3000")
        if (needIndent) {
            builder.append("\u3000".repeat(indentEm.toInt().coerceAtLeast(1)))
        }
        builder.append(line)
    }
    return builder.toAnnotatedString()
}

/**
 * 按真实排版测量切页（P0 修复核心）。
 *
 * 旧实现用「字号 × density」当作每个字符宽度来估算每行字数，再乘行数得到每页容量：
 * 汉字确为全角，但标点、数字、英文字母均为半角，且标点不能出现在行首（避头尾），
 * 于是实际每行能放的字比估算值多，导致「每页字符数」偏大 → 一页文字实际排版后比屏幕高
 * → 最后一屏内容被裁掉（字只显示一半），同时因为切分点与真实折行位置错位，段落被硬切，
 * 页面里出现大片空白。
 *
 * 现在改为：把文本按段落合并成候选串，交给 [androidx.compose.ui.text.TextMeasurer] 用
 * 与渲染完全一致的 TextStyle / 宽度做真实测量，用 `didOverflowHeight` 二分出「刚好装得下」
 * 的最大字符数，再做切分。这样每页内容都能完整落在屏幕内，且切分点在真实折行边界附近。
 */
private fun paginateByMeasurement(
    measurer: androidx.compose.ui.text.TextMeasurer,
    content: String,
    chapterTitle: String,
    showTitle: Boolean,
    widthPx: Float,
    heightPx: Float,
    style: androidx.compose.ui.text.TextStyle,
    paragraphSpacingPx: Float,
): List<String> {
    if (content.isBlank()) return listOf("")

    val constraints = androidx.compose.ui.unit.Constraints(
        maxWidth = widthPx.toInt().coerceAtLeast(1),
        maxHeight = androidx.compose.ui.unit.Constraints.Infinity,
    )

    // 待处理段落队列（可增删）：切页后剩余内容会插回队首
    val queue = ArrayDeque<String>()
    content.split("\n").forEach { queue.addLast(it.trimEnd()) }

    // 标题占据的高度（仅第一页额外消耗）
    val titleHeight: Float = if (showTitle && chapterTitle.isNotBlank()) {
        runCatching {
            measurer.measure(
                text = androidx.compose.ui.text.AnnotatedString(chapterTitle),
                style = style.copy(
                    fontSize = (style.fontSize.value + 2).sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ),
                constraints = constraints,
            ).size.height.toFloat() + 16 * 2.6f
        }.getOrDefault(0f)
    } else 0f

    fun measureHeight(text: String): Int = measurer.measure(
        text = androidx.compose.ui.text.AnnotatedString(text),
        style = style,
        constraints = constraints,
    ).size.height

    fun fits(text: String, limitPx: Float): Boolean {
        val m = measurer.measure(
            text = androidx.compose.ui.text.AnnotatedString(text),
            style = style,
            constraints = constraints,
        )
        return !m.didOverflowHeight && m.size.height <= limitPx
    }

    /** 二分查找 [text] 中能在 [limitPx] 内放下的最大字符前缀长度 */
    fun maxFittingLength(text: String, limitPx: Float): Int {
        var lo = 1
        var hi = text.length
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (fits(text.substring(0, mid), limitPx)) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /** 在 [limit] 附近挑选更自然的切分点（优先换行，其次中文句读，否则任意处） */
    fun naturalCut(text: String, limit: Int): Int {
        if (limit >= text.length) return text.length
        if (limit <= 0) return 0
        val windowStart = (limit - 40).coerceAtLeast(0)
        for (i in limit downTo (windowStart + 1)) {
            val c = text[i - 1]
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '”' || c == '…') return i
        }
        return limit
    }

    val pages = mutableListOf<String>()
    var isFirstPage = true
    var guard = 0

    while (queue.isNotEmpty() && guard < 20000) {
        guard++
        val remainingHeight = (heightPx - if (isFirstPage) titleHeight else 0f).coerceAtLeast(1f)

        // 贪心装入完整段落，直到塞不下
        val buffer = StringBuilder()
        var consumed = 0
        while (queue.isNotEmpty()) {
            val para = queue.first()
            val addition = when {
                para.isEmpty() && buffer.isEmpty() -> "\n"
                buffer.isEmpty() -> para
                else -> "\n$para"
            }
            val trial = buffer.toString() + addition
            if (!fits(trial, remainingHeight)) break
            buffer.append(addition)
            queue.removeFirst()
            consumed++
            // 已接近一屏，停止扩展（避免超大字符串测量开销）
            if (measureHeight(buffer.toString()) >= remainingHeight * 0.92f) break
        }

        val current = buffer.toString()
        if (current.isEmpty()) {
            // 第一个段落就装不下：按字符二分切分，把剩余内容插回队首
            val para = queue.first()
            val maxLen = maxFittingLength(para, remainingHeight)
            val cut = if (maxLen <= 0) 1 else naturalCut(para, maxLen)
            val head = para.substring(0, cut.coerceAtMost(para.length))
            val tail = para.substring(cut.coerceAtMost(para.length))
            queue.removeFirst()
            if (tail.isNotEmpty()) queue.addFirst(tail)
            pages.add(head)
        } else {
            // 已装入的整段内容刚好放得下，但若还塞得下更多（queue 仍有）则继续下一轮即可
            pages.add(current)
        }
        isFirstPage = false
    }

    return pages.ifEmpty { listOf("") }.filter { it.isNotEmpty() || pages.size == 1 }
}


/** 顶部栏（书名 + 章节标题两行；右侧收藏按钮 + 进度；长按可收起菜单） */
@Composable
private fun BoxScope.ReaderTopBar(
    bookTitle: String,
    title: String,
    backgroundColor: Color,
    textColor: Color,
    chapterIndex: Int,
    chapterCount: Int,
    pageText: String,
    fromCache: Boolean,
    sourceName: String = "",
    collected: Boolean = false,
    onToggleCollect: () -> Unit = {},
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bookTitle,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            // 章节标题 + 来源（换源后来源名实时刷新，让用户确认当前读的是哪个源）
            val sub = if (sourceName.isNotBlank()) "$title · $sourceName" else title
            Text(
                text = sub,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // 收藏 / 取消收藏（阅读中随手入库）
        IconButton(onClick = onToggleCollect) {
            Icon(
                imageVector = if (collected) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (collected) "移出书架" else "加入书架",
                tint = if (collected) MaterialTheme.colorScheme.primary else textColor,
            )
        }
        // 离线缓存提示（#30）
        if (fromCache) {
            Text(
                text = "缓存",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        // 阅读进度：全书章节进度（第 n/N 章）+ 本章页码，供快速定位
        if (chapterCount > 0) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${chapterIndex + 1}/$chapterCount 章",
                    color = textColor.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "${((chapterIndex + 1) * 100f / chapterCount).toInt()}% · $pageText",
                    color = textColor.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 底部菜单（进度滑块 + 页签按钮 + 对应面板） */
@Composable
private fun BoxScope.ReaderBottomMenu(
    viewModel: NovelReaderViewModel,
    chapters: List<ChapterItem>,
    chapterIndex: Int,
    chapterPosition: Int,
    cachedIndices: Set<Int>,
    fontSize: Float,
    nightMode: Boolean,
    pageMode: Int,
    pageAnim: Int,
    hideTopBar: Boolean,
    hideBottomBar: Boolean,
    hideChapterTitle: Boolean,
    lineHeight: Float,
    paragraphSpacing: Float,
    sidePadding: Float,
    autoPage: Boolean,
    autoPageInterval: Int,
    backgroundColor: Color,
    textColor: Color,
    menuTab: Int,
    volumePage: Boolean,
) {
    val backgroundColors = if (nightMode) ReaderBackgrounds.nightColors else ReaderBackgrounds.dayColors

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(),
        color = backgroundColor,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                    // 打开目录时定位到当前章节：用列表下标而非源返回的 index 字段
                    // （部分源 index 从 1 开始或存在漂移，直接用会定位错位）
                    LaunchedEffect(chapters, chapterPosition) {
                        if (chapters.isNotEmpty()) {
                            listState.scrollToItem(chapterPosition.coerceIn(0, chapters.size - 1))
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
                                val isCurrent = index == chapterPosition
                                val cached = ch.index in cachedIndices
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.switchChapter(index)
                                            viewModel.toggleMenu()
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = ch.title,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else textColor,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // 已缓存标记（#30：离线可读章节一眼可见）
                                    if (cached) {
                                        Text(
                                            text = "已缓存",
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
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
                1 -> MenuScrollPanel {
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
                    // 行距
                    SettingSlider(
                        label = "行距",
                        value = lineHeight,
                        display = String.format("%.1f", lineHeight),
                        valueRange = 1.2f..2.4f,
                        textColor = textColor,
                        onChange = { viewModel.setLineHeight(it) },
                    )
                    // 段距
                    SettingSlider(
                        label = "段距",
                        value = paragraphSpacing,
                        display = "${paragraphSpacing.toInt()}",
                        valueRange = 0f..24f,
                        textColor = textColor,
                        onChange = { viewModel.setParagraphSpacing(it) },
                    )
                    // 左右页边距
                    SettingSlider(
                        label = "页边距",
                        value = sidePadding,
                        display = "${sidePadding.toInt()}",
                        valueRange = 8f..40f,
                        textColor = textColor,
                        onChange = { viewModel.setSidePadding(it) },
                    )
                    // 自动翻页
                    SettingSwitch("自动翻页", autoPage) { viewModel.setAutoPage(it) }
                    if (autoPage) {
                        SettingSlider(
                            label = "翻页间隔(秒)",
                            value = autoPageInterval.toFloat(),
                            display = "$autoPageInterval",
                            valueRange = 5f..120f,
                            textColor = textColor,
                            onChange = { viewModel.setAutoPageInterval(it.toInt()) },
                        )
                    }
                }
                else -> MenuScrollPanel {
                    // ===== 设置面板（含隐藏栏目选项） =====
                    SettingSwitch("夜间模式", nightMode) { viewModel.toggleNightMode() }
                    SettingSwitch("隐藏顶部栏", hideTopBar) { viewModel.setHideTopBar(it) }
                    SettingSwitch("隐藏进度栏", hideBottomBar) { viewModel.setHideBottomBar(it) }
                    SettingSwitch("隐藏章节标题", hideChapterTitle) { viewModel.setHideChapterTitle(it) }
                    SettingSwitch("音量键翻页", volumePage) { viewModel.setVolumePage(it) }
                    Spacer(Modifier.height(12.dp))
                    // 换源
                    Text("换源", color = textColor, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        MenuButton("切换书源", selected = false, textColor = textColor) {
                            viewModel.openChangeSource()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 缓存（#30）
                    Text("缓存", color = textColor, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        MenuButton("缓存本章", selected = false, textColor = textColor) {
                            viewModel.cacheCurrentChapter()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(4.dp))

            // ===== 进度滑块行 =====
            if (!hideBottomBar) {
                val maxIndex = (chapters.size - 1).coerceAtLeast(0)
                var sliderValue by remember(chapterPosition, chapters.size) {
                    androidx.compose.runtime.mutableFloatStateOf(chapterPosition.toFloat())
                }
                val targetIndex = sliderValue.toInt().coerceIn(0, maxIndex)
                val targetTitle = chapters.getOrNull(targetIndex)?.title.orEmpty()
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 拖动预览：显示目标章节序号 + 标题（旧实现只能盲拖，落点不可知）
                    if (chapters.isNotEmpty()) {
                        Text(
                            text = "第 ${targetIndex + 1}/${chapters.size} 章  $targetTitle",
                            color = textColor.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
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
                                val target = sliderValue.toInt().coerceIn(0, maxIndex)
                                if (chapters.isNotEmpty() && target != chapterPosition) {
                                    viewModel.switchChapter(target)
                                    viewModel.toggleMenu()
                                }
                            },
                            valueRange = 0f..(if (maxIndex <= 0) 1f else maxIndex.toFloat()),
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
            }

            // ===== 页签按钮行：目录 / 界面 / 设置 / 换源 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TabButton("目录", menuTab == 0, textColor) { viewModel.setMenuTab(0) }
                TabButton("界面", menuTab == 1, textColor) { viewModel.setMenuTab(1) }
                TabButton("设置", menuTab == 2, textColor) { viewModel.setMenuTab(2) }
                // 换源提到一级入口：原实现藏在「设置」页签里，用户根本找不到（换源形同虚设）
                TabButton("换源", false, textColor) { viewModel.openChangeSource() }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 面板滚动容器（界面 / 设置页签内容较多）。
 *
 * 原实现把所有控件直接堆在无高度约束的 Column 里，小屏上会溢出屏幕外被裁掉，
 * 表现为「菜单选项过长、下面几个开关点不到」。这里限制最大高度并允许滚动。
 */
@Composable
private fun MenuScrollPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        content()
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

/** 设置滑块行（行距 / 段距 / 页边距 / 自动翻页间隔） */
@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    display: String,
    valueRange: ClosedFloatingPointRange<Float>,
    textColor: Color,
    onChange: (Float) -> Unit,
) {
    // 本地态保证拖动顺滑，抬手才落库（避免频繁写 DataStore 造成卡顿）
    var local by remember(value) { androidx.compose.runtime.mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = textColor,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = display,
                color = textColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 换源面板（覆盖层）：搜索同名书 + 同作者筛选 + 候选列表（支持分页加载更多） */
@Composable
private fun BoxScope.ChangeSourcePanel(
    viewModel: NovelReaderViewModel,
    candidates: List<MediaItem>,
    loading: Boolean,
    sameAuthorOnly: Boolean,
    error: String?,
    hasMore: Boolean,
    loadingMore: Boolean,
    backgroundColor: Color,
    textColor: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .fastTap { viewModel.closeChangeSource() },
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            color = backgroundColor,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 标题行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "换源",
                        color = textColor,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "关闭",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { viewModel.closeChangeSource() }
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 同作者开关
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "仅同作者",
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = sameAuthorOnly, onCheckedChange = viewModel::setSameAuthorOnly)
                }
                Spacer(Modifier.height(4.dp))

                when {
                    loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    candidates.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "未找到可切换的同名书源",
                                color = textColor,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(candidates, key = { "${it.sourceId}_${it.id}" }) { cand ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.switchSource(cand) }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        text = cand.title,
                                        color = textColor,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        cand.author?.let {
                                            Text(
                                                text = it,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (cand.sourceName.isNotBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "· ${cand.sourceName}",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    // 元信息（章节数 / 状态 / 更新时间）：帮助判断哪个源更完整
                                    val meta = BookMeta.summaryLine(cand)
                                    if (meta.isNotEmpty()) {
                                        Text(
                                            text = meta,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                HorizontalDivider(color = textColor.copy(alpha = 0.15f))
                            }
                            // 加载更多：原实现只搜第 1 页，更多源永远搜不到
                            if (hasMore || loadingMore) {
                                item(key = "load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(
                                                if (loadingMore) Modifier
                                                else Modifier.clickable { viewModel.loadMoreChangeSource() },
                                            )
                                            .padding(vertical = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (loadingMore) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text(
                                                text = "加载更多书源",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 长按弹出的正文选择/复制面板（#5）。
 *
 * 以章节正文为内容，提供「全选并复制」「复制本章」以及可拖拽选择的操作，
 * 底层使用 Compose 的 SelectionContainer 支持系统级长按选词/复制。
 */
@Composable
private fun TextSelectPanel(
    content: String,
    chapterTitle: String,
    backgroundColor: Color,
    textColor: Color,
    onClose: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var feedback by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .fastTap { onClose() },
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f),
            color = backgroundColor,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "选择文本",
                        color = textColor,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "关闭",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 操作行
                Row {
                    MenuButton("复制本章", selected = false, textColor = textColor) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(content))
                        feedback = "已复制本章全文"
                    }
                    Spacer(Modifier.width(8.dp))
                    MenuButton("复制标题+正文", selected = false, textColor = textColor) {
                        clipboard.setText(
                            androidx.compose.ui.text.AnnotatedString("$chapterTitle\n\n$content"),
                        )
                        feedback = "已复制标题与正文"
                    }
                }
                feedback?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LaunchedEffect(it) {
                        delay(1500)
                        feedback = null
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = textColor.copy(alpha = 0.15f))
                Spacer(Modifier.height(4.dp))
                // 正文可选区域
                androidx.compose.foundation.text.selection.SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        // 修复：原写法每次重组都会 new 一个 ScrollState(0)，滚动位置被重置，
                        // 长文选中后一拖动就跳回顶部。用 remember 保持滚动状态。
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
