package com.novelreader.ui.screens.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.components.CoverPlaceholderLetter
import com.novelreader.ui.screens.common.HomeViewModel

/**
 * 视频频道页（v1.5.0.0 从短剧频道泛化，短剧 / 电影 / 动漫三频道共用）。
 *
 * 对标红果短剧 / 各大视频 App 的频道页：
 * - 顶部标题 + 聚合状态 + 刷新按钮
 * - 横向分类 Tab（跨源聚合去重的分类名）
 * - 3 列竖版海报墙（3:4），点海报走影视详情页（看全部剧集 / 换源 / 播放）
 * - 触底自动加载该分类下一页（跨源聚合去重），空态可点按重试
 */
@Composable
fun VideoChannelScreen(
    title: String,
    state: HomeViewModel.VideoChannelState,
    onSelectCategory: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 顶栏：标题 + 聚合状态 + 刷新 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.categories.isNotEmpty()) {
                Text(
                    text = "聚合 ${state.categories.size} 个分类",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        // ===== 分类 Tab =====
        if (state.categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.categories, key = { it }) { category ->
                    FilterChip(
                        selected = state.selectedCategory == category,
                        onClick = { onSelectCategory(category) },
                        label = { Text(category) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ===== 内容 =====
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error ?: "暂无${title}内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onRefresh() },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "点一下重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            else -> {
                val gridState = rememberLazyGridState()
                val itemCount by rememberUpdatedState(state.items.size)

                // 触底预加载：滚动到最后 4 个条目时拉下一页
                LaunchedEffect(gridState) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .collect { last ->
                            if (last >= 0 && last >= itemCount - 4) onLoadMore()
                        }
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.items, key = { "${it.sourceId}:${it.id}" }) { item ->
                        ChannelPosterCard(item = item, onClick = { onOpenDetail(item) })
                    }
                    if (state.loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 频道竖版海报卡：3:4 封面 + 播放角标 + 更新进度角标 + 片名。
 *
 * 竖版海报（3:4）比影视首页的横版（2:3）更适合频道网格浏览，
 * 封面右下角放「播放」角标，左下角放更新进度（extras remarks，如「更新至第 12 集」）。
 */
@Composable
private fun ChannelPosterCard(
    item: MediaItem,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = item.cover
            if (cover.isNullOrBlank()) {
                CoverPlaceholderLetter(
                    modifier = Modifier.fillMaxSize(),
                    title = item.title,
                )
            } else {
                SubcomposeAsyncImage(
                    model = cover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Error -> CoverPlaceholderLetter(
                            modifier = Modifier.fillMaxSize(),
                            title = item.title,
                        )
                        is AsyncImagePainter.State.Loading -> CoverPlaceholderLetter(
                            modifier = Modifier.fillMaxSize(),
                            title = item.title,
                        )
                        else -> SubcomposeAsyncImageContent()
                    }
                }
            }

            // 右下角播放角标（红果短剧视觉锚点）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }

            // 备注角标（如「更新至第 12 集」）
            val badge = item.extras["remarks"].orEmpty()
            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
