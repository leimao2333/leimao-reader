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
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.domain.model.MediaItem
import com.novelreader.source.search.FuzzyMatch
import com.novelreader.source.search.VideoTitleKey

/**
 * 折叠式搜索结果列表。
 *
 * 「书名 + 作者」一致的多源结果折叠为一条聚合卡片：
 * - 折叠态：显示书名 / 作者 / 源数量徽标（如「12 个书源」），
 *   点击卡片展开组内全部源条目（即「换源」入口——换源 = 在展开列表中选择另一个源）。
 * - 展开态：逐条显示各源结果（封面 / 标题 / 来源 / 阅读按钮），可再次点击收起。
 *
 * 单条结果的组（无重复源）不显示徽标，行为与普通结果一致。
 *
 * @param type 内容类型。决定分组键与元信息行的默认口径：
 *   小说 / 漫画按书名归一化，影视按 [VideoTitleKey.of]（剥离年份 / 画质后缀）——v1.3.7.0。
 * @param unitLabel 源数量单位文案（「书源」/「播放源」）
 * @param metaLine 元信息行生成器；默认按 [type] 自动选 [BookMeta] 或 [VideoMeta]
 * @param onRead 「阅读」按钮回调；为 null 时不显示按钮（影视点击卡片即进详情，无需此按钮）
 */
@Composable
fun GroupedSearchResults(
    results: List<MediaItem>,
    isEmptyHint: String,
    onItemClick: (MediaItem) -> Unit,
    onRead: ((MediaItem) -> Unit)? = null,
    onToggleShelf: ((MediaItem) -> Unit)? = null,
    shelfKeys: Set<String> = emptySet(),
    type: com.novelreader.domain.model.SourceType = com.novelreader.domain.model.SourceType.NOVEL,
    unitLabel: String = "书源",
    metaLine: ((MediaItem) -> String)? = null,
    keyOf: ((MediaItem) -> String)? = null,
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

    // 分组键：显式传入优先；否则按类型选默认口径
    val groupKeyOf: (MediaItem) -> String = keyOf ?: when (type) {
        com.novelreader.domain.model.SourceType.VIDEO -> { item -> VideoTitleKey.of(item.title) }
        else -> { item -> FuzzyMatch.normalize(item.title) }
    }

    // 元信息行：显式传入优先；否则按类型选默认口径
    val metaOf: (MediaItem) -> String = metaLine ?: when (type) {
        com.novelreader.domain.model.SourceType.VIDEO -> { item -> VideoMeta.summaryLine(item) }
        else -> { item -> BookMeta.summaryLine(item) }
    }

    // 分组，组内保持源返回顺序
    val groups = remember(results, groupKeyOf) { FuzzyMatch.groupDuplicates(results, groupKeyOf) }

    // 展开的组 key 集合（旋转屏/进程恢复时保留）
    var expandedKeys by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.autoSaver()) {
        mutableStateOf(emptySet<String>())
    }

    // 侧边滚动条（v1.8.2.0）：搜索结果动辄上百条，没有位置指示很难知道自己翻到哪了
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
        groups.forEachIndexed { groupIndex, group ->
            val head = group.first()
            val groupKey = groupKeyOf(head)
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
                                onRead = onRead?.let { fn -> { fn(member) } },
                                onToggleShelf = onToggleShelf?.let { fn -> { fn(member) } },
                                inShelf = "${member.sourceId}|${member.id}" in shelfKeys,
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
                                text = "收起 ${group.size} 个$unitLabel",
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
                        unitLabel = unitLabel,
                        metaOf = metaOf,
                        onClick = {
                            if (hasMulti) expandedKeys = expandedKeys + groupKey
                            else onItemClick(head)
                        },
                    )
                }
            }
        }
        }

        VerticalScrollbar(
            listState = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp),
        )
    }
}

/** 折叠态的聚合卡片：书名 / 作者 / 源数量徽标 */
@Composable
private fun GroupCard(
    head: MediaItem,
    sourceCount: Int,
    hasMulti: Boolean,
    unitLabel: String,
    metaOf: (MediaItem) -> String,
    onClick: () -> Unit,
) {
    // 扁平聚合卡片（无 Card 阴影），对齐开源阅读紧凑观感
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
                    CoverPlaceholderLetter(Modifier.fillMaxSize(), head.title)
                } else {
                    coil.compose.SubcomposeAsyncImage(
                        model = head.cover,
                        contentDescription = head.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (painter.state) {
                            is coil.compose.AsyncImagePainter.State.Error ->
                                CoverPlaceholderLetter(Modifier.fillMaxSize(), head.title)
                            is coil.compose.AsyncImagePainter.State.Loading ->
                                CoverPlaceholderLetter(Modifier.fillMaxSize(), head.title)
                            else -> SubcomposeAsyncImageContent()
                        }
                    }
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
                // 作者行：影视不显示 type_name 当作者（元信息行已有分类），
                // 只有小说 / 漫画才走「作者 · 状态」
                if (head.type != com.novelreader.domain.model.SourceType.VIDEO) {
                    val authorLine = listOfNotNull(
                        head.author?.takeIf { it.isNotBlank() },
                        BookMeta.statusText(head),
                    ).joinToString(" · ")
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
                }
                // 元信息：小说=字数·最新章节·更新时间；影视=评分·分类·年份·地区·备注
                val meta = metaOf(head)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (hasMulti) {
                    Text(
                        text = "$sourceCount 个$unitLabel，点击展开换源",
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
