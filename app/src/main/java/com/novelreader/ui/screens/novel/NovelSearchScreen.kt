package com.novelreader.ui.screens.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelreader.domain.model.MediaItem
import com.novelreader.ui.components.MediaItemCard
import com.novelreader.ui.components.SearchBar
import com.novelreader.ui.components.SearchProgressBar

/**
 * 小说搜索页。
 *
 * 搜索框 + 模糊搜索开关 + 搜索历史 + 实时进度 + 结果列表，点击结果跳转详情页。
 */
@Composable
fun NovelSearchScreen(
    viewModel: NovelSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
) {
    val query by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    val fuzzyEnabled by viewModel.fuzzyEnabled.collectAsState()
    val history by viewModel.history.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
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
        )

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
                SearchResultList(
                    results = state.results,
                    isEmptyHint = "正在搜索中，结果将实时显示…",
                    onItemClick = onItemClick,
                )
            }
            // 搜索完成且有错误
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
                SearchResultList(
                    results = state.results,
                    isEmptyHint = "",
                    onItemClick = onItemClick,
                )
            }
        }
    }
}

/**
 * 搜索结果列表（共用）。
 */
@Composable
private fun SearchResultList(
    results: List<MediaItem>,
    isEmptyHint: String,
    onItemClick: (MediaItem) -> Unit,
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(results, key = { "${it.sourceId}_${it.id}" }) { item ->
            MediaItemCard(item = item, onClick = { onItemClick(item) })
        }
    }
}
