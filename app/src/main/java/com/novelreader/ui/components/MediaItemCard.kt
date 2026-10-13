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
import androidx.compose.material3.HorizontalDivider
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
    // 扁平列表项（无 Card 阴影/elevation），对齐开源阅读紧凑、流畅的观感
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            CoverImage(item = item)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 作者 + 状态（连载中/已完结）首行；影视改为「分类 · 备注」（v1.3.7.0）
                val status = BookMeta.statusText(item)
                val authorLine = if (item.type == com.novelreader.domain.model.SourceType.VIDEO) {
                    listOfNotNull(VideoMeta.typeName(item), VideoMeta.remarks(item)).joinToString(" · ")
                } else {
                    listOfNotNull(
                        item.author?.takeIf { it.isNotBlank() },
                        status,
                    ).joinToString(" · ")
                }
                if (authorLine.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = authorLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 简介（单行，替代多行元信息，减少高度与重组开销）
                val desc = item.desc?.takeIf { it.isNotBlank() }
                if (desc != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 来源名 + 操作区（阅读/收藏）合入同一行，紧凑
                val hasSource = item.sourceName.isNotBlank()
                if (hasSource || onRead != null || onToggleShelf != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasSource) {
                            Text(
                                text = item.sourceName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (onRead != null) {
                            if (hasSource) Spacer(Modifier.width(10.dp))
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
                                    .padding(horizontal = 14.dp, vertical = 5.dp),
                            )
                        }
                        if (onToggleShelf != null) {
                            if (hasSource || onRead != null) Spacer(Modifier.width(8.dp))
                            val tint = if (inShelf) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(
                                imageVector = if (inShelf) Icons.Filled.Bookmark
                                else Icons.Filled.BookmarkBorder,
                                contentDescription = if (inShelf) "已在书架" else "加入书架",
                                tint = tint,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable(onClick = onToggleShelf)
                                    .padding(2.dp),
                            )
                        }
                    }
                }
            }
        }
        // 底部分隔线（扁平列表用 divider 替代卡片阴影）
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        )
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
