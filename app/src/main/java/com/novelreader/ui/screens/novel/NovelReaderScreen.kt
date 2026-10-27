package com.novelreader.ui.screens.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.unit.Dp
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

/**
 * 分页模式下「到边界继续滑就换章」的触发阈值（v1.8.8.4）。
 *
 * 单位是**页宽的比例**：0.15 表示要多拖出 15% 屏宽才认。太小会在正常翻页的起手
 * 抖动里误触发（翻页变成切章），太大又要使劲拉到底才有反应。
 */
private const val EDGE_SWAP_THRESHOLD = 0.15f


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

    // v1.8.8.4：打开阅读页时整体淡入。
    // 以前点开一本书是先白等一段（拉章节），然后「啪」地整屏文字冒出来 ——
    // 主观感受就是「点了没反应，然后突然卡出一页」。220ms 淡入把这段等待接上，
    // 从点击到出内容之间就有了过渡，不再是硬切。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "readerEnterAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // v1.8.8.4：进入淡入（见上面 enterAlpha 的说明）
            .graphicsLayer { alpha = enterAlpha }
            .then(volumeKeyModifier)
            .background(backgroundColor),
    ) {
        when {
            loading -> {
                // v1.8.8.4：加载态改成**骨架屏**。
                // 以前只有一个居中的小圈，转场滑进来之后是一大片空白干等两三秒 ——
                // 用户的原话是「不动几秒、太素了」。骨架屏能先把版面结构立起来，
                // 让人感觉「内容正在长出来」，而不是页面卡住了。
                val skeletonPulse by rememberInfiniteTransition(label = "skeletonPulse")
                    .animateFloat(
                        initialValue = 0.05f,
                        targetValue = 0.13f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "skeletonAlpha",
                    )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .fastTap { viewModel.toggleMenu() },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = sidePadding.dp, vertical = 48.dp),
                    ) {
                        // 章节标题占位
                        SkeletonBar(0.45f, 20.dp, skeletonPulse, textColor)
                        Spacer(Modifier.height(22.dp))
                        // 正文占位：宽度参差，像真的段落
                        val lineWidths = listOf(1f, 0.97f, 1f, 0.93f, 0.99f, 1f, 0.72f)
                        repeat(4) {
                            lineWidths.forEach { w ->
                                SkeletonBar(w, 14.dp, skeletonPulse, textColor)
                                Spacer(Modifier.height(11.dp))
                            }
                            Spacer(Modifier.height(15.dp))
                        }
                    }
                    // 底部：转圈 + 明确的文字说明
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(22.dp),
                            strokeWidth = 2.dp,
                            color = textColor.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (chapter.title.isNotBlank()) "正在加载 ${chapter.title}" else "正在加载章节…",
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 40.dp),
                        )
                    }
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

    // ===== 连点翻屏（v1.8.8.4）=====
    // 旧实现每次点击都 `animateScrollTo(scrollState.value ± 0.9 屏)`。动画进行中
    // `value` 才前进了很少一点，于是「连点 5 下」加起来还不到一屏；而且每次都把
    // 上一个动画取消重来，手感是「点一下顿一下」—— 滚动模式连点翻不动就是这个原因。
    // 现在点击只累加 scrollTarget，由下面的 effect 朝最新目标滑过去：连点 5 下
    // 就一次滑 5 屏，中途不丢点击、不反复重启动画。
    var scrollTarget by remember(chapterIndex) { mutableIntStateOf(-1) }

    /** 请求前进 / 后退 delta 屏；false = 已经到底，调用方该切章 */
    fun requestScroll(delta: Float): Boolean {
        val max = scrollState.maxValue
        if (max <= 0) return false
        // 有待执行目标时以它为准（动画还在路上，scrollState.value 不可信）
        val base = if (scrollTarget >= 0) scrollTarget else scrollState.value
        if (delta > 0 && base >= max - 8) return false
        val target = (base + max * delta).toInt().coerceIn(0, max)
        scrollTarget = target
        return true
    }

    LaunchedEffect(scrollTarget) {
        val target = scrollTarget
        if (target < 0) return@LaunchedEffect
        try {
            scrollState.animateScrollTo(target)
        } finally {
            // 目标没变才算走完；被用户拖动打断或期间又点了，都留给下一轮
            if (scrollTarget == target) scrollTarget = -1
        }
    }

    if (pageTurn != null) {
        LaunchedEffect(pageTurn, chapterIndex, content) {
            pageTurn.collect { dir ->
                // 内容还没布局完（maxValue = 0）时别把「翻不动」当成「已到底」
                if (scrollState.maxValue <= 0) return@collect
                if (dir > 0) {
                    if (!requestScroll(0.9f)) onNextChapter()
                } else {
                    requestScroll(-0.9f)
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
                // v1.8.8.4：同步累加目标，不再每次从当前位置重算 —— 连点才追得上。
                // maxValue = 0（内容还没布局完）时不切章，否则点一下直接跳下一章。
                onUp = { requestScroll(-0.9f) },
                onCenter = onTapCenter,
                onDown = {
                    if (scrollState.maxValue > 0 && !requestScroll(0.9f)) onNextChapter()
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

        // v1.7.1.0：分页是异步算出来的，Pager 创建时 pageCount 还是 0，`initialPage` 无从生效。
        // 结果就绪后补一次定位（只在**首次**就绪时做：后续切章由 chapterIndex 那个 effect 归零）。
        var didInitialPosition by remember { mutableStateOf(false) }
        LaunchedEffect(pagesOrNull) {
            if (!didInitialPosition && pagesOrNull != null) {
                didInitialPosition = true
                if (startPage > 0 && startPage < pageCount) pagerState.scrollToPage(startPage)
            }
        }

        // ===== 连点翻页（v1.8.8.4）=====
        // 用「递增的请求号」驱动，而不是目标页的值：目标页可能连续两次算出来是同一个数
        // （比如已经钳在末页），值不变就不会触发重组、effect 也不会重启 —— 表现就是
        // 「点了完全没反应」。请求号每次都 +1，保证每一次点击都会被执行一次。
        //
        // 这里**不能用 `remember(chapterIndex)`**（踩过的坑）：切章时它会新建一个 State
        // 对象，而 `pageTap` 的 `pointerInput(Unit)` 在手势建立后不会重建，闭包里仍然
        // 握着**旧的** State —— 于是点击把值写进了一个没人读的废弃对象，组合侧收不到
        // 任何变化，effect 永远不执行。日志里的表现就是切章之后只有「点击」没有「翻页」。
        // 所以 State 的生命周期必须覆盖整个阅读页，切章时只重置目标、不动对象本身。
        var turnTick by remember { mutableIntStateOf(0) }
        val turnTarget = remember { intArrayOf(-1) }

        /** 请求翻 +delta 页；false = 真的到头了，调用方该切章 / 回上一章 */
        fun requestTurn(delta: Int): Boolean {
            val last = pagerState.pageCount - 1
            if (last < 0) return false
            val base = if (turnTarget[0] in 0..last) turnTarget[0] else pagerState.currentPage
            val target = base + delta
            if (target in 0..last) {
                turnTarget[0] = target
                turnTick++
                android.util.Log.d("NovelTurn", "点击 delta=$delta base=$base target=$target last=$last")
                return true
            }
            // 目标越界。**只有实际页面真的停在边界**才算「到头」——
            // 连点时目标累加得比页面快，几下就会越过末页；那种情况把目标钳在
            // 边界上照常翻页，绝不能因此切章（用户要的是连点翻页，不是快速下一章）。
            val atEdge = if (delta > 0) {
                pagerState.currentPage >= last
            } else {
                pagerState.currentPage <= 0
            }
            if (atEdge) {
                android.util.Log.d("NovelTurn", "到底 delta=$delta current=${pagerState.currentPage} last=$last")
                return false
            }
            turnTarget[0] = target.coerceIn(0, last)
            turnTick++
            android.util.Log.d(
                "NovelTurn",
                "钳制 delta=$delta base=$base target=$target → ${turnTarget[0]}",
            )
            return true
        }

        LaunchedEffect(turnTick) {
            if (turnTick == 0) return@LaunchedEffect
            val target = turnTarget[0]
            if (target < 0) return@LaunchedEffect
            val distance = kotlin.math.abs(target - pagerState.currentPage)
            android.util.Log.d(
                "NovelTurn",
                "翻页 target=$target current=${pagerState.currentPage} distance=$distance",
            )
            if (distance > 1) {
                // 连点：**直接跳**，不走动画。
                // animateScrollToPage 被取消时 Pager 会回弹到最近的整页 —— 连点间隔
                // 比动画短，每次都在中途取消、弹回原页，等于一下都没翻。瞬跳没有这个问题。
                pagerState.scrollToPage(target)
            } else if (distance == 1) {
                pagerState.animateScrollToPage(
                    target,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                )
            }
            // 目标没被新点击改过就清空；改过就留给下一轮
            if (turnTarget[0] == target) turnTarget[0] = -1
        }

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir ->
                    // 分页还没算出来（pageCount = 0）时不切章
                    if (pagerState.pageCount <= 0) return@collect
                    if (dir > 0) {
                        if (!requestTurn(1)) onNextChapter()
                    } else {
                        if (!requestTurn(-1)) onReachFirst()
                    }
                }
            }
        }

        var lastChapterIndex by remember { mutableIntStateOf(chapterIndex) }
        LaunchedEffect(chapterIndex) {
            if (lastChapterIndex != chapterIndex) {
                lastChapterIndex = chapterIndex
                // 切章后页码重新算，旧目标作废（State 对象本身不动，见上面的说明）
                turnTarget[0] = -1
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

        // ===== 到边界继续滑 = 换章（v1.8.8.4）=====
        // Pager 滑到第一页 / 最后一页就停住，不会告诉调用方「用户还想往后翻」，
        // 于是「右滑到顶」永远停在第一章第一页。
        // 这里用 pointerInput 在**不消费事件**的前提下观察手指位移（requireUnconsumed
        // = false，Pager 照常翻页）：停在第一页还往「前」拖超过 15% 屏宽就回上一章，
        // 最后一页往「后」拖同理去下一章。横向模式看 X、纵向模式看 Y。
        // 不走 nestedScroll 接未消费滚动量 —— 新系统上到达边界后那部分会被
        // overscroll 拉伸效果吃掉，父级根本收不到。
        fun edgeSwapModifier(vertical: Boolean): Modifier =
            Modifier.pointerInput(pagerState, vertical) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var fired = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!fired) {
                            val last = pagerState.pageCount - 1
                            val delta = if (vertical) {
                                change.position.y - down.position.y
                            } else {
                                change.position.x - down.position.x
                            }
                            val extent = (if (vertical) size.height else size.width).toFloat()
                            val limit = extent * EDGE_SWAP_THRESHOLD
                            if (last >= 0) {
                                when {
                                    pagerState.currentPage == 0 && delta > limit -> {
                                        fired = true
                                        onReachFirst()
                                    }
                                    pagerState.currentPage == last && delta < -limit -> {
                                        fired = true
                                        onNextChapter()
                                    }
                                }
                            }
                        }
                        if (!change.pressed) break
                    }
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
                // 到边界继续滑换章（v1.8.8.4）：横向翻页看 X 位移
                .then(edgeSwapModifier(vertical = false))
                .pageTap(
                    // v1.8.8.4：直接同步累加目标页，不再 `scope.launch` —— 走协程会让
                    // 点击延迟一帧才生效，连点时更容易丢。
                    // pageCount = 0（分页还没算出来）时不切章，否则点一下直接跳下一章
                    onLeft = { if (pagerState.pageCount > 0 && !requestTurn(-1)) onReachFirst() },
                    onCenter = onTapCenter,
                    onRight = { if (pagerState.pageCount > 0 && !requestTurn(1)) onNextChapter() },
                ),
        ) { page ->
            // v1.7.1.0：偏移在 graphicsLayer 的 lambda 内读取。
            // 之前在组合作用域读它 → 滚动每一帧都让本页重组、连带重建整页文本（快速连翻掉帧的主因）；
            // 放进 lambda 后只更新图层属性，不再触发重组。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // v1.8.8.4：每页算**自己的**相对偏移（-1 = 完全离开，0 = 正在中间）。
                        // 以前这里直接用全局的 currentPageOffsetFraction，所有可见页拿到同一个值，
                        // 翻页时前后两页以相同幅度一起缩放淡出 —— 那是「粗糙」的根源。
                        val pageOffset =
                            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        val transform = when (pageType) {
                            PreferencesManager.PAGE_TYPE_COVER -> PageTransform.cover(pageOffset)
                            PreferencesManager.PAGE_TYPE_SIMULATE -> PageTransform.simulate(pageOffset)
                            PreferencesManager.PAGE_TYPE_NO_ANIM -> PageTransform.NoAnim
                            else -> PageTransform.slide
                        }
                        // 3D 翻转要绕边缘转才有「翻书」感，绕中心转是扑克牌翻面
                        transformOrigin = TransformOrigin(transform.originX, 0.5f)
                        // v1.8.8.4：alpha < 1 时 Compose 默认把整页先渲到离屏 buffer 再合成，
                        // 而阅读页是整屏文字 —— 每帧一次全屏离屏合成，正是快速翻页掉帧的来源。
                        // ModulateAlpha 直接把 alpha 乘到绘制上，不建离屏层。
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                        alpha = transform.alpha
                        rotationY = transform.rotationY
                        // 阴影要配合 shape 才会真正画出来（只设 elevation 是空转）
                        shape = RectangleShape
                        shadowElevation = transform.shadowElevation
                        // 透视强度：20f 太扁（像贴纸平移）、8f 又太强（旋转时页面被压得明显），
                        // 16f 折中 —— 有纵深但看不出变形
                        cameraDistance = 16f * density
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

                // v1.8.8.4：卷曲阴影 —— 纸张翻起时靠书脊的一侧是暗的。
                // 加这一层渐变，3D 翻转看起来才有「纸的厚度」，而不是一块平面在转。
                // 除了「无动画」模式，其余模式都压这一层：默认的平移模式原来纯平移、
                // 一点层次都没有，加上边缘阴影后翻页立刻不像「图片在滑」了。
                if (pageType != PreferencesManager.PAGE_TYPE_NO_ANIM) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val shadeOffset = (pagerState.currentPage - page) +
                                    pagerState.currentPageOffsetFraction
                                val strength = kotlin.math.abs(shadeOffset).coerceIn(0f, 1f)
                                if (strength <= 0.01f) return@drawBehind
                                val band = size.width * 0.32f
                                val shade = Color.Black.copy(alpha = 0.55f * strength)
                                val brush = if (shadeOffset > 0f) {
                                    // 正在翻走：书脊在左，左边缘压暗
                                    Brush.horizontalGradient(
                                        colors = listOf(shade, Color.Transparent),
                                        startX = 0f,
                                        endX = band,
                                    )
                                } else {
                                    // 正在进入：从右侧滑来，右边缘背光
                                    Brush.horizontalGradient(
                                        colors = listOf(Color.Transparent, shade),
                                        startX = size.width - band,
                                        endX = size.width,
                                    )
                                }
                                drawRect(brush = brush)
                            },
                    )
                }
                // v1.8.8.4：卷边 —— 仿真模式下翻走的页在右边缘卷起一条，
                // 露出纸背面 + 落在下一页上的投影。纯 3D 旋转看起来还是「一块平面在转」，
                // 有了卷边才有纸的厚度，这是「翻书感」最直观的一笔。
                if (pageType == PreferencesManager.PAGE_TYPE_SIMULATE) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val offset = (pagerState.currentPage - page) +
                                    pagerState.currentPageOffsetFraction
                                // 只有正在翻走的页才有卷边；进来的页是平的
                                if (offset <= 0.02f) return@drawBehind
                                val progress = offset.coerceIn(0f, 1f)
                                val w = size.width
                                val h = size.height
                                val band = w * 0.16f * progress
                                if (band <= 0.5f) return@drawBehind

                                // 卷起的条带（露出的纸背面）
                                val curl = Path().apply {
                                    moveTo(w, 0f)
                                    lineTo(w - band, 0f)
                                    quadraticBezierTo(w - band * 0.30f, h * 0.5f, w - band, h)
                                    lineTo(w, h)
                                    close()
                                }
                                drawPath(
                                    path = curl,
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFFF7F4EC).copy(alpha = 0.96f),
                                            Color(0xFFD6D1C4).copy(alpha = 0.92f),
                                        ),
                                        startX = w - band,
                                        endX = w,
                                    ),
                                )
                                // 卷边在下方那页上投的影
                                val shadowBand = band * 2.2f
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.30f * progress),
                                        ),
                                        startX = w - shadowBand,
                                        endX = w - band,
                                    ),
                                    topLeft = Offset(w - shadowBand, 0f),
                                    size = Size(shadowBand, h),
                                )
                            },
                    )
                }
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

        // v1.7.1.0：分页是异步算出来的，Pager 创建时 pageCount 还是 0，`initialPage` 无从生效。
        // 结果就绪后补一次定位（只在**首次**就绪时做：后续切章由 chapterIndex 那个 effect 归零）。
        var didInitialPosition by remember { mutableStateOf(false) }
        LaunchedEffect(pagesOrNull) {
            if (!didInitialPosition && pagesOrNull != null) {
                didInitialPosition = true
                if (startPage > 0 && startPage < pageCount) pagerState.scrollToPage(startPage)
            }
        }

        // ===== 连点翻页（v1.8.8.4）=====
        // 用「递增的请求号」驱动，而不是目标页的值：目标页可能连续两次算出来是同一个数
        // （比如已经钳在末页），值不变就不会触发重组、effect 也不会重启 —— 表现就是
        // 「点了完全没反应」。请求号每次都 +1，保证每一次点击都会被执行一次。
        //
        // 这里**不能用 `remember(chapterIndex)`**（踩过的坑）：切章时它会新建一个 State
        // 对象，而 `pageTap` 的 `pointerInput(Unit)` 在手势建立后不会重建，闭包里仍然
        // 握着**旧的** State —— 于是点击把值写进了一个没人读的废弃对象，组合侧收不到
        // 任何变化，effect 永远不执行。日志里的表现就是切章之后只有「点击」没有「翻页」。
        // 所以 State 的生命周期必须覆盖整个阅读页，切章时只重置目标、不动对象本身。
        var turnTick by remember { mutableIntStateOf(0) }
        val turnTarget = remember { intArrayOf(-1) }

        /** 请求翻 +delta 页；false = 真的到头了，调用方该切章 / 回上一章 */
        fun requestTurn(delta: Int): Boolean {
            val last = pagerState.pageCount - 1
            if (last < 0) return false
            val base = if (turnTarget[0] in 0..last) turnTarget[0] else pagerState.currentPage
            val target = base + delta
            if (target in 0..last) {
                turnTarget[0] = target
                turnTick++
                android.util.Log.d("NovelTurn", "点击 delta=$delta base=$base target=$target last=$last")
                return true
            }
            // 目标越界。**只有实际页面真的停在边界**才算「到头」——
            // 连点时目标累加得比页面快，几下就会越过末页；那种情况把目标钳在
            // 边界上照常翻页，绝不能因此切章（用户要的是连点翻页，不是快速下一章）。
            val atEdge = if (delta > 0) {
                pagerState.currentPage >= last
            } else {
                pagerState.currentPage <= 0
            }
            if (atEdge) {
                android.util.Log.d("NovelTurn", "到底 delta=$delta current=${pagerState.currentPage} last=$last")
                return false
            }
            turnTarget[0] = target.coerceIn(0, last)
            turnTick++
            android.util.Log.d(
                "NovelTurn",
                "钳制 delta=$delta base=$base target=$target → ${turnTarget[0]}",
            )
            return true
        }

        LaunchedEffect(turnTick) {
            if (turnTick == 0) return@LaunchedEffect
            val target = turnTarget[0]
            if (target < 0) return@LaunchedEffect
            val distance = kotlin.math.abs(target - pagerState.currentPage)
            android.util.Log.d(
                "NovelTurn",
                "翻页 target=$target current=${pagerState.currentPage} distance=$distance",
            )
            if (distance > 1) {
                // 连点：**直接跳**，不走动画。
                // animateScrollToPage 被取消时 Pager 会回弹到最近的整页 —— 连点间隔
                // 比动画短，每次都在中途取消、弹回原页，等于一下都没翻。瞬跳没有这个问题。
                pagerState.scrollToPage(target)
            } else if (distance == 1) {
                pagerState.animateScrollToPage(
                    target,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                )
            }
            // 目标没被新点击改过就清空；改过就留给下一轮
            if (turnTarget[0] == target) turnTarget[0] = -1
        }

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir ->
                    // 分页还没算出来（pageCount = 0）时不切章
                    if (pagerState.pageCount <= 0) return@collect
                    if (dir > 0) {
                        if (!requestTurn(1)) onNextChapter()
                    } else {
                        if (!requestTurn(-1)) onReachFirst()
                    }
                }
            }
        }

        var lastChapterIndex by remember { mutableIntStateOf(chapterIndex) }
        LaunchedEffect(chapterIndex) {
            if (lastChapterIndex != chapterIndex) {
                lastChapterIndex = chapterIndex
                // 切章后页码重新算，旧目标作废（State 对象本身不动，见上面的说明）
                turnTarget[0] = -1
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

        // ===== 到边界继续滑 = 换章（v1.8.8.4）=====
        // Pager 滑到第一页 / 最后一页就停住，不会告诉调用方「用户还想往后翻」，
        // 于是「右滑到顶」永远停在第一章第一页。
        // 这里用 pointerInput 在**不消费事件**的前提下观察手指位移（requireUnconsumed
        // = false，Pager 照常翻页）：停在第一页还往「前」拖超过 15% 屏宽就回上一章，
        // 最后一页往「后」拖同理去下一章。横向模式看 X、纵向模式看 Y。
        // 不走 nestedScroll 接未消费滚动量 —— 新系统上到达边界后那部分会被
        // overscroll 拉伸效果吃掉，父级根本收不到。
        fun edgeSwapModifier(vertical: Boolean): Modifier =
            Modifier.pointerInput(pagerState, vertical) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var fired = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!fired) {
                            val last = pagerState.pageCount - 1
                            val delta = if (vertical) {
                                change.position.y - down.position.y
                            } else {
                                change.position.x - down.position.x
                            }
                            val extent = (if (vertical) size.height else size.width).toFloat()
                            val limit = extent * EDGE_SWAP_THRESHOLD
                            if (last >= 0) {
                                when {
                                    pagerState.currentPage == 0 && delta > limit -> {
                                        fired = true
                                        onReachFirst()
                                    }
                                    pagerState.currentPage == last && delta < -limit -> {
                                        fired = true
                                        onNextChapter()
                                    }
                                }
                            }
                        }
                        if (!change.pressed) break
                    }
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
                // 到边界继续滑换章（v1.8.8.4）：纵向翻页看 Y 位移
                .then(edgeSwapModifier(vertical = true))
                .pageTap(
                    // v1.8.8.4：以前这里是 `onRight = onNextChapter` —— 上下翻页模式下
                    // 点右侧直接跳下一章，一页都翻不动，连点更是每点一次换一章。
                    // 现在和其他模式一致：先翻页，翻到头才切章。
                    // pageCount = 0（分页还没算出来）时不切章，否则点一下直接跳下一章
                    onLeft = { if (pagerState.pageCount > 0 && !requestTurn(-1)) onReachFirst() },
                    onCenter = onTapCenter,
                    onRight = { if (pagerState.pageCount > 0 && !requestTurn(1)) onNextChapter() },
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

/**
 * 骨架屏里的一条占位条（v1.8.8.4）。
 *
 * 宽度按比例、圆角、透明度由外层的呼吸动画驱动 —— 加载时它在一片空白上
 * 「一起一伏」，比一个静止的转圈更像「内容正在长出来」。
 */
@Composable
private fun SkeletonBar(
    widthFraction: Float,
    height: Dp,
    alpha: Float,
    color: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction.coerceIn(0.05f, 1f))
            .height(height)
            .background(color.copy(alpha = alpha), RoundedCornerShape(4.dp)),
    )
}

/* ===================== 翻页动画 ===================== */

/**
 * 单页的翻页变换（v1.8.8.4 重做）。
 *
 * 关键修正：入参 [pageOffset] 必须是**这一页自己的**相对偏移
 * （`(currentPage - page) + currentPageOffsetFraction`），而不是全局的
 * `currentPageOffsetFraction`。以前所有页拿到的是同一个值，翻页时前后两页
 * 以相同幅度一起缩放、一起变淡 —— 视觉上就是「整块画面在抖」而不是
 * 「一页翻走、下一页进来」，这是翻页显得粗糙的根因。
 */
private data class PageTransform(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 1f,
    val rotationY: Float = 0f,
    val shadowElevation: Float = 0f,
    /** 变换原点：0 = 左边缘，0.5 = 中心，1 = 右边缘 */
    val originX: Float = 0.5f,
) {
    companion object {
        val NoAnim = PageTransform()
        val slide = PageTransform()

        /**
         * 覆盖：离开的页后退、变暗、压上阴影，进入的页从侧边盖上来。
         *
         * 缩放从原来的 12% 收到 10%，但把变化范围从「半页」改成「整页」
         * （[pageOffset] 现在是 -1..1 而不是 -0.5..0.5），实际观感更有层次。
         */
        fun cover(pageOffset: Float): PageTransform {
            val d = pageOffset.coerceIn(-1f, 1f)
            val leave = easeOutCubic(kotlin.math.abs(d))
            val scale = 1f - leave * 0.10f
            return PageTransform(
                scaleX = scale,
                scaleY = scale,
                alpha = 1f - leave * 0.30f,
                shadowElevation = leave * 18f,
            )
        }

        /**
         * 仿真：绕书脊做 3D 翻转。
         *
         * v1.8.8.4 二次调整：角度 45° → 22°。
         * 45° 时透视把页面宽度投影压到约 70%，翻页中间态会看到「左边一条空白、
         * 文字从中间开始」，观感是页面被挤扁、文字错位（用户反馈的「失真」）。
         * 22° 的投影宽度约 93%，几乎看不出变形，配合卷边仍然有翻书的层次。
         * 变换原点跟着方向走：翻走的页绕左边缘（书脊）转出，进来的页绕右边缘转进。
         */
        fun simulate(pageOffset: Float): PageTransform {
            val d = pageOffset.coerceIn(-1f, 1f)
            val leave = easeOutCubic(kotlin.math.abs(d))
            return PageTransform(
                rotationY = d * 22f,
                scaleX = 1f - leave * 0.03f,
                scaleY = 1f - leave * 0.02f,
                alpha = 1f - leave * 0.18f,
                shadowElevation = leave * 18f,
                // 翻走的页绕**左边缘**（书脊）转出，进来的页绕右边缘转进。
                // 录屏里发现原来写反了：翻页中间态是「左侧一条空白、文字从中间开始」，
                // 说明页面绕的是右边缘。交换之后纸张从书脊一侧翘起，符合直觉。
                originX = if (d >= 0f) 1f else 0f,
            )
        }

        /**
         * 缓出三次曲线：末段减速。
         *
         * 线性（原来的做法）在翻页收尾时是「突然停住」，加缓出后是「贴上去」，
         * 这是纸张和手指都会有的物理感。
         */
        private fun easeOutCubic(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            val inv = 1f - x
            return 1f - inv * inv * inv
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
