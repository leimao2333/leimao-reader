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
 * - **复合搜索**：查询串里多个标签（空格分隔）→ **同源交集**（同一部作品在同一个
 *   源里同时属于这几个分类），空则翻第 2 页，仍空退回跨源同名交集；
 *   决策逻辑在 [TagCompound]，此前「各标签第一页全局求交」的口径几乎必然为空；
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
        val current = TagQuery.parseWithPool(_query.value, tagPlan.tags)
        if (tag in current) return
        val next = current + tag
        _query.value = next.joinToString(" ")
        _activeTags.value = next
        search()
    }

    /** 从查询查询返回某标签（点已选 chip）。最后一个标签移除时同步清空结果 / 进度 */
    fun removeTag(tag: String) {
        // 以已选列表为准（chips 就是它渲染的）；手输场景才回退到解析查询串
        val parsed = if (tag in _activeTags.value) _activeTags.value
        else TagQuery.parseWithPool(_query.value, tagPlan.tags)
        val current = parsed - tag
        _query.value = current.joinToString(" ")
        _activeTags.value = current
        if (current.isEmpty()) {
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
     * 2. **复合改走同源交集 + 翻页探测**（见 [Compound] 的说明），并在空结果时
     *    说清是「没有作品同时属于这几个标签」，而不是笼统的「没有匹配的内容」。
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
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _notice.value = null
            resetProgress()
            _progress.value = null

            // 标签计划还没收集好就等一下（首次冷启动直进标签搜索的场景）
            ensurePlan()
            // 计划就绪后重解析：含空格的标签要靠池才能正确合并
            val tags = TagQuery.parseWithPool(_query.value, tagPlan.tags).ifEmpty { firstParse }

            val enabledIds = enabledSourceIds()
            if (enabledIds != null && enabledIds.isEmpty()) {
                _activeTags.value = emptyList()
                _results.value = emptyList()
                _searching.value = false
                _notice.value = "没有已启用的源，先去源管理里打开一个"
                return@launch
            }
            // 中英互译（v1.7.2.0）：一个标签词展开成标签池里**所有等价标签** ——
            // 输「百合」会同时搜池里的「百合」与「Yuri」，输「英语」也能命中「英語 ENG」
            // 这种复合标签。此前只按字面匹配，池里的英文标签对中文用户等于不存在。
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
            // 译名命中时告诉用户「实际按哪些标签搜的」，否则结果突然变多会显得莫名其妙。
            // 只有**展开出了别的名字**才提示；单纯大小写 / 空格差异（输 yuri 命中 Yuri）
            // 属于同一个词，提示它只会制造噪音。
            val translatedNotice = tags.mapIndexedNotNull { i, tag ->
                val variants = variantsPerTag[i]
                val self = TagQuery.normalizeTitle(tag)
                if (variants.none { TagQuery.normalizeTitle(it) != self }) return@mapIndexedNotNull null
                "「${tag}」已按 ${variants.joinToString("、")} 搜索"
            }.joinToString("；")

            if (known.isEmpty()) {
                _activeTags.value = emptyList()
                _results.value = emptyList()
                _searching.value = false
                _notice.value = "没有找到标签：${tags.joinToString("、")}（点右上角刷新标签试试）"
                return@launch
            }

            val found = if (knownEntries.size == 1) {
                searchOneTag(knownEntries[0])
            } else {
                searchCompound(knownEntries)
            }
            // v1.7.5.0：成人源内容按「隐藏成人内容」开关过滤（默认隐藏，直接剔除；
            // 关闭时沉底）。此前只对视频生效，漫画标签搜索里 nhentai 等照样排前面。
            val final = filterAdults(found)
            _activeTags.value = known
            _results.value = final
            _progress.value = null
            _searching.value = false
            val tail = when {
                missing.isNotEmpty() ->
                    "未找到标签：${missing.joinToString("、")}，已按其余标签搜索"
                final.isNotEmpty() -> null
                known.size > 1 -> "没有作品同时属于「${known.joinToString("」「")}」"
                else -> "「${known.first()}」下暂无内容"
            }
            _notice.value = listOfNotNull(
                translatedNotice.takeIf { it.isNotBlank() },
                tail,
            ).joinToString("；").takeIf { it.isNotBlank() }
        }
    }

    /** 计划为空时补一次（冷启动直进标签搜索） */
    private suspend fun ensurePlan() {
        if (tagPlan.plan.isNotEmpty()) return
        tagPlan = runCatching { tagRepository.tags(type) }.getOrDefault(TagPlan.EMPTY)
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
     */
    private suspend fun enabledSourceIds(): Set<String>? = runCatching {
        sourceRepository?.getSourcesByType(type)?.filter { it.enabled }?.map { it.id }?.toSet()
    }.getOrNull()

    /** 单标签：聚合所有拥有该分类的源的第 1 页，跨源去重 */
    private suspend fun searchOneTag(entries: List<Pair<String, String>>): List<MediaItem> =
        coroutineScope {
            val semaphore = Semaphore(8)
            addProgressTotal(entries.size)
            entries.map { (srcId, url) ->
                async {
                    semaphore.withPermit {
                        try {
                            runCatching { contentRepository.explore(srcId, url, 1) }
                                .getOrDefault(emptyList())
                        } finally {
                            stepProgress()
                        }
                    }
                }
            }.awaitAll().flatten().distinctBy { "${it.sourceId}:${it.id}" }
        }

    /**
     * 复合标签：**同源交集**（见 [TagCompound]）。
     *
     * 只在「同时拥有全部标签入口」的源上查（[TagCompound.participants]），
     * 每个源取各标签的第 1 页求交集；全空再翻到第 [MAX_COMPOUND_PAGES] 页，
     * 仍为空就退回跨源同名交集（老行为）作兜底，并且**不额外发请求**。
     *
     * 请求量 = 参与源数（≤ [MAX_COMPOUND_SOURCES]）× 标签数 × 页数，
     * 8 路并发；一旦某页出现交集立刻停止翻页。
     */
    private suspend fun searchCompound(
        entriesPerTag: List<List<Pair<String, String>>>,
    ): List<MediaItem> {
        val participants = TagCompound.participants(entriesPerTag)
        if (participants.isEmpty()) {
            // 没有任何源同时提供全部标签 → 只能跨源同名匹配（仍是旧口径）
            return TagQuery.intersect(entriesPerTag.map { searchOneTag(it) })
        }

        val sources = participants.take(MAX_COMPOUND_SOURCES)
        val bySrcPerTag = entriesPerTag.map { list -> list.associate { it.first to it.second } }
        var collected: List<MediaItem> = emptyList()
        var firstPage: List<Map<String, List<MediaItem>>>? = null

        var page = 1
        while (page <= MAX_COMPOUND_PAGES) {
            val fetched = fetchGrid(bySrcPerTag, sources, page)
            if (page == 1) firstPage = fetched
            val hits = TagCompound.intersectBySource(fetched)
            if (hits.isNotEmpty()) {
                collected = TagCompound.merge(listOf(collected, hits))
                break
            }
            // 第 1 页一个结果都没抓到（源不可达 / 分类为空）就别再翻页了
            if (fetched.all { m -> m.values.all { it.isEmpty() } }) break
            page += 1
        }
        if (collected.isNotEmpty()) return collected

        // 同源交集为空：退回跨源同名交集，复用已经抓到的第 1 页，不额外发请求
        return firstPage?.let { grid ->
            TagQuery.intersect(
                grid.map { perSource ->
                    perSource.values.flatten().distinctBy { "${it.sourceId}:${it.id}" }
                },
            )
        }.orEmpty()
    }

    /** 并发抓一格（标签 × 源 → 结果），返回 `[标签][源] = 结果`，源顺序即 [sources] */
    private suspend fun fetchGrid(
        bySrcPerTag: List<Map<String, String>>,
        sources: List<String>,
        page: Int,
    ): List<Map<String, List<MediaItem>>> = coroutineScope {
        val semaphore = Semaphore(8)
        // 本页将实际发出的请求数 = 各标签 × 该标签下有入口的源
        addProgressTotal(bySrcPerTag.sumOf { bySrc -> sources.count { bySrc.containsKey(it) } })
        bySrcPerTag.map { bySrc ->
            async {
                val perSource = LinkedHashMap<String, List<MediaItem>>()
                for (src in sources) {
                    val url = bySrc[src] ?: continue
                    perSource[src] = async {
                        semaphore.withPermit {
                            try {
                                runCatching { contentRepository.explore(src, url, page) }
                                    .getOrDefault(emptyList())
                            } finally {
                                stepProgress()
                            }
                        }
                    }.await()
                }
                perSource
            }
        }.awaitAll()
    }

    /** 成人源内容过滤：默认隐藏直接剔除，关闭时沉底（v1.7.5.0 扩到全部类型） */
    private suspend fun filterAdults(items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) return items
        val adultIds = runCatching {
            sourceRepository?.getSourcesByType(type)
                .orEmpty()
                .filter { it.isAdult }
                .map { it.id }
                .toSet()
        }.getOrDefault(emptySet())
        if (adultIds.isEmpty()) return items
        val hide = preferencesManager?.hideAdultFlow?.first() ?: true
        return if (hide) items.filterNot { it.sourceId in adultIds }
        else items.sortedBy { it.sourceId in adultIds }
    }

    companion object {
        /** 热门推荐标签上限（v1.6.1.0） */
        const val HOT_TAG_LIMIT = 12

        /**
         * 复合搜索最多查多少个源（v1.7.0.0）。
         *
         * 请求量 = 源数 × 标签数 × 页数。源动辄几十个，不限的话 3 个标签就要几百个
         * 请求、手机白等一分钟。取 12 个（标签池按覆盖度/手工排序排过，前面的更靠谱）
         * 已经是 12×2×2 = 48 个请求的上限，实际多半第 1 页就出结果。
         */
        const val MAX_COMPOUND_SOURCES = 12

        /**
         * 复合搜索每源翻到第几页（v1.7.0.0）。
         *
         * 一个源的一级分类页通常 20~30 条，交集要求作品**在两边都靠前**；
         * 翻到第 2 页能覆盖多数「两个标签都沾」的作品，再多收益递减且明显变慢。
         */
        const val MAX_COMPOUND_PAGES = 2
    }
}
