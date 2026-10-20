package com.novelreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
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
 * @param onLongClick 可选长按回调（v1.8.3.0 待办 #14：长按弹出操作菜单）；为 null 时只有点击。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaItemCard(
    item: MediaItem,
    onClick: () -> Unit,
    onRead: (() -> Unit)? = null,
    onToggleShelf: (() -> Unit)? = null,
    inShelf: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    // 扁平列表项（无 Card 阴影/elevation），对齐开源阅读紧凑、流畅的观感
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // v1.8.3.0 待办 #14：combinedClickable 自带长按震动反馈，
            // 点击仍走 onClick（进详情 / 阅读），长按单独弹操作菜单，两者不冲突。
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
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

/**
 * 无封面占位：显示书名首字（比纯图标更有辨识度），字号随占位块大小自适应。
 *
 * v1.7.4.0：底色改成**按书名生成**的稳定渐变。此前所有占位块共用同一个
 * `surfaceVariant` 灰底，一整排无封面的书（不少站点列表页根本不给封面）
 * 看上去就是一片同样的灰方块，既谈不上辨识度也谈不上好看。现在同一本书
 * 每次进来都是同一个色相，相邻的书颜色不同，扫一眼能分得清。
 */
@Composable
fun CoverPlaceholderLetter(modifier: Modifier = Modifier, title: String? = null) {
    val letter = remember(title) {
        title?.trim()?.take(2) ?: ""
    }
    // 低饱和 + 高明度：做背景不能抢封面本身的注意力，也要保证深色字够清晰
    val (start, end) = remember(title) { placeholderColors(title) }
    Box(
        modifier = modifier.background(
            brush = Brush.linearGradient(listOf(start, end)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (letter.isEmpty()) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = "无封面",
                tint = PlaceholderInk,
            )
        } else {
            Text(
                text = letter,
                style = MaterialTheme.typography.titleLarge,
                color = PlaceholderInk,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 占位块上的字色：固定在浅底上足够深的墨色，不随主题的深浅漂移 */
private val PlaceholderInk = Color(0xFF3B3B3B)

/** 由书名推出一对柔和的邻近色（同一书名每次结果一致） */
private fun placeholderColors(title: String?): Pair<Color, Color> {
    val seed = (title?.trim()?.hashCode() ?: 0)
    // 取绝对值避免负号把色相压到少数几个值上；乘个奇数打散相邻 hash 的连续性
    val hue = (kotlin.math.abs(seed * 2_654_435_761L) % 360).toFloat()
    return Color.hsv(hue, 0.22f, 0.93f) to
        Color.hsv((hue + 26f) % 360f, 0.30f, 0.86f)
}

@Composable
private fun Placeholder(title: String? = null) {
    CoverPlaceholderLetter(Modifier.fillMaxSize(), title)
}
