package com.novelreader.ui.screens.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.core.util.NovelGenres
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.components.MediaItemCard

/**
 * 独立小说书城页（TODO 第十五章 C4）。
 *
 * 顶部「书城」+ 返回；主体「榜单 / 分类」两分区：
 * - 榜单：从首页同一套聚合推荐流里按分类语义切出热门 / 完本 / 新书三榜，
 *   chip 切换，条目走现有 [MediaItemCard]；
 * - 分类：[NovelGenres] 题材导航网格，点击由外部复用 TAG_SEARCH 路由落地。
 *
 * 全部配色、排版走 [MaterialTheme]，不写死颜色，夜间模式自动跟随。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelBookCityScreen(
    viewModel: NovelBookCityViewModel,
    onItemClick: (MediaItem) -> Unit,
    onTagClick: (String) -> Unit,
    onBack: () -> Unit,
    /** 顶栏标题：小说「书城」/ 漫画「漫画书城」（D26 复用同一套榜单与分类框架） */
    title: String = "书城",
    /** 分类题材词表（D29）：小说用 [NovelGenres]，漫画传 `ComicGenres.ALL` */
    genres: List<String> = NovelGenres.ALL,
    /** 起始分区（D28）：0=榜单 / 1=分类。独立「排行榜」入口传 0 即可直达 */
    initialSection: Int = 0,
) {
    var section by rememberSaveable { mutableIntStateOf(initialSection) }
    val alive by viewModel.aliveCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                // 顶栏重叠修复：与首页/发现页一致，显式声明消费状态栏 inset。
                // enableEdgeToEdge 下 Scaffold 默认 inset 未生效会让标题压在状态栏上。
                windowInsets = WindowInsets.statusBars,
                title = {
                    Text(title)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (alive > 0) {
                        Text(
                            text = "聚合 $alive 个源",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // 裸 Column 不铺背景会透出 window 白底，夜间模式下发白
                .background(MaterialTheme.colorScheme.background),
        ) {
            PrimaryTabRow(selectedTabIndex = section) {
                Tab(
                    selected = section == 0,
                    onClick = { section = 0 },
                    text = { Text("榜单") },
                )
                Tab(
                    selected = section == 1,
                    onClick = { section = 1 },
                    text = { Text("分类") },
                )
            }

            when (section) {
                0 -> RankingSection(viewModel = viewModel, onItemClick = onItemClick)
                1 -> GenreSection(genres = genres, onTagClick = onTagClick)
            }
        }
    }
}

/** 榜单分区：榜单 chip + 跨源聚合结果 */
@Composable
private fun RankingSection(
    viewModel: NovelBookCityViewModel,
    onItemClick: (MediaItem) -> Unit,
) {
    val selected by viewModel.selectedBoard.collectAsState()
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(NovelBookCityViewModel.RANK_BOARDS) { index, board ->
                    FilterChip(
                        selected = index == selected,
                        onClick = { viewModel.selectBoard(index) },
                        label = { Text(board.title) },
                    )
                }
            }
        }

        if (loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (error != null && items.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = viewModel::refresh) { Text("重试") }
                }
            }
        } else if (items.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "该榜单暂无内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(items, key = { "${it.sourceId}:${it.id}" }) { item ->
                MediaItemCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onRead = { onItemClick(item) },
                )
            }
        }
    }
}

/** 分类分区：题材导航网格；点击由外部复用 TAG_SEARCH 路由 */
@Composable
private fun GenreSection(genres: List<String>, onTagClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "按题材找书",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Text(
            text = "点击题材，聚合全站同标签作品",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            gridItems(genres) { genre ->
                GenreCard(genre = genre, onClick = { onTagClick(genre) })
            }
        }
    }
}

/** 单个题材卡片：配色全部取自 colorScheme，跟随主题 */
@Composable
private fun GenreCard(
    genre: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = genre,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}
