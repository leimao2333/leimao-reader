package com.novelreader.ui.screens.common

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.novelreader.core.util.HomeFeedPlan
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.components.CoverPlaceholderLetter

/**
 * 小说 / 漫画首页「推荐」视图（v1.7.1.0）。
 *
 * **推荐与发现合并在这里**：
 * - 顶部「栏目」下拉，首项「推荐」（默认）= 各源推荐位的**横滑合览**；
 * - 选具体栏目 = 该栏目跨源的**聚合结果**（三列网格）。
 *
 * 这与影视首页 `VideoHomeContent` 的「分区视图 / 网格视图」是同一个思路，
 * 只是切换入口从「查看更多」换成了栏目下拉 —— 少一层跳转。
 *
 * 类型无关：小说与漫画共用本组件，数据由 [HomeViewModel.FeedHomeState] 提供。
 */
@Composable
fun FeedContent(
    state: HomeViewModel.FeedHomeState,
    onItemClick: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        FeedHeaderRow(
            state = state,
            onRefresh = onRefresh,
            onOpenSearch = onOpenSearch,
        )

        // 栏目标签条：只有「推荐」一项时不必显示（没有任何真实栏目可选）
        if (state.categories.size > 1) {
            FeedCategoryChips(
                categories = state.categories,
                selected = state.selectedCategory,
                onSelect = onSelectCategory,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (state.onRecommendTab) {
            FeedRecommendSections(state = state, onItemClick = onItemClick)
        } else {
            FeedCategoryResults(state = state, onItemClick = onItemClick)
        }
    }
}

// ============================== 推荐（横滑合览） ==============================

@Composable
private fun FeedRecommendSections(
    state: HomeViewModel.FeedHomeState,
    onItemClick: (MediaItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        val error = state.error
        when {
            // 加载中但已有内容：保留旧内容继续展示，骨架屏只在真空白时出现
            state.loading && state.rows.isEmpty() -> {
                items(count = 2, key = { "skeleton-$it" }) { FeedSectionSkeleton() }
            }

            state.rows.isEmpty() -> {
                item(key = "empty") { FeedEmptyHint(error ?: "暂无内容") }
            }

            else -> {
                // key = 位置 + 排标题。标题来自跨源栏目名合并，正常唯一；但兜底行
                // （更多源）在源特别多时会分成好几排，早期实现直接拿标题当 key，
                // 撞名就抛 IllegalArgumentException 闪退（实测滑到首页底部必崩）。
                // 加上位置前缀后，即便标题再重复也只是复用错位，不会崩溃。
                itemsIndexed(
                    state.rows,
                    key = { index, row -> "$index::${row.categoryId}::${row.title}" },
                ) { _, row ->
                    Column {
                        FeedSectionHeader(title = row.title)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(row.items, key = { "${it.sourceId}:${it.id}" }) { item ->
                                FeedCard(
                                    item = item,
                                    modifier = Modifier.width(FEED_CARD_WIDTH),
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

// ============================== 栏目（聚合网格） ==============================

@Composable
private fun FeedCategoryResults(
    state: HomeViewModel.FeedHomeState,
    onItemClick: (MediaItem) -> Unit,
) {
    when {
        state.loading && state.items.isEmpty() -> FeedGridSkeleton()
        state.items.isEmpty() -> FeedEmptyHint(state.error ?: "该栏目暂无内容")
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(state.items, key = { "${it.sourceId}:${it.id}" }) { item ->
                FeedCard(item = item, modifier = Modifier.fillMaxWidth(), onClick = { onItemClick(item) })
            }
        }
    }
}

// ============================== 顶栏 ==============================

/** 顶栏：搜索入口 + 聚合源状态 + 刷新 */
@Composable
private fun FeedHeaderRow(
    state: HomeViewModel.FeedHomeState,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val alive = state.aliveCount
            Text(
                text = if (alive > 0) "聚合 $alive 个源" else "正在探测书源…",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 有内容时的静默刷新指示：细圈代替整页骨架
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

/**
 * 栏目标签条（v1.7.2.0，回到「发现」页早期的横滑 chips 形态）。
 *
 * 「聚合 N 个源」下面直接铺一排可横滑的栏目标签：**首项固定「推荐」**（= 各源
 * 推荐位的横滑合览），后面接覆盖源数最多的那些栏目。想换栏目时一眼就能看到、
 * 一点就切，不用先展开下拉再找。
 *
 * 栏目总量可以有一千多个（各站自造栏目），全铺出来没有意义，所以只把**覆盖最广的
 * 前 [QUICK_CHIP_COUNT] 个**做成标签，剩下的长尾收进末尾的「更多」下拉里 ——
 * 两条路都留着：常用的够快，冷门的也到得了。
 */
@Composable
private fun FeedCategoryChips(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val quick = categories.take(1 + QUICK_CHIP_COUNT)
    val rest = categories.drop(1 + QUICK_CHIP_COUNT)

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(quick, key = { "chip::$it" }) { name ->
            CategoryChip(
                text = name,
                selected = name == selected,
                onClick = { if (name != selected) onSelect(name) },
            )
        }
        if (rest.isNotEmpty()) {
            item(key = "chip::__more__") {
                CategoryOverflowChip(names = rest, selected = selected, onSelect = onSelect)
            }
        }
    }
}

/** 一个栏目标签（选中态用主色容器，未选中用浅底） */
@Composable
private fun CategoryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * 长尾栏目的「更多」下拉。
 *
 * 标签条只铺得下常用的那十几个，剩下的（各站的独家栏目）都收在这里，
 * 免得「换栏目」这件事在长尾上无处可去。
 */
@Composable
private fun CategoryOverflowChip(
    names: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // 当前选中的正好在长尾里时，把这个标签本身点亮（否则用户看不出自己在哪）
    val currentInRest = names.contains(selected)
    val container =
        if (currentInRest) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (currentInRest) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

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
                text = if (currentInRest) selected else "更多",
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "更多栏目",
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            names.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        if (name != selected) onSelect(name)
                    },
                    trailingIcon = {
                        if (name == selected) {
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

/**
 * 标签条里最多铺几个栏目（不含首项「推荐」）。
 *
 * 铺太多会让常用栏目被挤出可视区，反而更难找；长尾交给末尾的「更多」下拉。
 */
private const val QUICK_CHIP_COUNT = 18

/** 分区标题 */
@Composable
private fun FeedSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    )
}

// ============================== 卡片 / 骨架 / 空态 ==============================

/** 一排横滑里的卡片宽度（2:3 封面；一行可见 3 张半） */
private val FEED_CARD_WIDTH = 96.dp

/**
 * 推荐卡片：2:3 封面 + 书名 + 作者。
 *
 * 影视的 `PosterCard` 展示评分 / 集数角标，书没有这些字段，
 * 改为在书名下显示作者（没有作者就显示源名）。
 */
@Composable
private fun FeedCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        ) {
            val cover = item.cover
            if (cover.isNullOrBlank()) {
                CoverPlaceholderLetter(modifier = Modifier.fillMaxSize(), title = item.title)
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
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = item.author?.takeIf { it.isNotBlank() } ?: item.sourceName.takeIf { it.isNotBlank() }
        if (sub != null) {
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 空态 / 错误提示 */
@Composable
private fun FeedEmptyHint(text: String) {
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

/** 骨架屏呼吸透明度 */
@Composable
private fun feedSkeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "feedSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "feedSkeletonAlpha",
    )
    return alpha
}

/** 分区骨架：一排 4 张灰卡 + 标题条 */
@Composable
private fun FeedSectionSkeleton() {
    val alpha = feedSkeletonAlpha()
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
                Column(modifier = Modifier.width(FEED_CARD_WIDTH)) {
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
private fun FeedGridSkeleton() {
    val alpha = feedSkeletonAlpha()
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
