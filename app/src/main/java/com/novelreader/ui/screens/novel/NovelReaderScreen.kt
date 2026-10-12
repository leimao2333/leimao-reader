package com.novelreader.ui.screens.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.theme.ReaderBackgrounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/* ===================== 手势 ===================== */

/** 三区单击：左 1/3 上一页，右 1/3 下一页，中间呼出菜单；长按 → 选文；双击 → 呼出菜单 */
private fun Modifier.pageTap(
    onLeft: () -> Unit,
    onCenter: () -> Unit,
    onRight: () -> Unit,
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
        if (w <= 0f) return@awaitEachGesture
        val x = u.position.x
        when {
            x < w / 3f -> onLeft()
            x > w * 2f / 3f -> onRight()
            else -> onCenter()
        }
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

/** 滚动模式：整屏分上下两半翻屏，中间竖条呼出菜单；长按选文；双击菜单 */
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
        if (x in (w / 3f)..(w * 2f / 3f) && y in (h * 0.28f)..(h * 0.72f)) {
            onCenter()
        } else if (y < h / 2f) {
            onUp()
        } else {
            onDown()
        }
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

private fun Modifier.fastTap(onTap: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation()
        if (up != null) onTap()
    }
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
    val sameAuthorOnly by viewModel.sameAuthorOnly.collectAsState()
    val changeSourceError by viewModel.changeSourceError.collectAsState()
    val changeSourceHasMore by viewModel.changeSourceHasMore.collectAsState()
    val changeSourceLoadingMore by viewModel.changeSourceLoadingMore.collectAsState()
    val fromCache by viewModel.fromCache.collectAsState()
    val cachedIndices by viewModel.cachedIndices.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val collected by viewModel.collected.collectAsState()
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

    val displayItem = currentItemState ?: item
    val chapterList = if (vmChapters.isNotEmpty()) vmChapters else chapters
    val chapterPosition = remember(chapterList, chapterIndex) {
        chapterList.indexOfFirst { it.index == chapterIndex }.coerceAtLeast(0)
    }

    var pageText by remember { mutableStateOf("") }
    var currentRatio by remember { mutableIntStateOf(0) }
    val chapterCount = chapterList.size
    var showTextSelect by remember { mutableStateOf(false) }

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
                    onLongPress = { showTextSelect = true },
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
                    onLongPress = { showTextSelect = true },
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
                    onLongPress = { showTextSelect = true },
                )
            }
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

        if (showTextSelect) {
            TextSelectPanel(
                content = content,
                chapterTitle = chapterTitle,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onClose = { showTextSelect = false },
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
    onLongPress: (() -> Unit)? = null,
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
                onLongPress = onLongPress,
            )
            .verticalScroll(scrollState)
            .padding(horizontal = sidePadding.dp, vertical = 48.dp),
    ) {
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
    onLongPress: (() -> Unit)? = null,
) {
    BoxWithConstraintsCompat { maxWidthDp, maxHeightDp ->
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current.density
        val availWidthPx = (maxWidthDp - sidePadding * 2f).coerceAtLeast(1f) * density
        val textHeightPx = (maxHeightDp - 96f).coerceAtLeast(1f) * density
        val style = readerTextStyle(fontSize, lineHeightMultiplier, fontFamily, bold, letterSpacing)
        val paraSpacingPx = paragraphSpacing * density

        val pages = remember(
            content, chapterTitle, hideChapterTitle, availWidthPx, textHeightPx,
            fontSize, lineHeightMultiplier, paraSpacingPx, fontFamily, bold, letterSpacing, indent,
        ) {
            paginateByMeasurement(
                measurer = textMeasurer,
                content = content,
                chapterTitle = chapterTitle,
                showTitle = !hideChapterTitle,
                widthPx = availWidthPx,
                heightPx = textHeightPx,
                style = style,
                paragraphSpacingPx = paraSpacingPx,
            )
        }
        val hasNextGate = content.isNotBlank() && hasNextChapter
        val pageCount = pages.size + if (hasNextGate) 1 else 0

        val startPage = remember(chapterIndex, initialPage, initialRatio, pageCount) {
            when {
                initialPage > 0 -> initialPage
                initialRatio > 0 && pageCount > 1 -> (initialRatio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })
        val scope = rememberCoroutineScope()

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir ->
                    if (dir > 0) {
                        val last = pagerState.pageCount - 1
                        if (pagerState.currentPage < last) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        else onNextChapter()
                    } else {
                        if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        else onReachFirst()
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
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        } else onReachFirst()
                    },
                    onCenter = onTapCenter,
                    onRight = {
                        val last = pagerState.pageCount - 1
                        if (pagerState.currentPage < last) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else onNextChapter()
                    },
                    onLongPress = onLongPress,
                ),
        ) { page ->
            val offset = pagerState.currentPageOffsetFraction
            val transform = when (pageType) {
                PreferencesManager.PAGE_TYPE_COVER -> PageTransform.cover(offset)
                PreferencesManager.PAGE_TYPE_SIMULATE -> PageTransform.simulate(offset)
                PreferencesManager.PAGE_TYPE_NO_ANIM -> PageTransform.NoAnim
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
    onLongPress: (() -> Unit)? = null,
) {
    BoxWithConstraintsCompat { maxWidthDp, maxHeightDp ->
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current.density
        val availWidthPx = (maxWidthDp - sidePadding * 2f).coerceAtLeast(1f) * density
        val textHeightPx = (maxHeightDp - 96f).coerceAtLeast(1f) * density
        val style = readerTextStyle(fontSize, lineHeightMultiplier, fontFamily, bold, letterSpacing)
        val paraSpacingPx = paragraphSpacing * density

        val pages = remember(
            content, chapterTitle, hideChapterTitle, availWidthPx, textHeightPx,
            fontSize, lineHeightMultiplier, paraSpacingPx, fontFamily, bold, letterSpacing, indent,
        ) {
            paginateByMeasurement(
                measurer = textMeasurer,
                content = content,
                chapterTitle = chapterTitle,
                showTitle = !hideChapterTitle,
                widthPx = availWidthPx,
                heightPx = textHeightPx,
                style = style,
                paragraphSpacingPx = paraSpacingPx,
            )
        }
        val hasNextGate = content.isNotBlank() && hasNextChapter
        val pageCount = pages.size + if (hasNextGate) 1 else 0

        val startPage = remember(chapterIndex, initialPage, initialRatio, pageCount) {
            when {
                initialPage > 0 -> initialPage
                initialRatio > 0 && pageCount > 1 -> (initialRatio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })
        val scope = rememberCoroutineScope()

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir ->
                    if (dir > 0) {
                        val last = pagerState.pageCount - 1
                        if (pagerState.currentPage < last) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        else onNextChapter()
                    } else {
                        if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        else onReachFirst()
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
                    onLongPress = onLongPress,
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
        Text(
            text = buildReaderText(pages[page], fontSize, paragraphSpacing, indent),
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
            ).size.height.toFloat() + 16 * 2.6f
        }.getOrDefault(0f)
    } else 0f

    fun measureHeight(text: String): Int = measurer.measure(
        text = AnnotatedString(text), style = style, constraints = constraints,
    ).size.height

    fun fits(text: String, limitPx: Float): Boolean {
        val m = measurer.measure(text = AnnotatedString(text), style = style, constraints = constraints)
        return !m.didOverflowHeight && m.size.height <= limitPx
    }

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
            if (!fits(trial, remainingHeight)) break
            buffer.append(addition)
            queue.removeFirst()
            if (measureHeight(buffer.toString()) >= remainingHeight * 0.92f) break
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

/* ===================== 长按选文 ===================== */

@Composable
private fun TextSelectPanel(
    content: String,
    chapterTitle: String,
    backgroundColor: Color,
    textColor: Color,
    onClose: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var feedback by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .fastTap { onClose() },
    ) {
        BoxWithConstraints(modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(maxHeight * 0.78f),
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
                        color = Color(0xFF4A6FA5),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    SelectChip("复制本章") {
                        clipboard.setText(AnnotatedString(content))
                        feedback = "已复制本章全文"
                    }
                    Spacer(Modifier.width(8.dp))
                    SelectChip("复制标题+正文") {
                        clipboard.setText(AnnotatedString("$chapterTitle\n\n$content"))
                        feedback = "已复制标题与正文"
                    }
                }
                feedback?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFF4A6FA5), style = MaterialTheme.typography.labelMedium)
                    LaunchedEffect(it) {
                        delay(1500)
                        feedback = null
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = textColor.copy(alpha = 0.15f))
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.text.selection.SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
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
}

@Composable
private fun SelectChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
