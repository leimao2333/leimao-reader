package com.novelreader.data.repository

import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ChapterLine
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.source.manager.SourceManager
import com.novelreader.source.search.FuzzyMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 内容仓库实现（Data 层）。
 *
 * 聚合多个源，提供跨源的内容查询能力。
 * 搜索时遍历该类型下所有已启用的源，合并结果。
 *
 * 所有方法均在 [Dispatchers.IO] 上执行——底层适配器使用阻塞式 HTTP，
 * 必须离开主线程，否则抛 NetworkOnMainThreadException。
 */
class ContentRepositoryImpl(
    private val sourceManager: SourceManager,
    private val localBookStore: com.novelreader.core.local.LocalBookStore? = null,
) : ContentRepository {

    /**
     * 取适配器：优先在线源；找不到时回退本地书库（v1.2.0.0），
     * 使本地 TXT 书走与在线书相同的详情/目录/正文链路。
     */
    private fun resolveAdapter(sourceId: String): com.novelreader.domain.model.SourceAdapter? {
        sourceManager.getAdapter(sourceId)?.let { return it }
        if (sourceId.startsWith(com.novelreader.core.local.LocalBookStore.SOURCE_PREFIX) && localBookStore != null) {
            val localId = sourceId.removePrefix(com.novelreader.core.local.LocalBookStore.SOURCE_PREFIX)
            val meta = localBookStore.getBook(localId) ?: return null
            return com.novelreader.source.local.LocalBookAdapter(meta, localBookStore)
        }
        return null
    }

    override suspend fun search(type: SourceType, keyword: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val adapters = sourceManager.getEnabledSourcesByType(type)
                .mapNotNull { sourceManager.getAdapter(it.id) }

            // 并发搜索（8 路）：此前串行逐源请求，一个死源就要等满它的超时，
            // 几十个源串起来是分钟级（v1.4.8.0 改并发，单源失败不影响其他源）。
            val semaphore = Semaphore(8)
            coroutineScope {
                adapters.map { adapter ->
                    async {
                        semaphore.withPermit {
                            runCatching { adapter.search(keyword, page) }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll().flatten()
            }
        }

    override fun searchWithProgress(
        type: SourceType,
        keyword: String,
        page: Int,
        concurrency: Int,
        fuzzyEnabled: Boolean,
    ): Flow<SearchState> = flow {
        // 冷启动竞态防护：深链自动搜索可能在源恢复完成前触发（total=0 空搜）。
        // 恢复已完成时该等待立即通过，无额外开销。
        if (!sourceManager.restoredFlow.value) {
            sourceManager.restoredFlow.filter { it }.first()
        }
        val adapters: List<Pair<SourceInfo, com.novelreader.domain.model.SourceAdapter>> =
            sourceManager.getEnabledSourcesByType(type)
                .mapNotNull { info ->
                    sourceManager.getAdapter(info.id)?.let { info to it }
                }

        val total = adapters.size

        // v1.5.8.0「优化搜索响应」：18+ 成人源排到搜索队列**末尾**，普通源先搜先上屏。
        // 这样搜索响应更快、普通结果先出；成人源结果最后统一沉底。
        // 同时记录成人源 id 集合，用于最终结果把成人内容沉底。
        val adultSourceIds = adapters.map { it.first }
            .filter { com.novelreader.source.category.SourceAdults.isAdult(it) }
            .map { it.id }
            .toSet()
        val orderedAdapters = adapters.sortedBy { (info, _) -> info.id in adultSourceIds }

        // 初始状态：开始搜索
        emit(
            SearchState(
                searchedCount = 0,
                totalCount = total,
                currentSource = "",
                isSearching = true,
                hasSearched = true,
                fuzzyEnabled = fuzzyEnabled,
            )
        )

        if (total == 0) {
            emit(
                SearchState(
                    searchedCount = 0,
                    totalCount = 0,
                    currentSource = "",
                    isSearching = false,
                    hasSearched = true,
                    fuzzyEnabled = fuzzyEnabled,
                )
            )
            return@flow
        }

        // 启动并发搜索任务，结果通过 Channel 汇总后由本协程串行 emit
        val maxConcurrency = concurrency.coerceIn(1, 32)
        val semaphore = Semaphore(maxConcurrency)
        // 结果容器：边搜边追加，搜索过程只做「增量去重」（轻量），
        // 排序/过滤只在最终统一做一次，避免每批结果都全量重排导致滚动卡顿。
        val results = mutableListOf<MediaItem>()
        val seenKeys = HashSet<String>()
        coroutineScope {
            val channel = kotlinx.coroutines.channels.Channel<Pair<String, List<MediaItem>>>(
                capacity = total.coerceAtLeast(1)
            )

            val searchJobs = orderedAdapters.map { (info, adapter) ->
                async {
                    semaphore.withPermit {
                        val batch = runCatching { adapter.search(keyword, page) }
                            .getOrDefault(emptyList())
                        // 给每个结果补上来源名（若适配器未填充）
                        val tagged = batch.map { it.copy(sourceName = it.sourceName.ifBlank { info.name }) }
                        channel.trySend(info.name to tagged)
                    }
                }
            }

            // 串行收集：每收到一个源的结果就 emit 一次进度。
            // 每批先做「批内过滤」（批小开销低）：模糊=相似度过滤，精准=书名一致过滤，
            // 保证边搜边显示的每条结果都与关键词相关（v1.1.4.0 回归修复）；
            // 全局排序仍在收尾统一做一次，避免每批全量重排卡顿。
            // 增量去重保证不出现同一源同一本书重复条目。
            val preFilter: (List<MediaItem>) -> List<MediaItem> =
                if (fuzzyEnabled) {
                    { batch -> FuzzyMatch.rank(batch, keyword) }
                } else {
                    { batch -> FuzzyMatch.exactRank(batch, keyword) }
                }
            var received = 0
            for (msg in channel) {
                val (sourceName, batch) = msg
                for (item in preFilter(batch)) {
                    val key = buildString {
                        append(item.sourceId)
                        append('|')
                        append(item.id.ifBlank { item.detailUrl })
                        if (item.id.isBlank() && item.detailUrl.isBlank()) {
                            append('|')
                            append(FuzzyMatch.normalize(item.title))
                            append('|')
                            append(FuzzyMatch.normalizeAuthor(item.author ?: ""))
                        }
                    }
                    if (seenKeys.add(key)) results.add(item)
                }
                received++
                emit(
                    SearchState(
                        results = results.toList(),
                        searchedCount = received,
                        totalCount = total,
                        currentSource = sourceName,
                        isSearching = received < total,
                        hasSearched = true,
                        fuzzyEnabled = fuzzyEnabled,
                    )
                )
                if (received >= total) break
            }

            searchJobs.forEach { it.await() }
            channel.close()
        }

        // 最终结果：统一做一次排序 / 过滤（模糊=相似度降序，精准=书名归一化一致）
        val rankedResults = if (fuzzyEnabled) {
            FuzzyMatch.rank(results, keyword)
        } else {
            FuzzyMatch.exactRank(results, keyword)
        }
        // v1.5.8.0：18+ 成人源的结果统一沉底（稳定排序，不打乱上面的匹配度顺序）
        val finalResults = rankedResults.sortedBy { it.sourceId in adultSourceIds }
        emit(
            SearchState(
                results = finalResults,
                searchedCount = total,
                totalCount = total,
                currentSource = "",
                isSearching = false,
                hasSearched = true,
                fuzzyEnabled = fuzzyEnabled,
            )
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun detail(item: MediaItem): MediaItem =
        withContext(Dispatchers.IO) {
            val adapter = resolveAdapter(item.sourceId)
                ?: return@withContext item
            runCatching { adapter.detail(item) }.getOrDefault(item)
        }

    override suspend fun chapterList(item: MediaItem): List<ChapterItem> =
        withContext(Dispatchers.IO) {
            val adapter = resolveAdapter(item.sourceId)
                ?: return@withContext emptyList()
            runCatching { adapter.chapterList(item) }.getOrDefault(emptyList())
        }

    override suspend fun chapterLines(item: MediaItem): List<ChapterLine> =
        withContext(Dispatchers.IO) {
            val adapter = resolveAdapter(item.sourceId)
                ?: return@withContext emptyList()
            runCatching { adapter.chapterLines(item) }.getOrDefault(emptyList())
        }

    override suspend fun content(chapter: ChapterItem): ContentResult =
        withContext(Dispatchers.IO) {
            val adapter = resolveAdapter(chapter.sourceId)
                ?: return@withContext ContentResult()
            runCatching { adapter.content(chapter) }.getOrDefault(ContentResult())
        }

    // ===== 发现页（v1.2.3.0 对标 legado exploreUrl/ruleExplore） =====

    override suspend fun exploreSources(
        type: SourceType,
        includeDisabled: Boolean,
    ): List<Pair<SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>> =
        withContext(Dispatchers.IO) {
            // v1.6.5.0：includeDisabled=true 时涵盖每一个源（含禁用源），标签池收集用；
            // 默认只取已启用源（发现页等场景，禁用的源不该出现）。
            val infos = if (includeDisabled) sourceManager.getSourcesByType(type)
            else sourceManager.getEnabledSourcesByType(type)
            // 并发枚举各源分类（8 路）：个别源的分类规则要发网络请求，串行会拖累整页。
            val semaphore = Semaphore(8)
            coroutineScope {
                infos.map { info -> async {
                    semaphore.withPermit {
                        sourceManager.getAdapter(info.id)?.let { adapter ->
                            val cats = runCatching { adapter.exploreCategories() }.getOrDefault(emptyList())
                            if (cats.isEmpty()) null else info to cats
                        }
                    }
                } }.awaitAll().filterNotNull()
            }
        }

    override suspend fun explore(sourceId: String, url: String, page: Int): List<MediaItem> =
        exploreFiltered(sourceId, url, page, emptyMap())

    override suspend fun exploreFiltered(
        sourceId: String,
        url: String,
        page: Int,
        filters: Map<String, String>,
    ): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val adapter = resolveAdapter(sourceId) ?: return@withContext emptyList()
            // v1.6.6.0：`search::<词>` 是「按题材搜索」伪入口（TagEntryUrl）。
            // 漫画源大多没有发现分类页，标签池给它们绑的就是这种入口——
            // 点击标签时改走源自己的搜索，而不是去请求不存在的分类页。
            val keyword = com.novelreader.core.util.TagEntryUrl.searchKeywordOf(url)
            val raw = if (keyword != null) {
                runCatching { adapter.search(keyword, page) }.getOrDefault(emptyList())
            } else {
                runCatching { adapter.exploreFilter(url, page, filters) }.getOrDefault(emptyList())
            }
            raw.map { it.copy(sourceName = it.sourceName.ifBlank { adapter.info.name }) }
        }

    override suspend fun searchableSourceIds(
        type: com.novelreader.domain.model.SourceType,
        limit: Int,
    ): List<String> = withContext(Dispatchers.IO) {
        val all = sourceManager.getSourcesByType(type)
        val enabledIds = runCatching { sourceManager.getEnabledSourcesByType(type).map { it.id } }
            .getOrDefault(emptyList())
            .toSet()
        // 中文名源优先（题材词是中文，这类源命中率高），其次已启用源；
        // 同档内保持管理器原有顺序（手动排序 / 名称序）
        val ordered = all.sortedWith(
            compareByDescending<SourceInfo> { info -> info.name.any { it.code in CJK_RANGE } }
                .thenByDescending { info -> info.id in enabledIds },
        )
        // v1.6.6.0：Venera 与其它源**交替**排列。
        // 漫画里 Venera 源占了大半（25/36），但名称排序常常让 legado 源排在前头，
        // 于是 limit 一截就把 Venera 全截掉了 —— Venera 的搜索能力等于白接入。
        // 交替后取前 N 个必然两类都有。
        val mixed = interleave(
            primary = ordered.filter { it.format == com.novelreader.domain.model.SourceFormat.VENERA },
            secondary = ordered.filter { it.format != com.novelreader.domain.model.SourceFormat.VENERA },
        )
        val ids = mixed.map { it.id }
        if (limit > 0) ids.take(limit) else ids
    }

    /** 交替合并两组（a1,b1,a2,b2,…），任一先耗尽后其余按序补上 */
    private fun <T> interleave(primary: List<T>, secondary: List<T>): List<T> {
        val out = ArrayList<T>(primary.size + secondary.size)
        var i = 0
        while (i < primary.size || i < secondary.size) {
            if (i < primary.size) out += primary[i]
            if (i < secondary.size) out += secondary[i]
            i++
        }
        return out
    }

    override suspend fun exploreCategoriesOf(
        sourceId: String,
    ): List<com.novelreader.domain.model.ExploreCategory> = withContext(Dispatchers.IO) {
        val adapter = resolveAdapter(sourceId) ?: return@withContext emptyList()
        // 故意不吞异常：影视首页据此区分「源不可达」与「源没有分类」，
        // 前者应立刻给出提示，而不是再白等一轮列表请求的超时。
        adapter.exploreCategories()
    }

    override suspend fun exploreCategoryTreeOf(
        sourceId: String,
    ): List<com.novelreader.domain.model.ExploreCategory> = withContext(Dispatchers.IO) {
        val adapter = resolveAdapter(sourceId) ?: return@withContext emptyList()
        adapter.exploreCategoryTree()
    }

    private companion object {
        /** 基本汉字区（U+4E00..U+9FFF）码点范围：用于「源名是中文吗」这一档排序 */
        val CJK_RANGE = '\u4E00'.code..'\u9FFF'.code
    }
}
