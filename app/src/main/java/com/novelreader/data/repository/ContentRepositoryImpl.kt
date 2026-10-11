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
) : ContentRepository {

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
        val adapters: List<Pair<SourceInfo, com.novelreader.domain.model.SourceAdapter>> =
            sourceManager.getEnabledSourcesByType(type)
                .mapNotNull { info ->
                    sourceManager.getAdapter(info.id)?.let { info to it }
                }

        val total = adapters.size

        // 结果处理：模糊模式做相似度排序并过滤不相关；精准模式只保留书名归一化后
        // 与关键词一致的条目（源返回的修饰性差异由 FuzzyMatch.normalize 抹平）
        fun applyRank(items: List<MediaItem>): List<MediaItem> =
            if (fuzzyEnabled) FuzzyMatch.rank(items, keyword) else FuzzyMatch.exactRank(items, keyword)

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
        val results = mutableListOf<MediaItem>()
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

            // 串行收集：每收到一个源的结果就 emit 一次进度
            var received = 0
            for (msg in channel) {
                val (sourceName, batch) = msg
                if (batch.isNotEmpty()) results.addAll(batch)
                received++
                emit(
                    SearchState(
                        results = applyRank(results.toList()),
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

        // 最终结果
        emit(
            SearchState(
                results = applyRank(results.toList()),
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
            val adapter = sourceManager.getAdapter(item.sourceId)
                ?: return@withContext item
            runCatching { adapter.detail(item) }.getOrDefault(item)
        }

    override suspend fun chapterList(item: MediaItem): List<ChapterItem> =
        withContext(Dispatchers.IO) {
            val adapter = sourceManager.getAdapter(item.sourceId)
                ?: return@withContext emptyList()
            runCatching { adapter.chapterList(item) }.getOrDefault(emptyList())
        }

    override suspend fun content(chapter: ChapterItem): ContentResult =
        withContext(Dispatchers.IO) {
            val adapter = sourceManager.getAdapter(chapter.sourceId)
                ?: return@withContext ContentResult()
            runCatching { adapter.content(chapter) }.getOrDefault(ContentResult())
        }
}
