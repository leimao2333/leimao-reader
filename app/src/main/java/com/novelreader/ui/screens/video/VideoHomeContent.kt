package com.novelreader.ui.screens.video

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
    onSelectSource: (String) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.expanded) {
            VideoGridContent(
                state = state,
                onBack = onCollapseCategory,
                onSelectCategory = onSelectCategory,
                onSelectArea = onSelectArea,
                onSelectYear = onSelectYear,
                onSelectOrder = onSelectOrder,
                onOpenPicker = { pickerOpen = true },
                onItemClick = onItemClick,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onOpenSearch = onOpenSearch,
            )
        } else {
            VideoSectionsContent(
                state = state,
                onOpenPicker = { pickerOpen = true },
                onExpandCategory = onExpandCategory,
                onItemClick = onItemClick,
                onRefresh = onRefresh,
                onOpenSearch = onOpenSearch,
            )
        }
    }

    if (pickerOpen) {
        SourcePickerDialog(
            sources = state.sources,
            currentId = state.currentSourceId,
            onPick = {
                pickerOpen = false
                onSelectSource(it)
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

// ============================== 分区视图 ==============================

@Composable
private fun VideoSectionsContent(
    state: HomeViewModel.VideoHomeState,
    onOpenPicker: () -> Unit,
    onExpandCategory: (String, String) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            SourceHeaderRow(
                state = state,
                onOpenPicker = onOpenPicker,
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
            state.loading -> {
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
                                PosterCard(
                                    item = cont.item,
                                    width = ROW_CARD_WIDTH,
                                    episodeLabel = cont.episodeLabel,
                                    progress = cont.progress,
                                    onClick = { onItemClick(cont.item) },
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }

                items(state.rows, key = { it.categoryId }) { row ->
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

/** 顶部：搜索入口 + 当前源（可点切换）+ 刷新 */
@Composable
private fun SourceHeaderRow(
    state: HomeViewModel.VideoHomeState,
    onOpenPicker: () -> Unit,
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
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = state.sources.isNotEmpty()) { onOpenPicker() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.currentSourceName.ifBlank { "选择视频源" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.sources.size > 1) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "切换源",
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "（${state.sources.size} 个源）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onOpenPicker: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val itemCount by rememberUpdatedState(state.items.size)

    // 触底分页：滚动到最后 4 个条目时预取下一页
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= 0 && last >= itemCount - 4) onLoadMore()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SourceHeaderRow(
            state = state,
            onOpenPicker = onOpenPicker,
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

        // 分类 chips（含「全部」），网格视图内可继续换分类
        if (state.categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.selectedCategory.isEmpty(),
                        onClick = { onSelectCategory("") },
                        label = { Text("全部") },
                    )
                }
                items(state.categories, key = { it.url }) { category ->
                    FilterChip(
                        selected = state.selectedCategory == category.url,
                        onClick = { onSelectCategory(category.url) },
                        label = { Text(category.title) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 地区 / 年份 / 排序三个维度用下拉（v1.3.5.0）。分类维度保留上面的 chips：
        // 分类可能有十几个且是最常用的切换，chips 一点即达比下拉快一倍。
        FilterDropdownRow(
            selectedArea = state.selectedArea,
            selectedYear = state.selectedYear,
            selectedOrder = state.selectedOrder,
            onSelectArea = onSelectArea,
            onSelectYear = onSelectYear,
            onSelectOrder = onSelectOrder,
        )
        Spacer(Modifier.height(8.dp))

        when {
            state.loading -> {
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
 * 片库筛选行：地区 / 年份 / 排序（分类维度由上面的 chips 承担，见 [VideoGridContent]）。
 *
 * 年份按**当前年**动态生成，不写死列表 —— 否则过几年就选不到最新年份了。
 */
@Composable
private fun FilterDropdownRow(
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

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
) {
    val sizeModifier = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    Column(modifier = sizeModifier.clickable(onClick = onClick)) {
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

/** 视频源选择弹窗（源可能很多，用列表滚动） */
@Composable
private fun SourcePickerDialog(
    sources: List<SourceInfo>,
    currentId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择视频源（${sources.size}）") },
        text = {
            Box(modifier = Modifier.heightIn(max = 420.dp)) {
                LazyColumn {
                    items(sources, key = { it.id }) { source ->
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(source.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (source.id == currentId) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (source.id == currentId) {
                                    Text(
                                        text = "使用中",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
