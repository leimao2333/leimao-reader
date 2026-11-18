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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.ui.components.MediaItemCard
import com.novelreader.ui.components.SourceFilterDropdown
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

    /**
     * 当前参与聚合的源数量（顶栏展示「聚合 N 个源」）。
     *
     * C10：跟随源选择变化——选中部分源时只统计这些源，避免「显示聚合 30 个源、
     * 内容却只有 3 个源」这种自相矛盾的提示。
     */
    private val _aliveCount = MutableStateFlow(0)
    val aliveCount: StateFlow<Int> = _aliveCount.asStateFlow()

    /** 探测到的全部发现源（供源选择面板展示，直接复用搜索页的 SourceFilterDropdown） */
    private val _sources = MutableStateFlow<List<SourceInfo>>(emptyList())
    val sources: StateFlow<List<SourceInfo>> = _sources.asStateFlow()

    /**
     * 选中的源 id 集合；空集 = 全部源（与搜索页「一个都不选 = 不限源」同一语义）。
     *
     * C10 取舍：这里只做 ViewModel 内存记忆（配置变更不丢），没有落 DataStore——
     * ExploreViewModel 在 AppNavHost 里只用 (contentRepository, type) 构造，
     * 拿不到 PreferencesManager；按任务硬约束不改 PreferencesManager / AppNavHost，
     * 也就不去碰另一个 agent 正在改的搜索源记忆键。
     */
    private val _selectedSourceIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedSourceIds: StateFlow<Set<String>> = _selectedSourceIds.asStateFlow()

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

    /** 全部源的计划：分类名 → 各（源id, 该源该分类的 url） */
    private var fullPlan: Map<String, List<Pair<String, String>>> = emptyMap()

    /** 按当前源选择过滤后的计划；发现内容只看这些源（C10） */
    private var catPlan: Map<String, List<Pair<String, String>>> = emptyMap()

    init {
        refresh()
    }

    /**
     * 切换选中源（空集 = 全部源）。
     *
     * 不重新探测源，只按新选择重建分类与内容——源列表本身是所有发现源共用的。
     */
    fun setSelectedSourceIds(ids: Set<String>) {
        val valid = ids.filter { it in fullPlan.values.asSequence().flatten().map { it.first }.toSet() }.toSet()
        if (_selectedSourceIds.value == valid) return
        _selectedSourceIds.value = valid
        applySourceFilter()
        reloadDisplay()
    }

    /**
     * 按选中源重建生效计划 / 分类 / 聚合源数。
     *
     * 被过滤掉的分类直接从下拉里消失（否则点了只会得到「该分类暂无内容」），
     * 当前选中分类若已不存在则回退到第一个分类。
     */
    private fun applySourceFilter() {
        val selected = _selectedSourceIds.value
        val plan = if (selected.isEmpty()) {
            fullPlan
        } else {
            fullPlan.mapValues { (_, entries) -> entries.filter { (srcId, _) -> srcId in selected } }
                .filterValues { it.isNotEmpty() }
        }
        catPlan = plan
        _aliveCount.value = plan.values.asSequence().flatten().map { it.first }.distinct().count()
        val rebuilt = plan.map { (title, entries) ->
            ExploreCategory(title, entries.firstOrNull()?.second.orEmpty())
        }
        _categories.value = rebuilt
        val current = _selectedCategory.value
        if (current != null && current.title !in plan) {
            _selectedCategory.value = rebuilt.firstOrNull()
        }
    }

    /** 推荐区要聚合的分类名：优先「推荐/热门/排行」语义分类，没有则用第一个兜底 */
    private fun recommendTitles(): List<String> {
        val keys = catPlan.keys
        val recommend = keys.filter { it.isRecommendLike() }
        return if (recommend.isNotEmpty()) recommend else keys.firstOrNull()?.let { listOf(it) } ?: emptyList()
    }

    /** 源选择变化后重载内容（不重新探测源，也不重置分类选择） */
    private fun reloadDisplay() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _recommended.value = aggregate(recommendTitles())
            val cat = _selectedCategory.value
            _results.value = if (cat == null) emptyList() else aggregate(listOf(cat.title))
            _loading.value = false
        }
    }

    /** 重新探测存活源 + 合并分类 + 加载推荐区 */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null

            // 1) 探测存活源（有发现分类且能拉到内容）
            val allSources = runCatching { contentRepository.exploreSources(type) }
                .getOrDefault(emptyList())
            _sources.value = allSources.map { it.first }
            if (allSources.isEmpty()) {
                fullPlan = emptyMap()
                applySourceFilter()
                _selectedCategory.value = null
                _recommended.value = emptyList()
                _results.value = emptyList()
                _loading.value = false
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
            fullPlan = plan

            // 3) 源选择里可能混进已失效的源 id：按本次探测结果收敛，
            //    否则用户会看到「选了源却是空的」而找不到原因。
            if (_selectedSourceIds.value.isNotEmpty()) {
                val alive = allSources.mapTo(HashSet()) { it.first.id }
                val valid = _selectedSourceIds.value intersect alive
                if (valid != _selectedSourceIds.value) _selectedSourceIds.value = valid
            }
            applySourceFilter()

            // 4) 加载推荐区：聚合选中源里「推荐/热门/排行」语义分类（跨源去重）
            _recommended.value = aggregate(recommendTitles())

            // 5) 默认选中第一个分类并加载其聚合内容
            _selectedCategory.value = _categories.value.firstOrNull()
            _results.value = _selectedCategory.value?.let { aggregate(listOf(it.title)) } ?: emptyList()

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
            // catPlan 已按选中源过滤，聚合结果天然只含选中源的内容（C10）
            _results.value = aggregate(listOf(category.title))
            _loading.value = false
        }
    }

    /**
     * 跨源聚合某分类名的内容（去重）。
     *
     * C10 去重口径：`sourceId:id`——不同源的同名书是不同条目（换源场景要分别展示），
     * 同一源内 id 相同的重复条目才合并。这也是发现页跨源聚合不会「一本书刷屏」的关键。
     */
    private suspend fun aggregate(categoryTitles: List<String>): List<MediaItem> {
        val plan = catPlan
        if (plan.isEmpty()) return emptyList()
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
 * 下方：栏目下拉（首个为「推荐」），内容区按选中栏目展示聚合结果。
 *
 * v1.7.1.0：正文抽成 [ExploreHeader] + [ExploreBody]，行为不变。
 * 首页的「发现」已改为并进推荐视图（`FeedContent` 的栏目下拉），
 * 本页作为独立路由保留。
 */
@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel,
    title: String,
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    // enableEdgeToEdge 下自绘顶栏必须自行避让系统栏，否则与下拉状态栏重叠
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 夜间模式跟随：裸 Column 不铺背景会透出 window 白底
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        ExploreHeader(
            title = title,
            onBack = onBack,
            onRefresh = viewModel::refresh,
            viewModel = viewModel,
        )
        ExploreBody(viewModel = viewModel, onItemClick = onItemClick)
    }
}

/**
 * 发现页顶栏。[onBack] 为 null 时不显示返回箭头。
 */
@Composable
private fun ExploreHeader(
    title: String?,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    viewModel: ExploreViewModel,
) {
    val alive by viewModel.aliveCount.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title ?: "发现",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (alive > 0) {
            Text(
                text = "聚合 $alive 个源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
    }
}

/** 发现页正文：栏目下拉 + 聚合结果列表 */
@Composable
private fun ExploreBody(
    viewModel: ExploreViewModel,
    onItemClick: (MediaItem) -> Unit,
) {
    val categories by viewModel.categories.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val recommended by viewModel.recommended.collectAsState()
    val results by viewModel.results.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSourceIds by viewModel.selectedSourceIds.collectAsState()

    // C10 源选择面板：与搜索页共用同一套多选下拉组件（一个都不选 = 全部源）。
    // 选中后只聚合这些源的发现内容，top 的「聚合 N 个源」也同步收敛。
    if (sources.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "源",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            SourceFilterDropdown(
                sources = sources,
                selectedIds = selectedSourceIds,
                onSelectionChange = viewModel::setSelectedSourceIds,
            )
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
