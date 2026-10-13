package com.novelreader.data.repository

import com.novelreader.domain.model.ChapterItem
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

            val results = mutableListOf<MediaItem>()
            for (adapter in adapters) {
                runCatching {
                    results.addAll(adapter.search(keyword, page))
                } // 单个源失败不影响其他源
            }
            results
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

            val searchJobs = adapters.map { (info, adapter) ->
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
        val finalResults = if (fuzzyEnabled) {
            FuzzyMatch.rank(results, keyword)
        } else {
            FuzzyMatch.exactRank(results, keyword)
        }
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

    override suspend fun content(chapter: ChapterItem): ContentResult =
        withContext(Dispatchers.IO) {
            val adapter = resolveAdapter(chapter.sourceId)
                ?: return@withContext ContentResult()
            runCatching { adapter.content(chapter) }.getOrDefault(ContentResult())
        }

    // ===== 发现页（v1.2.3.0 对标 legado exploreUrl/ruleExplore） =====

    override suspend fun exploreSources(
        type: SourceType,
    ): List<Pair<SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>> =
        withContext(Dispatchers.IO) {
            sourceManager.getEnabledSourcesByType(type)
                .mapNotNull { info ->
                    sourceManager.getAdapter(info.id)?.let { adapter ->
                        val cats = runCatching { adapter.exploreCategories() }.getOrDefault(emptyList())
                        if (cats.isEmpty()) null else info to cats
                    }
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
            runCatching { adapter.exploreFilter(url, page, filters) }.getOrDefault(emptyList())
                .map { it.copy(sourceName = it.sourceName.ifBlank { adapter.info.name }) }
        }

    override suspend fun exploreCategoriesOf(
        sourceId: String,
    ): List<com.novelreader.domain.model.ExploreCategory> = withContext(Dispatchers.IO) {
        val adapter = resolveAdapter(sourceId) ?: return@withContext emptyList()
        // 故意不吞异常：影视首页据此区分「源不可达」与「源没有分类」，
        // 前者应立刻给出提示，而不是再白等一轮列表请求的超时。
        adapter.exploreCategories()
    }
}
