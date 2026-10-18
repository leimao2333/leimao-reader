package com.novelreader.ui.screens.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagCompound
import com.novelreader.core.util.TagQuery
import com.novelreader.data.cache.TagPlan
import com.novelreader.data.repository.TagRepository
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.source.category.isAdult
import kotlinx.coroutines.CancellationException
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

/**
 * 标签搜索 ViewModel（v1.5.10.0 起；v1.6.1.0 重做 UI 后保留全量 + 新增热门标签；
 * v1.7.0.0 重修复合搜索）。
 *
 * - 标签来自 [TagRepository] 自动收集的各源发现分类（落盘缓存，冷启动即有）；
 * - **热门标签**：按标签覆盖源数倒序，取 top [HOT_TAG_LIMIT] 展示在搜索页顶部；
 * - **单标签搜索**：聚合所有拥有该分类的源的第 1 页内容，跨源去重；
 * - **复合搜索**：先对所有共同源的前两页求交；交集为空时依次退回跨源同名匹配与
 *   标签结果并集，避免组合条件把页面变成空白；
 * - 视频结果把 18+ 成人源内容沉底（与首页/搜索一致）。
 */
class TagSearchViewModel(
    private val contentRepository: ContentRepository,
    private val tagRepository: TagRepository,
    private val sourceRepository: SourceRepository?,
    val type: SourceType,
    initialTag: String,
    private val preferencesManager: com.novelreader.core.preferences.PreferencesManager? = null,
) : ViewModel() {

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
    private val _activeTags = MutableStateFlow<List<String>>(emptyList())
    val activeTags: StateFlow<List<String>> = _activeTags.asStateFlow()

    private var tagPlan: TagPlan = TagPlan.EMPTY
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchGeneration = 0L

    private data class TagSearchRequest(val tagIndex: Int, val sourceId: String, val url: String)
    private data class TagSearchResponse(val tagIndex: Int, val sourceId: String, val items: List<MediaItem>)

    init {
        collectTags(initialTag.isNotBlank())
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
    }

    /**
     * 把标签追加到查询串（空格分隔）并立即搜索 —— 复合搜索入口（v1.5.10.0）。
     *
     * v1.6.1.0 改进：同步把 [activeTags] 也更新，让热们推荐 chip 立刻呈现「已选」状态，
     * 不用等搜索请求回来才反映。
     */
    fun addTag(tag: String) {
        if (tag.isBlank()) return
        val current = TagAlias.dedupeAliases(
            TagQuery.parseWithPool(_query.value, tagPlan.tags).map(TagAlias::canonicalOf),
        )
        val next = TagAlias.dedupeAliases(current + TagAlias.canonicalOf(tag))
        if (next.size == current.size) {
            _query.value = current.joinToString(" ")
            _activeTags.value = current
            return
        }
        _query.value = next.joinToString(" ")
        _activeTags.value = next
        search()
    }

    /** 从查询查询返回某标签（点已选 chip）。最后一个标签移除时同步清空结果 / 进度 */
    fun removeTag(tag: String) {
        // 以已选列表为准（chips 就是它渲染的）；手输场景才回退到解析查询串
        val parsed = TagAlias.dedupeAliases(
            (if (tag in _activeTags.value) _activeTags.value
            else TagQuery.parseWithPool(_query.value, tagPlan.tags)).map(TagAlias::canonicalOf),
        )
        val aliases = TagAlias.aliasesOf(tag)
        val current = parsed.filterNot { candidate ->
            TagAlias.aliasesOf(candidate).any { it in aliases }
        }
        _query.value = current.joinToString(" ")
        _activeTags.value = current
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
     * 执行标签搜索（单标签 / 复合标签通用）。
     *
     * v1.7.0.0 三处口径修正：
     * 1. **池里没有的标签不再拖垮整次搜索**：以前它照样参与交集，任何空集合都会让
     *    交集清空，而提示却写着「已按其余标签搜索」—— 说好了搜索却返回空，
     *    这正是「复合搜索没作用」最直接的一条。现在这些标签直接剔除，只在提示里点名。
     * 2. **复合先查同源交集，再回退跨源同名或去重并集**（见 [TagCompound]），
     *    避免来源分类体系不同导致复合搜索整页空白。
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
        val firstParse = TagQuery.parseWithPool(_query.value, tagPlan.tags)
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
                val tags = TagQuery.parseWithPool(_query.value, tagPlan.tags)
                    .ifEmpty { firstParse }
                    .map(TagAlias::canonicalOf)
                    .distinct()
                    .let { TagAlias.dedupeAliases(it) }
                _query.value = tags.joinToString(" ")

                val enabledIds = enabledSourceIds()
                if (enabledIds != null && enabledIds.isEmpty()) {
                    _activeTags.value = emptyList()
                    _results.value = emptyList()
                    _notice.value = "没有已启用的源，先去源管理里打开一个"
                    return@launch
                }
                val variantsPerTag = tags.map { tag ->
                    TagAlias.matchesInPool(tag, tagPlan.tags).ifEmpty { listOf(tag) }
                }
                val entriesPerTag = variantsPerTag.map { variants ->
                    variants
                        .flatMap { tagPlan.entriesFor(it) }
                        .distinct()
                        .let { if (enabledIds == null) it else it.filter { e -> e.first in enabledIds } }
                }
                val known = tags.filterIndexed { i, _ -> entriesPerTag[i].isNotEmpty() }
                val missing = tags.filterNot { it in known }
                val knownEntries = entriesPerTag.filter { it.isNotEmpty() }
                val updatePartialResults: (List<MediaItem>) -> Unit = { partial ->
                    if (
                        generation == searchGeneration &&
                        tags.any(TagAlias::isExplicitAdultIntent)
                    ) {
                        _results.value = partial
                    }
                }
                val translatedNotice = tags.mapIndexedNotNull { i, tag ->
                    val variants = variantsPerTag[i]
                    val self = TagQuery.normalizeTitle(tag)
                    if (variants.none { TagQuery.normalizeTitle(it) != self }) return@mapIndexedNotNull null
                    "「${tag}」已按 ${variants.joinToString("、")} 搜索"
                }.joinToString("；")

                if (known.isEmpty()) {
                    _activeTags.value = emptyList()
                    _results.value = emptyList()
                    _notice.value = "没有找到标签：${tags.joinToString("、")}（点右上角刷新标签试试）"
                    return@launch
                }

                val searchResult = if (knownEntries.size == 1) {
                    TagCompound.RelaxedResult(
                        searchOneTag(knownEntries[0], onPartial = updatePartialResults),
                        usedUnionFallback = false,
                    )
                } else {
                    searchCompound(knownEntries, known, updatePartialResults)
                }
                if (generation != searchGeneration) return@launch
                val final = filterAdults(searchResult.items, tags)
                if (generation != searchGeneration) return@launch
                _activeTags.value = known
                _results.value = final
                val tail = when {
                    missing.isNotEmpty() ->
                        "未找到标签：${missing.joinToString("、")}，已按其余标签搜索"
                    searchResult.usedUnionFallback ->
                        "没有作品同时匹配全部标签，已显示任一标签匹配的结果"
                    final.isNotEmpty() -> null
                    known.size > 1 -> "没有作品同时属于「${known.joinToString("」「")}」"
                    else -> "「${known.first()}」下暂无内容"
                }
                _notice.value = listOfNotNull(
                    translatedNotice.takeIf { it.isNotBlank() },
                    tail,
                ).joinToString("；").takeIf { it.isNotBlank() }
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

    /** 单标签：聚合所有拥有该分类的源的第 1 页，跨源去重 */
    private suspend fun searchOneTag(
        entries: List<Pair<String, String>>,
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
                            exploreTagEntry(srcId, url, 1)
                        } finally {
                            stepProgress()
                        }
                    }
                    val snapshot = synchronized(lock) {
                        accumulated.addAll(items)
                        accumulated.distinctBy { "${it.sourceId}:${it.id}" }
                    }
                    onPartial(snapshot)
                    items
                }
            }.awaitAll().flatten().distinctBy { "${it.sourceId}:${it.id}" }
        }

    /**
     * 复合标签：先取所有同时含标签入口的源做交集；若仍为空，退回跨源同名匹配，
     * 最后显示任一标签命中的去重并集，避免复合搜索无结果时整页空白。
     */
    private suspend fun searchCompound(
        entriesPerTag: List<List<Pair<String, String>>>,
        tags: List<String>,
        onPartial: (List<MediaItem>) -> Unit,
    ): TagCompound.RelaxedResult {
        val participants = TagCompound.participants(entriesPerTag)
        if (participants.isEmpty()) {
            return TagCompound.withUnionFallback(
                strictMatches = emptyList(),
                resultsPerTag = entriesPerTag.map { searchOneTag(it) },
            )
        }

        if (
            type == SourceType.COMIC &&
            tags.all { TagAlias.canonicalOf(it) in WIDE_COMIC_SEARCH_TAGS }
        ) {
            val semaphore = Semaphore(WIDE_COMPOUND_CONCURRENCY)
            val partialPerTag = MutableList(entriesPerTag.size) { emptyList<MediaItem>() }
            val results = coroutineScope {
                entriesPerTag.mapIndexed { tagIndex, entries ->
                    async {
                        searchOneTag(entries, semaphore) { items ->
                            partialPerTag[tagIndex] = items
                            onPartial(TagCompound.merge(partialPerTag))
                        }
                    }
                }.awaitAll()
            }
            val byTagAndSource = results.map { items -> items.groupBy { it.sourceId } }
            val strictMatches = TagCompound.intersectBySource(byTagAndSource)
            return TagCompound.withUnionFallback(strictMatches, results)
        }

        val bySrcPerTag = entriesPerTag.map { list -> list.associate { it.first to it.second } }
        val candidatesPerTag = MutableList(entriesPerTag.size) { mutableListOf<MediaItem>() }
        var collected: List<MediaItem> = emptyList()

        var page = 1
        while (page <= MAX_COMPOUND_PAGES) {
            val fetched = fetchGrid(bySrcPerTag, participants, page)
            fetched.forEachIndexed { tagIndex, perSource ->
                candidatesPerTag[tagIndex].addAll(perSource.values.flatten())
            }
            val hits = TagCompound.intersectBySource(fetched)
            if (hits.isNotEmpty()) {
                collected = TagCompound.merge(listOf(collected, hits))
            }
            if (fetched.all { perSource -> perSource.values.all { it.isEmpty() } }) break
            page += 1
        }
        if (collected.isNotEmpty()) {
            return TagCompound.RelaxedResult(collected, usedUnionFallback = false)
        }

        return TagCompound.withUnionFallback(
            strictMatches = emptyList(),
            resultsPerTag = candidatesPerTag.map { items ->
                items.distinctBy { "${it.sourceId}:${it.id}" }
            },
        )
    }

    /** 并发抓取 `[标签][源]` 请求，所有参与源共享八路网络并发限制。 */
    private suspend fun fetchGrid(
        bySrcPerTag: List<Map<String, String>>,
        sources: List<String>,
        page: Int,
    ): List<Map<String, List<MediaItem>>> = coroutineScope {
        val semaphore = Semaphore(TAG_SEARCH_CONCURRENCY)
        val requests = bySrcPerTag.flatMapIndexed { tagIndex, bySource ->
            sources.mapNotNull { sourceId ->
                bySource[sourceId]?.let { url -> TagSearchRequest(tagIndex, sourceId, url) }
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
                    .forEach { put(it.sourceId, it.items) }
            }
        }
    }

    private suspend fun exploreTagEntry(sourceId: String, url: String, page: Int): List<MediaItem> =
        try {
            contentRepository.explore(sourceId, url, page)
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
        private const val TAG_SEARCH_CONCURRENCY = 8
        private const val WIDE_COMPOUND_CONCURRENCY = 16

        /** 热门推荐标签上限（v1.6.1.0） */
        const val HOT_TAG_LIMIT = 12

        /**
         * 复合搜索每源翻到第几页（v1.7.0.0）。
         *
         * 一个源的一级分类页通常 20~30 条，交集要求作品**在两边都靠前**；
         * 翻到第 2 页能覆盖多数「两个标签都沾」的作品，再多收益递减且明显变慢。
         */
        const val MAX_COMPOUND_PAGES = 2
    }
}
