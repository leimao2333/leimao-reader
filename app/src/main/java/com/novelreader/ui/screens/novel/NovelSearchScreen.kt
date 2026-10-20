package com.novelreader.ui.screens.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.novelreader.ui.components.GroupedSearchResults
import com.novelreader.ui.components.SearchBar
import com.novelreader.ui.components.SearchProgressBar
import com.novelreader.ui.components.SourceFilterDropdown

/**
 * 小说搜索页。
 *
 * 搜索框 + 模糊搜索开关 + 搜索历史 + 实时进度 + 结果列表，点击结果跳转详情页。
 */
@Composable
fun NovelSearchScreen(
    viewModel: NovelSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
    onRead: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val query by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    val fuzzyEnabled by viewModel.fuzzyEnabled.collectAsState()
    val history by viewModel.history.collectAsState()
    val shelfKeys by viewModel.shelfKeys.collectAsState()
    val longPressShelfEnabled by viewModel.longPressShelfEnabled.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSourceIds by viewModel.selectedSourceIds.collectAsState()

    // enableEdgeToEdge 下自绘顶栏必须自行避让系统栏
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // 顶部：返回按钮 + 搜索框同行（紧凑，贴合开源阅读）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 顶对齐 + 内边距，使箭头与搜索框垂直居中对齐（搜索栏整体较高，不能用整列居中）
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            SearchBar(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = viewModel::search,
                placeholder = "搜索小说",
                fuzzyEnabled = fuzzyEnabled,
                onFuzzyChange = viewModel::setFuzzyEnabled,
                history = history,
                onHistoryClick = { kw ->
                    viewModel.onQueryChange(kw)
                    viewModel.search()
                },
                onClearHistory = viewModel::clearHistory,
                onRemoveHistory = viewModel::removeHistory,
                modifier = Modifier.weight(1f),
            )
        }

        // 「按源搜索」（v1.8.1.1）：挑一个源就只搜它，其余源一个请求都不发 ——
        // 十几二十个源一起搜的等待时间省掉了，同名作品也不会被别的源串进来。
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
            // 未搜索：初始空态
            !state.hasSearched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "输入关键词搜索小说",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 搜索中：进度条 + 实时结果（可能为空）
            state.isSearching -> {
                SearchProgressBar(state = state)
                GroupedSearchResults(
                    results = state.results,
                    isEmptyHint = "正在搜索中，结果将实时显示…",
                    onItemClick = onItemClick,
                    onRead = onRead,
                    onToggleShelf = viewModel::toggleShelf,
                    shelfKeys = shelfKeys,
                    longPressEnabled = longPressShelfEnabled,
                )
            }
            // 搜索完成但有错误
            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // 搜索完成但无结果
            state.results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "未找到相关结果",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 搜索完成且有结果
            else -> {
                GroupedSearchResults(
                    results = state.results,
                    isEmptyHint = "",
                    onItemClick = onItemClick,
                    onRead = onRead,
                    onToggleShelf = viewModel::toggleShelf,
                    shelfKeys = shelfKeys,
                    longPressEnabled = longPressShelfEnabled,
                )
            }
        }
    }
}
