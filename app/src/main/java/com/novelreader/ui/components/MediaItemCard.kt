package com.novelreader.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 *
 * @param onRead 可选「阅读」按钮回调（小说搜索结果用）；为 null 时不显示阅读按钮。
 * @param onToggleShelf 可选「加入/移出书架」回调；为 null 时不显示收藏按钮（#22）。
 * @param inShelf 当前是否已在书架，决定收藏按钮文案与图标。
 */
@Composable
fun MediaItemCard(
    item: MediaItem,
    onClick: () -> Unit,
    onRead: (() -> Unit)? = null,
    onToggleShelf: (() -> Unit)? = null,
    inShelf: Boolean = false,
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
                // 作者 + 状态（连载中/已完结）首行
                val status = BookMeta.statusText(item)
                val authorLine = listOfNotNull(
                    item.author?.takeIf { it.isNotBlank() },
                    status,
                ).joinToString(" · ")
                if (authorLine.isNotEmpty()) {
                    Text(
                        text = authorLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 元信息行：字数 · 最新章节 · 更新时间（#23）
                val metaLine = BookMeta.summaryLine(item)
                if (metaLine.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 简介（小说等展示）
                item.desc?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 来源名
                if (item.sourceName.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "来源：${item.sourceName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 操作行：阅读（可选）+ 收藏（可选，#22 让搜索结果可直接入库，无需进详情页）
                if (onRead != null || onToggleShelf != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onRead != null) {
                            Text(
                                text = "阅读",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable(onClick = onRead)
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                        if (onRead != null && onToggleShelf != null) Spacer(Modifier.width(8.dp))
                        if (onToggleShelf != null) {
                            val tint = if (inShelf) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(
                                        tint.copy(alpha = 0.12f),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable(onClick = onToggleShelf)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    imageVector = if (inShelf) Icons.Filled.Bookmark
                                    else Icons.Filled.BookmarkBorder,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (inShelf) "已在书架" else "加入书架",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = tint,
                                )
                            }
                        }
                    }
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
            Placeholder(item.title)
        } else {
            SubcomposeAsyncImage(
                model = cover,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> Placeholder(item.title)
                    is AsyncImagePainter.State.Loading -> Placeholder(item.title)
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }
    }
}

/** 无封面占位：显示书名首字（比纯图标更有辨识度），字号随占位块大小自适应 */
@Composable
fun CoverPlaceholderLetter(modifier: Modifier = Modifier, title: String? = null) {
    val letter = remember(title) {
        title?.trim()?.firstOrNull()?.toString() ?: ""
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (letter.isEmpty()) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = "无封面",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = letter,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Placeholder(title: String? = null) {
    CoverPlaceholderLetter(Modifier.fillMaxSize(), title)
}
