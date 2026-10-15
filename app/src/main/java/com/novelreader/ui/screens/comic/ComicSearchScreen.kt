package com.novelreader.ui.screens.comic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.GroupedSearchResults
import com.novelreader.ui.components.SearchBar
import com.novelreader.ui.components.SearchProgressBar

/**
 * 漫画搜索页。
 *
 * 结果列表用 [GroupedSearchResults]（按书名归一化折叠，v1.7.0.0）：
 * v1.6.9.0 及以前这里走的是自带的扁平 [androidx.compose.foundation.lazy.LazyColumn]，
 * 于是「龙敖天」这种被多个源同时收录的漫画会在结果里一字排开好几条，
 * 既看不出是同名作品也点不出「换源」。现在与小说 / 影视同一套折叠逻辑。
 */
@Composable
fun ComicSearchScreen(
    viewModel: ComicSearchViewModel,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    /** 深链带入的关键词：非空时自动填入并搜索 */
    initialKeyword: String = "",
) {
    val query by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    val fuzzyEnabled by viewModel.fuzzyEnabled.collectAsState()
    val history by viewModel.history.collectAsState()

    // 深链关键词：进入即搜索（与小说 / 影视搜索页一致）
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
                text = "搜索漫画",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        SearchBar(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            placeholder = "搜索漫画",
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
                        text = "输入关键词搜索漫画",
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
                    type = SourceType.COMIC,
                    unitLabel = "漫画源",
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
                    type = SourceType.COMIC,
                    unitLabel = "漫画源",
                )
            }
        }
    }
}
