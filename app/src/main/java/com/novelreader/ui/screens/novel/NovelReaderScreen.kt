package com.novelreader.ui.screens.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
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
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt
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
    // v1.8.8.5：预缓存窗口（正文 + 标题），交给分页层提前排版，切章不再等排版。
    val cachedChapters by viewModel.cachedChapters.collectAsState()
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
                    prefetchedContent = cachedChapters,
                )
            }
            else -> {
                // v1.8.8.5：Canvas 三页翻页（参照 legadoT，整页手绘，绕开 Compose 子布局）
                CanvasPagerNovel(
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
                    bgColor = backgroundColor,
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
                    prefetchedContent = cachedChapters,
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
    /** 预缓存窗口（章 index → (标题, 正文)），用于提前排版（v1.8.8.5） */
    prefetchedContent: Map<Int, Pair<String, String>> = emptyMap(),
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

        // v1.8.8.5：排版结果按章缓存（整个阅读页生命周期内复用），配合预分页做到切章不白屏。
        val pageCache = remember { mutableMapOf<Int, List<String>>() }

        // v1.7.1.0：分页计算移出主线程（null = 还在算）
        val pagesOrNull = rememberPaginatedPagesCached(
            content = content,
            chapterIndex = chapterIndex,
            prefetched = prefetchedContent,
            cache = pageCache,
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
        // v1.8.8.5：去掉章末的「本章已读完」中转页。
        // 以前页数是 pages.size + 1，多出来那一页只写「本章已读完 / 继续翻页进入下一章」，
        // 用户翻到它就是多一次点击、多一次闪动，读书节奏被打断。
        // 现在页数就是正文页数：在最后一页再往后翻，requestTurn 直接返回 false →
        // 调用方切下一章，中间不再插一页。
        val pageCount = if (pagesReady) pages.size else 0

        val startPage = remember(chapterIndex, initialPage, initialRatio, pageCount) {
            when {
                initialPage > 0 -> initialPage
                initialRatio > 0 && pageCount > 1 -> (initialRatio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })

        // v1.7.1.0：分页是异步算出来的，Pager 创建时 pageCount 还是 0，`initialPage` 无从生效。
        // 结果就绪后补一次定位。
        //
        // v1.8.8.5 修：这个「首次」标志以前用 `remember { }`（不带 key），整个阅读页只生效一次。
        // 于是**切章之后再也不定位** —— 配合 chapterIndex effect 里的 scrollToPage(0)，
        // 结果是每次换章都停在第 0 页。往前翻章因此总落在上一章开头（用户报的严重问题）。
        // 现在把 chapterIndex 也作为 key，切章时会重新跑一遍定位（见下面 chapterIndex effect
        // 里对 didInitialPosition 的重置）。
        // v1.8.8.5 第三次修（关键）：换成「待恢复比例」机制，不再用 `didInitialPosition` 布尔标志。
        //
        // 布尔标志有个致命的时序漏洞：预分页缓存命中时，切章后 pagesOrNull 从**旧值**
        // 直接变成**新值**（中间不经过 null）。定位 effect 重启那一刻标志还是 true → 直接 return；
        // 紧接着切章 effect 才把标志清成 false，但此后 pagesOrNull 不再变化、effect 不会重启，
        // 于是**永远不定位** —— 表现就是用户看到的「往回翻仍停在上一章开头」。
        //
        // 现在：切章时登记「待恢复比例」，页数一就绪就把比例换算成页并消费掉（置 -1）。
        // 不依赖两个 effect 的先后顺序。
        var pendingRatio by remember { mutableIntStateOf(initialRatio.coerceIn(0, 10000)) }
        LaunchedEffect(pagesOrNull, pageCount, pendingRatio) {
            if (pagesOrNull == null || pageCount <= 0) return@LaunchedEffect
            val ratio = pendingRatio
            if (ratio < 0) return@LaunchedEffect
            pendingRatio = -1
            val target = when {
                initialPage > 0 -> initialPage
                ratio > 0 -> (ratio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, pageCount - 1)
            // 排查用：往回翻若仍落在开头，看这行是否出现、target 是否为 pageCount-1
            android.util.Log.d(
                "NovelRestore",
                "定位 chapter=$chapterIndex ratio=$ratio pageCount=$pageCount target=$target from=${pagerState.currentPage}",
            )
            // 无条件对齐（不再只在 target > 0 时跳）：切章后 Pager 可能还停在上一个
            // 阅读位置，新章只有 1~2 页时那里是越界的，必须拉回 0。
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
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
        // v1.8.8.5：改成「点击回调里直接发滚动指令」，不再经过 turnTick + LaunchedEffect。
        // 原来那次中转要额外等一个重组周期才发出指令，连点时指令还会排队，
        // 手上的感觉就是「点了不动 / 动一下停一下」。目标页仍然在 [requestTurn] 里
        // **同步**累加（保证连点不丢），只是把「发指令」提前到同一帧。
        val turnScope = rememberCoroutineScope()
        val turnTarget = remember { intArrayOf(-1) }
        var lastTurnAt by remember { mutableLongStateOf(0L) }

        /** 发出翻页；[target] 由 requestTurn 同步算好 */
        fun dispatchTurn(target: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            val rapid = now - lastTurnAt < 300L
            lastTurnAt = now
            turnScope.launch {
                if (rapid) {
                    // 连点：直接跳。animateScrollToPage 中途被取消会回弹到最近整页，
                    // 而连点间隔比动画短 → 每一下都在中途被取消、原地弹回，等于一下没翻。
                    pagerState.scrollToPage(target)
                } else {
                    pagerState.animateScrollToPage(
                        target,
                        // 翻页 200ms：参考实现的位移动画同样很短，够看出「翻过去了」，
                        // 又不会让连续点击排队等上一下。
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    )
                }
                // 落定后以实际页为准（响应中途可能被拖动或回弹改变）
                if (turnTarget[0] == target) turnTarget[0] = pagerState.currentPage
            }
        }

        /** 请求翻 +delta 页；false = 真的到头了，调用方该切章 / 回上一章 */
        fun requestTurn(delta: Int): Boolean {
            val last = pagerState.pageCount - 1
            if (last < 0) return false
            val base = if (turnTarget[0] in 0..last) turnTarget[0] else pagerState.currentPage
            val target = base + delta
            if (target in 0..last) {
                turnTarget[0] = target
                dispatchTurn(target)
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
            if (atEdge) return false
            turnTarget[0] = target.coerceIn(0, last)
            dispatchTurn(turnTarget[0])
            return true
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
        var lastInitialRatio by remember { mutableIntStateOf(initialRatio) }
        LaunchedEffect(chapterIndex, initialRatio) {
            if (lastChapterIndex != chapterIndex || lastInitialRatio != initialRatio) {
                lastChapterIndex = chapterIndex
                lastInitialRatio = initialRatio
                // 切章后页码重新算，旧目标作废（State 对象本身不动，见上面的说明）
                turnTarget[0] = -1
                // v1.8.8.5：登记本章要恢复的位置。往前翻时 ViewModel 给的是 10000（100%），
                // 由定位 effect 换算成「最后一页」；往后翻 / 点目录进来是 0，即第一页。
                pendingRatio = initialRatio.coerceIn(0, 10000)
                onPageChanged(0)
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

        // v1.8.8.5：翻页手感 —— 参照成熟实现（xrn1997/android-ebook 的 ReaderPager）。
        // 它的翻页成功阈值只有 **30dp**（不是半页），一次轻扫就翻；松手吸附动画也很短。
        // 依据屏宽换算成比例：1080px 屏宽上 30dp ≈ 90px ≈ 0.083。
        val pagerFling = PagerDefaults.flingBehavior(
            state = pagerState,
            pagerSnapDistance = PagerSnapDistance.atMost(1),
            snapPositionalThreshold = 0.08f,
            snapAnimationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        )

        HorizontalPager(
            state = pagerState,
            flingBehavior = pagerFling,
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
                        // 3D 翻转要绕边缘转才有「翻书」感，绕中心转是扑克牌翻面。
                        // v1.8.8.5：纵向支点也交给变换（仿真模式的翻走页要绕底边转）。
                        transformOrigin = TransformOrigin(transform.originX, transform.originY)
                        // v1.8.8.4：alpha < 1 时 Compose 默认把整页先渲到离屏 buffer 再合成，
                        // 而阅读页是整屏文字 —— 每帧一次全屏离屏合成，正是快速翻页掉帧的来源。
                        // ModulateAlpha 直接把 alpha 乘到绘制上，不建离屏层。
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                        alpha = transform.alpha
                        rotationY = transform.rotationY
                        // v1.8.8.5：平面倾角 —— 仿真翻页「边线斜着掀起」靠它
                        rotationZ = transform.rotationZ
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

/* ===================== 左右翻页：三页窗口（v1.8.8.5） ===================== */

/**
 * 左右翻页的**三页窗口**实现（参照 xrn1997/android-ebook 的 `ReaderPager`）。
 *
 * 为什么换掉 `HorizontalPager`：
 * 1. Pager 的位移来自 `currentPageOffsetFraction`（快照状态），动画期间会触发重组；
 *    这里位移是一个 `Animatable<Float>`，在 `offset {}` 里**布局期**读取 —— 翻页过程零重组，
 *    只重定位三个图层，这是「跟手不黏」的关键。
 * 2. Pager 会把邻近页一起组合、还要跑 fling/snap 判定；这里只有三个槽位、一条位移。
 * 3. 阈值按参考实现的 **30dp**（不是半页），轻扫就翻。
 *
 * 层级（后画的在上）：下一页（底）→ 当前页 → 上一页（顶），
 * 与参考项目的 FrameLayout addView 顺序一致：向后翻时当前页左移露出下层；
 * 向前翻时上一页从左侧滑入盖住当前页。
 *
 * 连点：位移动画进行中再点，**直接跳页**（不等动画）。Pager 的动画被取消会回弹到最近整页，
 * 连点间隔比动画短时每一下都被取消、原地弹回 —— 那正是「点了没翻」的来源。
 */
@Composable
private fun HorizontalWindowNovel(
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
    /** 阅读背景色：**每个页面槽位必须铺满它**，否则下面那页的文字会透上来叠印 */
    bgColor: Color,
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
    prefetchedContent: Map<Int, Pair<String, String>> = emptyMap(),
) {
    BoxWithConstraintsCompat { maxWidthDp, maxHeightDp ->
        val density = LocalDensity.current
        val densityDensity = density.density
        val availWidthPx = (maxWidthDp - sidePadding * 2f).coerceAtLeast(1f) * densityDensity
        val fontPx = fontSize * densityDensity *
            androidx.compose.ui.platform.LocalDensity.current.fontScale
        val textHeightPx = ((maxHeightDp - 96f).coerceAtLeast(1f) * densityDensity -
            fontPx * 1.8f).coerceAtLeast(1f)
        val paraSpacingPx = paragraphSpacing * densityDensity
        val titleSpacerPx = 16f * densityDensity

        val pageCache = remember { mutableMapOf<Int, List<String>>() }
        val pagesOrNull = rememberPaginatedPagesCached(
            content = content,
            chapterIndex = chapterIndex,
            prefetched = prefetchedContent,
            cache = pageCache,
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
        val pageCount = if (pagesReady) pages.size else 0

        val widthPx = maxWidthDp * densityDensity
        val scope = rememberCoroutineScope()
        // v1.8.8.5：drag 绑到 chapterIndex —— 切章时重建、初值必为 0。
        // 不绑的话，切章那一瞬若正处在翻页动画中途，drag 会停在 ±width，
        // 新章的当前页被 offset 推到屏幕外 → 正文整片空白（只有标题）。
        val drag = remember(chapterIndex) { Animatable(0f) }
        var moving by remember { mutableStateOf(false) }
        var cur by remember { mutableIntStateOf(0) }
        val turnThresholdPx = with(density) { 30.dp.toPx() }
        val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop

        // 定位：切章 / 分页就绪 / 书签跳转都走这里。
        // `initialRatio` 由 ViewModel 给：往前翻章是 10000（→ 最后一页），其余是 0（→ 第一页）。
        LaunchedEffect(chapterIndex, initialPage, initialRatio, pageCount) {
            if (pageCount <= 0) return@LaunchedEffect
            val target = when {
                initialPage > 0 -> initialPage
                initialRatio > 0 -> (initialRatio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, pageCount - 1)
            cur = target
            drag.snapTo(0f)
            moving = false
            android.util.Log.d(
                "NovelRestore",
                "三页窗口定位 chapter=$chapterIndex ratio=$initialRatio pageCount=$pageCount target=$target",
            )
        }

        // 进度 / 页码：cur 落定后回调（等价于原来 pagerState 的监听）
        LaunchedEffect(cur, pageCount) {
            if (pageCount <= 0) return@LaunchedEffect
            onPageChanged(cur)
            onPageTextChanged("${cur + 1}/$pageCount")
            onRatioChanged(if (pageCount <= 1) 0 else cur * 10000 / (pageCount - 1))
        }

        /** 翻一页；越界交给调用方切章 */
        fun turn(delta: Int) {
            if (pageCount <= 0) return
            val target = cur + delta
            if (target < 0) {
                onReachFirst()
                return
            }
            if (target > pageCount - 1) {
                onNextChapter()
                return
            }
            if (moving) {
                // 连点：直接跳，避免每一下都被上一段动画取消后回弹
                cur = target
                scope.launch { drag.snapTo(0f) }
                return
            }
            scope.launch {
                moving = true
                drag.animateTo(
                    if (delta > 0) -widthPx else widthPx,
                    tween(durationMillis = 200, easing = FastOutSlowInEasing),
                )
                cur = target
                drag.snapTo(0f)
                moving = false
            }
        }

        LaunchedEffect(autoPage, autoPageInterval, chapterIndex) {
            if (!autoPage) return@LaunchedEffect
            while (true) {
                delay(autoPageInterval * 1000L)
                turn(1)
            }
        }

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir -> turn(if (dir > 0) 1 else -1) }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageCount, chapterIndex, cur) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        var lastX = startX
                        var pointerX = startX
                        var moved = false
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                            pointerX = change.position.x
                            val delta = pointerX - lastX
                            lastX = pointerX
                            if (!moved && kotlin.math.abs(pointerX - startX) > touchSlop) {
                                moved = true
                            }
                            if (moved) {
                                change.consume()
                                val hasPrev = cur > 0
                                val hasNext = cur < pageCount - 1
                                // 边界方向不跟手：没有上一页时右滑位移恒为 0
                                val clamped = (drag.value + delta).coerceIn(
                                    if (hasNext) -widthPx else 0f,
                                    if (hasPrev) widthPx else 0f,
                                )
                                scope.launch { drag.snapTo(clamped) }
                            }
                        }
                        if (moving) return@awaitEachGesture
                        val totalDx = pointerX - startX
                        val hasPrev = cur > 0
                        val hasNext = cur < pageCount - 1
                        if (!moved) {
                            // 点击：左右三分区翻页，中间唤菜单
                            val w = size.width.toFloat()
                            when {
                                startX < w / 3f -> turn(-1)
                                startX > w * 2f / 3f -> turn(1)
                                else -> onTapCenter()
                            }
                        } else {
                            val success = kotlin.math.abs(totalDx) > turnThresholdPx
                            val toPrev = totalDx > 0 && hasPrev
                            val toNext = totalDx < 0 && hasNext
                            scope.launch {
                                moving = true
                                when {
                                    toPrev -> {
                                        drag.animateTo(
                                            if (success) widthPx else 0f,
                                            tween(if (success) 200 else 140),
                                        )
                                        if (success) cur = (cur - 1).coerceAtLeast(0)
                                    }
                                    toNext -> {
                                        drag.animateTo(
                                            if (success) -widthPx else 0f,
                                            tween(if (success) 200 else 140),
                                        )
                                        if (success) cur = (cur + 1).coerceAtMost(pageCount - 1)
                                    }
                                    else -> drag.animateTo(0f, tween(140))
                                }
                                drag.snapTo(0f)
                                moving = false
                            }
                            // 已经没有上一/下一页还继续往外拖 → 切章
                            if (success) {
                                if (!hasPrev && totalDx > 0) onReachFirst()
                                else if (!hasNext && totalDx < 0) onNextChapter()
                            }
                        }
                    }
                },
        ) {
            // 下一页：最底层（向后翻时当前页左移露出它）
            if (cur + 1 < pageCount) {
                WindowPageSlot(
                    pages = pages,
                    pageIndex = cur + 1,
                    offsetX = { 0f },
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
                    bgColor = bgColor,
                )
            }
            // 当前页：向后翻时随手势左移
            WindowPageSlot(
                pages = pages,
                pageIndex = cur,
                offsetX = { kotlin.math.min(drag.value, 0f) },
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
                    bgColor = bgColor,
            )
            // 上一页：最顶层，向前翻时从左侧滑入
            if (cur - 1 >= 0) {
                WindowPageSlot(
                    pages = pages,
                    pageIndex = cur - 1,
                    offsetX = { -widthPx + kotlin.math.max(drag.value, 0f) },
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
                    bgColor = bgColor,
                )
            }
        }

        // v1.8.8.5：pageCount = 0 也要显示加载态。
        // 以前只判 `!pagesReady`（pagesOrNull == null），而「空排版」不是 null ——
        // 于是既不画内容也不转圈，整页空白，用户只看到一条标题。
        if (!pagesReady || pageCount == 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.width(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** 三页窗口里的单个页面槽位：位移在 `offset {}` 里读 `drag`，动画期间只重布局、不重组 */
@Composable
private fun WindowPageSlot(
    pages: List<String>,
    pageIndex: Int,
    offsetX: () -> Float,
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
    bgColor: Color,
) {
    // v1.8.8.5 修（白屏真因）：**不能用提前 return**。
    // 首帧 pages 还是空的 → 直接 return，这一轮没有创建任何节点；
    // 等分页就绪再进来时，Compose 的组结构对不上（上一次这里什么都没有），
    // 结果是「Box 的背景画出来了、里面一个字都没有」。改成条件包裹就不会有这种重组错位。
    if (pageIndex in pages.indices) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 每个槽位必须铺不透明背景：三页窗口里「下一页」固定画在 (0,0) 被当前页盖住，
                // 当前页若没有背景，两页正文就会在同一位置叠印。
                .background(bgColor)
                // v1.8.8.5：位移改走 graphicsLayer.translationX（**绘制阶段**），
                // 不用 Modifier.offset（布局阶段）。两者都是「动画期间零重组」，
                // 但绘制阶段读写状态不会参与布局测量，翻页时少一次布局传递，也更不容易掉帧。
                .graphicsLayer { translationX = offsetX() },
        ) {
            PageContent(
                page = pageIndex,
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

/* ===================== Canvas 三页翻页（参照 legadoT） ===================== */

/**
 * 左右翻页：**整页 Canvas 手绘**（参照 skybbk1001/legadoT 的 PageView + PageDelegate 体系）。
 *
 * 为什么改成 Canvas 画而不是 Compose 子节点：
 * 1. legado 的每页内容是画到 Canvas / Bitmap 上的，翻页只做 `translate`，没有任何子 View 的
 *    测量/布局参与 —— 这是它「跟手、不掉帧」的根本；
 * 2. 上一版用 Compose 三页槽位时，真机上出现过「外层背景能画、内部 Column 一个字都不画」
 *    的问题（排查了提前 return、offset、颜色、字体参数都没找到），换 Canvas 直接绕开这条路；
 * 3. 文本排版结果（[TextLayoutResult]）按页缓存，翻页时只换 `translate` 偏移，零重新测量。
 *
 * 交互对齐 legado：翻页阈值 30dp、越界继续拖 = 切章、连点直接跳页、点击三分区。
 */
@Composable
private fun CanvasPagerNovel(
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
    bgColor: Color,
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
    prefetchedContent: Map<Int, Pair<String, String>> = emptyMap(),
) {
    BoxWithConstraintsCompat { maxWidthDp, maxHeightDp ->
        val density = LocalDensity.current
        val d = density.density
        val availWidthPx = (maxWidthDp - sidePadding * 2f).coerceAtLeast(1f) * d
        val fontPx = fontSize * d * androidx.compose.ui.platform.LocalDensity.current.fontScale
        val textHeightPx = ((maxHeightDp - 96f).coerceAtLeast(1f) * d - fontPx * 1.8f)
            .coerceAtLeast(1f)
        val paraSpacingPx = paragraphSpacing * d
        val titleSpacerPx = 16f * d

        val pageCache = remember { mutableMapOf<Int, List<String>>() }
        val pagesOrNull = rememberPaginatedPagesCached(
            content = content,
            chapterIndex = chapterIndex,
            prefetched = prefetchedContent,
            cache = pageCache,
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
        val pageCount = if (pagesOrNull != null) pages.size else 0

        val widthPx = maxWidthDp * d
        val scope = rememberCoroutineScope()
        // v1.8.8.5：位移改成**普通 Float state + 同步赋值**。
        // 之前用 Animatable 时，每个 MOVE 事件都 `scope.launch { drag.snapTo(...) }` ——
        // 触摸事件一秒几十个，协程并发写同一个值、顺序不保证，最后写进去的可能是较早那次，
        // 表现就是「拖到一半卡住 / 往前滑不动 / 停在两页中间」。
        // 现在拖动直接赋值（同步、有序），只有松手收尾才用一次协程做动画。
        var dragX by remember(chapterIndex) {
            androidx.compose.runtime.mutableFloatStateOf(0f)
        }
        var animJob by remember {
            androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null)
        }
        var moving by remember { mutableStateOf(false) }
        var cur by remember { mutableIntStateOf(0) }
        val turnThresholdPx = with(density) { 30.dp.toPx() }
        val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop

        /** 单一动画入口：先取消上一个动画任务，避免并发写 dragX */
        fun startAnim(target: Float, durationMs: Int, onEnd: (() -> Unit)? = null) {
            animJob?.cancel()
            animJob = scope.launch {
                moving = true
                val start = dragX
                androidx.compose.animation.core.animate(
                    initialValue = start,
                    targetValue = target,
                    animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                ) { value, _ -> dragX = value }
                dragX = target
                onEnd?.invoke()
                moving = false
            }
        }

        LaunchedEffect(chapterIndex, initialPage, initialRatio, pageCount) {
            if (pageCount <= 0) return@LaunchedEffect
            val target = when {
                initialPage > 0 -> initialPage
                initialRatio > 0 -> (initialRatio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, pageCount - 1)
            animJob?.cancel()
            cur = target
            dragX = 0f
            moving = false
        }

        LaunchedEffect(cur, pageCount) {
            if (pageCount <= 0) return@LaunchedEffect
            onPageChanged(cur)
            onPageTextChanged("${cur + 1}/$pageCount")
            onRatioChanged(if (pageCount <= 1) 0 else cur * 10000 / (pageCount - 1))
        }

        // ===== 文本排版：只排「可见的三页」，按页缓存 =====
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val style = androidx.compose.ui.text.TextStyle(
            color = textColor,
            fontSize = fontSize.sp,
            fontFamily = fontFamily,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            lineHeight = (fontSize * lineHeightMultiplier).sp,
            letterSpacing = letterSpacing.sp,
        )
        val textConstraints = androidx.compose.ui.unit.Constraints(
            maxWidth = availWidthPx.toInt().coerceAtLeast(1),
        )
        val layouts = remember(cur, pages, style, textConstraints, paragraphSpacing, indent) {
            val m = HashMap<Int, androidx.compose.ui.text.TextLayoutResult>(3)
            for (i in (cur - 1)..(cur + 1)) {
                if (i in pages.indices) {
                    m[i] = measurer.measure(
                        text = buildReaderText(pages[i], fontSize, paragraphSpacing, indent),
                        style = style,
                        constraints = textConstraints,
                    )
                }
            }
            m
        }
        // 章名单独排一次（只画在第 0 页）
        val titleLayout = remember(chapterTitle, style, textConstraints, fontSize) {
            if (chapterTitle.isBlank()) null
            else measurer.measure(
                text = chapterTitle,
                style = style.copy(
                    fontSize = (fontSize + 2).sp,
                    fontWeight = FontWeight.Bold,
                ),
                constraints = textConstraints,
            )
        }

        fun turn(delta: Int) {
            if (pageCount <= 0) return
            val target = cur + delta
            if (target < 0) {
                onReachFirst()
                return
            }
            if (target > pageCount - 1) {
                onNextChapter()
                return
            }
            if (moving) {
                // 连点：取消进行中的动画，直接落页
                animJob?.cancel()
                cur = target
                dragX = 0f
                moving = false
                return
            }
            startAnim(
                target = if (delta > 0) -widthPx else widthPx,
                durationMs = 200,
                onEnd = {
                    cur = target
                    dragX = 0f
                },
            )
        }

        LaunchedEffect(autoPage, autoPageInterval, chapterIndex) {
            if (!autoPage) return@LaunchedEffect
            while (true) {
                delay(autoPageInterval * 1000L)
                turn(1)
            }
        }

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir -> turn(if (dir > 0) 1 else -1) }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageCount, chapterIndex, cur) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 手指按下先打断上一次收尾动画，否则它会继续写 dragX
                        animJob?.cancel()
                        moving = false
                        val startX = down.position.x
                        var lastX = startX
                        var pointerX = startX
                        var moved = false
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                            pointerX = change.position.x
                            val delta = pointerX - lastX
                            lastX = pointerX
                            if (!moved && kotlin.math.abs(pointerX - startX) > touchSlop) {
                                moved = true
                            }
                            if (moved) {
                                change.consume()
                                val hasPrev = cur > 0
                                val hasNext = cur < pageCount - 1
                                // 同步赋值：触摸事件是串行的，不需要协程，也不会乱序
                                dragX = (dragX + delta).coerceIn(
                                    if (hasNext) -widthPx else 0f,
                                    if (hasPrev) widthPx else 0f,
                                )
                            }
                        }
                        val totalDx = pointerX - startX
                        val hasPrev = cur > 0
                        val hasNext = cur < pageCount - 1
                        if (!moved) {
                            val w = size.width.toFloat()
                            when {
                                startX < w / 3f -> turn(-1)
                                startX > w * 2f / 3f -> turn(1)
                                else -> onTapCenter()
                            }
                        } else {
                            val success = kotlin.math.abs(totalDx) > turnThresholdPx
                            val toPrev = totalDx > 0 && hasPrev
                            val toNext = totalDx < 0 && hasNext
                            val animTarget = when {
                                toPrev -> if (success) widthPx else 0f
                                toNext -> if (success) -widthPx else 0f
                                else -> 0f
                            }
                            startAnim(
                                target = animTarget,
                                durationMs = if (success) 200 else 140,
                                onEnd = {
                                    if (success && toPrev) cur = (cur - 1).coerceAtLeast(0)
                                    if (success && toNext) cur = (cur + 1).coerceAtMost(pageCount - 1)
                                    dragX = 0f
                                },
                            )
                            if (success) {
                                if (!hasPrev && totalDx > 0) onReachFirst()
                                else if (!hasNext && totalDx < 0) onNextChapter()
                            }
                        }
                    }
                },
        ) {
            val dx = dragX
            val padPx = sidePadding * d
            val topPx = 48f * d

            /** 画一页：背景 + （第 0 页的章名）+ 正文 + 页码 */
            fun drawPage(idx: Int, offsetX: Float) {
                if (idx !in pages.indices) return
                translate(offsetX, 0f) {
                    drawRect(color = bgColor)
                    // 正文严格裁到「分页时用的可用高度」内。
                    // TextLayoutResult 的实际高度会比分页测量多出零点几行（行高取整累积），
                    // 不按分页高度裁，最后一行就会压到页码上、看着像多了一行乱码。
                    clipRect(0f, 0f, size.width, topPx + textHeightPx) {
                        var y = topPx
                        if (idx == 0 && !hideChapterTitle && titleLayout != null) {
                            drawText(titleLayout, topLeft = Offset(padPx, y))
                            y += titleLayout.size.height + titleSpacerPx
                        }
                        layouts[idx]?.let { layout ->
                            drawText(layout, topLeft = Offset(padPx, y))
                        }
                    }
                    // 页码：画在正文区下方、底部留白里，始终可见
                    // 页码：注意这里必须写 ${pages.size}（带花括号）。
                    // 写成 `$pages.size` 时 Kotlin 只把 `$pages` 当变量，尾巴 `.size` 变字面量，
                    // 结果画出来是「整个列表的 toString() + .size」那一长串乱码。
                    val pageText = "${idx + 1}/${pages.size}"
                    val pageLayout = measurer.measure(
                        text = pageText,
                        style = style.copy(fontSize = (fontSize - 6).sp),
                    )
                    drawText(
                        pageLayout,
                        topLeft = Offset(
                            (widthPx - pageLayout.size.width) / 2f,
                            topPx + textHeightPx + 6f * d,
                        ),
                        alpha = 0.55f,
                    )
                }
            }

            // 层级（后画在上）：下一页 → 当前页 → 上一页
            if (cur + 1 < pageCount) drawPage(cur + 1, 0f)
            drawPage(cur, kotlin.math.min(dx, 0f))
            if (cur - 1 >= 0) drawPage(cur - 1, -widthPx + kotlin.math.max(dx, 0f))
        }

        if (pageCount == 0) {
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
    /** 预缓存窗口（章 index → (标题, 正文)），用于提前排版（v1.8.8.5） */
    prefetchedContent: Map<Int, Pair<String, String>> = emptyMap(),
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

        // v1.8.8.5：排版结果按章缓存（整个阅读页生命周期内复用），配合预分页做到切章不白屏。
        val pageCache = remember { mutableMapOf<Int, List<String>>() }

        // v1.7.1.0：分页计算移出主线程（null = 还在算）
        val pagesOrNull = rememberPaginatedPagesCached(
            content = content,
            chapterIndex = chapterIndex,
            prefetched = prefetchedContent,
            cache = pageCache,
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
        // v1.8.8.5：去掉章末的「本章已读完」中转页。
        // 以前页数是 pages.size + 1，多出来那一页只写「本章已读完 / 继续翻页进入下一章」，
        // 用户翻到它就是多一次点击、多一次闪动，读书节奏被打断。
        // 现在页数就是正文页数：在最后一页再往后翻，requestTurn 直接返回 false →
        // 调用方切下一章，中间不再插一页。
        val pageCount = if (pagesReady) pages.size else 0

        val startPage = remember(chapterIndex, initialPage, initialRatio, pageCount) {
            when {
                initialPage > 0 -> initialPage
                initialRatio > 0 && pageCount > 1 -> (initialRatio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })

        // v1.7.1.0：分页是异步算出来的，Pager 创建时 pageCount 还是 0，`initialPage` 无从生效。
        // 结果就绪后补一次定位。
        //
        // v1.8.8.5 修：这个「首次」标志以前用 `remember { }`（不带 key），整个阅读页只生效一次。
        // 于是**切章之后再也不定位** —— 配合 chapterIndex effect 里的 scrollToPage(0)，
        // 结果是每次换章都停在第 0 页。往前翻章因此总落在上一章开头（用户报的严重问题）。
        // 现在把 chapterIndex 也作为 key，切章时会重新跑一遍定位（见下面 chapterIndex effect
        // 里对 didInitialPosition 的重置）。
        // v1.8.8.5 第三次修（关键）：换成「待恢复比例」机制，不再用 `didInitialPosition` 布尔标志。
        //
        // 布尔标志有个致命的时序漏洞：预分页缓存命中时，切章后 pagesOrNull 从**旧值**
        // 直接变成**新值**（中间不经过 null）。定位 effect 重启那一刻标志还是 true → 直接 return；
        // 紧接着切章 effect 才把标志清成 false，但此后 pagesOrNull 不再变化、effect 不会重启，
        // 于是**永远不定位** —— 表现就是用户看到的「往回翻仍停在上一章开头」。
        //
        // 现在：切章时登记「待恢复比例」，页数一就绪就把比例换算成页并消费掉（置 -1）。
        // 不依赖两个 effect 的先后顺序。
        var pendingRatio by remember { mutableIntStateOf(initialRatio.coerceIn(0, 10000)) }
        LaunchedEffect(pagesOrNull, pageCount, pendingRatio) {
            if (pagesOrNull == null || pageCount <= 0) return@LaunchedEffect
            val ratio = pendingRatio
            if (ratio < 0) return@LaunchedEffect
            pendingRatio = -1
            val target = when {
                initialPage > 0 -> initialPage
                ratio > 0 -> (ratio / 10000f * (pageCount - 1)).toInt()
                else -> 0
            }.coerceIn(0, pageCount - 1)
            // 排查用：往回翻若仍落在开头，看这行是否出现、target 是否为 pageCount-1
            android.util.Log.d(
                "NovelRestore",
                "定位 chapter=$chapterIndex ratio=$ratio pageCount=$pageCount target=$target from=${pagerState.currentPage}",
            )
            // 无条件对齐（不再只在 target > 0 时跳）：切章后 Pager 可能还停在上一个
            // 阅读位置，新章只有 1~2 页时那里是越界的，必须拉回 0。
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
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
        // v1.8.8.5：改成「点击回调里直接发滚动指令」，不再经过 turnTick + LaunchedEffect。
        // 原来那次中转要额外等一个重组周期才发出指令，连点时指令还会排队，
        // 手上的感觉就是「点了不动 / 动一下停一下」。目标页仍然在 [requestTurn] 里
        // **同步**累加（保证连点不丢），只是把「发指令」提前到同一帧。
        val turnScope = rememberCoroutineScope()
        val turnTarget = remember { intArrayOf(-1) }
        var lastTurnAt by remember { mutableLongStateOf(0L) }

        /** 发出翻页；[target] 由 requestTurn 同步算好 */
        fun dispatchTurn(target: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            val rapid = now - lastTurnAt < 300L
            lastTurnAt = now
            turnScope.launch {
                if (rapid) {
                    // 连点：直接跳。animateScrollToPage 中途被取消会回弹到最近整页，
                    // 而连点间隔比动画短 → 每一下都在中途被取消、原地弹回，等于一下没翻。
                    pagerState.scrollToPage(target)
                } else {
                    pagerState.animateScrollToPage(
                        target,
                        // 翻页 200ms：参考实现的位移动画同样很短，够看出「翻过去了」，
                        // 又不会让连续点击排队等上一下。
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    )
                }
                // 落定后以实际页为准（响应中途可能被拖动或回弹改变）
                if (turnTarget[0] == target) turnTarget[0] = pagerState.currentPage
            }
        }

        /** 请求翻 +delta 页；false = 真的到头了，调用方该切章 / 回上一章 */
        fun requestTurn(delta: Int): Boolean {
            val last = pagerState.pageCount - 1
            if (last < 0) return false
            val base = if (turnTarget[0] in 0..last) turnTarget[0] else pagerState.currentPage
            val target = base + delta
            if (target in 0..last) {
                turnTarget[0] = target
                dispatchTurn(target)
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
            if (atEdge) return false
            turnTarget[0] = target.coerceIn(0, last)
            dispatchTurn(turnTarget[0])
            return true
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
        var lastInitialRatio by remember { mutableIntStateOf(initialRatio) }
        LaunchedEffect(chapterIndex, initialRatio) {
            if (lastChapterIndex != chapterIndex || lastInitialRatio != initialRatio) {
                lastChapterIndex = chapterIndex
                lastInitialRatio = initialRatio
                // 切章后页码重新算，旧目标作废（State 对象本身不动，见上面的说明）
                turnTarget[0] = -1
                // v1.8.8.5：登记本章要恢复的位置。往前翻时 ViewModel 给的是 10000（100%），
                // 由定位 effect 换算成「最后一页」；往后翻 / 点目录进来是 0，即第一页。
                pendingRatio = initialRatio.coerceIn(0, 10000)
                onPageChanged(0)
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
    /**
     * 平面旋转（v1.8.8.5）：仿真翻页靠它做出「纸页斜着掀起」的观感。
     * 纯 rotationY 是绕竖轴的透视翻转，页面始终是正矩形，看起来像卡片在转；
     * 加一个平面倾角，边线才是斜的、文字才是斜的 —— 这才像一张纸被掀起来。
     */
    val rotationZ: Float = 0f,
    val shadowElevation: Float = 0f,
    /** 变换原点：0 = 左边缘，0.5 = 中心，1 = 右边缘 */
    val originX: Float = 0.5f,
    /** 变换原点纵向：仿真模式下固定在底边（1），让纸页「从角上翘起」 */
    val originY: Float = 0.5f,
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
         * 仿真：v1.8.8.5 按参考实现（xrn1997/android-ebook 的 ReaderPager）改为**纯平移**。
         *
         * 那个项目翻页只有三个槽位（上一页 / 当前页 / 下一页）+ 一个 `Animatable` 位移，
         * 位移在布局期读取（`offset {}`），动画期间零重组、零额外绘制 —— 这是它「翻得飞快」的原因。
         *
         * 我们之前在这里叠了 3D 旋转 + 全屏渐变阴影 + 贝塞尔卷边，每帧要额外做两次全屏
         * 渐变填充和一次离屏合成，快速连翻时直接掉帧。既然目标是「响应和流畅」，
         * 就把这些全部去掉，只做平移。
         *
         * 注意：`PageTransform` 的字段仍保留（覆盖模式在用），这里只是不再设置它们。
         */
        fun simulate(pageOffset: Float): PageTransform = PageTransform()

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

/**
 * 带**预分页**的排版结果（v1.8.8.5）。
 *
 * 为什么要有这个：`rememberPaginatedPages` 每次切章都从头排一遍，排版期间调用方只显示
 * 一个进度圈（pageCount = 0，Pager 不渲染任何页）——用户体感是「切章白屏」。
 * 排版本身要用 TextMeasurer 逐段测量，长章节要几百毫秒，这段时间没法省。
 *
 * 解法是**提前算**：ViewModel 的预缓存窗口（档位可调，见设置「预加载章节」）已经抓好了
 * 后面几章的正文，这里拿同一份窗口在后台按当前排版参数提前排好，结果放进 [cache]。
 * 用户真的翻到那一章时，同一份缓存直接命中，`pagesOrNull` 一次就绪，没有白屏。
 *
 * 缓存失效：所有影响断行的参数（宽高 / 字号 / 行高 / 段距 / 字体 / 加粗 / 字距 / 缩进 /
 * 是否显示章名）合成一个指纹 [paramSig]，任一变化就把整份缓存清空重算——否则改字号后
 * 会用旧断行渲染新字号，末行会被裁掉。
 */
@Composable
private fun rememberPaginatedPagesCached(
    content: String,
    chapterIndex: Int,
    prefetched: Map<Int, Pair<String, String>>,
    cache: MutableMap<Int, List<String>>,
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
    val paragraphSpacingDp = paragraphSpacingPx / density.density
    val render: (String) -> AnnotatedString = { buildReaderText(it, fontSize, paragraphSpacingDp, indent) }
    val showTitle = !hideChapterTitle

    val paramSig = remember(
        widthPx, heightPx, fontSize, lineHeightMultiplier, paragraphSpacingPx,
        titleSpacerPx, fontFamily, bold, letterSpacing, indent, hideChapterTitle,
    ) {
        listOf(
            widthPx, heightPx, fontSize, lineHeightMultiplier, paragraphSpacingPx,
            titleSpacerPx, fontFamily, bold, letterSpacing, indent, hideChapterTitle,
        )
    }

    // 排版参数变了 → 旧缓存全部失效
    LaunchedEffect(paramSig) { cache.clear() }

    /** 单章排版：每次现造 TextMeasurer（它的内部布局缓存跨线程共享不安全） */
    suspend fun measure(text: String, title: String): List<String> =
        withContext(Dispatchers.Default) {
            val result = runCatching {
                paginateByMeasurement(
                    measurer = androidx.compose.ui.text.TextMeasurer(resolver, density, layoutDirection),
                    content = text,
                    chapterTitle = title,
                    showTitle = showTitle,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    style = style,
                    paragraphSpacingPx = paragraphSpacingPx,
                    render = render,
                    titleSpacerPx = titleSpacerPx,
                )
            }.getOrNull()
            // 空结果兜底成「整章一页」：宁可让用户滚动/被裁剪，也不能渲染成整页空白
            if (result.isNullOrEmpty()) listOf(text) else result
        }

    // v1.8.8.5 修（白屏根因）：**尺寸没测量出来之前绝不能分页**。
    // `BoxWithConstraints` 首帧拿到的是 0 宽/0 高（甚至布局未完成时是极小值），
    // 用它去排版，每一段都「放不下」→ 分页算法切出**空列表**，而这份空排版又被
    // 写进缓存；随后切到那一章时命中缓存 → pageCount = 0、又不满足「未就绪」条件
    // （不是 null）→ 既没有加载圈、也没有内容 → **整页白屏**（用户截图那一幕）。
    // 尺寸未就绪时直接不跑，等尺寸回调触发重组后再算。
    val sizeReady = widthPx >= 60f && heightPx >= 60f

    // 空列表一律当没缓存：宁可重排一次，也不能拿空排版去渲染
    var pages by remember(chapterIndex, paramSig) {
        mutableStateOf(cache[chapterIndex]?.takeIf { it.isNotEmpty() })
    }

    LaunchedEffect(chapterIndex, paramSig, sizeReady) {
        if (!sizeReady) return@LaunchedEffect
        cache[chapterIndex]?.takeIf { it.isNotEmpty() }?.let {
            pages = it
            return@LaunchedEffect
        }
        // 未命中 → 退回「先显示加载态再补」，和原来的行为一致
        pages = null
        val result = measure(content, chapterTitle)
        if (result.isNotEmpty()) cache[chapterIndex] = result
        pages = result
    }

    // ===== 预分页：把预缓存窗口里的章节提前排好 =====
    // 顺序按「离当前章由近到远」，保证紧接着的下一章最先就绪。
    // 已经在缓存里的跳过；命中 `cache` 判断放在循环里，避免 effect 频繁重启后重复劳动。
    LaunchedEffect(prefetched, paramSig, chapterIndex, sizeReady) {
        if (!sizeReady) return@LaunchedEffect
        if (prefetched.isEmpty()) return@LaunchedEffect
        val targets = prefetched.keys
            .filter { it != chapterIndex && cache[it] == null }
            .sortedBy { kotlin.math.abs(it - chapterIndex) }
        for (idx in targets) {
            if (cache[idx] != null) continue
            // v1.8.8.5：每章之间让一次路。预分页是纯 CPU 活（逐段文本测量），
            // 连排十本会把 Default 池占满、和翻页动画抢核心，表现为「翻页发黏」。
            // 80ms 的间隔对后台任务无所谓，但足够让渲染帧插进来。
            kotlinx.coroutines.delay(80)
            val entry = prefetched[idx] ?: continue
            val raw = entry.second
            if (raw.isBlank()) continue
            // 标题要跟着正文一起排：首页可用高度扣的是标题那一块，
            // 用空标题会让首页多排一行、末页少排一行。
            val p = measure(raw, entry.first)
            // 只缓存非空结果，避免「空排版」污染缓存导致后续白屏
            if (p.isNotEmpty()) cache[idx] = p
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
