package com.novelreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.domain.model.MediaItem

/**
 * 通用媒体列表项。
 *
 * 小说 / 漫画 / 视频 搜索结果共用此组件，展示封面 + 标题 + 作者。
 * 封面为空或加载失败时展示占位图。
 */
@Composable
fun MediaItemCard(
    item: MediaItem,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            CoverImage(item = item)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                item.author?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 封面图（带占位：无封面 / 加载中 / 加载失败统一显示占位图标）。
 */
@Composable
private fun CoverImage(item: MediaItem) {
    val cover = item.cover

    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 96.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (cover.isNullOrBlank()) {
            Placeholder()
        } else {
            SubcomposeAsyncImage(
                model = cover,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> Placeholder()
                    is AsyncImagePainter.State.Loading -> Placeholder()
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }
    }
}

@Composable
private fun Placeholder() {
    Icon(
        imageVector = Icons.Filled.Image,
        contentDescription = "无封面",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
