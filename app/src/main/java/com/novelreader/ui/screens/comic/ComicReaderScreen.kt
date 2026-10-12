package com.novelreader.ui.screens.comic

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem

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

    LaunchedEffect(item, chapter) {
        viewModel.init(item, chapters, chapter)
    }

    // 进入/切换章节后，先预热前几张图片
    val context = LocalContext.current
    LaunchedEffect(images) {
        images.take(PRELOAD_INITIAL).forEach { preloadImage(context, it) }
    }

    // 是否显示设置面板
    val showSettings = remember { mutableStateOf(false) }

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
                    onTap = { showSettings.value = !showSettings.value },
                )
            }
            else -> {
                // 左右翻页模式
                HorizontalPagerReader(
                    images = images,
                    currentPage = currentPage,
                    onPageChange = viewModel::goToPage,
                    onTap = { showSettings.value = !showSettings.value },
                    onNextChapter = { viewModel.nextChapter() },
                )
            }
        }

        // 顶部栏
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
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

        // 设置面板
        if (showSettings.value) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
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
                    Spacer(Modifier.height(16.dp))
                    Text("章节", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.prevChapter() }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "上一话", tint = Color.White)
                        }
                        Text(
                            text = chapterTitle.ifBlank { "未知章节" },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.nextChapter() }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "下一话", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** 上下滚动阅读（滚动时预加载后续图片） */
@Composable
private fun VerticalScrollReader(
    images: List<String>,
    onTap: () -> Unit,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 预加载：当前可见项之后的 [PRELOAD_AHEAD] 张
    LaunchedEffect(listState.firstVisibleItemIndex, images) {
        val start = listState.firstVisibleItemIndex + 1
        for (i in start until (start + PRELOAD_AHEAD).coerceAtMost(images.size)) {
            if (i >= 0) preloadImage(context, images[i])
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onTap,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
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
            )
        }
    }
}

/** 左右翻页阅读（预加载相邻页） */
@Composable
private fun HorizontalPagerReader(
    images: List<String>,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit,
    onNextChapter: () -> Unit = {},
) {
    // 章末附加一页引导页：最后一话图片滑完后还能继续翻入下一话
    val hasNextGate = images.isNotEmpty()
    val pageCount = images.size + if (hasNextGate) 1 else 0
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceAtMost((pageCount - 1).coerceAtLeast(0)),
        pageCount = { pageCount },
    )
    val context = LocalContext.current

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

    // 滑到章末引导页时进入下一话（每话只触发一次，防连跳）
    var gateConsumed by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pagerState.currentPage, images, currentPage) {
        if (hasNextGate && pagerState.currentPage == images.size && gateConsumed != currentPage) {
            gateConsumed = currentPage
            onNextChapter()
        }
    }

    // 预加载相邻页
    LaunchedEffect(pagerState.currentPage, images) {
        val cur = pagerState.currentPage
        for (i in (cur - 1)..(cur + 1)) {
            if (i in images.indices) preloadImage(context, images[i])
        }
    }

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
            contentAlignment = Alignment.Center,
        ) {
            if (page >= images.size) {
                // 章末引导页：本话已读完，继续滑动进入下一话
                Text(
                    text = "本话已读完 · 继续翻页进入下一话 ›",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else ComicImage(
                url = images.getOrNull(page) ?: "",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth,
                placeholderModifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 漫画图片（完美适配屏幕）。
 *
 * 宽度撑满、高度按图片原始比例缩放（ContentScale.FillWidth）。
 * 加载中显示占位，加载失败显示占位 + 点击重试。
 */
@Composable
private fun ComicImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    placeholderModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 重试计数：并入缓存键，点击重试时产生全新请求
    var retry by remember(url) { mutableIntStateOf(0) }
    val request = remember(url, retry) {
        ImageRequest.Builder(context)
            .data(url)
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

/** 预加载单张图片（异步入队，失败静默） */
private fun preloadImage(context: Context, url: String) {
    if (url.isBlank()) return
    val request = ImageRequest.Builder(context)
        .data(url)
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
