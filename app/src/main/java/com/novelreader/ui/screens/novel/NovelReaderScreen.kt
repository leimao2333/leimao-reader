package com.novelreader.ui.screens.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.core.text.ChineseConverter
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.theme.ReaderBackgrounds
import com.novelreader.ui.theme.SystemBarIconsOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/* ===================== 手势 ===================== */

/**
 * 三区单击（左右翻页 / 上下翻页共用）：左 1/3 上一页，右 1/3 下一页，中间呼出菜单。
 *
 * 关键修正（滑动翻页失效根因）：旧实现手写 `awaitEachGesture` + `awaitFirstDown` +
 * `it.consume()`，会与 Pager 内置的拖动翻页手势**竞争并消费指针事件**，导致滑动无法翻页。
 * 现改为 `detectTapGestures`——它天然只响应「按下即抬起」的 tap，不拦截、不消费
 * 拖动（drag）事件，Pager 的滑动翻页恢复生效。
 */
private fun Modifier.pageTap(
    onLeft: () -> Unit,
    onCenter: () -> Unit,
    onRight: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onTap = { offset ->
            val w = size.width.toFloat()
            if (w <= 0f) return@detectTapGestures
            val x = offset.x
            when {
                x < w / 3f -> onLeft()
                x > w * 2f / 3f -> onRight()
                else -> onCenter()
            }
        },
    )
}

/**
 * 滚动模式：整屏分上下两半翻屏，中间竖条呼出菜单。
 * 同样用 `detectTapGestures`，不拦截滚动拖拽。
 */
private fun Modifier.scrollTap(
    onUp: () -> Unit,
    onCenter: () -> Unit,
    onDown: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onTap = { offset ->
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            if (w <= 0f || h <= 0f) return@detectTapGestures
            val x = offset.x
            val y = offset.y
            if (x in (w / 3f)..(w * 2f / 3f) && y in (h * 0.28f)..(h * 0.72f)) {
                onCenter()
            } else if (y < h / 2f) {
                onUp()
            } else {
                onDown()
            }
        },
    )
}

private fun Modifier.fastTap(onTap: () -> Unit): Modifier = this.pointerInput(Unit) {
    detectTapGestures(onTap = { onTap() })
}

/* ===================== 阅读页 ===================== */

@Composable
fun NovelReaderScreen(
    viewModel: NovelReaderViewModel,
    item: MediaItem,
    chapters: List<ChapterItem>,
    chapter: ChapterItem,
    onBack: () -> Unit,
) {
    val rawContent by viewModel.content.collectAsState()
    val convertMode by viewModel.convertMode.collectAsState()
    // 简繁转换：逐字映射，长度与下标一一对应，
    // 因此正文搜索的匹配位置在转换前后保持一致。
    val content = remember(rawContent, convertMode) {
        ChineseConverter.convert(rawContent, convertMode)
    }
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
    val pageType by viewModel.pageType.collectAsState()
    val hideTopBar by viewModel.hideTopBar.collectAsState()
    val hideBottomBar by viewModel.hideBottomBar.collectAsState()
    val hideChapterTitle by viewModel.hideChapterTitle.collectAsState()
    val chapterIndex by viewModel.currentChapterIndex.collectAsState()
    val initialTextPosition by viewModel.initialTextPosition.collectAsState()
    val initialPageIndex by viewModel.initialPageIndex.collectAsState()
    val changeSourceOpen by viewModel.changeSourceOpen.collectAsState()
    val changeSourceCandidates by viewModel.changeSourceCandidates.collectAsState()
    val changeSourceLoading by viewModel.changeSourceLoading.collectAsState()
    val changeSourceProgress by viewModel.changeSourceProgress.collectAsState()
    val sameAuthorOnly by viewModel.sameAuthorOnly.collectAsState()
    val changeSourceError by viewModel.changeSourceError.collectAsState()
    val changeSourceHasMore by viewModel.changeSourceHasMore.collectAsState()
    val changeSourceLoadingMore by viewModel.changeSourceLoadingMore.collectAsState()
    val fromCache by viewModel.fromCache.collectAsState()
    val cachedIndices by viewModel.cachedIndices.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val collected by viewModel.collected.collectAsState()
    val shelfStateReady by viewModel.shelfStateReady.collectAsState()
    val shelfPromptEnabled by viewModel.shelfPromptEnabled.collectAsState()
    val hasNextChapter by viewModel.hasNextChapter.collectAsState()
    val hasPrevChapter by viewModel.hasPrevChapter.collectAsState()
    val currentItemState by viewModel.currentItemState.collectAsState()
    val vmChapters by viewModel.chapters.collectAsState()

    // 大修新增
    val fontFamilyIndex by viewModel.fontFamily.collectAsState()
    val bold by viewModel.bold.collectAsState()
    val indent by viewModel.indent.collectAsState()
    val letterSpacing by viewModel.letterSpacing.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val fullScreen by viewModel.fullScreen.collectAsState()
    val volumeUpPrev by viewModel.volumeUpPrev.collectAsState()
    val readSeconds by viewModel.readSeconds.collectAsState()
    val replaceRules by viewModel.replaceRules.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val chapterQuery by viewModel.chapterQuery.collectAsState()
    val chapterDesc by viewModel.chapterDesc.collectAsState()
    val replaceOpen by viewModel.replaceOpen.collectAsState()
    val contentSearchOpen by viewModel.contentSearchOpen.collectAsState()
    val speaking by viewModel.speaking.collectAsState()
    val speechRate by viewModel.speechRate.collectAsState()

    val displayItem = currentItemState ?: item
    val chapterList = if (vmChapters.isNotEmpty()) vmChapters else chapters
    val chapterPosition = remember(chapterList, chapterIndex) {
        chapterList.indexOfFirst { it.index == chapterIndex }.coerceAtLeast(0)
    }

    var pageText by remember { mutableStateOf("") }
    var currentRatio by remember { mutableIntStateOf(0) }
    val chapterCount = chapterList.size

    LaunchedEffect(item, chapter) {
        viewModel.init(item, chapters, chapter)
    }

    // v1.8.1.9：未加入书架的作品，打开阅读后询问一次是否加入。
    // 必须等 shelfStateReady —— collected 初始为 false，直接判断会误弹。
    var showShelfPrompt by remember { mutableStateOf(false) }
    var shelfPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(shelfStateReady, collected, displayItem) {
        if (shelfStateReady && !collected && !shelfPrompted && shelfPromptEnabled) {
            shelfPrompted = true
            showShelfPrompt = true
        }
    }
    if (showShelfPrompt) {
        AlertDialog(
            onDismissRequest = { showShelfPrompt = false },
            title = { Text("加入书架") },
            text = {
                Text("《${displayItem.title}》还没加入书架，加入后可随时在书架继续阅读。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showShelfPrompt = false
                    viewModel.toggleCollect()
                }) { Text("加入书架") }
            },
            dismissButton = {
                TextButton(onClick = { showShelfPrompt = false }) { Text("暂不") }
            },
        )
    }

    // 提示条自动消失
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1800)
            viewModel.dismissToast()
        }
    }

    // 阅读时长
    DisposableEffect(Unit) {
        viewModel.onReaderVisible()
        onDispose { viewModel.onReaderHidden() }
    }

    // 屏幕常亮
    val readerView = LocalView.current
    DisposableEffect(readerView) {
        val previous = readerView.keepScreenOn
        readerView.keepScreenOn = true
        onDispose { readerView.keepScreenOn = previous }
    }

    val context = LocalContext.current
    // 沉浸式全屏
    DisposableEffect(fullScreen) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, readerView) }
        if (fullScreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 阅读亮度
    DisposableEffect(brightness) {
        val activity = context.findActivity()
        if (activity == null) return@DisposableEffect onDispose { }
        val attrs = activity.window.attributes
        val original = attrs.screenBrightness
        attrs.screenBrightness =
            if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
        activity.window.attributes = attrs
        onDispose {
            val a2 = activity.window.attributes
            a2.screenBrightness = original
            activity.window.attributes = a2
        }
    }

    val backgroundColors = if (nightMode) ReaderBackgrounds.nightColors else ReaderBackgrounds.dayColors
    val bgIndexSafe = bgIndex.coerceIn(backgroundColors.indices)
    val backgroundColor = backgroundColors[bgIndexSafe].background
    val textColor = backgroundColors[bgIndexSafe].text

    // 状态栏图标跟着纸张色走（v1.8.8.2）：纸张色独立于全局主题 —— 白天模式也可能选「藏蓝」这类
    // 深底配色，此时必须用白色图标，否则状态栏在深色纸上一片模糊。全屏时系统栏是隐藏的，
    // 这里设的值只影响下滑临时唤出系统栏时的观感。
    SystemBarIconsOn(backgroundColor)

    val resolvedFontFamily = when (fontFamilyIndex) {
        PreferencesManager.FONT_FAMILY_SERIF -> FontFamily.Serif
        PreferencesManager.FONT_FAMILY_MONO -> FontFamily.Monospace
        else -> FontFamily.Default
    }

    // 音量键翻页
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
                    if (volumeUpPrev) viewModel.volumePrevPage() else viewModel.volumeNextPage(); true
                }
                Key.VolumeDown -> {
                    if (volumeUpPrev) viewModel.volumeNextPage() else viewModel.volumePrevPage(); true
                }
                else -> false
            }
        }

    val isScroll = pageType == PreferencesManager.PAGE_TYPE_SCROLL
    val isVertical = pageType == PreferencesManager.PAGE_TYPE_VERTICAL

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(volumeKeyModifier)
            .background(backgroundColor),
    ) {
        when {
            loading -> {
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
                    Text(error ?: "加载失败", color = textColor, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
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
            isScroll -> {
                ScrollNovel(
                    content = content,
                    chapterTitle = chapterTitle,
                    chapterIndex = chapterIndex,
                    initialRatio = initialTextPosition,
                    fontSize = fontSize,
                    fontFamily = resolvedFontFamily,
                    bold = bold,
                    indent = indent,
                    letterSpacing = letterSpacing,
                    lineHeightMultiplier = lineHeight,
                    paragraphSpacing = paragraphSpacing,
                    sidePadding = sidePadding,
                    textColor = textColor,
                    hideChapterTitle = hideChapterTitle,
                    hasNextChapter = hasNextChapter,
                    onScrollRatio = { ratio ->
                        currentRatio = (ratio * 10000f).toInt()
                        viewModel.saveScrollRatio(chapterIndex, ratio)
                    },
                    autoPage = autoPage,
                    autoPageInterval = autoPageInterval,
                    onTapCenter = { viewModel.toggleMenu() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPageTextChanged = { pageText = it },
                    pageTurn = viewModel.pageTurn,
                )
            }
            isVertical -> {
                VerticalPagerNovel(
                    content = content,
                    chapterTitle = chapterTitle,
                    chapterIndex = chapterIndex,
                    initialPage = initialPageIndex,
                    initialRatio = initialTextPosition,
                    fontSize = fontSize,
                    fontFamily = resolvedFontFamily,
                    bold = bold,
                    indent = indent,
                    letterSpacing = letterSpacing,
                    lineHeightMultiplier = lineHeight,
                    paragraphSpacing = paragraphSpacing,
                    sidePadding = sidePadding,
                    textColor = textColor,
                    hideChapterTitle = hideChapterTitle,
                    autoPage = autoPage,
                    autoPageInterval = autoPageInterval,
                    hasNextChapter = hasNextChapter,
                    onTapCenter = { viewModel.toggleMenu() },
                    onReachFirst = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPageChanged = { page -> viewModel.savePageIndex(chapterIndex, page) },
                    onRatioChanged = { r -> currentRatio = r; viewModel.updatePagerRatio(r) },
                    onPageTextChanged = { pageText = it },
                    pageTurn = viewModel.pageTurn,
                )
            }
            else -> {
                HorizontalPagerNovel(
                    content = content,
                    chapterTitle = chapterTitle,
                    chapterIndex = chapterIndex,
                    initialPage = initialPageIndex,
                    initialRatio = initialTextPosition,
                    fontSize = fontSize,
                    fontFamily = resolvedFontFamily,
                    bold = bold,
                    indent = indent,
                    letterSpacing = letterSpacing,
                    lineHeightMultiplier = lineHeight,
                    paragraphSpacing = paragraphSpacing,
                    sidePadding = sidePadding,
                    textColor = textColor,
                    hideChapterTitle = hideChapterTitle,
                    pageType = pageType,
                    autoPage = autoPage,
                    autoPageInterval = autoPageInterval,
                    hasNextChapter = hasNextChapter,
                    onTapCenter = { viewModel.toggleMenu() },
                    onReachFirst = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPageChanged = { page -> viewModel.savePageIndex(chapterIndex, page) },
                    onRatioChanged = { r -> currentRatio = r; viewModel.updatePagerRatio(r) },
                    onPageTextChanged = { pageText = it },
                    pageTurn = viewModel.pageTurn,
                )
            }
        }

        // ===== 常驻顶部状态条：显示本章名（菜单收起时；菜单展开时由顶部栏显示更完整信息） =====
        if (!showMenu && !hideTopBar) {
            ReaderChapterStatusBar(
                bookTitle = displayItem.title,
                chapterTitle = chapterTitle,
                textColor = textColor,
            )
        }

        // ===== 菜单层 =====
        if (showMenu) {
            if (!hideTopBar) {
                ReaderTopBar(
                    bookTitle = displayItem.title,
                    sourceName = displayItem.sourceName,
                    title = chapterTitle,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    chapterIndex = chapterPosition,
                    chapterCount = chapterCount,
                    pageText = pageText,
                    fromCache = fromCache,
                    collected = collected,
                    onToggleCollect = { viewModel.toggleCollect() },
                    onAddBookmark = { viewModel.addBookmark(currentRatio, content.take(60)) },
                    onOpenContentSearch = { viewModel.openContentSearch() },
                    onBack = onBack,
                )
            }
            ReaderBottomMenu(
                viewModel = viewModel,
                chapters = chapterList,
                chapterPosition = chapterPosition,
                cachedIndices = cachedIndices,
                chapterQuery = chapterQuery,
                chapterDesc = chapterDesc,
                bookmarks = bookmarks,
                menuTab = menuTab,
                backgroundColor = backgroundColor,
                textColor = textColor,
                nightMode = nightMode,
                fontSize = fontSize,
                fontFamily = fontFamilyIndex,
                bold = bold,
                indent = indent,
                letterSpacing = letterSpacing,
                lineHeight = lineHeight,
                paragraphSpacing = paragraphSpacing,
                sidePadding = sidePadding,
                bgIndex = bgIndex,
                pageType = pageType,
                fullScreen = fullScreen,
                hideTopBar = hideTopBar,
                hideBottomBar = hideBottomBar,
                hideChapterTitle = hideChapterTitle,
                volumePage = volumePageEnabled,
                volumeUpPrev = volumeUpPrev,
                autoPage = autoPage,
                autoPageInterval = autoPageInterval,
                brightness = brightness,
                readSeconds = readSeconds,
                replaceRules = replaceRules,
                convertMode = convertMode,
                speaking = speaking,
                speechRate = speechRate,
                onAddBookmark = { viewModel.addBookmark(currentRatio, content.take(60)) },
            )
        }

        if (changeSourceOpen) {
            ChangeSourcePanel(
                candidates = changeSourceCandidates,
                loading = changeSourceLoading,
                sameAuthorOnly = sameAuthorOnly,
                error = changeSourceError,
                hasMore = changeSourceHasMore,
                loadingMore = changeSourceLoadingMore,
                progress = changeSourceProgress,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onToggleSameAuthor = { viewModel.setSameAuthorOnly(it) },
                onPick = { viewModel.switchSource(it) },
                onLoadMore = { viewModel.loadMoreChangeSource() },
                onClose = { viewModel.closeChangeSource() },
            )
        }

        if (replaceOpen) {
            TextReplacePanel(
                rules = replaceRules,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onSave = { viewModel.setReplaceRules(it) },
                onClose = { viewModel.closeReplaceRules() },
            )
        }

        if (contentSearchOpen) {
            ContentSearchPanel(
                viewModel = viewModel,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onClose = { viewModel.closeContentSearch() },
            )
        }

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

/* ===================== 阅读正文样式 ===================== */

private fun readerTextStyle(
    fontSize: Float,
    lineHeightMultiplier: Float,
    fontFamily: FontFamily,
    bold: Boolean,
    letterSpacing: Float,
): TextStyle = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineHeightMultiplier).sp,
    fontFamily = fontFamily,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    letterSpacing = letterSpacing.sp,
    textAlign = TextAlign.Justify,
)

/* ===================== 滚动模式 ===================== */

@Composable
private fun ScrollNovel(
    content: String,
    chapterTitle: String,
    chapterIndex: Int,
    initialRatio: Int,
    fontSize: Float,
    fontFamily: FontFamily,
    bold: Boolean,
    indent: Boolean,
    letterSpacing: Float,
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
) {
    val scrollState = remember(chapterIndex) { ScrollState(0) }
    val scope = rememberCoroutineScope()

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

    val scrollRatio by remember {
        androidx.compose.runtime.derivedStateOf {
            val max = scrollState.maxValue
            if (max > 0) (scrollState.value.toFloat() / max).coerceIn(0f, 1f) else 0f
        }
    }
    LaunchedEffect(scrollRatio) {
        onPageTextChanged("${(scrollRatio * 100).toInt()}%")
    }

    LaunchedEffect(chapterIndex, content, initialRatio) {
        if (initialRatio in 1..10000) {
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            scrollState.scrollTo((initialRatio / 10000f * scrollState.maxValue).toInt())
        }
    }

    LaunchedEffect(chapterIndex, content) {
        snapshotFlow { scrollState.value }.collectLatest { v ->
            delay(400)
            val max = scrollState.maxValue
            if (max > 0) onScrollRatio(v.toFloat() / max)
        }
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .scrollTap(
                onUp = {
                    scope.launch {
                        val target = (scrollState.value - scrollState.maxValue * 0.9f).toInt().coerceAtLeast(0)
                        scrollState.animateScrollTo(target)
                    }
                },
                onCenter = onTapCenter,
                onDown = {
                    scope.launch {
                        val max = scrollState.maxValue
                        if (scrollState.value >= max - 8) {
                            onNextChapter()
                        } else {
                            val target = (scrollState.value + max * 0.9f).toInt().coerceAtMost(max)
                            scrollState.animateScrollTo(target)
                        }
                    }
                },
            )
            .verticalScroll(scrollState)
            .padding(horizontal = sidePadding.dp, vertical = 48.dp),
    ) {
        // 注意：Box 的子项会全部叠在同一位置，标题/下一章会压在正文上。
        // 必须用 Column 纵向排列，Box 只承担滚动与内边距。
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!hideChapterTitle) {
                Text(
                    text = chapterTitle,
                    fontSize = (fontSize + 2).sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = buildReaderText(content, fontSize, paragraphSpacing, indent),
                fontSize = fontSize.sp,
                fontFamily = fontFamily,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                lineHeight = (fontSize * lineHeightMultiplier).sp,
                letterSpacing = letterSpacing.sp,
                textAlign = TextAlign.Justify,
            )

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
                        color = if (hasNextChapter) MaterialTheme.colorScheme.primary else textColor.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/* ===================== 左右翻页 ===================== */

@Composable
private fun HorizontalPagerNovel(
    content: String,
    chapterTitle: String,
    chapterIndex: Int,
    initialPage: Int,
    initialRatio: Int,
    fontSize: Float,
    fontFamily: FontFamily,
    bold: Boolean,
    indent: Boolean,
    letterSpacing: Float,
    lineHeightMultiplier: Float,
    paragraphSpacing: Float,
    sidePadding: Float,
    textColor: Color,
    hideChapterTitle: Boolean,
    pageType: Int,
    autoPage: Boolean,
    autoPageInterval: Int,
    hasNextChapter: Boolean,
    onTapCenter: () -> Unit,
    onReachFirst: () -> Unit,
    onNextChapter: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onRatioChanged: (Int) -> Unit,
    onPageTextChanged: (String) -> Unit = {},
    pageTurn: kotlinx.coroutines.flow.SharedFlow<Int>? = null,
) {
    BoxWithConstraintsCompat { maxWidthDp, maxHeightDp ->
        val density = LocalDensity.current.density
        val availWidthPx = (maxWidthDp - sidePadding * 2f).coerceAtLeast(1f) * density
        // 底部预留页码行高度，且分页测量与渲染同用 buildReaderText（段距占位行/首行缩进参与测量），
        // 否则渲染比测量高，末行会被裁掉一半
        val fontPx = fontSize * density * androidx.compose.ui.platform.LocalDensity.current.fontScale
        val textHeightPx = ((maxHeightDp - 96f).coerceAtLeast(1f) * density - fontPx * 1.8f).coerceAtLeast(1f)
        val paraSpacingPx = paragraphSpacing * density
        val titleSpacerPx = 16f * density

        // v1.7.1.0：分页计算移出主线程（null = 还在算）
        val pagesOrNull = rememberPaginatedPages(
            content = content,
            chapterTitle = chapterTitle,
            hideChapterTitle = hideChapterTitle,
            widthPx = availWidthPx,
            heightPx = textHeightPx,
            fontSize = fontSize,
            lineHeightMultiplier = lineHeightMultiplier,
            paragraphSpacingPx = paraSpacingPx,
            titleSpacerPx = titleSpacerPx,
            fontFamily = fontFamily,
            bold = bold,
            letterSpacing = letterSpacing,
            indent = indent,
        )
        val pages = pagesOrNull.orEmpty()
        val pagesReady = pagesOrNull != null
        val hasNextGate = content.isNotBlank() && hasNextChapter
        // 分页未就绪时页数记 0：Pager 不渲染内容，避免「只有半章」的中间态被翻到
        val pageCount = if (pagesReady) pages.size + if (hasNextGate) 1 else 0 else 0

        val startPage = remember(chapterIndex, initialPage, initialRatio, pageCount) {
            when {
                initialPage > 0 -> initialPage
                initialRatio > 0 && pageCount > 1 -> (initialRatio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })
        val scope = rememberCoroutineScope()

        // v1.7.1.0：分页是异步算出来的，Pager 创建时 pageCount 还是 0，`initialPage` 无从生效。
        // 结果就绪后补一次定位（只在**首次**就绪时做：后续切章由 chapterIndex 那个 effect 归零）。
        var didInitialPosition by remember { mutableStateOf(false) }
        LaunchedEffect(pagesOrNull) {
            if (!didInitialPosition && pagesOrNull != null) {
                didInitialPosition = true
                if (startPage > 0 && startPage < pageCount) pagerState.scrollToPage(startPage)
            }
        }

        // v1.7.1.0 翻页节流：动画进行中不重启动画，而是按 targetPage 继续递进并直接跳页。
        // 旧实现每次连点都 animateScrollToPage(currentPage ± 1)，动画被反复取消 → "跟不上"。
        suspend fun turn(delta: Int): Boolean {
            val last = pagerState.pageCount - 1
            val base = if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.currentPage
            val target = base + delta
            if (target < 0 || target > last) return false
            if (pagerState.isScrollInProgress) pagerState.scrollToPage(target)
            else pagerState.animateScrollToPage(target)
            return true
        }

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir ->
                    if (dir > 0) {
                        if (!turn(1)) onNextChapter()
                    } else {
                        if (!turn(-1)) onReachFirst()
                    }
                }
            }
        }

        var lastChapterIndex by remember { mutableIntStateOf(chapterIndex) }
        LaunchedEffect(chapterIndex) {
            if (lastChapterIndex != chapterIndex) {
                lastChapterIndex = chapterIndex
                if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
                onPageChanged(0)
            }
        }

        var gateConsumed by remember { mutableIntStateOf(-1) }
        LaunchedEffect(pagerState.currentPage, pageCount, chapterIndex) {
            if (hasNextGate && pageCount > 0 &&
                pagerState.currentPage == pageCount - 1 &&
                gateConsumed != chapterIndex
            ) {
                gateConsumed = chapterIndex
                onNextChapter()
            }
        }

        LaunchedEffect(pagerState, chapterIndex) {
            snapshotFlow { pagerState.currentPage }.collectLatest { page ->
                delay(300)
                if (!pagerState.isScrollInProgress) onPageChanged(page)
            }
        }

        LaunchedEffect(pagerState, pageCount) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                // 分页未就绪（pageCount = 0）时不报页码，免得页码栏闪一下「1/0」
                if (pageCount <= 0) return@collect
                onPageTextChanged("${page + 1}/$pageCount")
                onRatioChanged(
                    if (pageCount <= 1) 0 else (page * 10000 / (pageCount - 1))
                )
            }
        }

        LaunchedEffect(autoPage, autoPageInterval, chapterIndex) {
            if (!autoPage) return@LaunchedEffect
            while (true) {
                delay(autoPageInterval * 1000L)
                val last = pagerState.pageCount - 1
                if (pagerState.currentPage < last) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                else onNextChapter()
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .pageTap(
                    onLeft = {
                        scope.launch { if (!turn(-1)) onReachFirst() }
                    },
                    onCenter = onTapCenter,
                    onRight = {
                        scope.launch { if (!turn(1)) onNextChapter() }
                    },
                ),
        ) { page ->
            // v1.7.1.0：currentPageOffsetFraction 改到 graphicsLayer 的 lambda 内读取。
            // 之前在组合作用域读它 → 滚动每一帧都让本页重组、连带重建整页文本（快速连翻掉帧的主因）；
            // 放进 lambda 后只更新图层属性，不再触发重组。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val offset = pagerState.currentPageOffsetFraction
                        val transform = when (pageType) {
                            PreferencesManager.PAGE_TYPE_COVER -> PageTransform.cover(offset)
                            PreferencesManager.PAGE_TYPE_SIMULATE -> PageTransform.simulate(offset)
                            PreferencesManager.PAGE_TYPE_NO_ANIM -> PageTransform.NoAnim
                            else -> PageTransform.slide
                        }
                        translationX = transform.translationX
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                        alpha = transform.alpha
                        rotationY = transform.rotationY
                        shadowElevation = transform.shadowElevation
                        cameraDistance = 20f * density
                    },
            ) {
                PageContent(
                    page = page,
                    pages = pages,
                    chapterTitle = chapterTitle,
                    hideChapterTitle = hideChapterTitle,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    bold = bold,
                    indent = indent,
                    letterSpacing = letterSpacing,
                    lineHeightMultiplier = lineHeightMultiplier,
                    paragraphSpacing = paragraphSpacing,
                    sidePadding = sidePadding,
                    textColor = textColor,
                )
            }
        }

        // v1.7.1.0：分页在后台线程算，就绪前显示轻量加载态。
        // 此时 pageCount = 0、Pager 不渲染任何页，所以这层覆盖不会挡到已渲染的内容。
        if (!pagesReady) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.width(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/* ===================== 上下翻页 ===================== */

@Composable
private fun VerticalPagerNovel(
    content: String,
    chapterTitle: String,
    chapterIndex: Int,
    initialPage: Int,
    initialRatio: Int,
    fontSize: Float,
    fontFamily: FontFamily,
    bold: Boolean,
    indent: Boolean,
    letterSpacing: Float,
    lineHeightMultiplier: Float,
    paragraphSpacing: Float,
    sidePadding: Float,
    textColor: Color,
    hideChapterTitle: Boolean,
    autoPage: Boolean,
    autoPageInterval: Int,
    hasNextChapter: Boolean,
    onTapCenter: () -> Unit,
    onReachFirst: () -> Unit,
    onNextChapter: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onRatioChanged: (Int) -> Unit,
    onPageTextChanged: (String) -> Unit = {},
    pageTurn: kotlinx.coroutines.flow.SharedFlow<Int>? = null,
) {
    BoxWithConstraintsCompat { maxWidthDp, maxHeightDp ->
        val density = LocalDensity.current.density
        val availWidthPx = (maxWidthDp - sidePadding * 2f).coerceAtLeast(1f) * density
        // 底部预留页码行高度，且分页测量与渲染同用 buildReaderText（段距占位行/首行缩进参与测量），
        // 否则渲染比测量高，末行会被裁掉一半
        val fontPx = fontSize * density * androidx.compose.ui.platform.LocalDensity.current.fontScale
        val textHeightPx = ((maxHeightDp - 96f).coerceAtLeast(1f) * density - fontPx * 1.8f).coerceAtLeast(1f)
        val paraSpacingPx = paragraphSpacing * density
        val titleSpacerPx = 16f * density

        // v1.7.1.0：分页计算移出主线程（null = 还在算）
        val pagesOrNull = rememberPaginatedPages(
            content = content,
            chapterTitle = chapterTitle,
            hideChapterTitle = hideChapterTitle,
            widthPx = availWidthPx,
            heightPx = textHeightPx,
            fontSize = fontSize,
            lineHeightMultiplier = lineHeightMultiplier,
            paragraphSpacingPx = paraSpacingPx,
            titleSpacerPx = titleSpacerPx,
            fontFamily = fontFamily,
            bold = bold,
            letterSpacing = letterSpacing,
            indent = indent,
        )
        val pages = pagesOrNull.orEmpty()
        val pagesReady = pagesOrNull != null
        val hasNextGate = content.isNotBlank() && hasNextChapter
        // 分页未就绪时页数记 0：Pager 不渲染内容，避免「只有半章」的中间态被翻到
        val pageCount = if (pagesReady) pages.size + if (hasNextGate) 1 else 0 else 0

        val startPage = remember(chapterIndex, initialPage, initialRatio, pageCount) {
            when {
                initialPage > 0 -> initialPage
                initialRatio > 0 && pageCount > 1 -> (initialRatio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })
        val scope = rememberCoroutineScope()

        // v1.7.1.0：分页是异步算出来的，Pager 创建时 pageCount 还是 0，`initialPage` 无从生效。
        // 结果就绪后补一次定位（只在**首次**就绪时做：后续切章由 chapterIndex 那个 effect 归零）。
        var didInitialPosition by remember { mutableStateOf(false) }
        LaunchedEffect(pagesOrNull) {
            if (!didInitialPosition && pagesOrNull != null) {
                didInitialPosition = true
                if (startPage > 0 && startPage < pageCount) pagerState.scrollToPage(startPage)
            }
        }

        // v1.7.1.0 翻页节流：动画进行中不重启动画，而是按 targetPage 继续递进并直接跳页。
        // 旧实现每次连点都 animateScrollToPage(currentPage ± 1)，动画被反复取消 → "跟不上"。
        suspend fun turn(delta: Int): Boolean {
            val last = pagerState.pageCount - 1
            val base = if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.currentPage
            val target = base + delta
            if (target < 0 || target > last) return false
            if (pagerState.isScrollInProgress) pagerState.scrollToPage(target)
            else pagerState.animateScrollToPage(target)
            return true
        }

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir ->
                    if (dir > 0) {
                        if (!turn(1)) onNextChapter()
                    } else {
                        if (!turn(-1)) onReachFirst()
                    }
                }
            }
        }

        var lastChapterIndex by remember { mutableIntStateOf(chapterIndex) }
        LaunchedEffect(chapterIndex) {
            if (lastChapterIndex != chapterIndex) {
                lastChapterIndex = chapterIndex
                if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
                onPageChanged(0)
            }
        }

        var gateConsumed by remember { mutableIntStateOf(-1) }
        LaunchedEffect(pagerState.currentPage, pageCount, chapterIndex) {
            if (hasNextGate && pageCount > 0 &&
                pagerState.currentPage == pageCount - 1 &&
                gateConsumed != chapterIndex
            ) {
                gateConsumed = chapterIndex
                onNextChapter()
            }
        }

        LaunchedEffect(pagerState, chapterIndex) {
            snapshotFlow { pagerState.currentPage }.collectLatest { page ->
                delay(300)
                if (!pagerState.isScrollInProgress) onPageChanged(page)
            }
        }

        LaunchedEffect(pagerState, pageCount) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                // 分页未就绪（pageCount = 0）时不报页码，免得页码栏闪一下「1/0」
                if (pageCount <= 0) return@collect
                onPageTextChanged("${page + 1}/$pageCount")
                onRatioChanged(if (pageCount <= 1) 0 else (page * 10000 / (pageCount - 1)))
            }
        }

        LaunchedEffect(autoPage, autoPageInterval, chapterIndex) {
            if (!autoPage) return@LaunchedEffect
            while (true) {
                delay(autoPageInterval * 1000L)
                val last = pagerState.pageCount - 1
                if (pagerState.currentPage < last) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                else onNextChapter()
            }
        }

        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .pageTap(
                    onLeft = onReachFirst,
                    onCenter = onTapCenter,
                    onRight = onNextChapter,
                ),
        ) { page ->
            Box(modifier = Modifier.fillMaxSize()) {
                PageContent(
                    page = page,
                    pages = pages,
                    chapterTitle = chapterTitle,
                    hideChapterTitle = hideChapterTitle,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    bold = bold,
                    indent = indent,
                    letterSpacing = letterSpacing,
                    lineHeightMultiplier = lineHeightMultiplier,
                    paragraphSpacing = paragraphSpacing,
                    sidePadding = sidePadding,
                    textColor = textColor,
                )
            }
        }

        // v1.7.1.0：分页计算中的轻量加载态（同横向翻页）
        if (!pagesReady) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.width(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** 单页内容（左右 / 上下翻页共用） */
@Composable
private fun PageContent(
    page: Int,
    pages: List<String>,
    chapterTitle: String,
    hideChapterTitle: Boolean,
    fontSize: Float,
    fontFamily: FontFamily,
    bold: Boolean,
    indent: Boolean,
    letterSpacing: Float,
    lineHeightMultiplier: Float,
    paragraphSpacing: Float,
    sidePadding: Float,
    textColor: Color,
) {
    if (page >= pages.size) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("本章已读完", fontSize = fontSize.sp, color = textColor.copy(alpha = 0.6f))
            Spacer(Modifier.height(12.dp))
            Text("继续翻页进入下一章 ›", fontSize = (fontSize - 2).sp, color = textColor.copy(alpha = 0.4f))
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = sidePadding.dp, vertical = 48.dp),
    ) {
        if (page == 0 && !hideChapterTitle) {
            Text(
                text = chapterTitle,
                fontSize = (fontSize + 2).sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
        // v1.7.1.0：缓存 AnnotatedString —— 以前任何一次父级重组都会重建整页文本（含逐段 split）
        val pageText = remember(pages, page, fontSize, paragraphSpacing, indent) {
            buildReaderText(pages[page], fontSize, paragraphSpacing, indent)
        }
        Text(
            text = pageText,
            fontSize = fontSize.sp,
            fontFamily = fontFamily,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            lineHeight = (fontSize * lineHeightMultiplier).sp,
            letterSpacing = letterSpacing.sp,
            textAlign = TextAlign.Justify,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${page + 1}/${pages.size}",
            fontSize = (fontSize - 6).sp,
            color = textColor.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

/** BoxWithConstraints 的轻量封装（返回 dp 尺寸） */
@Composable
private fun BoxWithConstraintsCompat(content: @Composable (maxWidthDp: Float, maxHeightDp: Float) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        content(maxWidth.value, maxHeight.value)
    }
}

/* ===================== 翻页动画 ===================== */

private data class PageTransform(
    val translationX: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 1f,
    val rotationY: Float = 0f,
    val shadowElevation: Float = 0f,
) {
    companion object {
        val NoAnim = PageTransform()
        val slide = PageTransform()

        /** 覆盖：离场页轻微缩放淡出，新页从右侧覆盖上来 */
        fun cover(offset: Float): PageTransform {
            val abs = kotlin.math.abs(offset).coerceIn(0f, 1f)
            val scale = 1f - abs * 0.12f
            return PageTransform(scaleX = scale, scaleY = scale, alpha = 1f - abs * 0.2f, shadowElevation = abs * 8f)
        }

        /** 仿真：页面绕左侧书脊做 3D 翻转（比旧版更接近真实翻页） */
        fun simulate(offset: Float): PageTransform {
            val abs = kotlin.math.abs(offset).coerceIn(0f, 1f)
            // 翻转角度与位移方向绑定：往左拖（offset>0）→ 页面绕 Y 轴向左翻起
            val rotation = offset.coerceIn(-1f, 1f) * 70f
            val scale = 1f - abs * 0.08f
            return PageTransform(
                rotationY = rotation,
                scaleX = scale,
                scaleY = scale,
                alpha = 1f - abs * 0.12f,
                shadowElevation = abs * 14f,
            )
        }
    }
}

/* ===================== 正文构建 / 分页 ===================== */

/**
 * 构造阅读正文的 [AnnotatedString]：
 * - [indent] 为真时给每个段落补 2 个全角空格首行缩进（原文已缩进则不重复）；
 * - 段间距用「加高字号的零宽占位行」实现。
 */
private fun buildReaderText(
    content: String,
    fontSize: Float,
    paragraphSpacingDp: Float,
    indent: Boolean,
): AnnotatedString {
    if (content.isBlank()) return AnnotatedString("")
    val builder = AnnotatedString.Builder()
    val spacingEm = (paragraphSpacingDp / fontSize).coerceAtLeast(0f)
    val lines = content.split("\n")
    lines.forEachIndexed { i, raw ->
        if (i > 0) {
            builder.append("\n")
            if (spacingEm > 0.05f) {
                val spacerStyle = SpanStyle(fontSize = paragraphSpacingDp.coerceAtLeast(0f).sp, letterSpacing = 0.sp)
                val start = builder.length
                builder.append("\u200B")
                builder.addStyle(spacerStyle, start, builder.length)
                builder.append("\n")
            }
        }
        val line = raw.trimStart('\u3000', ' ', '\t')
        val invalidIndent = indent && line.isNotEmpty() && !raw.startsWith("\u3000\u3000")
        if (invalidIndent) builder.append("\u3000\u3000")
        builder.append(line)
    }
    return builder.toAnnotatedString()
}

/**
 * 按真实排版测量切页（TextMeasurer 二分）。
 * 每页内容保证完整落在屏幕内，切分点尽量落在句读 / 换行边界。
 */
private fun paginateByMeasurement(
    measurer: androidx.compose.ui.text.TextMeasurer,
    content: String,
    chapterTitle: String,
    showTitle: Boolean,
    widthPx: Float,
    heightPx: Float,
    style: TextStyle,
    paragraphSpacingPx: Float,
    /** 与渲染一致的字串构造（含段距占位行与首行缩进），保证测量=渲染 */
    render: (String) -> AnnotatedString,
    /** 标题下方 Spacer(16.dp) 的真实像素高度 */
    titleSpacerPx: Float = 41.6f,
): List<String> {
    if (content.isBlank()) return listOf("")
    val constraints = Constraints(maxWidth = widthPx.toInt().coerceAtLeast(1), maxHeight = Constraints.Infinity)

    val queue = ArrayDeque<String>()
    content.split("\n").forEach { queue.addLast(it.trimEnd()) }

    val titleHeight: Float = if (showTitle && chapterTitle.isNotBlank()) {
        runCatching {
            measurer.measure(
                text = AnnotatedString(chapterTitle),
                style = style.copy(fontSize = (style.fontSize.value + 2).sp, fontWeight = FontWeight.Bold),
                constraints = constraints,
            ).size.height.toFloat() + titleSpacerPx
        }.getOrDefault(0f)
    } else 0f

    /** 同 [fits]，但把真实高度返回供复用 —— 避免同一串文本被重复测量（v1.7.1.0） */
    fun fitsHeight(text: String, limitPx: Float): Int? {
        val m = measurer.measure(text = render(text), style = style, constraints = constraints)
        return if (!m.didOverflowHeight && m.size.height <= limitPx) m.size.height else null
    }

    fun fits(text: String, limitPx: Float): Boolean = fitsHeight(text, limitPx) != null

    fun maxFittingLength(text: String, limitPx: Float): Int {
        var lo = 1
        var hi = text.length
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (fits(text.substring(0, mid), limitPx)) {
                best = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return best
    }

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
        val buffer = StringBuilder()
        while (queue.isNotEmpty()) {
            val para = queue.first()
            val addition = when {
                para.isEmpty() && buffer.isEmpty() -> "\n"
                buffer.isEmpty() -> para
                else -> "\n$para"
            }
            val trial = buffer.toString() + addition
            // 复用本次测量的高度：以前 fits() 量完 trial 后，又对完全相同的 buffer 再量一次（100% 冗余）
            val trialHeight = fitsHeight(trial, remainingHeight) ?: break
            buffer.append(addition)
            queue.removeFirst()
            if (trialHeight >= remainingHeight * 0.92f) break
        }
        val current = buffer.toString()
        if (current.isEmpty()) {
            val para = queue.first()
            val maxLen = maxFittingLength(para, remainingHeight)
            val cut = if (maxLen <= 0) 1 else naturalCut(para, maxLen)
            val head = para.substring(0, cut.coerceAtMost(para.length))
            val tail = para.substring(cut.coerceAtMost(para.length))
            queue.removeFirst()
            if (tail.isNotEmpty()) queue.addFirst(tail)
            pages.add(head)
        } else {
            pages.add(current)
        }
        isFirstPage = false
    }
    return pages.ifEmpty { listOf("") }.filter { it.isNotEmpty() || pages.size == 1 }
}

/**
 * **后台线程**计算分页结果（v1.7.1.0）。
 *
 * 以前分页是 `remember { paginateByMeasurement(...) }` —— 在**主线程的组合阶段同步执行**。
 * 一章几千字要跑几十次二分排版测量，动辄几十到几百毫秒全砸在 UI 线程上，
 * 表现就是「切章 / 改字号时整个界面卡住」。现在换成在 [Dispatchers.Default] 上算。
 *
 * 返回值：
 * - `null` = **还在算**（刚切章 / 刚改字号），调用方应显示轻量加载态；
 * - 非 null = 就绪（可能只有一页空串，表示本章无正文）。
 *
 * 关于线程安全：**不复用 `rememberTextMeasurer()` 的实例**，而是在后台协程里新建
 * 一个 [androidx.compose.ui.text.TextMeasurer]（内部持有布局缓存，跨线程共享不安全）。
 * [androidx.compose.ui.text.font.FontFamily.Resolver] 本身是线程安全的（Compose 在
 * 任意线程排版文本都走它），因此新建实例不会引入竞态。
 */
@Composable
private fun rememberPaginatedPages(
    content: String,
    chapterTitle: String,
    hideChapterTitle: Boolean,
    widthPx: Float,
    heightPx: Float,
    fontSize: Float,
    lineHeightMultiplier: Float,
    paragraphSpacingPx: Float,
    titleSpacerPx: Float,
    fontFamily: FontFamily,
    bold: Boolean,
    letterSpacing: Float,
    indent: Boolean,
): List<String>? {
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val style = readerTextStyle(fontSize, lineHeightMultiplier, fontFamily, bold, letterSpacing)
    // buildReaderText 的段距用 dp 单位，这里从像素换算回去（与渲染完全同源）
    val paragraphSpacingDp = paragraphSpacingPx / density.density
    // 与渲染同源的字串构造（含段距占位行 / 首行缩进）——测量等于渲染，末行才不会被裁
    val render: (String) -> AnnotatedString = { buildReaderText(it, fontSize, paragraphSpacingDp, indent) }

    // 任一影响分页的输入变化 → 立刻退回「计算中」，避免旧分页短暂错位显示
    var pages by remember(
        content, chapterTitle, hideChapterTitle, widthPx, heightPx,
        fontSize, lineHeightMultiplier, paragraphSpacingPx, titleSpacerPx,
        fontFamily, bold, letterSpacing, indent,
    ) { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(
        content, chapterTitle, hideChapterTitle, widthPx, heightPx,
        fontSize, lineHeightMultiplier, paragraphSpacingPx, titleSpacerPx,
        fontFamily, bold, letterSpacing, indent,
    ) {
        pages = withContext(Dispatchers.Default) {
            runCatching {
                paginateByMeasurement(
                    measurer = androidx.compose.ui.text.TextMeasurer(resolver, density, layoutDirection),
                    content = content,
                    chapterTitle = chapterTitle,
                    showTitle = !hideChapterTitle,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    style = style,
                    paragraphSpacingPx = paragraphSpacingPx,
                    render = render,
                    titleSpacerPx = titleSpacerPx,
                )
            }.getOrElse {
                // 分页失败不该让正文消失：退化成「整章一页」
                listOf(content)
            }
        }
    }
    return pages
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
