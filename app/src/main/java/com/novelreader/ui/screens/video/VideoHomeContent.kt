package com.novelreader.ui.screens.video

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.core.util.VideoFilters
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceInfo
import com.novelreader.ui.components.CoverPlaceholderLetter
import com.novelreader.ui.screens.common.HomeViewModel
import kotlinx.coroutines.launch

/**
 * 影视首页内容区（v1.3.3.0 初版，v1.3.4.0 改为 MoonTV 式多分区）。
 *
 * 两类视图共用本组件，靠 [HomeViewModel.VideoHomeState.expanded] 分流：
 * - **分区视图**：继续观看 + 各一级分类各一排横滑，排头右侧「查看更多 ›」
 * - **网格视图**：单个分类的海报墙 + 触底分页（点「查看更多」或分类 chip 进入）
 *
 * 对标 MoonTV 首页的「继续观看 + 热门电影 / 热门剧集 / 热门综艺」结构，
 * 差别在于分区来自采集站的一级分类，而非豆瓣预设片单。
 */
@Composable
fun VideoHomeContent(
    state: HomeViewModel.VideoHomeState,
    onSelectCategory: (String) -> Unit,
    onSelectArea: (String) -> Unit,
    onSelectYear: (String) -> Unit,
    onSelectOrder: (String) -> Unit,
    onExpandCategory: (String, String) -> Unit,
    onCollapseCategory: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenSearch: () -> Unit,
    /** 长按「继续观看」海报删除该条观看记录（v1.5.10.0 历史编辑） */
    onRemoveContinue: ((MediaItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.expanded) {
            VideoGridContent(
                state = state,
                onBack = onCollapseCategory,
                onSelectCategory = onSelectCategory,
                onSelectArea = onSelectArea,
                onSelectYear = onSelectYear,
                onSelectOrder = onSelectOrder,
                onItemClick = onItemClick,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onOpenSearch = onOpenSearch,
            )
        } else {
            VideoSectionsContent(
                state = state,
                onExpandCategory = onExpandCategory,
                onItemClick = onItemClick,
                onRefresh = onRefresh,
                onOpenSearch = onOpenSearch,
                onRemoveContinue = onRemoveContinue,
            )
        }
    }
}

// ============================== 分区视图 ==============================

@Composable
private fun VideoSectionsContent(
    state: HomeViewModel.VideoHomeState,
    onExpandCategory: (String, String) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onRemoveContinue: ((MediaItem) -> Unit)? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            SourceHeaderRow(
                state = state,
                onRefresh = onRefresh,
                onOpenSearch = onOpenSearch,
            )
        }

        // 非致命提示（如「已自动切换到某源」）：有内容时也显示，随下次手动换源清除
        state.notice?.let { notice ->
            item(key = "notice") {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        val error = state.error
        when {
            // 加载中但已有内容：保留旧内容继续展示（v1.5.7.0），骨架屏只在真空白时出现，
            // 否则一刷新页面就被顶成灰卡——看起来像「每次进入视频页面都是空白」
            state.loading && state.rows.isEmpty() -> {
                items(count = 2, key = { "skeleton-$it" }) {
                    SectionSkeleton()
                }
            }

            state.rows.isEmpty() -> {
                item(key = "empty") { EmptyHint(error ?: "暂无内容") }
            }

            else -> {
                if (state.continueWatching.isNotEmpty()) {
                    item(key = "continue") {
                        SectionHeader(title = "继续观看")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                state.continueWatching,
                                key = { "${it.item.sourceId}:${it.item.id}" },
                            ) { cont ->
                                // 长按删除观看记录（v1.5.10.0 历史编辑）
                                PosterCard(
                                    item = cont.item,
                                    width = ROW_CARD_WIDTH,
                                    episodeLabel = cont.episodeLabel,
                                    progress = cont.progress,
                                    onClick = { onItemClick(cont.item) },
                                    onLongClick = onRemoveContinue?.let { remove ->
                                        { remove(cont.item) }
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }

                // key 用「分类id+标题」保证唯一：聚合模式下多个源的分类 url 可能重复
                // （如空 url / 相同分类地址），此前只用 categoryId 会撞 key 导致闪退
                items(state.rows, key = { "${it.categoryId}::${it.title}" }) { row ->
                    Column {
                        SectionHeader(
                            title = row.title,
                            onMore = { onExpandCategory(row.categoryId, row.title) },
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(row.items, key = { "${it.sourceId}:${it.id}" }) { item ->
                                PosterCard(
                                    item = item,
                                    width = ROW_CARD_WIDTH,
                                    onClick = { onItemClick(item) },
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

/** 分区标题行：标题 + 右侧「查看更多 ›」 */
@Composable
private fun SectionHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onMore)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "查看更多",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 顶部：搜索入口 + 聚合源状态 + 刷新 */
@Composable
private fun SourceHeaderRow(
    state: HomeViewModel.VideoHomeState,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Icon(
            Icons.Filled.Search,
            contentDescription = "搜索",
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onOpenSearch),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        // 聚合模式：显示「聚合 N 个源」；不再提供手动选单源（死源比例高，聚合天然跳过）
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val aliveCount = state.availableSources.size
            Text(
                text = if (aliveCount > 0) "聚合 $aliveCount 个源" else "正在探测视频源…",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 有内容时的静默刷新指示：细圈代替整页骨架（v1.5.7.0）
            if (state.loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
    }
}

// ============================== 网格视图 ==============================

@Composable
private fun VideoGridContent(
    state: HomeViewModel.VideoHomeState,
    onBack: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectArea: (String) -> Unit,
    onSelectYear: (String) -> Unit,
    onSelectOrder: (String) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val itemCount by rememberUpdatedState(state.items.size)

    // 触底分页：滚动到最后 4 个条目时预取下一页。
    // 必须以「用户真的滚动过」为前提（v1.5.7.0）：从详情页返回 / 切 Tab 回来时
    // LazyGridState 恢复到原位，此前初始可见索引一满足条件就自动翻页，
    // 内容凭空变多、白耗请求，也违背「加载一次之后固定」。
    LaunchedEffect(gridState) {
        var userScrolled = false
        launch {
            gridState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) userScrolled = true
            }
        }
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (userScrolled && last >= 0 && last >= itemCount - 4) onLoadMore()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SourceHeaderRow(
            state = state,
            onRefresh = onRefresh,
            onOpenSearch = onOpenSearch,
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        Text(
            text = state.expandedTitle.ifBlank { "全部" },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        )

        // 分类 / 地区 / 年份 / 排序四个维度统一下拉（v1.5.10.0：分类从横滑 chips 改为
        // 下拉框 —— 分类一多横滑要划很久，下拉一步直达）
        FilterDropdownRow(
            categories = state.categories,
            selectedCategory = state.selectedCategory,
            onSelectCategory = onSelectCategory,
            selectedArea = state.selectedArea,
            selectedYear = state.selectedYear,
            selectedOrder = state.selectedOrder,
            onSelectArea = onSelectArea,
            onSelectYear = onSelectYear,
            onSelectOrder = onSelectOrder,
        )
        Spacer(Modifier.height(8.dp))

        when {
            state.loading && state.items.isEmpty() -> {
                GridSkeleton()
            }

            state.items.isEmpty() -> {
                EmptyHint(state.error ?: "暂无内容")
            }

            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.items, key = { "${it.sourceId}:${it.id}" }) { item ->
                        PosterCard(
                            item = item,
                            width = null,
                            onClick = { onItemClick(item) },
                        )
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

// ============================== 筛选栏 ==============================

/**
 * 片库筛选行：分类 / 地区 / 年份 / 排序（v1.5.10.0 起分类也用下拉）。
 *
 * 年份按**当前年**动态生成，不写死列表 —— 否则过几年就选不到最新年份了。
 */
@Composable
private fun FilterDropdownRow(
    categories: List<com.novelreader.domain.model.ExploreCategory>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    selectedArea: String,
    selectedYear: String,
    selectedOrder: String,
    onSelectArea: (String) -> Unit,
    onSelectYear: (String) -> Unit,
    onSelectOrder: (String) -> Unit,
) {
    val areaOptions = remember { VideoFilters.areas() }
    val yearOptions = remember {
        VideoFilters.years(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
    }
    val orderOptions = remember { VideoFilters.orders() }
    // 分类选项：「全部」（空 url）+ 各聚合分类；同名不同 url 的分类去重保序
    val categoryOptions = remember(categories) {
        buildList {
            add(VideoFilters.ALL)
            val seen = HashSet<String>()
            categories.forEach { cat ->
                if (cat.title.isNotBlank() && seen.add(cat.title)) {
                    add(VideoFilters.Option(cat.title, cat.url))
                }
            }
        }
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (categoryOptions.size > 1) {
            item { FilterDropdown("分类", categoryOptions, selectedCategory, onSelectCategory) }
        }
        item { FilterDropdown("地区", areaOptions, selectedArea, onSelectArea) }
        item { FilterDropdown("年份", yearOptions, selectedYear, onSelectYear) }
        item { FilterDropdown("排序", orderOptions, selectedOrder, onSelectOrder) }
    }
}

/**
 * 单个筛选下拉。
 *
 * 未筛选时按钮显示维度名（「地区」/「年份」/「排序」），已筛选时用 primaryContainer
 * 高亮并显示所选值 —— 这样一眼能看出当前生效了哪几维筛选。
 */
@Composable
private fun FilterDropdown(
    label: String,
    options: List<VideoFilters.Option>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val active = selected.isNotBlank()
    val container =
        if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(container)
                .clickable { open = true }
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = VideoFilters.labelOf(options, selected, label),
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        open = false
                        // 重选当前项不必再请求一次（ViewModel 里也有幂等判断，这里少一次数据流抖动）
                        if (option.value != selected) onSelect(option.value)
                    },
                    trailingIcon = {
                        if (option.value == selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

// ============================== 公共零件 ==============================

/** 分区横滑卡片宽度（2:3 后约 152dp 高，一屏能看到 3 张半，对齐 MoonTV 手机端） */
private val ROW_CARD_WIDTH = 104.dp

/**
 * 海报卡：2:3 封面 + 一行标题 + 角标。
 *
 * 角标优先级：集数/备注（右下）> 评分（右上）。
 * [progress] > 0 时在封面下沿画一条时间进度条（「继续观看」用）。
 *
 * @param width 固定宽度（分区横滑用）；传 null 表示撑满父容器（网格用）
 */
@Composable
private fun PosterCard(
    item: MediaItem,
    width: Dp?,
    episodeLabel: String = "",
    progress: Float = 0f,
    onClick: () -> Unit,
    /** 长按回调（「继续观看」删除记录用；null = 无长按） */
    onLongClick: (() -> Unit)? = null,
) {
    val sizeModifier = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    val clickModifier = if (onLongClick != null) {
        @OptIn(ExperimentalFoundationApi::class)
        sizeModifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        sizeModifier.clickable(onClick = onClick)
    }
    Column(modifier = clickModifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
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

            // 右上角：评分（采集站的 vod_score）
            val score = item.scoreLabel()
            if (score.isNotBlank() && episodeLabel.isBlank()) {
                Text(
                    text = score,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }

            // 右下角：集数（优先）/ 备注（「更新至第 12 集」等）
            val badge = episodeLabel.ifBlank { item.extras["remarks"].orEmpty() }
            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        // 时间进度条（继续观看）
        if (progress > 0f) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 采集站评分字段清洗：`8.50` → `8.5`，`0` / 空 → 空串 */
private fun MediaItem.scoreLabel(): String {
    val raw = extras["score"].orEmpty().trim()
    if (raw.isEmpty() || raw == "0" || raw == "0.0") return ""
    val value = raw.toDoubleOrNull() ?: return ""
    if (value <= 0.0) return ""
    return if (value == value.toInt().toDouble()) raw else String.format("%.1f", value)
}

/** 空态 / 错误提示 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/** 骨架屏呼吸透明度（MoonTV 的 `animate-pulse`） */
@Composable
private fun skeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    return alpha
}

/** 分区骨架：一排 4 张灰卡 + 标题条 */
@Composable
private fun SectionSkeleton() {
    val alpha = skeletonAlpha()
    Column {
        Box(
            Modifier
                .padding(start = 12.dp, top = 8.dp, bottom = 10.dp)
                .width(96.dp)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .alpha(alpha),
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(4) {
                Column(modifier = Modifier.width(ROW_CARD_WIDTH)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .alpha(alpha),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .height(10.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(3.dp),
                            )
                            .alpha(alpha),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 网格骨架：三列灰卡 */
@Composable
private fun GridSkeleton() {
    val alpha = skeletonAlpha()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = 12) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .alpha(alpha),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(10.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(3.dp),
                        )
                        .alpha(alpha),
                )
            }
        }
    }
}

