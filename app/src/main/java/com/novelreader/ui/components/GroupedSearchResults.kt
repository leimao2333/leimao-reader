package com.novelreader.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.domain.model.MediaItem
import com.novelreader.source.search.FuzzyMatch

/**
 * 折叠式搜索结果列表。
 *
 * 「书名 + 作者」一致的多源结果折叠为一条聚合卡片：
 * - 折叠态：显示书名 / 作者 / 源数量徽标（如「12 个书源」），
 *   点击卡片展开组内全部源条目（即「换源」入口——换源 = 在展开列表中选择另一个源）。
 * - 展开态：逐条显示各源结果（封面 / 标题 / 来源 / 阅读按钮），可再次点击收起。
 *
 * 单条结果的组（无重复源）不显示徽标，行为与普通结果一致。
 */
@Composable
fun GroupedSearchResults(
    results: List<MediaItem>,
    isEmptyHint: String,
    onItemClick: (MediaItem) -> Unit,
    onRead: (MediaItem) -> Unit,
) {
    if (results.isEmpty()) {
        if (isEmptyHint.isNotBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = isEmptyHint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // 分组（书名+作者归一化），组内保持源返回顺序
    val groups = remember(results) { FuzzyMatch.groupDuplicates(results) }

    // 展开的组 key 集合（旋转屏/进程恢复时保留）
    var expandedKeys by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.autoSaver()) {
        mutableStateOf(emptySet<String>())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        groups.forEachIndexed { groupIndex, group ->
            val head = group.first()
            val groupKey = "${FuzzyMatch.normalize(head.title)}|${head.author?.let { FuzzyMatch.normalize(it) }.orEmpty()}"
            val isExpanded = groupKey in expandedKeys
            val hasMulti = group.size > 1

            item(key = "group_$groupIndex") {
                if (isExpanded) {
                    // ===== 展开态：组内全部源条目 =====
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                    ) {
                        group.forEach { member ->
                            MediaItemCard(
                                item = member,
                                onClick = { onItemClick(member) },
                                onRead = { onRead(member) },
                            )
                        }
                        // 收起按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedKeys = expandedKeys - groupKey }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.ExpandLess,
                                contentDescription = "收起",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "收起 ${group.size} 个书源",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                } else {
                    // ===== 折叠态：聚合卡片 =====
                    GroupCard(
                        head = head,
                        sourceCount = group.size,
                        hasMulti = hasMulti,
                        onClick = {
                            if (hasMulti) expandedKeys = expandedKeys + groupKey
                            else onItemClick(head)
                        },
                    )
                }
            }
        }
    }
}

/** 折叠态的聚合卡片：书名 / 作者 / 源数量徽标 */
@Composable
private fun GroupCard(
    head: MediaItem,
    sourceCount: Int,
    hasMulti: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 简版封面（复用占位逻辑即可，不放大图避免折叠态过重）
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 96.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (head.cover.isNullOrBlank()) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = "无封面",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    coil.compose.SubcomposeAsyncImage(
                        model = head.cover,
                        contentDescription = head.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = head.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                head.author?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (hasMulti) {
                    Text(
                        text = "$sourceCount 个书源，点击展开换源",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (head.sourceName.isNotBlank()) {
                    Text(
                        text = "来源：${head.sourceName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasMulti) {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
