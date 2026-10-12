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

    LaunchedEffect(item, chapter) {
        viewModel.init(item, chapters, chapter)
    }

    // 进入/切换章节后，先预热前几张图片
    val context = LocalContext.current
    LaunchedEffect(images, imageHeaders, imageScripts) {
        images.take(PRELOAD_INITIAL).forEach { preloadImage(context, it, imageHeaders, imageScripts[it]) }
    }

    // 统一菜单：顶部栏（返回 / 章节名 / 设置）+ 设置面板 + 底部章节条，
    // 三者由「点击画面」统一呼出与收起；默认隐藏，图片占满整屏。
    val menuVisible = remember { mutableStateOf(false) }
    // 设置面板是菜单的一部分：收起菜单时一并关闭，避免下次呼出时残留
    val showSettings = remember { mutableStateOf(false) }

    val toggleMenu: () -> Unit = {
        menuVisible.value = !menuVisible.value
        if (!menuVisible.value) showSettings.value = false
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
                    hasPrevChapter = hasPrevChapter,
                    hasNextChapter = hasNextChapter,
                    onPrevChapter = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onTap = toggleMenu,
                    bottomContentPadding = barTotalHeight,
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
                )
            }
        }

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

/** 上下滚动阅读（滚动时预加载后续图片；章末给出下一话入口） */
@Composable
private fun VerticalScrollReader(
    images: List<String>,
    headers: Map<String, String>,
    scripts: Map<String, String>,
    chapterIndex: Int,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onTap: () -> Unit,
    bottomContentPadding: Dp,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 切话后回到顶部：LazyListState 在同一个 Composable 实例上会保留上一话的滚动位置，
    // 不重置的话点「下一话」会直接停在新一话的末尾。
    LaunchedEffect(chapterIndex, images) {
        listState.scrollToItem(0)
    }

    // 预加载：当前可见项之后的 [PRELOAD_AHEAD] 张
    LaunchedEffect(listState.firstVisibleItemIndex, images, headers, scripts) {
        val start = listState.firstVisibleItemIndex + 1
        for (i in start until (start + PRELOAD_AHEAD).coerceAtMost(images.size)) {
            if (i >= 0) preloadImage(context, images[i], headers, scripts[images[i]])
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // 滚到底继续拖 → 下一话；滚到顶继续拖 → 上一话
            .edgeOverscroll(
                resetKey = chapterIndex,
                vertical = true,
                canScrollBackward = { listState.canScrollBackward },
                canScrollForward = { listState.canScrollForward },
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
            ComicImage(
                url = url,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                contentScale = ContentScale.FillWidth,
                placeholderModifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                headers = headers,
                script = scripts[url],
            )
        }
        // 章末：本话读完与去向（小说滚动模式同款「下一章 ›」入口）
        if (images.isNotEmpty()) {
            item {
                ChapterEndFooter(
                    hasPrevChapter = hasPrevChapter,
                    hasNextChapter = hasNextChapter,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter,
                )
            }
        }
    }
}

/** 章末去向区：上一话 / 下一话（到边界时给出明确提示，不静默无响应） */
@Composable
private fun ChapterEndFooter(
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (hasNextChapter) "本话已读完" else "已是最后一话",
            color = Color(0xFF9E9E9E),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChapterEndButton(
                text = "‹ 上一话",
                enabled = hasPrevChapter,
                onClick = onPrevChapter,
            )
            Spacer(Modifier.width(16.dp))
            ChapterEndButton(
                text = "下一话 ›",
                enabled = hasNextChapter,
                highlight = true,
                onClick = onNextChapter,
            )
        }
    }
}

/** 章末按钮（主按钮高亮，到边界置灰不可点） */
@Composable
private fun ChapterEndButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    val bg = when {
        !enabled -> Color(0xFF2A2A2A)
        highlight -> Color(0xFF4A6FA5)
        else -> Color(0xFF333333)
    }
    val fg = if (enabled) Color.White else Color(0xFF7A7A7A)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

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
) {
    // 不再额外插「章末引导页」：末页继续拖 / 首页往前拖 由 edgeOverscroll 直接切话，
    // 与滚动模式的边界行为保持一致
    val pageCount = images.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 同步外部 currentPage 与 pager 状态
    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage.coerceAtMost(images.size - 1).coerceAtLeast(0)) {
            pagerState.animateScrollToPage(currentPage.coerceAtMost(images.size - 1).coerceAtLeast(0))
        }
    }

    // 用户滑动翻页时，回写 currentPage（引导页不计入图片页码）
    LaunchedEffect(pagerState.currentPage) {
        val page = pagerState.currentPage
        if (page < images.size) onPageChange(page)
    }

    // 预加载相邻页
    LaunchedEffect(pagerState.currentPage, images, headers, scripts) {
        val cur = pagerState.currentPage
        for (i in (cur - 1)..(cur + 1)) {
            if (i in images.indices) preloadImage(context, images[i], headers, scripts[images[i]])
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            // 末页继续拖 → 下一话；首页往前拖 → 上一话
            .edgeOverscroll(
                resetKey = chapterIndex,
                vertical = false,
                canScrollBackward = { pagerState.currentPage > 0 },
                canScrollForward = { pagerState.currentPage < pageCount - 1 },
                onOverscrollStart = onPrevChapter,
                onOverscrollEnd = onNextChapter,
            ),
    ) { page ->
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
                        if (pagerState.currentPage < images.size - 1) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            onNextChapter()
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            ComicImage(
                url = images.getOrNull(page) ?: "",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth,
                placeholderModifier = Modifier.fillMaxSize(),
                headers = headers,
                script = scripts[images.getOrNull(page) ?: ""],
            )
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
 * [script] 为源给出的逐图后处理脚本（禁漫等站的「按 N 段纵向打乱」还原），
 * 命中时挂一个 Coil transformation，让**解码后、进缓存前**就把图拼回正常顺序。
 */
@Composable
private fun ComicImage(
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
            // 防盗链请求头（Referer 等）：漫画 CDN 缺它会 403
            .apply { headers.forEach { (k, v) -> setHeader(k, v) } }
            .apply { applyBandUnscramble(this, bands) }
            .memoryCacheKey("$url#$retry")
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
        .apply { headers.forEach { (k, v) -> setHeader(k, v) } }
        .apply { applyBandUnscramble(this, ComicImageScramble.bandCountFromScript(script)) }
        // 与 ComicImage 的缓存键保持同一套（它的 retry 从 0 起），预热才真正命中
        .memoryCacheKey("$url#0")
        .build()
    context.imageLoader.enqueue(request)
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

/** 预加载常量 */
private const val PRELOAD_INITIAL = 4
private const val PRELOAD_AHEAD = 2

/** 底部章节导航条高度（正文/设置面板都据此让位） */
private val CHAPTER_BAR_HEIGHT = 48.dp

/** 越过边界触发翻话所需的累计拖拽距离（太小会误触，太大要拖很久） */
private val EDGE_OVERSCROLL_THRESHOLD = 90.dp
