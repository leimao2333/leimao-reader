package com.novelreader.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
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
 * @param onToggleShelf 收藏（加入/移出书架）回调；为 null 时不显示收藏按钮
 * @param shelfKeys 已在书架的条目 key 集合（`"sourceId|itemId"`）
 * @param longPressEnabled 长按是否弹操作菜单（v1.8.3.0 待办 #14，设置里可关）
 * @param grid 结果展示方式（v1.8.6.0 待办 #26）：false = 列表（默认），true = 宫格。
 *   宫格里格子撑不开，多源组改为**点击弹换源面板**，语义与列表态的「就地展开」一致。
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
    longPressEnabled: Boolean = true,
    grid: Boolean = false,
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

    // 长按操作菜单的目标条目（v1.8.3.0 待办 #14）；groupSize 用于多源聚合卡片补「展开换源」
    var actionTarget by remember { mutableStateOf<MediaItem?>(null) }
    var actionGroupSize by remember { mutableStateOf(1) }
    var actionGroupKey by remember { mutableStateOf<String?>(null) }

    // 网格态换源的组（v1.8.6.0 待办 #26）：格子里没法就地展开，改成弹层
    var sheetGroup by remember { mutableStateOf<List<MediaItem>?>(null) }

    /** 统一的收藏动作：先关菜单再执行，避免列表即时刷新时菜单还压在上面 */
    val toggleShelfAndDismiss: ((MediaItem) -> Unit)? = onToggleShelf?.let { fn ->
        { item -> actionTarget = null; fn(item) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (grid) {
            GridResults(
                groups = groups,
                groupKeyOf = groupKeyOf,
                metaOf = metaOf,
                shelfKeys = shelfKeys,
                longPressEnabled = longPressEnabled,
                onItemClick = onItemClick,
                onRead = onRead,
                onToggleShelf = onToggleShelf,
                onLongClickTarget = { member, size, key ->
                    actionTarget = member
                    actionGroupSize = size
                    actionGroupKey = key
                },
                onExpandGroup = { sheetGroup = it },
                unitLabel = unitLabel,
            )
        } else {
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
                                onLongClick = if (longPressEnabled) {
                                    {
                                        actionTarget = member
                                        actionGroupSize = group.size
                                        actionGroupKey = groupKey
                                    }
                                } else null,
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
                        onLongClick = if (longPressEnabled) {
                            {
                                actionTarget = head
                                actionGroupSize = group.size
                                actionGroupKey = groupKey
                            }
                        } else null,
                        // 折叠态也给收藏入口（v1.8.3.0 待办 #15）：绝大多数结果都是
                        // 折叠卡片，只在展开行里放收藏图标等于「默认看不见」。
                        inShelf = "${head.sourceId}|${head.id}" in shelfKeys,
                        onToggleShelf = onToggleShelf?.let { fn -> { fn(head) } },
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

        // ===== 网格态换源面板（v1.8.6.0 待办 #26）=====
        val sheet = sheetGroup
        if (sheet != null) {
            GridSourceSheet(
                group = sheet,
                shelfKeys = shelfKeys,
                onItemClick = { member -> sheetGroup = null; onItemClick(member) },
                onRead = onRead?.let { fn -> { member -> sheetGroup = null; fn(member) } },
                onToggleShelf = onToggleShelf,
                onLongClick = if (longPressEnabled) {
                    { member ->
                        actionTarget = member
                        actionGroupSize = sheet.size
                        actionGroupKey = groupKeyOf(member)
                    }
                } else null,
                onDismiss = { sheetGroup = null },
            )
        }

        // ===== 长按操作菜单（v1.8.3.0 待办 #14）=====
        val target = actionTarget
        if (target != null) {
            SearchResultActionSheet(
                item = target,
                inShelf = "${target.sourceId}|${target.id}" in shelfKeys,
                isVideo = type == com.novelreader.domain.model.SourceType.VIDEO,
                groupSize = actionGroupSize,
                unitLabel = unitLabel,
                onExpandGroup = actionGroupKey?.let { key ->
                    { actionTarget = null; expandedKeys = expandedKeys + key }
                },
                onToggleShelf = toggleShelfAndDismiss?.let { fn -> { fn(target) } },
                onRead = onRead?.let { fn -> { actionTarget = null; fn(target) } },
                onOpenDetail = { actionTarget = null; onItemClick(target) },
                onDismiss = { actionTarget = null },
            )
        }
    }
}

/** 折叠态的聚合卡片：书名 / 作者 / 源数量徽标 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(
    head: MediaItem,
    sourceCount: Int,
    hasMulti: Boolean,
    unitLabel: String,
    metaOf: (MediaItem) -> String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    inShelf: Boolean = false,
    onToggleShelf: (() -> Unit)? = null,
) {
    // 扁平聚合卡片（无 Card 阴影），对齐开源阅读紧凑观感
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
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
        if (onToggleShelf != null) {
            Icon(
                imageVector = if (inShelf) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (inShelf) "已收藏" else "收藏",
                tint = if (inShelf) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onToggleShelf)
                    .padding(2.dp),
            )
            if (hasMulti) Spacer(Modifier.width(6.dp))
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

/**
 * 宫格态搜索结果（v1.8.6.0 待办 #26）。
 *
 * 两列网格，每个格子 = 一个聚合组（与列表态的折叠卡片一一对应）：
 * - 单源组：点击直接进（阅读 / 播放 / 详情，与列表态一致）；
 * - 多源组：点击弹换源面板 [GridSourceSheet] —— 格子里没有就地展开的空间；
 * - 长按：与列表态同一套操作菜单（由调用方挂 [SearchResultActionSheet]）；
 * - 封面右上角常驻收藏入口（折叠态聚合卡也有，见 GroupCard）。
 */
@Composable
private fun GridResults(
    groups: List<List<MediaItem>>,
    groupKeyOf: (MediaItem) -> String,
    metaOf: (MediaItem) -> String,
    shelfKeys: Set<String>,
    longPressEnabled: Boolean,
    onItemClick: (MediaItem) -> Unit,
    onRead: ((MediaItem) -> Unit)?,
    onToggleShelf: ((MediaItem) -> Unit)?,
    onLongClickTarget: (MediaItem, Int, String) -> Unit,
    onExpandGroup: (List<MediaItem>) -> Unit,
    unitLabel: String,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(
            groups,
            key = { group -> "grid_${groupKeyOf(group.first())}" },
        ) { group ->
            GridResultCell(
                head = group.first(),
                sourceCount = group.size,
                unitLabel = unitLabel,
                metaOf = metaOf,
                inShelf = "${group.first().sourceId}|${group.first().id}" in shelfKeys,
                longPressEnabled = longPressEnabled,
                onClick = {
                    if (group.size > 1) onExpandGroup(group) else onItemClick(group.first())
                },
                onLongClick = if (longPressEnabled) {
                    {
                        onLongClickTarget(
                            group.first(),
                            group.size,
                            groupKeyOf(group.first()),
                        )
                    }
                } else null,
                onToggleShelf = onToggleShelf?.let { fn -> { fn(group.first()) } },
            )
        }
    }
}

/** 宫格里的一个格子：封面（多源徽标 + 收藏角标）+ 标题 + 来源行 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GridResultCell(
    head: MediaItem,
    sourceCount: Int,
    unitLabel: String,
    metaOf: (MediaItem) -> String,
    inShelf: Boolean,
    longPressEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onToggleShelf: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
            // 多源徽标：盖在封面左下，带半透明底保证白底封面也看得清
            if (sourceCount > 1) {
                Text(
                    text = "$sourceCount $unitLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            // 收藏角标：列表态折叠卡也有同样入口，格子不能少
            if (onToggleShelf != null) {
                Icon(
                    imageVector = if (inShelf) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (inShelf) "已收藏" else "收藏",
                    tint = if (inShelf) MaterialTheme.colorScheme.primary
                    else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clickable(onClick = onToggleShelf)
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = head.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // 来源 / 元信息一行：单源时有用（认得出是哪个站），多源时徽标已经说了
        if (sourceCount <= 1) {
            val line = head.sourceName.takeIf { it.isNotBlank() } ?: metaOf(head)
            if (line.isNotBlank()) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 宫格态换源面板（v1.8.6.0 待办 #26）。
 *
 * 格子里没法「就地展开」，把组内全部源列进底部面板：点成员直接进（阅读 / 播放），
 * 可收藏、可长按（长按菜单由调用方挂到列表态同一套 [SearchResultActionSheet]）。
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
private fun GridSourceSheet(
    group: List<MediaItem>,
    shelfKeys: Set<String>,
    onItemClick: (MediaItem) -> Unit,
    onRead: ((MediaItem) -> Unit)?,
    onToggleShelf: ((MediaItem) -> Unit)?,
    onLongClick: ((MediaItem) -> Unit)?,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = "选择来源 · ${group.size} 个",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            androidx.compose.material3.HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
            group.forEach { member ->
                val inShelf = "${member.sourceId}|${member.id}" in shelfKeys
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onItemClick(member) },
                            onLongClick = onLongClick?.let { fn -> { fn(member) } },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 60.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (member.cover.isNullOrBlank()) {
                            CoverPlaceholderLetter(Modifier.fillMaxSize(), member.title)
                        } else {
                            coil.compose.SubcomposeAsyncImage(
                                model = member.cover,
                                contentDescription = member.title,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                when (painter.state) {
                                    is coil.compose.AsyncImagePainter.State.Error ->
                                        CoverPlaceholderLetter(Modifier.fillMaxSize(), member.title)
                                    is coil.compose.AsyncImagePainter.State.Loading ->
                                        CoverPlaceholderLetter(Modifier.fillMaxSize(), member.title)
                                    else -> SubcomposeAsyncImageContent()
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = member.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = member.sourceName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (onToggleShelf != null) {
                        IconButton(onClick = { onToggleShelf(member) }) {
                            Icon(
                                imageVector = if (inShelf) Icons.Filled.Bookmark
                                else Icons.Filled.BookmarkBorder,
                                contentDescription = if (inShelf) "已收藏" else "收藏",
                                tint = if (inShelf) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onRead != null) {
                        IconButton(onClick = { onRead(member) }) {
                            Icon(
                                imageVector = Icons.Filled.MenuBook,
                                contentDescription = "阅读",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 长按搜索结果弹出的操作菜单（v1.8.3.0 待办 #14）。
 *
 * 长按与点击刻意分开：点卡片仍然进详情 / 阅读，长按才出这层菜单，
 * 想收藏不必先点开再退回来。菜单只列当前页实际支持的动作
 * （没有收藏能力的列表不显示收藏项）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultActionSheet(
    item: MediaItem,
    inShelf: Boolean,
    isVideo: Boolean,
    groupSize: Int,
    unitLabel: String,
    onExpandGroup: (() -> Unit)?,
    onToggleShelf: (() -> Unit)?,
    onRead: (() -> Unit)?,
    onOpenDetail: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            // 标题行：让用户确认长按的是哪一条（聚合组里可能是多个源中的某一个）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(
                        item.author?.takeIf { it.isNotBlank() },
                        item.sourceName.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
            if (onToggleShelf != null) {
                SheetActionRow(
                    icon = if (inShelf) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    label = when {
                        isVideo && inShelf -> "取消收藏"
                        isVideo -> "加入收藏"
                        inShelf -> "移出书架"
                        else -> "加入书架"
                    },
                    onClick = onToggleShelf,
                )
            }
            if (onRead != null) {
                SheetActionRow(
                    icon = Icons.Filled.MenuBook,
                    label = if (isVideo) "播放" else "阅读",
                    onClick = onRead,
                )
            }
            SheetActionRow(
                icon = Icons.Filled.OpenInNew,
                label = "查看详情",
                onClick = onOpenDetail,
            )
            if (groupSize > 1 && onExpandGroup != null) {
                SheetActionRow(
                    icon = Icons.Filled.ExpandMore,
                    label = "展开 $groupSize 个${unitLabel}换源",
                    onClick = onExpandGroup,
                )
            }
        }
    }
}

/** 菜单里的一行操作：图标 + 文案，整行可点 */
@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
