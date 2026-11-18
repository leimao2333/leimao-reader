package com.novelreader.ui.screens.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.components.CoverPlaceholderLetter
import com.novelreader.ui.components.LazyCover

/**
 * 详情页「相关推荐」（C13）：同标签 / 同作者作品的一排横滑卡片。
 *
 * 为什么单独成文件：卡片只负责展示与点击，数据加载在 NovelDetailViewModel，
 * 详情页本体已经很长，拆开后两处都能独立看清。列表为空时整块不渲染，
 * 失败静默由 ViewModel 保证（见 [NovelDetailViewModel.loadRelated]）。
 */
@Composable
internal fun RelatedRecommendations(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "相关推荐",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "${it.sourceId}:${it.id}" }) { rec ->
                RelatedBookCard(item = rec, onClick = { onItemClick(rec) })
            }
        }
    }
}

/** 推荐卡片：封面 + 书名 + 作者，点击进对应作品详情 */
@Composable
private fun RelatedBookCard(item: MediaItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(96.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 128.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // 必须用 LazyCover 而非 SubcomposeAsyncImage：本卡片嵌在 LazyRow 里，
            // 两层 SubcomposeLayout 在快速滚动时会崩（measure on a deactivated node）。
            LazyCover(
                model = item.cover,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                fallback = { CoverPlaceholderLetter(Modifier.fillMaxSize(), item.title) },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val author = item.author?.takeIf { it.isNotBlank() }
        if (author != null) {
            Text(
                text = author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
