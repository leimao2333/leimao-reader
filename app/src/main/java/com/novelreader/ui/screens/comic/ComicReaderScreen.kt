package com.novelreader.ui.screens.comic

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.novelreader.core.image.ComicImageScramble
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import kotlinx.coroutines.launch

/**
 * 漫画阅读页。
 *
 * 核心功能：
 * - 图片完美适配屏幕（宽度撑满、按比例缩放）
 * - 两种翻页方式：上下滚动 / 左右翻页
 * - 图片预加载：滚动 / 翻页时提前加载邻近图片，减少等待
 * - 加载中显示占位，加载失败可点击重试
 * - 点击屏幕呼出设置面板、上一话 / 下一话
 */
@Composable
fun ComicReaderScreen(
    viewModel: ComicReaderViewModel,
    item: MediaItem,
    chapters: List<ChapterItem>,
    chapter: ChapterItem,
    onBack: () -> Unit,
) {
    val images by viewModel.images.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val pageMode by viewModel.pageMode.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val chapterTitle by viewModel.chapterTitle.collectAsState()
    val imageHeaders by viewModel.imageHeaders.collectAsState()
    val imageScripts by viewModel.imageScripts.collectAsState()
    val chapterPosition by viewModel.chapterIndex.collectAsState()
    val chapterCount by viewModel.chapterCount.collectAsState()
    val hasPrevChapter by viewModel.hasPrevChapter.collectAsState()
    val hasNextChapter by viewModel.hasNextChapter.collectAsState()
    val preloadCount by viewModel.preloadCount.collectAsState()
    val preloadWifiOnly by viewModel.preloadWifiOnly.collectAsState()
    val landscapeDoublePage by viewModel.landscapeDoublePage.collectAsState()

    LaunchedEffect(item, chapter) {
        viewModel.init(item, chapters, chapter)
    }

    // 进入/切换章节后，先预热前几张图片
    val context = LocalContext.current
    // 是否允许预加载：未开启「仅 WiFi」时总是允许；开启时仅在 WiFi 下允许
    val canPreload = !preloadWifiOnly ||
        com.novelreader.core.util.NetworkStatus.isWifi(context)
    LaunchedEffect(images, imageHeaders, imageScripts, preloadCount, canPreload) {
        if (!canPreload) return@LaunchedEffect
        images.take(preloadCount).forEach { preloadImage(context, it, imageHeaders, imageScripts[it]) }
    }

    // C3 横屏双页：仅左右翻页模式 + 开启横屏双页 + 设备处于横屏时生效
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val doublePage = landscapeDoublePage && pageMode == 1 && isLandscape

    // 统一菜单：顶部栏（返回 / 章节名 / 设置）+ 设置面板 + 底部章节条，
    // 三者由「点击画面」统一呼出与收起；默认隐藏，图片占满整屏。
    val menuVisible = remember { mutableStateOf(false) }
    // 设置面板是菜单的一部分：收起菜单时一并关闭，避免下次呼出时残留
    val showSettings = remember { mutableStateOf(false) }

    val toggleMenu: () -> Unit = {
        menuVisible.value = !menuVisible.value
        if (!menuVisible.value) showSettings.value = false
    }

    // 当前是否有图片处于「放大」状态：放大时锁住翻页 / 滚动，专心查看细节
    val zoomed = remember { mutableStateOf(false) }

    // C3 方向锁定：锁定后设备旋转不改变屏幕方向，退出阅读页自动恢复
    val orientationLocked = remember { mutableStateOf(false) }
    val activity = remember(context) { (context as? android.content.ContextWrapper)?.let { ctx ->
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return@let c
            c = c.baseContext
        }
        null
    } }
    androidx.compose.runtime.DisposableEffect(orientationLocked.value, activity) {
        val act = activity
        if (orientationLocked.value && act != null) {
            val current = act.resources.configuration.orientation
            act.requestedOrientation =
                if (current == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
        }
        onDispose {
            act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 应用是 edge-to-edge（targetSdk 35）绘制：系统状态栏 / 手势条会盖在内容之上。
    // 顶部栏与底部章节条必须避开系统栏区域，否则：
    // - 顶部「设置」图标上半部落在状态栏里，点它没反应（实测 y=84 无效、y=140 生效）；
    // - 底部章节条被手势条遮挡。
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 底部章节条总占位 = 条高 + 手势条高度（滚动列表的 contentPadding 要用这个值）
    val barTotalHeight = CHAPTER_BAR_HEIGHT + navBottomInset

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
            error != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BrokenImage,
                        contentDescription = null,
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = error ?: "加载失败",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        TextButton(onClick = { viewModel.retry() }) {
                            Text("重新加载", color = Color(0xFF6A9BD8))
                        }
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = onBack) {
                            Text("返回", color = Color(0xFF6A9BD8))
                        }
                    }
                }
            }
            pageMode == 0 -> {
                // 上下滚动模式
                VerticalScrollReader(
                    images = images,
                    headers = imageHeaders,
                    scripts = imageScripts,
                    chapterIndex = chapterPosition,
                    onPrevChapter = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onTap = toggleMenu,
                    bottomContentPadding = barTotalHeight,
                    zoomed = zoomed.value,
                    onZoomChanged = { zoomed.value = it },
                    preloadCount = preloadCount,
                    canPreload = canPreload,
                )
            }
            else -> {
                // 左右翻页模式
                HorizontalPagerReader(
                    images = images,
                    headers = imageHeaders,
                    scripts = imageScripts,
                    currentPage = currentPage,
                    chapterIndex = chapterPosition,
                    onPageChange = viewModel::goToPage,
                    onTap = toggleMenu,
                    onPrevChapter = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    zoomed = zoomed.value,
                    onZoomChanged = { zoomed.value = it },
                    preloadCount = preloadCount,
                    canPreload = canPreload,
                    doublePage = doublePage,
                )
            }
        }

        // ===== 边缘竖滑调亮度 / 音量（C2）：覆盖在内容之上，只在左右边缘起手时响应 =====
        ComicReaderEdgeGestures()

        // ===== 菜单 = 顶部栏 + 设置面板 + 底部章节条，由「点击画面」统一呼出 / 收起 =====
        // 此前顶部栏与底部章节条是常驻的，会一直压着图片；现在它们都属于菜单，
        // 不呼出时整屏都是图片。
        AnimatedVisibility(
            visible = menuVisible.value,
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹ 返回",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = chapterTitle,
                    color = Color.White,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSettings.value = !showSettings.value }) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
                }
            }
        }

        // 设置面板（菜单的一部分；上移整个底部章节条的高度，避免被遮挡）
        AnimatedVisibility(
            visible = menuVisible.value && showSettings.value,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = barTotalHeight),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吞掉面板内的点击，避免点空白处把菜单一起收起
                    .clickable(interactionSource = null, indication = null) { },
                color = Color(0xFF1E1E1E),
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("翻页方式", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        PageModeButton("上下滚动", pageMode == 0) { viewModel.setPageMode(0) }
                        Spacer(Modifier.width(12.dp))
                        PageModeButton("左右翻页", pageMode == 1) { viewModel.setPageMode(1) }
                    }
                    Spacer(Modifier.height(12.dp))
                    // C3 横屏双页开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "横屏双页",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "横屏时左右翻页并排展示两页",
                                color = Color(0xFF9E9E9E),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(
                            checked = landscapeDoublePage,
                            onCheckedChange = { viewModel.setLandscapeDoublePage(it) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // C3 方向锁定开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "锁定方向",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "旋转设备不改变屏幕方向，退出阅读自动恢复",
                                color = Color(0xFF9E9E9E),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(
                            checked = orientationLocked.value,
                            onCheckedChange = { orientationLocked.value = it },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "点击画面呼出 / 收起菜单；滚到本话结尾继续拖 → 下一话，滚到开头继续拖 → 上一话（左右翻页同理）",
                        color = Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // 底部章节导航条（与小说阅读器一致的「上一话 / 下一话」机制，属菜单）
        AnimatedVisibility(
            visible = menuVisible.value,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ComicChapterBar(
                chapterTitle = chapterTitle,
                chapterPosition = chapterPosition,
                chapterCount = chapterCount,
                hasPrevChapter = hasPrevChapter,
                hasNextChapter = hasNextChapter,
                onPrevChapter = { viewModel.prevChapter() },
                onNextChapter = { viewModel.nextChapter() },
            )
        }
    }
}

/**
 * 底部常驻章节导航条：`‹ 上一话 ｜ 第 x/y 话 · 话名 ｜ 下一话 ›`。
 *
 * 漫画阅读器此前的章节切换只藏在设置面板里，且滚动模式读完本话没有任何去向；
 * 这里与小说阅读器的「上一章 / 下一章」对齐，任何时候都能直接切话。
 */
@Composable
private fun ComicChapterBar(
    chapterTitle: String,
    chapterPosition: Int,
    chapterCount: Int,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = Color.White
    val unavailable = Color.White.copy(alpha = 0.3f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 背景铺满整块（含手势条区域），内容再避开系统手势条：
            // 否则章节条被手势条压住，点击命中率低
            .background(Color.Black.copy(alpha = 0.72f))
            .navigationBarsPadding()
            .height(CHAPTER_BAR_HEIGHT)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevChapter,
            enabled = hasPrevChapter,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "上一话",
                tint = if (hasPrevChapter) available else unavailable,
            )
        }
        Text(
            text = buildString {
                if (chapterCount > 0) append("第 ").append(chapterPosition + 1)
                    .append("/").append(chapterCount).append(" 话")
                if (chapterTitle.isNotBlank()) {
                    if (chapterCount > 0) append(" · ")
                    append(chapterTitle)
                }
                if (chapterCount == 0 && chapterTitle.isBlank()) append("未知章节")
            },
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onNextChapter,
            enabled = hasNextChapter,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "下一话",
                tint = if (hasNextChapter) available else unavailable,
            )
        }
    }
}

/** 上下滚动阅读（滚动时预加载后续图片；拖到底 / 顶继续拖切话） */
@Composable
private fun VerticalScrollReader(
    images: List<String>,
    headers: Map<String, String>,
    scripts: Map<String, String>,
    chapterIndex: Int,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onTap: () -> Unit,
    bottomContentPadding: Dp,
    zoomed: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    preloadCount: Int,
    canPreload: Boolean,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 切话后回到顶部：LazyListState 在同一个 Composable 实例上会保留上一话的滚动位置，
    // 不重置的话点「下一话」会直接停在新一话的末尾。
    LaunchedEffect(chapterIndex, images) {
        listState.scrollToItem(0)
    }

    // 预加载：当前可见项之后的 [preloadCount] 张
    LaunchedEffect(listState.firstVisibleItemIndex, images, headers, scripts, preloadCount, canPreload) {
        if (!canPreload) return@LaunchedEffect
        val start = listState.firstVisibleItemIndex + 1
        for (i in start until (start + preloadCount).coerceAtMost(images.size)) {
            if (i >= 0) preloadImage(context, images[i], headers, scripts[images[i]])
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // 放大查看细节时锁住滚动切话（图片平移由缩放组件自己消费，列表不再滚）
            .edgeOverscroll(
                resetKey = chapterIndex,
                vertical = true,
                canScrollBackward = { listState.canScrollBackward && !zoomed },
                canScrollForward = { listState.canScrollForward && !zoomed },
                onOverscrollStart = onPrevChapter,
                onOverscrollEnd = onNextChapter,
            )
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onTap,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = bottomContentPadding),
    ) {
        itemsIndexed(images) { _, url ->
            ZoomableComicImage(
                url = url,
                // 不再给每张图加 vertical padding：黑底会从缝里透出来，
                // 在条漫 / 分镜图上表现为一条条横向黑线。图片直接上下相接即为连续的一页。
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
                placeholderModifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                headers = headers,
                script = scripts[url],
                onZoomStateChanged = onZoomChanged,
            )
        }
    }
}

// 章末「本话已读完 / 上一话 / 下一话」引导区已移除：
// 它和「翻到底继续拖 → 下一话」（edgeOverscroll）功能重复，且会占掉一整屏的最后一段，
// 让读者以为本话只有这些内容。现在读完本话继续往下拖即可切话，入口只保留菜单里的底部章节条。

/** 左右翻页阅读（预加载相邻页；三区点击：左=上一页/上一话，中=设置，右=下一页/下一话） */
@Composable
private fun HorizontalPagerReader(
    images: List<String>,
    headers: Map<String, String>,
    scripts: Map<String, String>,
    currentPage: Int,
    chapterIndex: Int,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit,
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    zoomed: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    preloadCount: Int,
    canPreload: Boolean,
    doublePage: Boolean,
) {
    // C3 双页模式：每屏并排展示两张图（左图 = 偶数下标），页数 = ceil(n/2)。
    // currentPage 语义统一为「左侧图片下标」（单页模式即图片下标），进度/翻页逻辑不受影响。
    val pageCount = if (doublePage) (images.size + 1) / 2 else images.size
    val pageCountSafe = pageCount.coerceAtLeast(1)

    // 外部 currentPage（图片下标）→ pager 页下标
    val currentPagerPage = if (doublePage) {
        (currentPage.coerceIn(0, images.size - 1).coerceAtLeast(0)) / 2
    } else {
        currentPage.coerceIn(0, pageCountSafe - 1)
    }

    val pagerState = rememberPagerState(
        initialPage = currentPagerPage.coerceIn(0, pageCountSafe - 1),
        pageCount = { pageCountSafe },
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 同步外部 currentPage 与 pager 状态
    LaunchedEffect(currentPage, doublePage) {
        val target = if (doublePage) {
            (currentPage.coerceIn(0, images.size - 1).coerceAtLeast(0)) / 2
        } else {
            currentPage.coerceAtMost(images.size - 1).coerceAtLeast(0)
        }
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    // 用户滑动翻页时，回写 currentPage（双页模式回写左图下标）
    LaunchedEffect(pagerState.currentPage, doublePage) {
        val page = pagerState.currentPage
        val imgIdx = if (doublePage) page * 2 else page
        if (imgIdx < images.size) onPageChange(imgIdx)
    }

    // 预加载相邻页（双页模式翻一页前进两图）
    LaunchedEffect(pagerState.currentPage, images, headers, scripts, preloadCount, canPreload, doublePage) {
        if (!canPreload) return@LaunchedEffect
        val page = pagerState.currentPage
        val step = if (doublePage) 2 else 1
        val startImg = page * step
        val ahead = ((preloadCount - 1).coerceAtLeast(0)) * step
        for (i in (startImg - step)..(startImg + ahead)) {
            if (i in images.indices) preloadImage(context, images[i], headers, scripts[images[i]])
        }
    }

    HorizontalPager(
        state = pagerState,
        // 放大查看细节时禁止滑动翻页（缩放组件会自己吞掉拖动事件，这里双保险）
        userScrollEnabled = !zoomed,
        modifier = Modifier
            .fillMaxSize()
            // 末页继续拖 → 下一话；首页往前拖 → 上一话
            .edgeOverscroll(
                resetKey = chapterIndex,
                vertical = false,
                canScrollBackward = { pagerState.currentPage > 0 && !zoomed },
                canScrollForward = { pagerState.currentPage < pageCountSafe - 1 && !zoomed },
                onOverscrollStart = onPrevChapter,
                onOverscrollEnd = onNextChapter,
            ),
    ) { page ->
        val leftImg = if (doublePage) page * 2 else page
        val rightImg = if (doublePage) leftImg + 1 else -1
        Box(
            modifier = Modifier
                .fillMaxSize()
                .comicPagerTap(
                    onLeft = {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        } else {
                            onPrevChapter()
                        }
                    },
                    onCenter = onTap,
                    onRight = {
                        if (pagerState.currentPage < pageCountSafe - 1) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            onNextChapter()
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (doublePage) {
                // 双页并排：左右各占一半，各用 FillWidth 适配（保持图片原始比例）
                Row(modifier = Modifier.fillMaxSize()) {
                    ZoomableComicImage(
                        url = images.getOrNull(leftImg) ?: "",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        placeholderModifier = Modifier.fillMaxSize(),
                        headers = headers,
                        script = scripts[images.getOrNull(leftImg) ?: ""],
                        onZoomStateChanged = onZoomChanged,
                    )
                    ZoomableComicImage(
                        url = images.getOrNull(rightImg) ?: "",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        placeholderModifier = Modifier.fillMaxSize(),
                        headers = headers,
                        script = scripts[images.getOrNull(rightImg) ?: ""],
                        onZoomStateChanged = onZoomChanged,
                    )
                }
            } else {
                ZoomableComicImage(
                    url = images.getOrNull(page) ?: "",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillWidth,
                    placeholderModifier = Modifier.fillMaxSize(),
                    headers = headers,
                    script = scripts[images.getOrNull(page) ?: ""],
                    onZoomStateChanged = onZoomChanged,
                )
            }
        }
    }
}

/**
 * 翻页模式三区点击：左 1/3 上一页（首页再点→上一话）、右 1/3 下一页（末页再点→下一话）、
 * 中间呼出设置面板。
 *
 * 用 `detectTapGestures` 而非 `awaitEachGesture`：它只响应「按下即抬起」的 tap，
 * 不消费拖动事件，因此不会与 Pager 的滑动翻页抢手势。
 */
private fun Modifier.comicPagerTap(
    onLeft: () -> Unit,
    onCenter: () -> Unit,
    onRight: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onTap = { offset ->
            val w = size.width.toFloat()
            if (w <= 0f) return@detectTapGestures
            when {
                offset.x < w / 3f -> onLeft()
                offset.x > w * 2f / 3f -> onRight()
                else -> onCenter()
            }
        },
    )
}

/**
 * 「越过边界」检测：手指已经把内容推到顶 / 底之后仍然继续拖，就触发上一话 / 下一话。
 *
 * 设计要点：
 * - **只观测指针位移，从不 consume**，所以列表自身滚动、惯性、翻页都不受影响；
 * - 触发条件是「主动把内容拖过边界」，而不是「滚到末尾就切」——
 *   后者在内容不足一屏时会一加载就到底，导致连续跳章；
 * - `resetKey` 变化（切话）时清空累计位移，避免上一话的余量把新一话直接顶走。
 *
 * @param vertical true=上下滚动（看 y 轴），false=左右翻页（看 x 轴）
 * @param canScrollBackward 当前是否还能往前滚（false 表示已到顶/首屏）
 * @param canScrollForward 当前是否还能往后滚（false 表示已到底/末页）
 */
@Composable
private fun Modifier.edgeOverscroll(
    resetKey: Any?,
    vertical: Boolean,
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
    onOverscrollStart: () -> Unit,
    onOverscrollEnd: () -> Unit,
): Modifier {
    // 用 rememberUpdatedState 保证回调/状态判断始终读到最新值，
    // 而 pointerInput 的协程只启动一次（否则每帧重启手势循环，永远等不到抬手）
    val backward by rememberUpdatedState(canScrollBackward)
    val forward by rememberUpdatedState(canScrollForward)
    val overStart by rememberUpdatedState(onOverscrollStart)
    val overEnd by rememberUpdatedState(onOverscrollEnd)

    return this.pointerInput(resetKey, vertical) {
        val threshold = EDGE_OVERSCROLL_THRESHOLD.toPx()
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                var accStart = 0f
                var accEnd = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    // 手指位移：< 0 → 往「后」拖（下滚 / 右翻），> 0 → 往「前」拖
                    val delta = if (vertical) {
                        change.position.y - change.previousPosition.y
                    } else {
                        change.position.x - change.previousPosition.x
                    }
                    if (delta == 0f) continue
                    if (delta < 0f) {
                        accStart = 0f
                        if (forward()) {
                            accEnd = 0f
                        } else {
                            accEnd += -delta
                            if (accEnd >= threshold) {
                                accEnd = 0f
                                overEnd()
                            }
                        }
                    } else {
                        accEnd = 0f
                        if (backward()) {
                            accStart = 0f
                        } else {
                            accStart += delta
                            if (accStart >= threshold) {
                                accStart = 0f
                                overStart()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 漫画图片（完美适配屏幕）。
 *
 * 宽度撑满、高度按图片原始比例缩放（ContentScale.FillWidth）。
 * 加载中显示占位，加载失败显示占位 + 点击重试。
 *
 * [script] 为源给出的逐图后处理脚本（「按 N 段纵向打乱」的还原方式），
 * 命中时挂一个 Coil transformation，让**解码后、进缓存前**就把图拼回正常顺序。
 */
@Composable
internal fun ComicImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    placeholderModifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    script: String? = null,
) {
    val context = LocalContext.current
    // 重试计数：并入缓存键，点击重试时产生全新请求
    var retry by remember(url) { mutableIntStateOf(0) }
    val bands = remember(url, script) { ComicImageScramble.bandCountFromScript(script) }
    val request = remember(url, retry, headers, bands) {
        ImageRequest.Builder(context)
            .data(url)
            .apply { applyImageDefaults(url, headers) }
            .apply { applyBandUnscramble(this, bands) }
            .memoryCacheKey("$url#$retry")
            // 失败时把「原因」落到日志，便于区分 403 防盗链 / DNS / 解码失败。
            // 只记异常类名：异常消息里往往带图片地址，日志不出现具体站点。
            .listener(
                onError = { _, result ->
                    android.util.Log.w(
                        IMAGE_LOG_TAG,
                        "图片加载失败 err=${result.throwable?.javaClass?.simpleName}",
                    )
                },
            )
            .build()
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        loading = { ComicLoading(placeholderModifier) },
        error = { ComicError(placeholderModifier, onRetry = { retry++ }) },
    )
}

/** 加载中占位 */
@Composable
private fun ComicLoading(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = Color(0xFF888888),
            strokeWidth = 2.dp,
        )
    }
}

/** 加载失败占位（可点击重试） */
@Composable
private fun ComicError(
    modifier: Modifier,
    onRetry: () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = Color(0xFF888888),
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry) {
                Text("图片加载失败，点击重试", color = Color(0xFF6A9BD8))
            }
        }
    }
}

/**
 * 命中「分段乱序」还原脚本时挂上 transformation。
 *
 * 变换要读写像素，必须用软件位图（hardware bitmap 不支持变换），因此显式关掉。
 */
private fun applyBandUnscramble(builder: ImageRequest.Builder, bands: Int?) {
    if (bands == null || bands < 2) return
    builder.transformations(ReverseBandsTransformation(bands))
    builder.allowHardware(false)
}

/** 预加载单张图片（异步入队，失败静默） */
private fun preloadImage(
    context: Context,
    url: String,
    headers: Map<String, String> = emptyMap(),
    script: String? = null,
) {
    if (url.isBlank()) return
    val request = ImageRequest.Builder(context)
        .data(url)
        .apply { applyImageDefaults(url, headers) }
        .apply { applyBandUnscramble(this, ComicImageScramble.bandCountFromScript(script)) }
        // 与 ComicImage 的缓存键保持同一套（它的 retry 从 0 起），预热才真正命中
        .memoryCacheKey("$url#0")
        .build()
    context.imageLoader.enqueue(request)
}

/**
 * 给漫画图片请求补齐「浏览器级」默认头。
 *
 * 源自己声明的请求头（legado 的 `header=` / Venera 的 `headers`）优先级最高，一律不覆盖；
 * 缺失时按下面三条补齐 —— 这是大量漫画 CDN 直接返回 403 / 空体的常见原因：
 * - `User-Agent`：Coil 默认发 `okhttp/x.y`，不少图床按 UA 判断是否放行；
 * - `Referer`：源没给时退化为图片自身站点（同站点图床常据此防盗链）；
 * - `Accept`：声明可接受的图片类型，避免部分 CDN 因 Accept 不匹配返回错误页。
 */
private fun ImageRequest.Builder.applyImageDefaults(url: String, headers: Map<String, String>) {
    headers.forEach { (k, v) -> setHeader(k, v) }
    val declared = headers.keys.map { it.lowercase() }
    if ("user-agent" !in declared) setHeader("User-Agent", IMAGE_USER_AGENT)
    if ("referer" !in declared) {
        runCatching {
            val u = java.net.URI(url)
            val scheme = u.scheme
            val host = u.host
            if (scheme != null && host != null) setHeader("Referer", "$scheme://$host/")
        }
    }
    if ("accept" !in declared) {
        setHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
    }
}

/** 翻页方式选择按钮 */
@Composable
private fun PageModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color(0xFF4A6FA5) else Color(0xFF333333)
    val fg = if (selected) Color.White else Color(0xFFAAAAAA)
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

/** 图片加载日志标签（排查「图片加载失败」时用 logcat 过滤它） */
private const val IMAGE_LOG_TAG = "ComicImage"

/** 图片请求默认 UA（Coil 默认的 okhttp/x.y 会被部分图床拒绝） */
private const val IMAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** 底部章节导航条高度（正文/设置面板都据此让位） */
private val CHAPTER_BAR_HEIGHT = 48.dp

/** 越过边界触发翻话所需的累计拖拽距离（太小会误触，太大要拖很久） */
private val EDGE_OVERSCROLL_THRESHOLD = 90.dp
