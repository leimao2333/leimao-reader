package com.novelreader.ui.screens.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagEntryUrl
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
    private val _activeTags = MutableStateFlow(
        initialTag.trim().takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty(),
    )
    val activeTags: StateFlow<List<String>> = _activeTags.asStateFlow()

    private var tagPlan: TagPlan = TagPlan.EMPTY
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchGeneration = 0L

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
    ) {
        companion object {
            val EMPTY = CompoundOutcome(emptyList())
        }
    }

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
        _activeTags.value = emptyList()
    }

    private fun logicalTagsInQuery(query: String): List<String> =
        _activeTags.value.takeIf { it.isNotEmpty() && it.joinToString(" ") == query }
            ?: TagQuery.parse(query)

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
                val tags = logicalTagsInQuery(_query.value)
                    .ifEmpty { firstParse }
                    .distinct()
                    .let { TagAlias.dedupeAliases(it) }

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
                val entriesPerTag = tags.mapIndexed { index, query ->
                    variantsPerTag[index]
                        .flatMap { variant -> tagPlan.searchEntriesFor(variant, query) }
                        .distinct()
                        .let { if (enabledIds == null) it else it.filter { e -> e.first in enabledIds } }
                        // v1.8.1.0：同一个源最多留 [MAX_URLS_PER_SOURCE] 条入口。
                        // 池里有多个等价写法时，上面的 flatMap 会让同一个源出现好几条
                        // 相同的搜索入口，请求数被平白翻倍。
                        .let(::limitEntriesPerSource)
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

                if (missing.isNotEmpty()) {
                    _activeTags.value = emptyList()
                    _results.value = emptyList()
                    _notice.value = listOfNotNull(
                        translatedNotice.takeIf { it.isNotBlank() },
                        "未找到标签：${missing.joinToString("、")}，复合搜索要求全部标签都存在（点右上角刷新标签试试）",
                    ).joinToString("；")
                    return@launch
                }

                _notice.value = compoundNote ?: translatedNotice.takeIf { it.isNotBlank() }
                val outcome = if (knownEntries.size == 1) {
                    CompoundOutcome(searchOneTag(knownEntries[0], onPartial = updatePartialResults))
                } else {
                    searchCompound(knownEntries, known, updatePartialResults)
                }
                val searchResult = outcome.items
                if (generation != searchGeneration) return@launch
                // 同一部作品在不同源上标题写法可能不同（繁简 / 带作者后缀），
                // 按作品身份键再去重一次，避免结果里出现两条「同一部」。
                val final = filterAdults(searchResult, tags)
                    .distinctBy { TagQuery.identityKey(it.title) }
                if (generation != searchGeneration) return@launch
                _activeTags.value = known.map(TagAlias::canonicalOf).distinct()
                _results.value = final
                val tail = when {
                    final.isNotEmpty() -> outcome.note
                    known.size > 1 -> "没有作品同时属于「${known.joinToString("」「")}」（每个大集里各要命中至少一个标签）"
                    else -> "「${known.first()}」下暂无内容"
                }
                _notice.value = listOfNotNull(
                    compoundNote ?: translatedNotice.takeIf { it.isNotBlank() },
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
        val bySrcPerTag = entriesPerTag.map { list -> list.groupBy({ it.first }, { it.second }) }

        // 无码 / 全彩这类「宽标签」入口极多，提高并发，免得一次复合搜索要等好几分钟。
        val wideComic = type == SourceType.COMIC &&
            tags.all { TagAlias.canonicalOf(it) in WIDE_COMIC_SEARCH_TAGS }
        val semaphore = Semaphore(
            if (wideComic) WIDE_COMPOUND_CONCURRENCY else TAG_SEARCH_CONCURRENCY,
        )

        val unionPerTag = MutableList(entriesPerTag.size) { mutableListOf<MediaItem>() }
        var page = 1
        var matched: List<MediaItem> = emptyList()
        while (page <= MAX_COMPOUND_PAGES) {
            val sizesBefore = unionPerTag.map { it.size }
            val fetched = fetchGrid(bySrcPerTag, sources, page, semaphore)
            fetched.forEachIndexed { tagIndex, perSource ->
                unionPerTag[tagIndex].addAll(perSource.values.flatten())
            }
            matched = TagQuery.intersect(unionPerTag.map(::unionOf))
            onPartial(matched)
            // 所有源这一页都空 → 已经翻到底
            if (fetched.all { perSource -> perSource.values.all { it.isEmpty() } }) break
            // 够看了就停（多数搜索 1 页即可，不必白翻）
            if (matched.size >= COMPOUND_ENOUGH_RESULTS) break
            // 每个大集都没有新条目 → 再翻也是同一批，停
            val grew = unionPerTag.indices.any { unionPerTag[it].size > sizesBefore[it] }
            if (!grew) break
            page += 1
        }
        if (matched.isNotEmpty()) return CompoundOutcome(matched)

        // ===== 兜底一：组合关键词直搜（v1.8.1.1）=====
        // 「无码」「全彩」这类标签在漫画源里通常没有真实分类页，只能退化为**标题搜索**
        // （见 TagEntryUrl 的说明）。分成两个词各搜一遍再按标题求交，几乎必然为空 ——
        // 因为一部作品的标题很少同时写着「无码」和「全彩」。
        // 这里改由源站自己去判断：把两个词拼成一次查询（「无码 全彩」）发过去，
        // 源站若支持多词匹配就能直接给出同时命中的作品，只需源数那么多次请求。
        val keyword = tags.joinToString(" ")
        val combined = searchCombinedKeyword(sources, keyword, semaphore)
        if (combined.isNotEmpty()) {
            onPartial(combined)
            return CompoundOutcome(
                combined,
                "这两组在漫画源里大多没有真实分类页、只能按标题搜，已改用组合关键词「$keyword」直搜（由源站判定同时匹配）",
            )
        }

        // ===== 兜底二：只给第一个大集（v1.8.1.1）=====
        // 连组合搜索都没结果时，与其让用户对着空屏，不如给出第一个大集的内容，
        // 并明确说明「这不是交集结果」。
        val firstGroup = unionOf(unionPerTag.first())
        return if (firstGroup.isNotEmpty()) {
            CompoundOutcome(
                firstGroup,
                "没有作品同时属于「${tags.joinToString("」「")}」，下面只显示「${tags.first()}」组的单独结果",
            )
        } else {
            CompoundOutcome.EMPTY
        }
    }

    /**
     * 组合关键词直搜：把多个标签拼成一次查询，交给源站自己判定是否同时匹配。
     *
     * 只在逐集合交集为空时才调用，正常路径不受影响（不会把源站的宽松匹配混进结果）。
     */
    private suspend fun searchCombinedKeyword(
        sources: List<String>,
        keyword: String,
        semaphore: Semaphore,
    ): List<MediaItem> = coroutineScope {
        val url = TagEntryUrl.search(keyword)
        addProgressTotal(sources.size)
        sources.map { sourceId ->
            async {
                semaphore.withPermit {
                    try {
                        exploreTagEntry(sourceId, url, 1)
                    } finally {
                        stepProgress()
                    }
                }
            }
        }.awaitAll().flatten().distinctBy { "${it.sourceId}:${it.id}" }
    }

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
                list.sortedBy { entry ->
                    val isSearch = TagEntryUrl.searchKeywordOf(entry.second) != null ||
                        TagEntryUrl.categoryOf(entry.second)?.kind == "search"
                    if (isSearch) 1 else 0
                }.take(MAX_URLS_PER_SOURCE)
            }
        }
    }

    /**
     * 单个标签的并集：按归一化标题去重，保留首次出现的条目。
     *
     * 同一部作品在多个源 / 多个同义入口下会重复出现，先按标题收敛，交集判定才不会被
     * 重复项干扰；保留顺序 = 源顺序 + 页内顺序，结果稳定可复现。
     */
    private fun unionOf(items: List<MediaItem>): List<MediaItem> =
        items.distinctBy { item ->
            TagQuery.normalizeTitle(item.title).ifEmpty { "${item.sourceId}:${item.id}" }
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
