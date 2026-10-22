package com.novelreader.ui.screens.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.GroupedSearchResults
import com.novelreader.ui.components.SearchBar
import com.novelreader.ui.components.SearchProgressBar
import com.novelreader.ui.components.SourceFilterDropdown

/**
 * 视频搜索页。
 *
 * 结果按片名折叠聚合（v1.3.7.0）：同一部片在多个采集站都搜到时只显示一条，
 * 卡片标「N 个播放源，点击展开换源」，展开后逐条列出各源结果。
 * 折叠键用 [com.novelreader.source.search.VideoTitleKey]——采集站片名带
 * 年份 / 画质 / 版本后缀（`流浪地球3(2026)`），不额外剥离就折叠不起来。
 */
@Composable
fun VideoSearchScreen(
    viewModel: VideoSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    /** 深链带入的关键词：非空时自动填入并搜索 */
    initialKeyword: String = "",
) {
    val query by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    val fuzzyEnabled by viewModel.fuzzyEnabled.collectAsState()
    val history by viewModel.history.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSourceIds by viewModel.selectedSourceIds.collectAsState()
    val shelfKeys by viewModel.shelfKeys.collectAsState()
    val longPressShelfEnabled by viewModel.longPressShelfEnabled.collectAsState()
    /** 结果展示：true = 宫格，false = 列表（v1.8.6.0 待办 #26，与设置页同一 key） */
    val searchGrid by viewModel.searchGrid.collectAsState()
    /** 宫格列数（v1.8.7.5）：0 = 自适应 */
    val gridColumns by viewModel.gridColumns.collectAsState()

    // 深链关键词：进入即搜索
    androidx.compose.runtime.LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotBlank()) {
            viewModel.onQueryChange(initialKeyword)
            viewModel.search()
        }
    }

    // enableEdgeToEdge 下自绘顶栏必须自行避让系统栏
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "搜索视频",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            // 宫格 / 列表切换（v1.8.6.0 待办 #26）
            IconButton(onClick = { viewModel.setSearchGrid(!searchGrid) }) {
                Icon(
                    imageVector = if (searchGrid) Icons.AutoMirrored.Filled.ViewList
                    else Icons.Filled.GridView,
                    contentDescription = if (searchGrid) "切换为列表" else "切换为宫格",
                )
            }
        }

        SearchBar(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            placeholder = "搜索视频",
            fuzzyEnabled = fuzzyEnabled,
            onFuzzyChange = viewModel::setFuzzyEnabled,
            history = history,
            onHistoryClick = { kw ->
                viewModel.onQueryChange(kw)
                viewModel.search()
            },
            onClearHistory = viewModel::clearHistory,
            onRemoveHistory = viewModel::removeHistory,
        )

        // 「按源搜索」（v1.8.1.1）：挑一个源就只搜它，其余源一个请求都不发。
        // 「按源搜索」多选下拉（v1.8.1.2）：一个都不选 = 全部源
        if (sources.isNotEmpty()) {
            SourceFilterDropdown(
                sources = sources,
                selectedIds = selectedSourceIds,
                onSelectionChange = viewModel::setSelectedSources,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        when {
            !state.hasSearched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "输入关键词搜索视频",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.isSearching -> {
                SearchProgressBar(state = state)
                GroupedSearchResults(
                    results = state.results,
                    isEmptyHint = "正在搜索中，结果将实时显示…",
                    onItemClick = onItemClick,
                    onToggleShelf = viewModel::toggleShelf,
                    shelfKeys = shelfKeys,
                    type = SourceType.VIDEO,
                    unitLabel = "播放源",
                    longPressEnabled = longPressShelfEnabled,
                    grid = searchGrid,
                    gridColumns = gridColumns,
                )
            }
            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            state.results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "未找到相关结果",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                GroupedSearchResults(
                    results = state.results,
                    isEmptyHint = "",
                    onItemClick = onItemClick,
                    onToggleShelf = viewModel::toggleShelf,
                    shelfKeys = shelfKeys,
                    type = SourceType.VIDEO,
                    unitLabel = "播放源",
                    longPressEnabled = longPressShelfEnabled,
                    grid = searchGrid,
                    gridColumns = gridColumns,
                )
            }
        }
    }
}
