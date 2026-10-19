package com.novelreader.ui.screens.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.core.util.TagQuery
import com.novelreader.core.util.TagQuerySpec
import com.novelreader.core.util.TagTagger
import com.novelreader.data.cache.TagPlan
import com.novelreader.data.repository.TagRepository
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.source.category.isAdult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 标签搜索 ViewModel（v1.5.10.0 起；v1.6.1.0 重做 UI 后保留全量 + 新增热门标签；
 * v1.7.0.0 重修复合搜索；v1.7.9.9 改为并集相交）。
 *
 * - 标签来自 [TagRepository] 自动收集的各源发现分类（落盘缓存，冷启动即有）；
 * - **热门标签**：按标签覆盖源数倒序，取 top [HOT_TAG_LIMIT] 展示在搜索页顶部；
 * - **单标签搜索**：聚合所有拥有该分类的源的第 1 页内容，跨源去重；
 * - **复合搜索**：每个标签先取「它自己全部译名 / 转义写法 / 全部源」的并集，
 *   再把各标签的并集按归一化标题求交（「无码 全彩」= 全量无码 ∪ ∩ 全量全彩 ∪）。
 *   跨源同名作品视为同一部，不再要求两个标签落在同一个源上；
 * - 视频结果把 18+ 成人源内容沉底（与首页/搜索一致）。
 */
class TagSearchViewModel(
    private val contentRepository: ContentRepository,
    private val tagRepository: TagRepository,
    private val sourceRepository: SourceRepository?,
    val type: SourceType,
    initialTag: String,
    private val preferencesManager: com.novelreader.core.preferences.PreferencesManager? = null,
    /** 本地标签索引（v1.8.1.2）：建好后标签搜索直接查它，不再受源站有无分类页限制 */
    private val tagIndexRepository: com.novelreader.data.repository.TagIndexRepository? = null,
) : ViewModel() {

    /** 索引构建状态（v1.8.1.2） */
    private val _indexing = MutableStateFlow(false)
    val indexing: StateFlow<Boolean> = _indexing.asStateFlow()

    private val _indexMessage = MutableStateFlow<String?>(null)
    val indexMessage: StateFlow<String?> = _indexMessage.asStateFlow()

    private val _indexCount = MutableStateFlow(0)
    val indexCount: StateFlow<Int> = _indexCount.asStateFlow()

    /**
     * 构建本地标签索引（v1.8.1.2）。
     *
     * 遍历已启用的源：抓列表 → 逐部拉详情 → 读出源站声明的题材 → 入库。
     * 建好后 [search] 会优先查这份索引，复合筛选因此是真的按标签求交。
     */
    fun buildIndex() {
        val repo = tagIndexRepository ?: run {
            _indexMessage.value = "当前环境不支持本地索引"
            return
        }
        if (_indexing.value) return
        viewModelScope.launch {
            _indexing.value = true
            _indexMessage.value = "准备中…"
            try {
                val picked = _selectedSourceIds.value
                val total = repo.build(
                    type = type,
                    // 选了源就只索引选中的源：全量遍历很慢，先按源建能快速看到效果
                    sourceIds = picked,
                    onProgress = { p ->
                        _indexMessage.value =
                            "扫描 ${p.scannedCount} 部 · 打标签 ${p.indexedCount} 部 · " +
                                "${p.doneSources}/${p.totalSources} 源" +
                                if (p.currentSource.isNotBlank()) "（${p.currentSource}）" else ""
                    },
                )
                _indexCount.value = repo.count(type)
                _indexMessage.value = "完成：$total 部作品已打标签"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TagSearch", "构建标签索引失败", error)
                _indexMessage.value = "建索引失败：${error.message ?: "未知错误"}"
            } finally {
                _indexing.value = false
            }
        }
    }

    /**
     * 封面视觉打标（v1.8.1.4）：对已收录作品的封面跑 WD14 模型，
     * 输出的 Danbooru 标签映射进受控词表后以 `origin = 视觉` 入库。
     *
     * 模型约 356MB，仅在 Wi-Fi 下按需下载（[TagIndexRepository.runVisualTagging] 负责）。
     */
    fun runVisualTagging() {
        val repo = tagIndexRepository ?: run {
            _indexMessage.value = "当前环境不支持视觉打标"
            return
        }
        if (_indexing.value) return
        viewModelScope.launch {
            _indexing.value = true
            _indexMessage.value = "准备视觉模型…"
            try {
                val count = repo.runVisualTagging(type) { message -> _indexMessage.value = message }
                _indexCount.value = repo.count(type)
                _indexMessage.value = if (count > 0) {
                    "视觉打标完成：$count 部封面已入库"
                } else {
                    "没有待打标的封面（先搜索一次收录内容）"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TagSearch", "视觉打标失败", error)
                _indexMessage.value = "视觉打标失败：${error.message ?: "未知错误"}"
            } finally {
                _indexing.value = false
            }
        }
    }

    /**
     * 「按源搜索」：已启用的源列表 + 当前选中的源（null = 全部源）（v1.8.1.2）。
     *
     * 标签池是跨源收集的；只搜某一个源能避免几十个源一起等的长尾，也便于核对某个源的内容。
     */
    private val _sources = MutableStateFlow<List<com.novelreader.domain.model.SourceInfo>>(emptyList())
    val sources: StateFlow<List<com.novelreader.domain.model.SourceInfo>> = _sources.asStateFlow()

    private val _selectedSourceIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedSourceIds: StateFlow<Set<String>> = _selectedSourceIds.asStateFlow()

    /** 选择源（**多选**；空集合 = 全部源，v1.8.1.2）。已有查询时立即按新范围重搜。 */
    fun setSelectedSources(ids: Set<String>) {
        _selectedSourceIds.value = ids
        if (_query.value.isNotBlank()) search()
    }

    /** 全部可用标签（自动收集） */
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags.asStateFlow()

    /**
     * 全部标签（按覆盖源数倒序）（v1.6.5.0）。
     *
     * 「更多」面板**直接展示所有标签**（不再只给热门 top N）——标签池动辄上百个，
     * 只放热门等于大部分标签用户永远点不到。排序仍按覆盖源数，覆盖面广的在前。
     */
    private val _tagsByCoverage = MutableStateFlow<List<String>>(emptyList())
    val tagsByCoverage: StateFlow<List<String>> = _tagsByCoverage.asStateFlow()

    /**
     * 热门推荐标签（v1.6.1.0）：按覆盖源数倒序，取 top [HOT_TAG_LIMIT]。
     *
     * 在搜索页顶部作为「一键选择」入口，用户点选即触发复合搜索（多次复用 + 自动追加）。
     * 「覆盖源数」= 该标签下有多少 (sourceId, url) 对，越多说明越多源支持该分类。
     */
    private val _hotTags = MutableStateFlow<List<String>>(emptyList())
    val hotTags: StateFlow<List<String>> = _hotTags.asStateFlow()

    /** 标签池是否正在收集（首次使用要逐源探测，视频源较慢） */
    private val _collecting = MutableStateFlow(false)
    val collecting: StateFlow<Boolean> = _collecting.asStateFlow()

    /** 查询串（标签之间用空格分开） */
    private val _query = MutableStateFlow(initialTag.trim())
    val query: StateFlow<String> = _query.asStateFlow()

    /** 搜索结果 */
    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    /** 是否正在搜索 */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** 搜索进度（完成的标签数 / 总标签数） */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    /** 提示（标签未找到 / 搜索失败等，非致命） */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 最近一次成功搜索的标签（用于结果区标题） */
    private val _activeTags = MutableStateFlow(
        initialTag.trim().takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty(),
    )
    val activeTags: StateFlow<List<String>> = _activeTags.asStateFlow()

    private var tagPlan: TagPlan = TagPlan.EMPTY
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchGeneration = 0L

    // ===== 分页（v1.8.1.2）=====
    /** 本次搜索的上下文：滚到底时按同一批入口继续抓下一页 */
    private data class PagingContext(
        val tags: List<String>,
        val entriesPerTag: List<List<Pair<String, String>>>,
        val sources: List<String>,
        val allSearchOnly: Boolean,
        val single: Boolean,
        val keyword: String,
        val union: List<LinkedHashMap<String, MediaItem>>,
    )

    private var paging: PagingContext? = null
    private var nextPage = 2
    private var preloadJob: kotlinx.coroutines.Job? = null
    private val _hasMore = MutableStateFlow(false)

    /** 还有没有下一页（触底加载用） */
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)

    /** 是否正在加载下一页 */
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /**
     * 组合关键词搜索结果缓存（v1.8.1.1，LRU 8 条 / 10 分钟）。
     *
     * 「无码 全彩」这类查询每次都要打几十个源、耗时几十秒；用户来回切页或重复搜索时
     * 没必要再打一遍。命中缓存直接秒开，并在提示里标明是缓存结果。
     */
    private val combinedCache =
        object : LinkedHashMap<String, Pair<Long, List<MediaItem>>>(8, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Pair<Long, List<MediaItem>>>?,
            ): Boolean = size > 8
        }

    private data class TagSearchRequest(val tagIndex: Int, val sourceId: String, val url: String)
    private data class TagSearchResponse(val tagIndex: Int, val sourceId: String, val items: List<MediaItem>)

    /**
     * 复合搜索产出：结果 + 展示说明（[note]）。
     *
     * [note] 用来解释「为什么这批结果长这样」—— 走了组合关键词直搜、还是只拿到了单组内容，
     * 都必须写清楚，不能让用户以为这就是严格交集。
     */
    private data class CompoundOutcome(
        val items: List<MediaItem>,
        val note: String? = null,
        /** 严格求交时每个大集的并集，分页继续累积用（v1.8.1.2） */
        val union: List<LinkedHashMap<String, MediaItem>>? = null,
    ) {
        companion object {
            val EMPTY = CompoundOutcome(emptyList())
        }
    }

    init {
        collectTags(initialTag.isNotBlank())
        viewModelScope.launch {
            _sources.value = runCatching {
                sourceRepository?.getSourcesByType(type)?.filter { it.enabled }.orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    /** 收集标签池：先读缓存（毫秒级），空则现场收集 */
    private fun collectTags(thenSearch: Boolean) {
        viewModelScope.launch {
            _collecting.value = true
            tagPlan = runCatching { tagRepository.tags(type) }.getOrDefault(TagPlan.EMPTY)
            _allTags.value = tagPlan.tags
            _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
            _tagsByCoverage.value = tagPlan.hotTags(0)
            _collecting.value = false
            if (thenSearch && _results.value.isEmpty()) search()
        }
    }

    /** 强制重新收集标签（源变了 / 分类没刷出来时用） */
    fun refreshTags() {
        viewModelScope.launch {
            _collecting.value = true
            tagPlan = runCatching { tagRepository.refresh(type) }.getOrDefault(TagPlan.EMPTY)
            _allTags.value = tagPlan.tags
            _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
            _tagsByCoverage.value = tagPlan.hotTags(0)
            _collecting.value = false
        }
    }

    fun setQuery(value: String) {
        _query.value = value
        _activeTags.value = emptyList()
    }

    private fun logicalTagsInQuery(query: String): List<String> =
        _activeTags.value.takeIf { it.isNotEmpty() && it.joinToString(" ") == query }
            ?: TagQuery.parse(query)

    /**
     * 把一个语法条件展开成「一组等价写法」（v1.8.1.3）。
     *
     * - 普通标签：受控词表里的全部译名（[TagAlias.aliasesOf]）；
     * - 通配：先在受控词表 + 标签池里匹配出真实标签，再各自展开译名 ——
     *   `魔法*` 会命中「魔法」「魔法少女」等，而不是拿星号去请求源站。
     */
    private fun expandClauseNames(spec: TagQuerySpec.Clause): Set<String> {
        if (!spec.wildcard) return TagAlias.aliasesOf(spec.name)
        val vocabulary = buildList {
            TagAlias.GROUPS.forEach { addAll(it) }
            addAll(tagPlan.tags)
            addAll(tagPlan.plan.keys)
        }
        return TagQuerySpec.expandWildcard(spec.name, vocabulary)
            .flatMap { matched -> TagAlias.aliasesOf(matched) }
            .toSet()
    }

    /**
     * 排除条件：网络结果不带标签，只能按标题包含关系过滤（索引侧已由 SQL 整条剔除）。
     *
     * 术语用 [TagAlias.searchTermsFor] 展开，`-无码` 也能排掉标题写着「無修正」的作品。
     */
    private fun withoutExcluded(
        items: List<MediaItem>,
        excludeTerms: List<String>,
    ): List<MediaItem> {
        if (excludeTerms.isEmpty()) return items
        return items.filterNot { item ->
            val key = TagQuery.normalizeTitle(item.title)
            excludeTerms.any { term -> key.contains(term) }
        }
    }

    /**
     * 把标签追加到查询串（空格分隔）并立即搜索 —— 复合搜索入口（v1.5.10.0）。
     *
     * v1.6.1.0 改进：同步把 [activeTags] 也更新，让热们推荐 chip 立刻呈现「已选」状态，
     * 不用等搜索请求回来才反映。
     */
    fun addTag(tag: String) {
        if (tag.isBlank()) return
        val current = TagAlias.dedupeAliases(logicalTagsInQuery(_query.value))
        val next = TagAlias.dedupeAliases(current + tag)
        if (next.size == current.size) {
            _activeTags.value = current.map(TagAlias::canonicalOf).distinct()
            return
        }
        _query.value = next.joinToString(" ")
        _activeTags.value = next.map(TagAlias::canonicalOf).distinct()
        search()
    }

    /** 从查询查询返回某标签（点已选 chip）。最后一个标签移除时同步清空结果 / 进度 */
    fun removeTag(tag: String) {
        val parsed = logicalTagsInQuery(_query.value)
        val aliases = TagAlias.aliasesOf(tag)
        val current = parsed.filterNot { candidate ->
            TagAlias.aliasesOf(candidate).any { it in aliases }
        }
        _query.value = current.joinToString(" ")
        _activeTags.value = TagAlias.dedupeAliases(current.map(TagAlias::canonicalOf))
        if (current.isEmpty()) {
            searchGeneration += 1
            searchJob?.cancel()
            _results.value = emptyList()
            _searching.value = false
            _notice.value = null
            _progress.value = null
        } else {
            search()
        }
    }

    /**
     * 触底加载下一页（v1.8.1.2）。
     *
     * 只抓一页、增量追加，不重跑整次搜索 —— 用户往下滚多少就抓多少。
     */
    fun loadMore() {
        if (_loadingMore.value || !_hasMore.value) return
        val ctx = paging ?: return
        val generation = searchGeneration
        _loadingMore.value = true
        viewModelScope.launch {
            try {
                val page = nextPage
                val items = fetchPage(ctx, page)
                if (generation != searchGeneration) return@launch
                if (items.isEmpty()) {
                    _hasMore.value = false
                } else {
                    appendResults(items)
                    nextPage = page + 1
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TagSearch", "加载下一页失败，page=$nextPage", error)
                _hasMore.value = false
            } finally {
                _loadingMore.value = false
            }
        }
    }

    /**
     * 首次搜索完成后**后台预取 3 页**（v1.8.1.2）。
     *
     * 用户滚到底时数据往往已经在那儿了，滚动不会卡在「等下一页请求」上。
     */
    private fun preloadNextPages(generation: Long) {
        val ctx = paging ?: return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            var loaded = 0
            while (loaded < PRELOAD_PAGES) {
                if (generation != searchGeneration) return@launch
                val page = nextPage
                val items = try {
                    fetchPage(ctx, page)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("TagSearch", "预加载失败，page=$page", error)
                    emptyList()
                }
                if (generation != searchGeneration) return@launch
                if (items.isEmpty()) {
                    _hasMore.value = false
                    return@launch
                }
                appendResults(items)
                nextPage = page + 1
                loaded++
            }
        }
    }

    /** 抓某一页（单标签 / 复合严格求交 / 组合直搜三种形态） */
    private suspend fun fetchPage(ctx: PagingContext, page: Int): List<MediaItem> = when {
        ctx.single -> searchOneTag(ctx.entriesPerTag[0], page)
        ctx.allSearchOnly -> searchCombinedKeyword(
            ctx.sources, ctx.keyword, Semaphore(WIDE_COMBINED_CONCURRENCY), page,
        )
        else -> {
            val bySrc = ctx.entriesPerTag.map { list -> list.groupBy({ it.first }, { it.second }) }
            strictIntersection(
                bySrc, ctx.sources, page, Semaphore(TAG_SEARCH_CONCURRENCY), {}, ctx.union,
            ).matched
        }
    }

    /** 增量追加结果（按作品身份键去重，顺序保持） */
    private fun appendResults(items: List<MediaItem>) {
        if (items.isEmpty()) return
        _results.value = (_results.value + items).distinctBy { TagQuery.identityKey(it.title) }
    }

    /**
     * 执行标签搜索（单标签 / 复合标签通用）。
     *
     * v1.7.0.0 三处口径修正：
     * 1. **池里没有的标签不再拖垮整次搜索**：以前它照样参与交集，任何空集合都会让
     *    交集清空，而提示却写着「已按其余标签搜索」—— 说好了搜索却返回空，
     *    这正是「复合搜索没作用」最直接的一条。现在这些标签直接剔除，只在提示里点名。
     * 2. **复合搜索按「每标签全量并集求交」**（v1.7.9.9 起，见 [searchCompound]），
     *    不再限定「同源同时拥有全部入口」，避免源边界把同名作品切掉。
     * 3. **只打已启用的源**（见 [enabledSourceIds]）：标签池是含禁用源收集的，
     *    以前搜索也照着池子全打一遍，用户停用的源照样被请求、每个都要干等到超时。
     */
    // ===== 搜索进度（v1.7.1.0）：把「已完成请求数 / 当前已知请求总数」实时上屏 =====
    private val progressDone = java.util.concurrent.atomic.AtomicInteger(0)
    private val progressTotal = java.util.concurrent.atomic.AtomicInteger(0)

    private fun resetProgress() {
        progressDone.set(0)
        progressTotal.set(0)
    }

    /** 登记本批将发出 [n] 个请求（累加进总量） */
    private fun addProgressTotal(n: Int) {
        if (n <= 0) return
        progressTotal.addAndGet(n)
        publishProgress()
    }

    /** 完成 1 个请求 */
    private fun stepProgress() {
        progressDone.incrementAndGet()
        publishProgress()
    }

    private fun publishProgress() {
        val total = progressTotal.get()
        if (total <= 0) return
        _progress.value = progressDone.get().coerceAtMost(total) to total
    }

    fun search() {
        val firstParse = logicalTagsInQuery(_query.value)
        if (firstParse.isEmpty()) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _notice.value = null
            resetProgress()
            _progress.value = null

            try {
                ensurePlan()
                // v1.8.1.3 查询语法：-排除 / 大类:标签 / 通配 *
                val specs = TagQuerySpec.parse(_query.value)
                val positiveSpecs = specs.filterNot { it.exclude }
                if (positiveSpecs.isEmpty()) {
                    _results.value = emptyList()
                    _notice.value = "只写了排除条件（-标签），至少留一个要搜索的标签"
                    return@launch
                }
                // 每个正条件展开成「一组等价写法」：普通标签取别名组，通配按受控词表 + 标签池展开
                val expandedPerSpec = positiveSpecs.map(::expandClauseNames)
                // 展开为空（通配没匹配到任何词）的条件直接视为未知标签
                val uniqueSpecs = positiveSpecs.mapIndexed { i, spec -> spec to expandedPerSpec[i] }
                    .filter { (spec, names) -> names.isNotEmpty() || !spec.wildcard }
                    .distinctBy { (spec, names) -> spec.category to names }
                if (uniqueSpecs.isEmpty()) {
                    // 全是通配且一个词都没匹配到（如 `zzzz*`）
                    _results.value = emptyList()
                    _notice.value = "通配没有匹配到任何标签，换个写法试试"
                    return@launch
                }

                val tags = uniqueSpecs.map { (spec, _) -> spec.name }
                val namesPerTag = uniqueSpecs.map { it.second }
                val clauses = uniqueSpecs.map { (spec, names) ->
                    com.novelreader.data.repository.TagIndexRepository.TagClause(names, spec.category)
                }
                // 排除条件：索引侧整条剔除；网络侧按标题包含关系过滤（源站结果不带标签）
                val excludeNames = specs.filter { it.exclude }
                    .flatMap(::expandClauseNames)
                    .toSet()
                val excludeTerms = specs.filter { it.exclude }
                    .flatMap { spec -> TagAlias.searchTermsFor(spec.name) + spec.name }
                    .map(TagQuery::normalizeTitle)
                    .filter { it.isNotEmpty() }

                // v1.8.1.2：**索引与搜索不冲突**。
                // 先看本地索引里已经收录的，有就立刻显示 —— 但**不 return**，
                // 网络搜索照跑，新结果合并进来、再回写索引，越搜越全。
                // v1.8.1.3：索引查询带上大类 / 排除 / 成人整类过滤，并返回置信度排序。
                val indexedTotal = tagIndexRepository?.count(type) ?: 0
                _indexCount.value = indexedTotal
                var localItems: List<MediaItem> = emptyList()
                if (indexedTotal > 0 && clauses.isNotEmpty()) {
                    val hideAdult = preferencesManager?.hideAdultFlow?.first() ?: true
                    val explicitAdult = tags.any(TagAlias::isExplicitAdultIntent) ||
                        uniqueSpecs.any { (spec, _) -> spec.category == TagTagger.CATEGORY_ADULT }
                    localItems = tagIndexRepository?.search(
                        type = type,
                        clauses = clauses,
                        excludeTags = excludeNames,
                        hideAdult = hideAdult && !explicitAdult,
                    ).orEmpty()
                    if (localItems.isNotEmpty()) {
                        _results.value = localItems
                    }
                }

                val enabledIds = enabledSourceIds()
                if (enabledIds != null && enabledIds.isEmpty()) {
                    _activeTags.value = emptyList()
                    _results.value = emptyList()
                    _notice.value = "没有已启用的源，先去源管理里打开一个"
                    return@launch
                }
                val variantsPerTag = uniqueSpecs.map { (spec, names) ->
                    names.flatMap { name -> TagAlias.matchesInPool(name, tagPlan.tags) }
                        .distinct()
                        .ifEmpty { listOf(spec.name) }
                }
                // 按源搜索：选了某个源就只保留它的入口（优先级高于「已启用源」集合）
                val allowedIds = _selectedSourceIds.value.takeIf { it.isNotEmpty() } ?: enabledIds
                val entriesPerTag = uniqueSpecs.mapIndexed { index, (spec, _) ->
                    variantsPerTag[index]
                        .flatMap { variant -> tagPlan.searchEntriesFor(variant, spec.name) }
                        .distinct()
                        .let { if (allowedIds == null) it else it.filter { e -> e.first in allowedIds } }
                        // v1.8.1.0：同一个源最多留 [MAX_URLS_PER_SOURCE] 条入口。
                        // 池里有多个等价写法时，上面的 flatMap 会让同一个源出现好几条
                        // 相同的搜索入口，请求数被平白翻倍。
                        .let(::limitEntriesPerSource)
                }
                val known = tags.filterIndexed { i, _ -> entriesPerTag[i].isNotEmpty() }
                val missing = tags.filterNot { it in known }
                val knownEntries = entriesPerTag.filter { it.isNotEmpty() }
                // 纯关键词搜索（没有真实分类页）才做标题相关性过滤：
                // 源站的包含匹配会把《全面接触》算进「全彩」、把英文源的一堆无关漫画算进「无码」，
                // 不过滤的话流式上屏的那几秒全是噪声（v1.8.1.2）。
                val searchOnlyResult = knownEntries.all { list -> list.all(::isSearchEntry) }

                // v1.8.1.1：**流式上屏**。以前只有「明确成人标签」才允许增量刷新，
                // 用户点完搜索只能对着转圈等全部源返回。现在只要有结果就先显示，
                // 后面的源陆续补充进来（结果一致单调增加）。
                // v1.8.1.2：中间结果也要过相关性过滤，否则用户先看到的全是不相关的东西。
                val updatePartialResults: (List<MediaItem>) -> Unit = { partial ->
                    if (generation == searchGeneration) {
                        val visible =
                            if (searchOnlyResult) filterByGroupKeywords(partial, tags) else partial
                        if (visible.isNotEmpty()) _results.value = visible
                    }
                }
                val translatedNotice = tags.mapIndexedNotNull { i, tag ->
                    val variants = variantsPerTag[i]
                    val self = TagQuery.normalizeTitle(tag)
                    val terms = TagAlias.searchTermsFor(tag)
                    val hasKeywordEntries = entriesPerTag[i].any { (_, url) ->
                        TagEntryUrl.searchKeywordOf(url) != null ||
                            TagEntryUrl.categoryOf(url)?.kind == "search"
                    }
                    when {
                        terms.size > 1 && hasKeywordEntries ->
                            "搜索「${tag}」时同时检索：${terms.joinToString("、")}"
                        variants.any { TagQuery.normalizeTitle(it) != self } ->
                            "「${tag}」已按 ${variants.joinToString("、")} 搜索"
                        else -> null
                    }
                }.joinToString("；")

                // 复合搜索把「大集」**完整**摊开显示（v1.8.1.0 起不再只给前 5 个）：
                // 用户能一眼确认每一组到底包含哪些转译写法，也就明白结果是
                // 「每组各命中一个」而不是「随便命中一个」。
                val compoundNote = if (tags.size > 1) {
                    tags.joinToString("  ∩  ") { tag ->
                        val group = TagAlias.searchTermsFor(tag).ifEmpty { listOf(tag) }
                        "「$tag」组 ${group.size} 个写法：${group.joinToString("、")}"
                    }
                } else {
                    null
                }

                if (known.isEmpty()) {
                    // 网络侧一个可搜入口都没有：只要索引命中就直接展示，
                    // 不再像旧版那样把已经查到的结果一起清空。
                    if (localItems.isNotEmpty()) {
                        _activeTags.value = tags.map(TagAlias::canonicalOf).distinct()
                        _results.value = localItems
                        _notice.value = "本地索引命中 ${localItems.size} 部；网络源没有这些标签的入口"
                        return@launch
                    }
                    _activeTags.value = emptyList()
                    _results.value = emptyList()
                    _notice.value = listOfNotNull(
                        translatedNotice.takeIf { it.isNotBlank() },
                        "未找到标签：${missing.joinToString("、")}，" +
                            if (missing.size == tags.size) {
                                "换个写法，或点「已收录」旁的入口建本地索引"
                            } else {
                                "复合搜索要求全部标签都存在"
                            },
                    ).joinToString("；")
                    return@launch
                }

                // v1.8.1.3：语法修饰（排除 / 大类 / 通配）也写进提示 ——
                // 用户写了 `-有码` 却没看到任何说明时，会以为条件被吞了。
                val syntaxNote = specs
                    .filter { it.exclude || it.category != null || it.wildcard }
                    .joinToString("；") { spec ->
                        buildString {
                            append(TagQuerySpec.describe(spec))
                            if (spec.wildcard) append("（展开 ${expandClauseNames(spec).size} 个写法）")
                        }
                    }
                    .takeIf { it.isNotBlank() }
                val headNote = listOfNotNull(
                    syntaxNote,
                    compoundNote ?: translatedNotice.takeIf { it.isNotBlank() },
                ).joinToString("；").takeIf { it.isNotBlank() }

                _notice.value = headNote
                val outcome = if (knownEntries.size == 1) {
                    CompoundOutcome(searchOneTag(knownEntries[0], onPartial = updatePartialResults))
                } else {
                    searchCompound(knownEntries, known, updatePartialResults)
                }
                val searchResult = outcome.items
                if (generation != searchGeneration) return@launch
                // 同一部作品在不同源上标题写法可能不同（繁简 / 带作者后缀），
                // 按作品身份键再去重一次，避免结果里出现两条「同一部」。
                val network = withoutExcluded(
                    filterAdults(searchResult, tags)
                        .let { if (searchOnlyResult) filterByGroupKeywords(it, tags) else it },
                    excludeTerms,
                )
                // 索引里的是「按真实题材命中」的，不再过标题过滤；与网络结果合并去重
                val final = withoutExcluded(localItems + network, excludeTerms)
                    .distinctBy { TagQuery.identityKey(it.title) }
                if (generation != searchGeneration) return@launch
                _activeTags.value = known.map(TagAlias::canonicalOf).distinct()
                _results.value = final
                // 边搜边标记：把这批结果写进本地索引（后台执行，不打断搜索）
                if (final.isNotEmpty()) {
                    viewModelScope.launch {
                        runCatching { tagIndexRepository?.indexItems(type, final) }
                        _indexCount.value = tagIndexRepository?.count(type) ?: 0
                    }
                }
                // 分页上下文：后续「加载更多」按同一批入口抓下一页（v1.8.1.2）
                if (final.isNotEmpty()) {
                    paging = PagingContext(
                        tags = known,
                        entriesPerTag = knownEntries,
                        sources = knownEntries.flatMap { list -> list.map { it.first } }.distinct(),
                        allSearchOnly = knownEntries.all { list -> list.all(::isSearchEntry) },
                        single = knownEntries.size == 1,
                        keyword = known.joinToString(" "),
                        union = outcome.union.orEmpty(),
                    )
                    nextPage = 2
                    _hasMore.value = true
                    preloadNextPages(generation)
                } else {
                    paging = null
                    _hasMore.value = false
                }
                val tail = when {
                    final.isNotEmpty() -> outcome.note
                    known.size > 1 -> "没有作品同时属于「${known.joinToString("」「")}」（每个大集里各要命中至少一个标签）"
                    else -> "「${known.first()}」下暂无内容"
                }
                _notice.value = listOfNotNull(headNote, tail)
                    .joinToString("；")
                    .takeIf { it.isNotBlank() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("TagSearch", "标签搜索失败，type=$type", error)
                if (generation == searchGeneration) {
                    _results.value = emptyList()
                    _notice.value = "标签搜索失败，请重试或检查书源配置"
                }
            } finally {
                if (generation == searchGeneration) {
                    _progress.value = null
                    _searching.value = false
                }
            }
        }
    }

    /** 计划为空时补一次（冷启动直进标签搜索） */
    private suspend fun ensurePlan() {
        if (tagPlan.plan.isNotEmpty()) return
        tagPlan = tagRepository.tags(type)
        _allTags.value = tagPlan.tags
        _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
        _tagsByCoverage.value = tagPlan.hotTags(0)
    }

    /**
     * 该类型下**已启用**的源 id 集合；查不到（没有 SourceRepository / 异常）返回 null = 不过滤。
     *
     * [TagRepository] 的标签池是**含禁用源**收集的（v1.6.5.0 起的有意口径：停用某源不该
     * 让它的标签从池子里消失），但真正发请求必须只打已启用的源 —— 否则用户停用了一个源，
     * 标签搜索依旧去请求它，每个都要干等到超时才罢休（默认 20s）。实测在一次复合搜索里，
     * 12 个参与源中有 10 个是已停用的，点一下标签要等好几分钟才出结果。
     * 没有源仓库时返回 null；读取失败则向上报错，不用「不过滤」掩盖故障。
     */
    private suspend fun enabledSourceIds(): Set<String>? = try {
        sourceRepository?.getSourcesByType(type)?.filter { it.enabled }?.map { it.id }?.toSet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.e("TagSearch", "读取已启用书源失败，type=$type", error)
        throw error
    }

    /**
     * 单标签：聚合所有拥有该分类的源，抓**指定一页**（v1.8.1.2 改为分页）。
     *
     * 以前每源固定抓 1~2 页就结束，结果自然少；现在每页由 UI 触底触发，
     * 用户想看多少就往下翻多少，且每返回一个源就回调，边出边显示。
     */
    private suspend fun searchOneTag(
        entries: List<Pair<String, String>>,
        page: Int = 1,
        semaphore: Semaphore = Semaphore(TAG_SEARCH_CONCURRENCY),
        onPartial: (List<MediaItem>) -> Unit = {},
    ): List<MediaItem> =
        coroutineScope {
            val accumulated = mutableListOf<MediaItem>()
            val lock = Any()
            addProgressTotal(entries.size)
            entries.map { (srcId, url) ->
                async {
                    val items = semaphore.withPermit {
                        try {
                            exploreTagEntry(srcId, url, page)
                        } finally {
                            stepProgress()
                        }
                    }
                    if (items.isNotEmpty()) {
                        val snapshot = synchronized(lock) {
                            accumulated.addAll(items)
                            accumulated.distinctBy { "${it.sourceId}:${it.id}" }
                        }
                        onPartial(snapshot)
                    }
                    items
                }
            }.awaitAll()
            synchronized(lock) { accumulated.distinctBy { "${it.sourceId}:${it.id}" } }
        }

    /**
     * 复合标签：**每个标签先取全量并集，再把各并集按标题求交**（v1.7.9.9 重构）。
     *
     * 口径示例：「无码 全彩」= （无码 + 無修正 + uncensored… 全部译名 / 转义写法 /
     * 全部源的结果并集）∩（全彩 + full color + カラー… 的全部并集）。
     *
     * 与 v1.7.0.0 ~ v1.7.9.8 的「同源严格相交」的区别：
     * - 不再要求「同一个源同时拥有两个标签入口」才参与。以前无码只在 A 站、全彩只在
     *   B 站时，两个标签的源集合没有交集，直接返回空；现在 A 的并集与 B 的并集照样
     *   按标题相交，同名作品能被找出来。
     * - 判定同一部作品仍用**归一化标题**（[TagQuery.normalizeTitle]）：跨源同名即视为
     *   同一部 —— 这是用户口径，代价是同名不同作会合并成一条。
     * - 交集为空时保持空结果，不回退成并集。
     */
    private suspend fun searchCompound(
        entriesPerTag: List<List<Pair<String, String>>>,
        tags: List<String>,
        onPartial: (List<MediaItem>) -> Unit,
    ): CompoundOutcome {
        if (entriesPerTag.isEmpty() || entriesPerTag.any { it.isEmpty() }) return CompoundOutcome.EMPTY

        // 并集口径：每个标签查它自己的全部入口；源集合取并集（各标签的源可以完全不同）。
        val sources = entriesPerTag.flatMap { entries -> entries.map { it.first } }.distinct()
        if (sources.isEmpty()) return CompoundOutcome.EMPTY

        val wideComic = type == SourceType.COMIC &&
            tags.all { TagAlias.canonicalOf(it) in WIDE_COMIC_SEARCH_TAGS }
        val semaphore = Semaphore(
            if (wideComic) WIDE_COMPOUND_CONCURRENCY else TAG_SEARCH_CONCURRENCY,
        )

        // v1.8.1.2 **关键修正**：只有「真实分类入口」才代表「作品属于这个标签」。
        // 纯关键词入口搜出来的是「标题里出现了这个词」，把它当作该标签的成员去求交，
        // 只会得到一堆看起来毫无关系的内容（实测：搜「无码 全彩」回来《全面接触》
        // 和一堆英文无关漫画）。所以这里把两类入口分开。
        val categoryEntries = entriesPerTag.map { list -> list.filterNot(::isSearchEntry) }
        val withoutCategory = categoryEntries.indices.filter { categoryEntries[it].isEmpty() }

        if (withoutCategory.isNotEmpty()) {
            // 有标签在漫画源里根本没有分类页 → 「组合筛选」在数据层不成立。
            // 这时**如实**展示各标签单独搜到的内容（标题必须真的包含对应写法），
            // 而不是拿源站宽松匹配的结果冒充交集。
            val union = coroutineScope {
                entriesPerTag.map { entries ->
                    async { searchOneTag(entries, 1, semaphore) }
                }.awaitAll().flatten()
            }.distinctBy { "${it.sourceId}:${it.id}" }
            val visible = filterByGroupKeywords(union, tags)
            if (visible.isEmpty()) return CompoundOutcome.EMPTY
            onPartial(visible)
            return CompoundOutcome(
                visible,
                "「${withoutCategory.joinToString("」「") { tags[it] }}」在漫画源里没有真实分类页、" +
                    "只能按标题搜，无法与其它标签做组合筛选；下面是各标签单独搜到、" +
                    "且标题确实包含对应写法的内容（无码 / 全彩这类属性常不写进标题，搜不到不代表没有）",
            )
        }

        // 全部标签都有真实分类页 → 严格求交（组内并、组间交）
        val bySrcPerTag = categoryEntries.map { list -> list.groupBy({ it.first }, { it.second }) }
        val strict = strictIntersection(bySrcPerTag, sources, 1, semaphore, onPartial)
        return if (strict.matched.isNotEmpty()) {
            CompoundOutcome(strict.matched, union = strict.unionPerTag)
        } else {
            CompoundOutcome.EMPTY
        }
    }
    private data class StrictResult(
        val matched: List<MediaItem>,
        val unionPerTag: List<LinkedHashMap<String, MediaItem>>,
    )

    /**
     * 严格求交（单页）：每个大集先取组内并集（组内 OR），再把各大集按作品身份键求交（组间 AND）。
     *
     * v1.8.1.2 改为**单页**：翻页交给 UI 触底触发（[loadMore]），[carry] 用于跨页累积，
     * 否则第 2 页只能和本页自己求交，跨源同名作品会对不上。
     */
    private suspend fun strictIntersection(
        bySrcPerTag: List<Map<String, List<String>>>,
        sources: List<String>,
        page: Int,
        semaphore: Semaphore,
        onPartial: (List<MediaItem>) -> Unit,
        carry: List<LinkedHashMap<String, MediaItem>>? = null,
    ): StrictResult {
        val unionPerTag = carry ?: MutableList(bySrcPerTag.size) {
            LinkedHashMap<String, MediaItem>()
        }
        val fetched = fetchGrid(bySrcPerTag, sources, page, semaphore)
        fetched.forEachIndexed { tagIndex, perSource ->
            perSource.values.flatten().forEach { item ->
                val key = TagQuery.identityKey(item.title)
                    .ifEmpty { "${item.sourceId}:${item.id}" }
                unionPerTag[tagIndex].putIfAbsent(key, item)
            }
        }
        val matched = TagQuery.intersect(unionPerTag.map { it.values.toList() })
        onPartial(matched)
        return StrictResult(matched, unionPerTag)
    }

    /**
     * 组合关键词直搜：把多个标签拼成一次查询，抓**指定一页**（v1.8.1.2 分页化）。
     *
     * 哪个源先返回就先追加（流式），用户点完几秒就能看到第一批。
     */
    private suspend fun searchCombinedKeyword(
        sources: List<String>,
        keyword: String,
        semaphore: Semaphore,
        page: Int = 1,
        onPartial: (List<MediaItem>) -> Unit = {},
    ): List<MediaItem> = coroutineScope {
        val url = TagEntryUrl.search(keyword)
        val accumulated = mutableListOf<MediaItem>()
        val lock = Any()
        addProgressTotal(sources.size)
        sources.map { sourceId ->
            async {
                val items = semaphore.withPermit {
                    try {
                        exploreTagEntry(sourceId, url, page)
                    } finally {
                        stepProgress()
                    }
                }
                if (items.isNotEmpty()) {
                    val snapshot = synchronized(lock) {
                        accumulated.addAll(items)
                        accumulated.distinctBy { "${it.sourceId}:${it.id}" }
                    }
                    onPartial(snapshot)
                }
                items
            }
        }.awaitAll()
        synchronized(lock) { accumulated.distinctBy { "${it.sourceId}:${it.id}" } }
    }

    /**
     * 相关性过滤（v1.8.1.2）。
     *
     * 源站的关键词搜索是「包含匹配」：搜「无码 全彩」会把标题里只有一个「全」字的
     * 《全面接触》也返回来（实测就是这样），列表看着跟标签毫无关系。
     * 这里要求标题**至少命中一个大集里的某个写法**，把这种纯噪声滤掉。
     *
     * 注意：不要求两组都命中 —— 「无码」「全彩」这类属性大多不写进标题，
     * 要求同时命中会直接归零；两组都命中的情况由严格求交那条路负责。
     */
    private fun filterByGroupKeywords(items: List<MediaItem>, tags: List<String>): List<MediaItem> {
        if (items.isEmpty()) return items
        val groups = tags.map { tag ->
            TagAlias.searchTermsFor(tag)
                .map { it.lowercase().replace(" ", "") }
                .filter { it.isNotEmpty() }
        }
        return items.filter { item ->
            val title = item.title.lowercase().replace(" ", "")
            groups.any { terms -> terms.any { title.contains(it) } }
        }
    }

    /** 该入口是否**只能按关键词搜**（源站没给这个标签真实分类页）。 */
    private fun isSearchEntry(entry: Pair<String, String>): Boolean =
        TagEntryUrl.searchKeywordOf(entry.second) != null ||
            TagEntryUrl.categoryOf(entry.second)?.kind == "search"

    /**
     * 每个源只保留 [MAX_URLS_PER_SOURCE] 条入口。
     *
     * 标签池里同一个标签常挂着多个等价写法的入口（真分类 + 搜索），全发出去会让请求数
     * 成倍膨胀；保留时优先真分类页（结果就是该分类的作品，比标题搜索准得多）。
     */
    private fun limitEntriesPerSource(
        entries: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        if (entries.size <= MAX_URLS_PER_SOURCE) return entries
        val bySource = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        entries.forEach { entry -> bySource.getOrPut(entry.first) { mutableListOf() }.add(entry) }
        return bySource.values.flatMap { list ->
            if (list.size <= MAX_URLS_PER_SOURCE) {
                list
            } else {
                list.sortedBy { entry -> if (isSearchEntry(entry)) 1 else 0 }
                    .take(MAX_URLS_PER_SOURCE)
            }
        }
    }

    /** 并发抓取 `[标签][源]` 请求，所有参与源共享同一个并发限制。 */
    private suspend fun fetchGrid(
        bySrcPerTag: List<Map<String, List<String>>>,
        sources: List<String>,
        page: Int,
        semaphore: Semaphore,
    ): List<Map<String, List<MediaItem>>> = coroutineScope {
        val requests = bySrcPerTag.flatMapIndexed { tagIndex, bySource ->
            sources.flatMap { sourceId ->
                bySource[sourceId].orEmpty().map { url ->
                    TagSearchRequest(tagIndex, sourceId, url)
                }
            }
        }
        addProgressTotal(requests.size)
        val responses = requests.map { request ->
            async {
                val items = semaphore.withPermit {
                    try {
                        exploreTagEntry(request.sourceId, request.url, page)
                    } finally {
                        stepProgress()
                    }
                }
                TagSearchResponse(request.tagIndex, request.sourceId, items)
            }
        }.awaitAll()
        List(bySrcPerTag.size) { tagIndex ->
            LinkedHashMap<String, List<MediaItem>>().apply {
                responses.asSequence()
                    .filter { it.tagIndex == tagIndex }
                    .groupBy { it.sourceId }
                    .forEach { (sourceId, sourceResponses) ->
                        put(
                            sourceId,
                            sourceResponses.flatMap { it.items }
                                .distinctBy { "${it.sourceId}:${it.id}" },
                        )
                    }
            }
        }
    }

    /**
     * 单个标签请求，带**硬超时**（v1.8.1.1）。
     *
     * 标签搜索会同时打几十个源，只要有几个源卡住，整次搜索就被拖到它们超时为止 ——
     * 这是「进度条走到 40/41 停住不动」的常见原因。这里给每个请求设上限，
     * 超时就当这个源没有结果，先让用户看到其余源的内容。
     */
    private suspend fun exploreTagEntry(sourceId: String, url: String, page: Int): List<MediaItem> =
        try {
            withTimeout(SEARCH_REQUEST_TIMEOUT_MS) {
                contentRepository.explore(sourceId, url, page)
            }
        } catch (timeout: TimeoutCancellationException) {
            android.util.Log.w("TagSearch", "标签请求超时（${SEARCH_REQUEST_TIMEOUT_MS}ms），sourceId=$sourceId")
            emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("TagSearch", "标签请求失败，sourceId=$sourceId", error)
            emptyList()
        }

    /** 成人源内容默认隐藏；用户明确选择成人/修饰标签时视为本次主动请求。 */
    private suspend fun filterAdults(items: List<MediaItem>, tags: List<String>): List<MediaItem> {
        if (items.isEmpty()) return items
        val adultIds = sourceRepository?.getSourcesByType(type)
            ?.filter { it.isAdult }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
        if (adultIds.isEmpty()) return items
        val hide = preferencesManager?.hideAdultFlow?.first() ?: true
        val explicitlyRequested = tags.any(TagAlias::isExplicitAdultIntent)
        return if (hide && !explicitlyRequested) items.filterNot { it.sourceId in adultIds }
        else items.sortedBy { it.sourceId in adultIds }
    }

    companion object {
        private val WIDE_COMIC_SEARCH_TAGS = setOf("无码", "全彩")

        /**
         * 并发上限（v1.8.1.1 上调）。
         *
         * 标签搜索是「几十个源各发一次、彼此独立」的典型场景，串行等最慢的源毫无意义。
         * 普通组合 8 → 12，宽标签（无码 / 全彩走组合直搜）16 → 32：
         * 41 个源两批就能打完，配合单请求 8 秒硬超时，整体从分钟级压到十秒级。
         */
        private const val TAG_SEARCH_CONCURRENCY = 24
        private const val WIDE_COMPOUND_CONCURRENCY = 32

        /** 组合关键词直搜的并发（与严格求交并行时各占一半，避免互相抢占） */
        private const val WIDE_COMBINED_CONCURRENCY = 16

        /** 单标签搜索每个源抓几页（v1.8.1.1：原来只抓 1 页，结果太少） */
        private const val SINGLE_TAG_PAGES = 2

        /** 组合关键词直搜每个源抓几页 */
        private const val COMBINED_SEARCH_PAGES = 2

        /** 首次搜索完成后后台预取几页（v1.8.1.2） */
        private const val PRELOAD_PAGES = 3

        /**
         * 单个标签请求的硬超时（v1.8.1.1）。
         *
         * 源站卡住时默认要等 20 秒，一个源就能把整次搜索拖到 20 秒以上；
         * 8 秒拿不到就当作该源没有结果，先让用户看到其余源的内容。
         */
        private const val SEARCH_REQUEST_TIMEOUT_MS = 8_000L

        /** 组合关键词结果的缓存有效期（10 分钟） */
        private const val COMBINED_CACHE_TTL_MS = 10 * 60 * 1000L

        /**
         * 同一个标签下**每个源**最多保留几条入口（v1.8.1.0）。
         *
         * 标签池里一个标签常挂着多条入口（真分类 + 若干搜索写法），全发出去请求数成倍增长：
         * 搜「无码 全彩」实测会打出上千个请求。按源截到 2 条后请求量降一个数量级，
         * 真分类入口优先保留（它给出的就是该分类的作品，比标题搜索准）。
         */
        private const val MAX_URLS_PER_SOURCE = 2

        /** 热门推荐标签上限（v1.6.1.0） */
        const val HOT_TAG_LIMIT = 12

        /**
         * 复合搜索每源翻页上限（v1.7.0.0；v1.8.0.0 起改为「按需翻页」）。
         *
         * 以前固定翻 2 页：够不够都只翻 2 页 —— 结果太少时用户毫无办法。
         * 现在改成双重条件：**交集够 [COMPOUND_ENOUGH_RESULTS] 条就停**（常见情况
         * 只请求 1 页，比过去更快），不够才继续往下翻，最多到本上限。
         * 上限从 2 提到 6：结果空/极少时多翻几页才有机会捞出跨源同作。
         */
        const val MAX_COMPOUND_PAGES = 6

        /**
         * 复合搜索「够了」的阈值（v1.8.0.0）。
         *
         * 一屏网格约 20~30 条，两屏量级即可满足浏览；再多用户也翻不到，
         * 换取的是少跑几轮网络请求。
         */
        const val COMPOUND_ENOUGH_RESULTS = 40
    }
}
