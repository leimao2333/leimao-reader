package com.novelreader.ui.screens.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.novelreader.ui.components.MediaItemCard
import com.novelreader.ui.components.SearchBar
import com.novelreader.ui.components.SearchProgressBar

/**
 * 视频搜索页。
 */
@Composable
fun VideoSearchScreen(
    viewModel: VideoSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val query by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    val fuzzyEnabled by viewModel.fuzzyEnabled.collectAsState()
    val history by viewModel.history.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
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
                SearchResultList(
                    results = state.results,
                    isEmptyHint = "正在搜索中，结果将实时显示…",
                    onItemClick = onItemClick,
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
                SearchResultList(
                    results = state.results,
                    isEmptyHint = "",
                    onItemClick = onItemClick,
                )
            }
        }
    }
}

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
