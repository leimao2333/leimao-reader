package com.novelreader.ui.screens.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem

/**
 * 漫画阅读页。
 *
 * 核心功能：
 * - 图片完美适配屏幕（宽度撑满、按比例缩放）
 * - 两种翻页方式：上下滚动 / 左右翻页
 * - 点击屏幕呼出设置面板
 * - 上一话 / 下一话
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

    // 是否显示设置面板
    val showSettings = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

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
                Text(
                    text = error ?: "加载失败",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
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
                }
            }
        }
    }
}

/** 上下滚动阅读 */
@Composable
private fun VerticalScrollReader(
    images: List<String>,
    onTap: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onTap,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(images) { index, url ->
            ComicImage(
                url = url,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
            )
        }
    }
}

/** 左右翻页阅读 */
@Composable
private fun HorizontalPagerReader(
    images: List<String>,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = currentPage,
        pageCount = { images.size },
    )

    // 同步外部 currentPage 与 pager 状态
    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) {
            pagerState.animateScrollToPage(currentPage)
        }
    }

    // 用户滑动翻页时，回写 currentPage
    LaunchedEffect(pagerState.currentPage) {
        onPageChange(pagerState.currentPage)
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
            ComicImage(
                url = images.getOrNull(page) ?: "",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 漫画图片（完美适配屏幕）。
 *
 * 宽度撑满、高度按图片原始比例缩放，ContentScale.FitWidth。
 * 加载中显示占位。
 */
@Composable
private fun ComicImage(
    url: String,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillWidth,
    )
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
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
