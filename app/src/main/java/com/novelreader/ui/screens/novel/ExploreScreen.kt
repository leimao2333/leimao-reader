package com.novelreader.ui.screens.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.ui.components.MediaItemCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 发现页 ViewModel（v1.4.2.0 聚合式发现）。
 *
 * 对标影视首页的聚合式源：不再让用户手动挑单个源（书源失效比例高），
 * 而是自动探测所有存活源 → 合并分类（同名去重）→ 每个分类跨源聚合内容去重。
 * 顶部额外加一个「推荐」区（聚合各源带「推荐/热门/排行」语义的分类）。
 *
 * 小说 / 漫画通过 [type] 天然隔离：`exploreSources(type)` 只返回该类型的源，
 * 两个发现页的源与内容互不重合。
 */
class ExploreViewModel(
    private val contentRepository: ContentRepository,
    private val type: SourceType,
) : ViewModel() {

    /** 合并去重后的分类（跨源同名合并，保序） */
    private val _categories = MutableStateFlow<List<ExploreCategory>>(emptyList())
    val categories: StateFlow<List<ExploreCategory>> = _categories.asStateFlow()

    /** 存活源数量（顶栏展示「聚合 N 个源」） */
    private val _aliveCount = MutableStateFlow(0)
    val aliveCount: StateFlow<Int> = _aliveCount.asStateFlow()

    /** 当前选中的分类（null = 推荐区） */
    private val _selectedCategory = MutableStateFlow<ExploreCategory?>(null)
    val selectedCategory: StateFlow<ExploreCategory?> = _selectedCategory.asStateFlow()

    /** 推荐区内容（跨源聚合） */
    private val _recommended = MutableStateFlow<List<MediaItem>>(emptyList())
    val recommended: StateFlow<List<MediaItem>> = _recommended.asStateFlow()

    /** 选中分类的聚合内容 */
    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    /** 会话内缓存：分类名 → 各（源id, 该源该分类的 url） */
    private var catPlan: Map<String, List<Pair<String, String>>>? = null

    init {
        refresh()
    }

    /** 重新探测存活源 + 合并分类 + 加载推荐区 */
    fun refresh() {
        catPlan = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null

            // 1) 探测存活源（有发现分类且能拉到内容）
            val allSources = runCatching { contentRepository.exploreSources(type) }
                .getOrDefault(emptyList())
            if (allSources.isEmpty()) {
                _loading.value = false
                _aliveCount.value = 0
                _categories.value = emptyList()
                _recommended.value = emptyList()
                _results.value = emptyList()
                return@launch
            }

            // 2) 合并分类（同名去重，但每个源保留自己的分类 url）
            // 结构：分类名 → 各（源id, 该源该分类的 url）
            val plan = LinkedHashMap<String, MutableList<Pair<String, String>>>()
            for ((src, cats) in allSources) {
                for (cat in cats) {
                    if (cat.title.isBlank()) continue
                    plan.getOrPut(cat.title) { mutableListOf() }.add(src.id to cat.url)
                }
            }
            catPlan = plan
            _categories.value = plan.map { (title, entries) ->
                ExploreCategory(title, entries.firstOrNull()?.second.orEmpty())
            }
            _aliveCount.value = allSources.size

            // 3) 加载推荐区：聚合各源「推荐/热门/排行」语义分类
            val recommendCats = plan.filterKeys { it.isRecommendLike() }
            _recommended.value = if (recommendCats.isNotEmpty()) {
                aggregate(recommendCats.keys.toList())
            } else {
                // 没有推荐类分类时，用第一个分类兜底
                plan.keys.firstOrNull()?.let { aggregate(listOf(it)) } ?: emptyList()
            }

            // 4) 默认选中第一个分类（或推荐区为空时选第一个）
            _selectedCategory.value = _categories.value.firstOrNull()
            _selectedCategory.value?.let { loadCategory(it) }

            _loading.value = false
        }
    }

    /** 选中分类（传 null 回推荐区） */
    fun selectCategory(category: ExploreCategory?) {
        if (category == _selectedCategory.value) return
        _selectedCategory.value = category
        if (category != null) loadCategory(category)
    }

    private fun loadCategory(category: ExploreCategory) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _results.value = aggregate(listOf(category.title))
            _loading.value = false
        }
    }

    /** 跨源聚合某分类名的内容（去重） */
    private suspend fun aggregate(categoryTitles: List<String>): List<MediaItem> {
        val plan = catPlan ?: return emptyList()
        val entries = categoryTitles.flatMap { plan[it].orEmpty() }
        if (entries.isEmpty()) return emptyList()
        val merged = coroutineScope {
            entries.map { (srcId, url) -> async {
                runCatching { contentRepository.explore(srcId, url, 1) }
                    .getOrDefault(emptyList())
            } }.awaitAll().flatten()
        }
        val seen = LinkedHashSet<String>()
        return merged.filter { seen.add("${it.sourceId}:${it.id}") }
            // v1.6.5.0：把分类名写进条目 tags，详情页顶部「标签小栏」据此展示
            // （源自身不带 kind 时，浏览上下文是唯一的标签来源）
            .map { it.copy(tags = it.tags + categoryTitles) }
    }
}

/** 判断分类名是否带「推荐/热门/排行」语义 */
private fun String.isRecommendLike(): Boolean =
    contains("推荐") || contains("热门") || contains("排行") || contains("精选") ||
        contains("榜单") || contains("人气")

/**
 * 发现页栏目下拉（v1.5.10.0）。
 *
 * 首项「推荐」（selectedTitle 为 null 时选中），其余为聚合栏目；
 * 未选栏目时按钮显示「栏目」，已选时高亮并显示栏目名。
 */
@Composable
private fun ExploreCategoryDropdown(
    categories: List<ExploreCategory>,
    selectedTitle: String?,
    onSelect: (ExploreCategory?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val active = selectedTitle != null
    val container = if (active) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(container)
                .clickable { open = true }
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedTitle ?: "栏目",
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

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            DropdownMenuItem(
                text = { Text("推荐") },
                onClick = {
                    open = false
                    onSelect(null)
                },
                trailingIcon = {
                    if (!active) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
            categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelect(cat)
                    },
                    trailingIcon = {
                        if (cat.title == selectedTitle) {
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
 * 发现页（v1.4.2.0 聚合式）。
 *
 * 顶部：返回 + 标题 + 「聚合 N 个源」+ 刷新；
 * 下方：分类 chips（首个为「推荐」），内容区按选中分类展示聚合结果。
 */
@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel,
    title: String,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val categories by viewModel.categories.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val recommended by viewModel.recommended.collectAsState()
    val results by viewModel.results.collectAsState()
    val aliveCount by viewModel.aliveCount.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
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
                modifier = Modifier.weight(1f),
            )
            if (aliveCount > 0) {
                Text(
                    text = "聚合 $aliveCount 个源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        // 栏目下拉（v1.5.10.0：从横滑 chips 改为下拉框，栏目多时不用来回划）
        if (categories.isNotEmpty()) {
            ExploreCategoryDropdown(
                categories = categories,
                selectedTitle = selectedCategory?.title,
                onSelect = viewModel::selectCategory,
            )
            Spacer(Modifier.height(8.dp))
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && results.isEmpty() && recommended.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = error ?: "", color = MaterialTheme.colorScheme.error)
                }
            categories.isEmpty() && recommended.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "当前类型的书源没有配置发现地址\n（源 JSON 需包含 exploreUrl / ruleExplore）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            else -> {
                val display = if (selectedCategory == null) recommended else results
                if (display.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该分类暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(display, key = { "${it.sourceId}:${it.id}" }) { item ->
                            MediaItemCard(
                                item = item,
                                onClick = { onItemClick(item) },
                                onRead = { onItemClick(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}
