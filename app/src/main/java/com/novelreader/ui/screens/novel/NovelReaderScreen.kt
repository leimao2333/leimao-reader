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
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
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
import com.novelreader.core.reader.PageCache
import com.novelreader.core.reader.ReaderGesturePolicy
import com.novelreader.core.reader.ReaderGesturePolicy.TapAction
import com.novelreader.core.reader.ReaderLayoutMath
import com.novelreader.core.reader.ReaderPaginator
import com.novelreader.core.text.ChineseConverter
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.theme.ReaderBackgrounds
import com.novelreader.ui.theme.SystemBarIconsOn
import com.novelreader.ui.theme.LocalAppDarkTheme
import com.novelreader.ui.theme.applySystemBarIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.SubcomposeAsyncImage
import com.novelreader.core.reader.ReaderImageParser
import com.novelreader.core.reader.ReaderSentenceCursor

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
    // A10：正文里的图片段落（markdown 语法 / 独立成行图片 URL）抽出来单独渲染成卡片；
    // 分页与朗读都用「剔除图片后的纯文本」，避免把 `![alt](url)` 当正文画出来 / 念出来。
    val images = remember(content) { ReaderImageParser.parse(content) }
    val pagedContent = remember(content) { ReaderImageParser.strip(content) }
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    // v1.8.8.5：预缓存窗口（正文 + 标题），交给分页层提前排版，切章不再等排版。
    val cachedChapters by viewModel.cachedChapters.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val bgIndex by viewModel.bgIndex.collectAsState()
    val nightBgIndex by viewModel.nightBgIndex.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val lineHeight by viewModel.lineHeight.collectAsState()
    val paragraphSpacing by viewModel.paragraphSpacing.collectAsState()
    val sidePadding by viewModel.sidePadding.collectAsState()
    val autoPage by viewModel.autoPage.collectAsState()
    val autoPageInterval by viewModel.autoPageInterval.collectAsState()
    val autoPageSpeed by viewModel.autoPageSpeed.collectAsState()
    // A8：点击分区模式（0=三区，1=两区），在阅读菜单里切
    val tapZone by viewModel.tapZone.collectAsState()
    val tapLeftRatio by viewModel.tapLeftRatio.collectAsState()
    val tapRightRatio by viewModel.tapRightRatio.collectAsState()
    val pullBookmark by viewModel.pullBookmark.collectAsState()
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
    // A12：当前朗读句下标（-1 = 未在朗读）。与 ViewModel 用同一份「剔除图片后的纯文本」切句，
    // 所以这里在转换后的 pagedContent 上按下标取句即可对齐。
    val currentSentenceIndex by viewModel.currentSentenceIndex.collectAsState()
    val spokenSentence = remember(pagedContent, currentSentenceIndex) {
        if (currentSentenceIndex < 0) null
        else ReaderSentenceCursor.sentences(pagedContent).getOrNull(currentSentenceIndex)
    }

    val displayItem = currentItemState ?: item
    val chapterList = if (vmChapters.isNotEmpty()) vmChapters else chapters
    val chapterPosition = remember(chapterList, chapterIndex) {
        chapterList.indexOfFirst { it.index == chapterIndex }.coerceAtLeast(0)
    }

    var pageText by remember { mutableStateOf("") }
    var currentRatio by remember { mutableIntStateOf(0) }
    val chapterCount = chapterList.size

    // A13：无障碍 —— 给阅读内容容器一个可读的语义摘要（第几章 / 共几章 / 读到哪）。
    // 按「页」给一句摘要，而不是给每一页正文挂语义，避免语义树膨胀拖慢渲染。
    val readerContentDescription = remember(chapterTitle, chapterPosition, chapterCount, pageText) {
        buildString {
            append("第 ").append(chapterPosition + 1).append(" 章")
            if (chapterTitle.isNotBlank()) append("，").append(chapterTitle)
            if (chapterCount > 0) append("，共 ").append(chapterCount).append(" 章")
            if (pageText.isNotBlank()) {
                if (pageText.contains('/')) append("，第 ").append(pageText).append(" 页")
                else append("，阅读进度 ").append(pageText)
            }
        }
    }

    // A10：当前全屏查看的插图（null = 未打开全屏）
    var fullImage by remember { mutableStateOf<ReaderImageParser.ImageParagraph?>(null) }

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
    // 夜间模式优化：离开阅读页时把系统栏图标明暗还原成应用主题（深底纸张色会用白图标，
    // 不还原会被带回浅色上一页）。用 rememberUpdatedState 取最新值，避免 DisposableEffect 重启。
    val appDarkTheme = LocalAppDarkTheme.current
    val latestAppDarkTheme by rememberUpdatedState(appDarkTheme)
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
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.applySystemBarIcons(darkIcons = !latestAppDarkTheme)
        }
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
    // 夜间模式优化：白天/夜间各自的背景索引独立，切换纸张色不再串味
    val effectiveBgIndex = if (nightMode) nightBgIndex else bgIndex
    val bgIndexSafe = effectiveBgIndex.coerceIn(backgroundColors.indices)
    // 夜间模式优化：日夜/纸张色切换用 200ms 渐变，去掉生硬闪切
    val backgroundColor by animateColorAsState(
        targetValue = backgroundColors[bgIndexSafe].background,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "reader-bg",
    )
    val textColor by animateColorAsState(
        targetValue = backgroundColors[bgIndexSafe].text,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "reader-text",
    )

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
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // A8：手柄方向键 / PageUp·PageDown / A·B 也能翻页，且**不受**「音量键翻页」开关影响
            //（那个开关管的是 24/25 两个音量键，这里先排掉，避免把音量调节劫持成翻页）
            val nativeCode = event.key.nativeKeyCode
            if (nativeCode != 24 && nativeCode != 25) {
                val dir = ReaderGesturePolicy.gamepadDirection(nativeCode)
                if (dir != 0) {
                    if (dir > 0) viewModel.volumeNextPage() else viewModel.volumePrevPage()
                    return@onPreviewKeyEvent true
                }
            }
            if (!volumePageEnabled) return@onPreviewKeyEvent false
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
            // 顶栏重叠修复（v1.9.6.x）：沉浸全屏的 hide(systemBars) 在部分场景会失效
            // （主题切换后重组 / 下拉唤出状态栏 / Activity 恢复），系统状态栏重新显示，
            // 而下方常驻状态条原本按「状态栏一定隐藏」写死从顶端画，结果压在时间信号上。
            // 这里按实际 inset 统一避让：hide 生效时 statusBars=0（无影响）；状态栏可见时
            // 整块内容（正文 + 状态条 + 菜单顶栏）一起下移，相对位置不变，不会撞正文。
            // 注意 ReaderTopBar 里原有的 statusBarsPadding 已同步移除，避免双重偏移。
            .statusBarsPadding()
            // v1.8.8.4：进入淡入（见上面 enterAlpha 的说明）
            .graphicsLayer { alpha = enterAlpha }
            .then(volumeKeyModifier)
            .background(backgroundColor)
            // A13：给正文容器挂语义描述（不合并子节点，菜单/按钮仍各自可聚焦）。
            .semantics(mergeDescendants = false) {
                contentDescription = readerContentDescription
            },
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
                            // 骨架屏与真实正文用同一套页边，避免「加载时疏、加载完变密」的跳动
                            .padding(
                                start = sidePadding.dp,
                                end = sidePadding.dp,
                                top = NovelPageTokens.TOP_DP.dp,
                                bottom = NovelPageTokens.BOTTOM_DP.dp,
                            ),
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
                    content = pagedContent,
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
                    autoPageSpeed = autoPageSpeed,
                    onTapCenter = { viewModel.toggleMenu() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPageTextChanged = { pageText = it },
                    pageTurn = viewModel.pageTurn,
                )
            }
            isVertical -> {
                VerticalPagerNovel(
                    content = pagedContent,
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
                    autoPageSpeed = autoPageSpeed,
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
                    content = pagedContent,
                    spokenSentence = spokenSentence,
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
                    autoPageSpeed = autoPageSpeed,
                    tapZone = tapZone,
                    tapLeftRatio = tapLeftRatio,
                    tapRightRatio = tapRightRatio,
                    pullBookmark = pullBookmark,
                    onAddBookmark = { viewModel.addBookmark(currentRatio, content.take(60)) },
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
            // A9：右侧补「时钟 · 当前页/总页」。沉浸全屏下系统状态栏被隐藏，
            // 这条自绘状态条是用户唯一能看到时间与页码的地方。
            val clock = rememberClockText()
            ReaderChapterStatusBar(
                bookTitle = displayItem.title,
                chapterTitle = chapterTitle,
                textColor = textColor,
                trailingInfo = if (pageText.isBlank()) clock else "$clock · $pageText",
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
                bgIndex = bgIndexSafe,
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

        // A10：正文插图 —— 图片段落独立成卡片条（不碰 Canvas 分页），点击进入全屏。
        // 菜单展开时不显示，避免和底部菜单叠在一起抢触摸。
        if (images.isNotEmpty() && !showMenu) {
            NovelImageGallery(
                images = images,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onOpen = { fullImage = it },
            )
        }
        fullImage?.let { img ->
            NovelImageFullScreen(
                image = img,
                onClose = { fullImage = null },
            )
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

/* ===================== 正文页排版节奏（参照 xrn1997/android-ebook） ===================== */

/**
 * 正文页的垂直节奏，四套翻页实现（滚动 / Pager / 三页窗口 / Canvas 自绘）共用一套值。
 *
 * 参照 `xrn1997/android-ebook` 的 `ReaderPageCard`：
 * - 页边只留薄薄一圈（它是 `padding(top = 5, bottom = 5)` + 系统栏 inset），
 *   而不是原来那样上下各 48dp —— 那一圈 96dp 加上再额外扣的 1.8 行页码，
 *   一屏凭空少掉两行半，底部看着就是**空出一大块**；
 * - 页码**固定占一行高**（它是 30dp 常量），有无文字都不变，正文可用高度才稳定。
 *
 * 顶部比参考项目多一些：沉浸全屏下没有状态栏 inset 可避让，
 * 而阅读页顶部常驻一条章节状态条（labelSmall + 6dp 内边距 ≈ 28dp），
 * 正文必须让开它，否则两行字会叠在一起。
 */
private object NovelPageTokens {
    /** 正文顶部留白（让开常驻章节状态条，再留一点呼吸） */
    const val TOP_DP = 34f

    /**
     * 正文底部留白（页码行之下）。
     *
     * 不能像参考项目那样只留 5dp：它是 `windowInsetsPadding(systemBars)` 先把手势条让开了，
     * 而本阅读器是沉浸全屏（systemBars 被隐藏），页码会被手势条的触摸区压住。
     * 这里留 16dp：页码行 26dp 叠在上面，页码文字中心距底 ≈29dp，仍在手势条（≈24dp）之上。
     */
    const val BOTTOM_DP = 16f

    /** 页码行固定高度：正文区与页码行同高，换页/加载都不跳动 */
    const val PAGE_ROW_DP = 26f

    /** 页码字号上限（参考项目固定 14sp，这里跟着正文字号走但收窄区间） */
    const val PAGE_NUMBER_SP_MAX = 13f

    /** 上下预留合计（dp）：`maxHeight - 这个值` 才是正文可用高度 */
    const val VERTICAL_RESERVED_DP = TOP_DP + BOTTOM_DP + PAGE_ROW_DP

    /** 章标题相对正文的字号比例（参考项目 14sp / 正文 18~20sp），并夹在合理区间 */
    fun titleSp(size: Float): Float = (size * 0.72f).coerceIn(12f, 16f)

    /** 页码字号：比正文小 6sp，但不超过 13sp、不低于 10sp */
    fun pageNumberSp(size: Float): Float = (size - 6f).coerceIn(10f, PAGE_NUMBER_SP_MAX)
}

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
    /** 自动翻页倍速（A5），1.0x = 按 autoPageInterval 原速 */
    autoPageSpeed: Float = 1f,
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
            // v1.8.8.6：与分页模式同一套页边（顶部让开常驻章节状态条，底部只留一点呼吸）
            .padding(
                start = sidePadding.dp,
                end = sidePadding.dp,
                top = NovelPageTokens.TOP_DP.dp,
                bottom = 16.dp,
            ),
    ) {
        // 注意：Box 的子项会全部叠在同一位置，标题/下一章会压在正文上。
        // 必须用 Column 纵向排列，Box 只承担滚动与内边距。
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!hideChapterTitle) {
                // 与分页模式同一份标题样式：小字、0.7 透明度、单行省略
                Text(
                    text = chapterTitle,
                    fontSize = NovelPageTokens.titleSp(fontSize).sp,
                    fontFamily = fontFamily,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
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
                        // 不用 colorScheme.primary：浅色主题的深紫压在夜间纸张上对比度太低，
                        // 这里直接用正文色（加粗已足够强调），夜间也清晰。
                        color = if (hasNextChapter) textColor else textColor.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CanvasPagerNovel(
    /** A8：点击分区模式（0 = 三区，1 = 两区） */
    tapZone: Int = 0,
    /** A8：三区点击的左 / 右区宽度比例 */
    tapLeftRatio: Float = 1f / 3f,
    tapRightRatio: Float = 1f / 3f,
    /** A8：下拉加书签开关 */
    pullBookmark: Boolean = true,
    /** A8：下拉加书签（下拉超过阈值时回调） */
    onAddBookmark: () -> Unit = {},
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
    /** 自动翻页倍速（A5），1.0x = 按 autoPageInterval 原速 */
    autoPageSpeed: Float = 1f,
    hasNextChapter: Boolean,
    onTapCenter: () -> Unit,
    onReachFirst: () -> Unit,
    onNextChapter: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onRatioChanged: (Int) -> Unit,
    onPageTextChanged: (String) -> Unit = {},
    pageTurn: kotlinx.coroutines.flow.SharedFlow<Int>? = null,
    prefetchedContent: Map<Int, Pair<String, String>> = emptyMap(),
    /** A12：当前朗读句文本（未朗读时为 null），画正文时叠一层高亮背景 */
    spokenSentence: String? = null,
) {
    BoxWithConstraintsCompat { maxWidthDp, maxHeightDp ->
        val density = LocalDensity.current
        val d = density.density
        val availWidthPx = (maxWidthDp - sidePadding * 2f).coerceAtLeast(1f) * d
        // v1.8.8.6：页边收窄。原来是「上下各 48dp + 再扣 1.8 行给页码」，一屏凭空少两行半，
        // 底部看着空一大块；现在只留 NovelPageTokens 的薄边 + 一条固定页码行。
        val textHeightPx = (maxHeightDp - NovelPageTokens.VERTICAL_RESERVED_DP)
            .coerceAtLeast(1f) * d
        val topPx = NovelPageTokens.TOP_DP * d
        val bottomPx = NovelPageTokens.BOTTOM_DP * d
        val pageRowPx = NovelPageTokens.PAGE_ROW_DP * d
        val paraSpacingPx = paragraphSpacing * d
        val titleSpacerPx = 10f * d

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
        // v1.8.8.6：连点翻页的**累计目标页** + 上一次翻页时间。
        // 不能 `remember(chapterIndex)`：切章会新建 State 对象，而 Canvas 的 pointerInput
        // 闭包 / 自动翻页协程仍握着旧对象 → 写入无效（这是 H/V 版本踩过的老坑）。
        // 生命周期覆盖整个阅读页，切章时在下面的定位 effect 里手动重置。
        var turnTarget by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
        var lastTurnAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
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
            // v1.8.8.6：切章 / 重新分页后，累计目标归位到恢复页；首次点击恢复正常动画
            turnTarget = target
            lastTurnAt = 0L
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
        // 章名单独排一次（只画在第 0 页）。
        // v1.8.8.6：对齐参考项目 —— 14sp 级别小字、0.7 透明度、单行省略、左对齐。
        // 原来是「正文 +2sp 居中加粗」，压在正文上方很抢戏，首屏也因此少排一行。
        val titleLayout = remember(chapterTitle, style, textConstraints, fontSize, textColor) {
            if (chapterTitle.isBlank()) null
            else measurer.measure(
                text = chapterTitle,
                style = style.copy(
                    fontSize = NovelPageTokens.titleSp(fontSize).sp,
                    fontWeight = FontWeight.Normal,
                    color = textColor.copy(alpha = 0.7f),
                ),
                constraints = textConstraints,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // v1.8.8.6：连点翻页修复（此前「点不快」的真正原因）。
        // 旧实现 `val target = cur + delta`：`cur` 只有动画结束(onEnd)才提交，动画进行中
        // 再点，target 仍等于正在飞向的那一页 → 第二下被「吃掉」；而且每次新手势的 down
        // 又会 `animJob?.cancel()` 掐掉上一段动画，于是连点时 `cur` 永远提交不了，几下之后
        // 仍停在第一页 —— 看起来就是「连点翻不动」。
        // 现在改用**独立的累计目标 turnTarget**（每一下基于上一次目标累加，与动画无关），
        // 连点（间隔 < 250ms）直接瞬跳，不等动画、不排队。
        fun turn(delta: Int) {
            if (pageCount <= 0) return
            val last = pageCount - 1
            val base = if (turnTarget in 0..last) turnTarget else cur
            var target = base + delta
            if (target !in 0..last) {
                // 越界：只有实际页面真的停在边界才算「到头」。
                // 连点时目标累加得比页面快，几下就会越过末页；那种情况钳在边界照常翻，
                // 绝不因此切章（用户要的是连点翻页，不是快速下一章）。
                val atEdge = if (delta > 0) cur >= last else cur <= 0
                if (atEdge) {
                    if (delta > 0) onNextChapter() else onReachFirst()
                    return
                }
                target = target.coerceIn(0, last)
            }
            turnTarget = target
            if (target == cur) return

            val now = android.os.SystemClock.uptimeMillis()
            val rapid = now - lastTurnAt < 250L
            lastTurnAt = now

            if (rapid || moving || kotlin.math.abs(target - cur) > 1) {
                // 连点 / 动画未结束 / 一次跨多页：直接瞬跳，保证每一下都立刻前进
                animJob?.cancel()
                cur = target
                dragX = 0f
                moving = false
                return
            }
            // 单击（相邻页、且非连点）：走一页位移动画
            startAnim(
                target = if (delta > 0) -widthPx else widthPx,
                durationMs = 180,
                onEnd = {
                    cur = target
                    dragX = 0f
                },
            )
        }

        LaunchedEffect(autoPage, autoPageInterval, chapterIndex) {
            if (!autoPage) return@LaunchedEffect
            while (true) {
                // A5：按倍速换算间隔（1.0x 等于原设定；倍速越高停得越短，下限 300ms 防闪屏）
                delay(
                    com.novelreader.core.reader.ReaderAutoPagePolicy.intervalMs(
                        autoPageInterval * 1000L,
                        autoPageSpeed,
                    )
                )
                turn(1)
            }
        }

        if (pageTurn != null) {
            LaunchedEffect(pageTurn, chapterIndex) {
                pageTurn.collect { dir -> turn(if (dir > 0) 1 else -1) }
            }
        }

        // ===== A7 长按选词 =====
        // 选中的是「当前页内的字符区间」，翻页/切章就清空（区间只对某一页的排版有效）。
        var selPage by remember { mutableIntStateOf(-1) }
        var selStart by remember { mutableIntStateOf(-1) }
        var selEnd by remember { mutableIntStateOf(-1) }
        // A12：朗读句用 snapshot state 承接。Canvas 的 onDraw 只有在读到快照状态时才
        // 会在值变化时自动重绘；纯参数变化不保证触发重绘，所以这里把参数转成 state。
        var spoken by remember { mutableStateOf(spokenSentence) }
        LaunchedEffect(spokenSentence) { spoken = spokenSentence }
        // 正文首个字的 y 偏移：第 0 页有章名时整体下移，选词命中必须用同一个原点。
        val textTopOffsetPx = if (!hideChapterTitle && titleLayout != null) {
            titleLayout.size.height + titleSpacerPx
        } else 0f
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        fun clearSelection() {
            selPage = -1
            selStart = -1
            selEnd = -1
        }
        // 换页即清空选区，避免高亮错位到别的页
        LaunchedEffect(cur, pageCount) { clearSelection() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageCount, chapterIndex, cur) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // A7：记录按下时刻，松手时据此区分「点击」与「长按选词」
                        val downTime = android.os.SystemClock.uptimeMillis()
                        // 手指按下先打断上一次收尾动画，否则它会继续写 dragX
                        animJob?.cancel()
                        moving = false
                        val startX = down.position.x
                        val startY = down.position.y
                        var maxDownDy = 0f
                        var lastX = startX
                        var pointerX = startX
                        var moved = false
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                            pointerX = change.position.x
                            // A8：记录向下位移的最大值，用于「下拉加书签」判定
                            maxDownDy = kotlin.math.max(maxDownDy, change.position.y - startY)
                            val delta = pointerX - lastX
                            lastX = pointerX
                            if (!moved && kotlin.math.abs(pointerX - startX) > touchSlop) {
                                moved = true
                                // v1.8.8.6：进入拖动视为全新交互，累计目标归位到当前页
                                turnTarget = cur
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
                        // A8：纯竖直下拉（横向没超过 slop）且位移够 → 加书签。
                        // 必须拦在「点击」分支之前，否则竖滑会被当成翻页 / 呼菜单。
                        // 开关关闭时整条手势不触发，正常交给翻页 / 呼菜单判定。
                        if (pullBookmark && !moved && ReaderGesturePolicy.shouldPullBookmark(
                                dy = maxDownDy / d,
                                dx = (pointerX - startX) / d,
                            )
                        ) {
                            clearSelection()
                            onAddBookmark()
                            return@awaitEachGesture
                        }
                        if (!moved) {
                            // A7：按住 ≥420ms 视为长按 → 选中按下位置的词（走松手判定，
                            // 不额外挂 pointerInput，避免和翻页手势抢事件）。
                            if (android.os.SystemClock.uptimeMillis() - downTime >= 420L) {
                                // 两区模式没有中间点击区，长按用来呼菜单 —— 与面板说明
                                // 「左右翻页，长按呼菜单」保持一致，否则用户按说明长按却弹出
                                // 复制条，等于两条手势抢同一个动作。
                                if (tapZone == 1) {
                                    clearSelection()
                                    onTapCenter()
                                    return@awaitEachGesture
                                }
                                val layout = layouts[cur]
                                if (layout != null) {
                                    val padPxLocal = sidePadding * d
                                    val localX = startX - padPxLocal
                                    val localY = down.position.y -
                                        (topPx + if (cur == 0) textTopOffsetPx else 0f)
                                    val offset = layout.getOffsetForPosition(
                                        Offset(localX, localY)
                                    )
                                    val word = layout.getWordBoundary(offset)
                                    if (word != null && word.end > word.start) {
                                        selPage = cur
                                        selStart = word.start
                                        selEnd = word.end
                                    }
                                }
                                return@awaitEachGesture
                            }
                            // 普通点击：先清掉已有选区，再按分区翻页 / 呼菜单
                            clearSelection()
                            // A8：点击分区交给 ReaderGesturePolicy（可配三区/两区），
                            // 不再写死 1/3 分界。默认 THREE_ZONE 与旧行为完全一致。
                            when (
                                ReaderGesturePolicy.resolveTap(
                                    startX,
                                    size.width.toFloat(),
                                    if (tapZone == 1) ReaderGesturePolicy.TapZoneMode.TWO_ZONE
                                    else ReaderGesturePolicy.TapZoneMode.THREE_ZONE,
                                    leftRatio = tapLeftRatio,
                                    rightRatio = tapRightRatio,
                                )
                            ) {
                                TapAction.PREV_PAGE -> turn(-1)
                                TapAction.NEXT_PAGE -> turn(1)
                                TapAction.TOGGLE_MENU -> onTapCenter()
                                TapAction.NONE -> Unit
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
                                    // v1.8.8.6：拖动落页后把累计目标对齐到实际页，避免下一次点击基于旧目标
                                    turnTarget = cur
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
            val w = size.width
            // 翻页进度 0..1（0 = 静止，1 = 已翻满一页）
            val p = (kotlin.math.abs(dx) / w).coerceIn(0f, 1f)

            /** 画一页的**内容本体**（背景 + 第 0 页章名 + 正文 + 页码），不含位移 */
            fun drawPageBody(idx: Int) {
                if (idx !in pages.indices) return
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
                        // A7：选中区间叠一层半透明底色。画在文字之后，不遮挡字形；
                        // getPathForRange 的坐标相对文本排版原点，所以要先平移到 padPx / y。
                        if (idx == selPage && selStart >= 0 && selEnd > selStart) {
                            translate(padPx, y) {
                                drawPath(
                                    layout.getPathForRange(selStart, selEnd),
                                    color = textColor.copy(alpha = 0.26f),
                                )
                            }
                        }
                        // A12：朗读句高亮 —— 在当前页排版文本里定位当前句，复用
                        // A7 的 drawPath(getPathForRange) 坐标换算思路，画一层更淡的底色。
                        // 为什么直接在已排好的 AnnotatedString 上 indexOf：buildReaderText
                        // 只在段首加缩进、段间插零宽占位行，句子内部逐字保留，所以当前句
                        // 一定原样出现在某页的排版文本里，省去「全文字符区间 → 页内区间」换算。
                        // 颜色用 textColor 的半透明叠加（非写死色值），白天/夜间都清晰。
                        val sentence = spoken
                        if (sentence != null) {
                            val built = layout.layoutInput.text.text
                            val s = built.indexOf(sentence)
                            if (s >= 0) {
                                translate(padPx, y) {
                                    drawPath(
                                        layout.getPathForRange(s, s + sentence.length),
                                        color = textColor.copy(alpha = 0.20f),
                                    )
                                }
                            }
                        }
                    }
                }
                // 页码：画在正文下方那条**固定高度页码行**的正中（对齐参考项目的 ReaderPageCard）。
                // 注意这里必须写 ${pages.size}（带花括号）。
                // 写成 `$pages.size` 时 Kotlin 只把 `$pages` 当变量，尾巴 `.size` 变字面量，
                // 结果画出来是「整个列表的 toString() + .size」那一长串乱码。
                val pageText = "${idx + 1}/${pages.size}"
                val pageLayout = measurer.measure(
                    text = pageText,
                    style = style.copy(fontSize = NovelPageTokens.pageNumberSp(fontSize).sp),
                )
                drawText(
                    pageLayout,
                    topLeft = Offset(
                        (w - pageLayout.size.width) / 2f,
                        topPx + textHeightPx + bottomPx +
                            (pageRowPx - pageLayout.size.height) / 2f,
                    ),
                    alpha = 0.45f,
                )
            }

            /** 平移后画一页 */
            fun drawPageAt(idx: Int, offsetX: Float) {
                if (idx !in pages.indices) return
                translate(offsetX, 0f) { drawPageBody(idx) }
            }

            /**
             * 画「被掀起」的页：绕装订边横向压缩，自由边叠一层随进度加深的阴影。
             *
             * 这是**仿真**模式的近似实现：真·卷曲需要逐像素网格变形与光照，
             * 在 CanvaDrawScope 里每帧重算代价太高（以前那版就是这么掉帧的）。
             * 用「装订边锚定的横向压缩 + 自由边阴影」能拿到七成观感，
             * 且完全由 RenderThread 处理、不产生额外离屏合成。
             */
            fun drawLiftedPage(idx: Int, pivotX: Float, progress: Float) {
                if (idx !in pages.indices) return
                val sx = 1f - 0.38f * progress
                scale(scaleX = sx, scaleY = 1f, pivot = Offset(pivotX, size.height)) {
                    drawPageBody(idx)
                }
                // 自由边阴影：装订边在左 → 自由边在右，反之亦然。
                val band = 56f * d
                val alpha = 0.30f * progress
                if (alpha <= 0.01f) return
                if (pivotX <= 0f) {
                    val edge = w * sx
                    val startX = (edge - band).coerceAtLeast(0f)
                    if (edge - startX > 1f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = alpha)),
                                startX = startX,
                                endX = edge,
                            ),
                            topLeft = Offset(startX, 0f),
                            size = Size(edge - startX, size.height),
                        )
                    }
                } else {
                    val edge = w - w * sx
                    val endX = (edge + band).coerceAtMost(w)
                    if (endX - edge > 1f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                                startX = edge,
                                endX = endX,
                            ),
                            topLeft = Offset(edge, 0f),
                            size = Size(endX - edge, size.height),
                        )
                    }
                }
            }

            // 按 pageType 分发。以前这里无条件走「三页平移」，pageType 传进来后根本没用 ——
            // 覆盖 / 仿真 / 无动画三种模式渲染结果完全一样，用户切了也没变化。
            when (pageType) {
                PreferencesManager.PAGE_TYPE_NO_ANIM -> {
                    // 无动画：只画当前页，切换页时瞬间替换（不拖动、不留位移）
                    drawPageAt(cur, 0f)
                }

                PreferencesManager.PAGE_TYPE_COVER -> {
                    // 覆盖：底层页不动，新的页从侧边盖上来。
                    drawPageAt(cur, 0f)
                    if (dx < 0f && cur + 1 < pageCount) {
                        drawPageAt(cur + 1, w + dx) // 从右侧滑入
                    } else if (dx > 0f && cur - 1 >= 0) {
                        drawPageAt(cur - 1, -w + dx) // 从左侧滑入
                    }
                }

                PreferencesManager.PAGE_TYPE_SIMULATE -> {
                    // 仿真：下一/上一页铺在底层原位，当前页绕装订边掀起。
                    when {
                        dx < 0f && cur + 1 < pageCount -> {
                            drawPageAt(cur + 1, 0f)
                            drawLiftedPage(cur, pivotX = 0f, progress = p) // 绕左边缘（装订边）掀起
                        }
                        dx > 0f && cur - 1 >= 0 -> {
                            drawPageAt(cur - 1, 0f)
                            drawLiftedPage(cur, pivotX = w, progress = p)
                        }
                        else -> drawPageAt(cur, 0f)
                    }
                }

                else -> {
                    // 滑动：三页平移（原实现，层级：下一页 → 当前页 → 上一页）
                    if (cur + 1 < pageCount) drawPageAt(cur + 1, 0f)
                    drawPageAt(cur, kotlin.math.min(dx, 0f))
                    if (cur - 1 >= 0) drawPageAt(cur - 1, -w + kotlin.math.max(dx, 0f))
                }
            }
        }

        // A7：选中后的操作条。贴在屏幕下方而不是浮在选中的字旁边 ——
        // 手绘 Canvas 里精确定位弹窗成本高，底部固定位置不会挡住刚选中的那行。
        if (selPage >= 0 && selStart >= 0 && selEnd > selStart) {
            val selectedText = pages.getOrNull(selPage)?.let { page ->
                page.substring(
                    selStart.coerceIn(0, page.length),
                    selEnd.coerceIn(0, page.length),
                )
            }.orEmpty()
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 88.dp)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(24.dp),
                        )
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        if (selectedText.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(selectedText))
                        }
                        clearSelection()
                    }) { Text("复制") }
                    TextButton(onClick = { clearSelection() }) { Text("取消") }
                }
            }
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
    /** 自动翻页倍速（A5），1.0x = 按 autoPageInterval 原速 */
    autoPageSpeed: Float = 1f,
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
        // v1.8.8.6：页边收窄（见 NovelPageTokens）——原来是「上下各 48dp + 再扣 1.8 行给页码」
        val textHeightPx = (maxHeightDp - NovelPageTokens.VERTICAL_RESERVED_DP)
            .coerceAtLeast(1f) * density
        val paraSpacingPx = paragraphSpacing * density
        val titleSpacerPx = 10f * density

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
                // A5：按倍速换算间隔（1.0x 等于原设定；倍速越高停得越短，下限 300ms 防闪屏）
                delay(
                    com.novelreader.core.reader.ReaderAutoPagePolicy.intervalMs(
                        autoPageInterval * 1000L,
                        autoPageSpeed,
                    )
                )
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
            // v1.8.8.6：页边收窄到 NovelPageTokens（原上下各 48dp，底部还额外空 1.8 行给页码）
            .padding(
                start = sidePadding.dp,
                end = sidePadding.dp,
                top = NovelPageTokens.TOP_DP.dp,
                bottom = NovelPageTokens.BOTTOM_DP.dp,
            ),
    ) {
        if (page == 0 && !hideChapterTitle) {
            // 与 CanvasPagerNovel 的 titleLayout 同一份样式：小字、0.7 透明度、单行省略、左对齐
            Text(
                text = chapterTitle,
                fontSize = NovelPageTokens.titleSp(fontSize).sp,
                fontFamily = fontFamily,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }
        // v1.7.1.0：缓存 AnnotatedString —— 以前任何一次父级重组都会重建整页文本（含逐段 split）
        val pageText = remember(pages, page, fontSize, paragraphSpacing, indent) {
            buildReaderText(pages[page], fontSize, paragraphSpacing, indent)
        }
        // A6 残页处理（纯视觉，不改页数 / 字符区间 / 高亮）：多页章节的末页若内容很少，
        // 用同一份纯函数判定策略——KEEP_CENTERED 时在正文前补一个等权 Spacer，与正文后的
        // 那个一起把末页文字垂直居中，避免大片留白堆在下方。MERGE_UP 需要改分页结果
        // （把末页并回上一页），会影响翻页 / 高亮，这里按约定保持原样不处理。
        val lastPageStrategy = if (pages.size > 1 && page == pages.lastIndex) {
            ReaderLayoutMath.partialPageStrategy(pages.last().length, pages.first().length)
        } else {
            ReaderLayoutMath.PartialStrategy.NORMAL
        }
        if (lastPageStrategy == ReaderLayoutMath.PartialStrategy.KEEP_CENTERED) {
            Spacer(Modifier.weight(1f))
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
        // 页码固定占一行高、居中（对齐参考项目 ReaderPageCard 的 pageNumberRowHeight 契约）：
        // 有没有文字都占同样的高度，正文可用高度才不会随页码行塌陷而漂移
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NovelPageTokens.PAGE_ROW_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${page + 1}/${pages.size}",
                fontSize = NovelPageTokens.pageNumberSp(fontSize).sp,
                color = textColor.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
        }
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
         * 仿真：透视掀纸翻页（A3）。
         *
         * 历史：v1.8.8.5 曾按「三槽位 + 单 Animatable 位移」的参考实现退化成**纯平移**，
         * 换来了流畅但完全不像翻书。现在把透视旋转加回来，但**不再**做以前的
         * 「全屏渐变阴影 + 贝塞尔卷边」——那两项每帧要两次全屏渐变填充 + 一次离屏合成，
         * 是快速连翻掉帧的真正来源。
         *
         * 现在的做法只用 `graphicsLayer` 的三项硬件属性（rotationY / rotationZ / shadowElevation），
         * 全部由 RenderThread 处理，不产生额外的全屏绘制：
         * - `rotationY` 绕**装订边**转到接近 90°，纸页真正「掀起」而不是平移；
         * - `rotationZ` 给一点平面倾角，边线是斜的，避免看起来像卡片翻面；
         * - `shadowElevation` 随翻页进度增长，纸页离开桌面时自然投影。
         *
         * 支点选择：翻走页绕它「留在原位的那条边」（装订边）转，这样页面像被手捏住
         * 外缘掀过去；`originY = 1f` 让它从底角起翘，符合纸页的受力方向。
         */
        fun simulate(pageOffset: Float): PageTransform {
            val d = pageOffset.coerceIn(-1f, 1f)
            val progress = kotlin.math.abs(d)
            if (progress <= 0.001f) return PageTransform()
            val eased = easeOutCubic(progress)
            // d > 0：下一页从右侧进入，本页绕右边缘（装订边）转；d < 0 反之。
            val sign = if (d > 0f) 1f else -1f
            return PageTransform(
                // 轻微回缩，模拟纸页在透视里「变小一点」；幅度要小，否则正文会缩得发虚。
                scaleX = 1f - eased * 0.04f,
                scaleY = 1f - eased * 0.02f,
                // 不透明度只掉一点：掉太多会在纸页上直接看到下层正文，像重影。
                alpha = 1f - eased * 0.12f,
                rotationY = sign * eased * 86f,
                rotationZ = sign * eased * 1.6f,
                shadowElevation = eased * 24f,
                originX = if (d > 0f) 1f else 0f,
                originY = 1f,
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
 * A16：排版结果的 LRU 缓存（容量 12 章）。
 *
 * 为什么是文件级单例而不是 `remember`：预分页在后台协程并发跑，单例自身线程安全
 * （[PageCache] 内部加锁）；且缓存 key 已收进正文 hash / 章名 hash / 全部排版参数，
 * 跨章节、跨阅读会话复用都不会串味。容量有上限，不会随阅读时长无限涨内存。
 */
private val readerPageCache = PageCache(PageCache.DEFAULT_MAX_SIZE)

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
    /** 首行缩进：render 闭包里捕获了它，签名显式持有才能把它写进缓存 key */
    indent: Boolean = false,
): List<String> {
    // A1：分页主算法已抽到 core/reader/ReaderPaginator。这里只做两件事：
    // ① 把 Compose 的 TextMeasurer 适配成纯测量接口；② 接线 A16 的 LRU 缓存。
    val width = widthPx.toInt().coerceAtLeast(1)

    // A16：key 收进全部影响断行的输入。字体 / 字重来自同一个 readerTextStyle，
    // 其散列值参与 key —— 任一排版参数变化都会产生新 key，绝不返回旧布局。
    val cacheKey = PageCache.keyOf(
        contentHash = content.hashCode(),
        titleHash = chapterTitle.hashCode(),
        widthPx = width,
        heightPx = heightPx,
        fontSize = style.fontSize.value,
        lineHeightMultiplier = if (
            style.lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified && style.fontSize.value > 0f
        ) {
            style.lineHeight.value / style.fontSize.value
        } else 0f,
        paragraphSpacingPx = paragraphSpacingPx,
        titleSpacerPx = titleSpacerPx,
        fontFamily = style.fontFamily?.hashCode()?.toString() ?: "default",
        bold = style.fontWeight == FontWeight.Bold,
        letterSpacing = style.letterSpacing.value,
        indent = indent,
        hideChapterTitle = !showTitle,
    )
    readerPageCache.get(cacheKey)?.let { return it }

    // 测量适配：约束 maxHeight = Infinity（与旧实现一致），故判定只需比较高度。
    val adapter = object : ReaderPaginator.MeasureAdapter {
        override fun measureHeight(text: String, widthPx: Int): Float =
            measurer.measure(
                text = render(text),
                style = style,
                constraints = Constraints(maxWidth = widthPx, maxHeight = Constraints.Infinity),
            ).size.height.toFloat()

        override fun measureTitleHeight(title: String, widthPx: Int): Float = runCatching {
            // 必须与渲染端（CanvasPagerNovel.titleLayout / PageContent 标题）同一份字号与行数：
            // 测量说「标题占 A 高」、渲染占 B 高，首屏就会多切或少切一行。
            measurer.measure(
                text = AnnotatedString(title),
                style = style.copy(
                    fontSize = NovelPageTokens.titleSp(style.fontSize.value).sp,
                    fontWeight = FontWeight.Normal,
                ),
                constraints = Constraints(maxWidth = widthPx, maxHeight = Constraints.Infinity),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            ).size.height.toFloat() + titleSpacerPx
        }.getOrDefault(0f)
    }

    val result = ReaderPaginator(
        measurer = adapter,
        config = ReaderPaginator.Config(
            widthPx = widthPx,
            heightPx = heightPx,
            showTitle = showTitle,
        ),
    ).paginate(content, chapterTitle).pageTexts
    readerPageCache.put(cacheKey, result)
    return result
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
                    indent = indent,
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
                    indent = indent,
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

/* ===================== A10 正文插图卡片 ===================== */

/** 本地图片解码时限制的最大边长（像素），防止超大图撑爆内存。 */
private const val LOCAL_IMAGE_MAX_DIM = 2048

/**
 * 底部横向滚动的插图卡片条。
 *
 * 为什么是「独立卡片条」而不是画进 Canvas 分页里：图片解码 / OOM / 加载阻塞
 * 是三项增强里最容易崩的地方，塞进手绘分页主流程风险太高。这里让插图完全
 * 独立于分页渲染，各自加载、各自降级，互不影响翻页。
 */
@Composable
private fun BoxScope.NovelImageGallery(
    images: List<ReaderImageParser.ImageParagraph>,
    backgroundColor: Color,
    textColor: Color,
    onOpen: (ReaderImageParser.ImageParagraph) -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(backgroundColor.copy(alpha = 0.94f))
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = "插图（${images.size}）",
            color = textColor.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            images.forEach { img ->
                NovelImageCard(
                    image = img,
                    textColor = textColor,
                    onClick = { onOpen(img) },
                )
            }
        }
    }
}

@Composable
private fun NovelImageCard(
    image: ReaderImageParser.ImageParagraph,
    textColor: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            // A13：卡片补无障碍语义，TalkBack 能读出是「插图 + alt」
            .semantics { contentDescription = image.alt.ifBlank { "插图" } }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            NovelImageThumb(image = image, textColor = textColor)
        }
        Text(
            text = image.alt.ifBlank { "图片" },
            color = textColor.copy(alpha = 0.8f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 缩略图：网络图走 Coil，本地图走 BitmapFactory（IO 线程 + 采样限尺寸）。 */
@Composable
private fun NovelImageThumb(image: ReaderImageParser.ImageParagraph, textColor: Color) {
    val desc = image.alt.ifBlank { "插图" }
    if (image.isNetwork) {
        SubcomposeAsyncImage(
            model = image.source,
            contentDescription = desc,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(20.dp),
                        strokeWidth = 2.dp,
                        color = textColor.copy(alpha = 0.6f),
                    )
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载失败", color = textColor.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            },
        )
    } else {
        val bitmap = rememberLocalImage(image.source)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = desc,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = image.alt.ifBlank { "本地图片" },
                    color = textColor.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * 本地图片解码：IO 线程 + 先读边界再按 [LOCAL_IMAGE_MAX_DIM] 采样，失败返回 null 降级。
 *
 * 为什么放 IO 线程且限制尺寸：本地插图可能是几 MB 的原图，主线程直接 decode 会 ANR，
 * 不采样直接 decode 会 OOM。这里双保险，任何异常都吞掉降级成 alt 占位。
 */
@Composable
private fun rememberLocalImage(path: String): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val filePath = path.trim().removePrefix("file://")
                if (filePath.isEmpty()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val sample = localSampleSize(bounds.outWidth, bounds.outHeight)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(filePath, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/** 计算 2 的幂采样倍数，把解码结果的长边压到 [LOCAL_IMAGE_MAX_DIM] 以内。 */
private fun localSampleSize(width: Int, height: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= LOCAL_IMAGE_MAX_DIM && h / 2 >= LOCAL_IMAGE_MAX_DIM) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}

/** 全屏查看单张插图：点背景或右上角「关闭」退出。 */
@Composable
private fun BoxScope.NovelImageFullScreen(
    image: ReaderImageParser.ImageParagraph,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .clickable(onClick = onClose),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            NovelImageThumb(image = image, textColor = Color.White)
        }
        Text(
            text = "关闭",
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .clickable(onClick = onClose)
                .semantics { contentDescription = "关闭图片" }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

