package com.novelreader.ui.screens.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.ui.components.MediaItemCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 发现页 ViewModel（v1.2.3 对标 legado 发现）。
 *
 * 数据流：已启用源中筛出配置了 exploreUrl 的源 → 选源 → 选分类 →
 * 按分类地址拉取列表（{{page}} 分页，「加载更多」追加）。
 */
class ExploreViewModel(
    private val contentRepository: ContentRepository,
    private val type: SourceType,
) : ViewModel() {

    /** 源 + 其发现分类 */
    data class SourceEntry(val info: SourceInfo, val categories: List<ExploreCategory>)

    private val _sources = MutableStateFlow<List<SourceEntry>>(emptyList())
    val sources: StateFlow<List<SourceEntry>> = _sources.asStateFlow()

    private val _selectedSource = MutableStateFlow<SourceEntry?>(null)
    val selectedSource: StateFlow<SourceEntry?> = _selectedSource.asStateFlow()

    private val _selectedCategory = MutableStateFlow<ExploreCategory?>(null)
    val selectedCategory: StateFlow<ExploreCategory?> = _selectedCategory.asStateFlow()

    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var page = 1
    private var canLoadMore = true
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        refresh()
    }

    /** 重新枚举有发现分类的源；默认选中第一个源的第一个分类 */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val entries = runCatching { contentRepository.exploreSources(type) }
                .getOrDefault(emptyList())
                .map { SourceEntry(it.first, it.second) }
            _sources.value = entries
            _loading.value = false
            if (entries.isNotEmpty()) {
                selectSource(entries.first())
            } else {
                _selectedSource.value = null
                _selectedCategory.value = null
                _results.value = emptyList()
            }
        }
    }

    fun selectSource(entry: SourceEntry) {
        _selectedSource.value = entry
        entry.categories.firstOrNull()?.let { selectCategory(it) }
            ?: run {
                _selectedCategory.value = null
                _results.value = emptyList()
            }
    }

    fun selectCategory(category: ExploreCategory) {
        _selectedCategory.value = category
        page = 1
        canLoadMore = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val src = _selectedSource.value
            val list = if (src == null) emptyList() else runCatching {
                contentRepository.explore(src.info.id, category.url, 1)
            }.onFailure { _error.value = it.message ?: "加载失败" }
                .getOrDefault(emptyList())
            _results.value = list
            canLoadMore = list.isNotEmpty()
            _loading.value = false
        }
    }

    /** 加载下一页（追加，按 detailUrl 去重） */
    fun loadMore() {
        val src = _selectedSource.value ?: return
        val category = _selectedCategory.value ?: return
        if (_loading.value || _loadingMore.value || !canLoadMore) return
        _loadingMore.value = true
        viewModelScope.launch {
            val next = page + 1
            val list = runCatching {
                contentRepository.explore(src.info.id, category.url, next)
            }.getOrDefault(emptyList())
            if (list.isEmpty()) {
                canLoadMore = false
            } else {
                page = next
                val seen = _results.value.mapTo(HashSet()) { it.detailUrl.ifBlank { it.id + it.title } }
                _results.value = _results.value + list.filter {
                    seen.add(it.detailUrl.ifBlank { it.id + it.title })
                }
            }
            _loadingMore.value = false
        }
    }
}

/**
 * 发现页（v1.2.3 对标 legado）。
 *
 * 顶部：返回 + 源选择 + 分类选择；下方结果列表（复用 MediaItemCard）+ 加载更多。
 */
@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel,
    title: String,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val results by viewModel.results.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val error by viewModel.error.collectAsState()

    var sourceMenuOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：返回 + 标题 + 源/分类两个下拉
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(12.dp))

            // 源选择
            Box {
                TextButton(onClick = { sourceMenuOpen = true }) {
                    Text(
                        text = selectedSource?.info?.name ?: "选择书源",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp),
                    )
                    Icon(Icons.Filled.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
                    if (sources.isEmpty()) {
                        DropdownMenuItem(text = { Text("没有支持发现的源") }, onClick = {})
                    }
                    sources.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(entry.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                sourceMenuOpen = false
                                viewModel.selectSource(entry)
                            },
                        )
                    }
                }
            }

            // 分类选择
            Box {
                TextButton(
                    enabled = selectedSource != null,
                    onClick = { categoryMenuOpen = true },
                ) {
                    Text(
                        text = selectedCategory?.title ?: "分类",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(110.dp),
                    )
                    Icon(Icons.Filled.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                    selectedSource?.categories?.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                categoryMenuOpen = false
                                viewModel.selectCategory(cat)
                            },
                        )
                    }
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            sources.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "当前类型的书源没有配置发现地址\n（源 JSON 需包含 exploreUrl / ruleExplore）",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = error ?: "", color = MaterialTheme.colorScheme.error)
            }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "该分类暂无内容",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(results, key = { it.detailUrl.ifBlank { it.id + it.title } }) { item ->
                    MediaItemCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onRead = { onItemClick(item) },
                    )
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.loadMore() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loadingMore) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        } else {
                            Text(
                                text = "加载更多",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
