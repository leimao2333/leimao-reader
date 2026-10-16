package com.novelreader.data.repository

import com.novelreader.core.source.SourceErrorLog
import com.novelreader.core.util.runCatchingCc
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ChapterLine
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.SourceEnumeration
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val preferencesManager: com.novelreader.core.preferences.PreferencesManager? = null,
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
            val sources = sourceManager.getEnabledSourcesByType(type)
            // v1.7.5.0：隐藏成人时跳过成人源（与 searchWithProgress 同口径）
            val hideAdult = preferencesManager?.hideAdultFlow?.first() ?: true
            val adapters = sources
                .filter { !hideAdult || !com.novelreader.source.category.SourceAdults.isAdult(it) }
                .mapNotNull { sourceManager.getAdapter(it.id) }

            // 并发搜索（8 路）：此前串行逐源请求，一个死源就要等满它的超时，
            // 几十个源串起来是分钟级（v1.4.8.0 改并发，单源失败不影响其他源）。
            val semaphore = Semaphore(8)
            coroutineScope {
                adapters.map { adapter ->
                    async {
                        semaphore.withPermit {
                            runCatchingCc { adapter.search(keyword, page) }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll().flatten()
            }.let { com.novelreader.core.util.CoverMemory.fill(it) }
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
        // v1.7.5.0：默认「隐藏成人内容」时**根本不搜**成人源（既省请求也彻底不出成人结果）。
        val hideAdult = preferencesManager?.hideAdultFlow?.first() ?: true
        val effectiveAdapters = if (hideAdult) {
            adapters.filter { (info, _) -> info.id !in adultSourceIds }
        } else {
            adapters
        }
        val orderedAdapters = effectiveAdapters.sortedBy { (info, _) -> info.id in adultSourceIds }
        val effectiveTotal = orderedAdapters.size
        val effectiveAdultIds = if (hideAdult) emptySet() else adultSourceIds

        // 初始状态：开始搜索
        emit(
            SearchState(
                searchedCount = 0,
                totalCount = effectiveTotal,
                currentSource = "",
                isSearching = true,
                hasSearched = true,
                fuzzyEnabled = fuzzyEnabled,
            )
        )

        if (effectiveTotal == 0) {
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
        // v1.7.1.0：单源硬超时 / 整体兜底超时。此前漫画搜索会长时间卡在「0/N 个源」——
        // Venera 源单次调用上限 60s、且全部源共用一个 WebView 实际串行，死源 / 被墙站点
        // 不设上限就把整轮搜索拖成分钟级。
        val perSourceTimeoutMs = 15_000L
        val overallTimeoutMs = 45_000L
        // 结果容器：边搜边追加，搜索过程只做「增量去重」（轻量），
        // 排序/过滤只在最终统一做一次，避免每批结果都全量重排导致滚动卡顿。
        val results = mutableListOf<MediaItem>()
        val seenKeys = HashSet<String>()
        coroutineScope {
            val channel = kotlinx.coroutines.channels.Channel<Pair<String, List<MediaItem>>>(
                capacity = effectiveTotal.coerceAtLeast(1)
            )

            val searchJobs = orderedAdapters.map { (info, adapter) ->
                async {
                    semaphore.withPermit {
                        // 超时按「空结果」处理并照常计入进度，不再让单个源无限期占着并发槽
                        val batch = runCatchingCc {
                            kotlinx.coroutines.withTimeoutOrNull(perSourceTimeoutMs) {
                                adapter.search(keyword, page)
                            }
                        }.getOrNull().orEmpty()
                        // 给每个结果补上来源名（若适配器未填充）
                        val tagged = batch.map { it.copy(sourceName = it.sourceName.ifBlank { info.name }) }
                        channel.trySend(info.name to tagged)
                    }
                }
            }

            // 兜底看门狗：到点就关闭 channel，让下面的循环带着已有结果收尾
            val watchdog = launch {
                kotlinx.coroutines.delay(overallTimeoutMs)
                channel.close()
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
                        totalCount = effectiveTotal,
                        currentSource = sourceName,
                        isSearching = received < effectiveTotal,
                        hasSearched = true,
                        fuzzyEnabled = fuzzyEnabled,
                    )
                )
                if (received >= effectiveTotal) break
            }

            searchJobs.forEach { it.cancel() }
            watchdog.cancel()
            channel.close()
        }

        // 最终结果：统一做一次排序 / 过滤（模糊=相似度降序，精准=书名归一化一致）
        val rankedResults = if (fuzzyEnabled) {
            FuzzyMatch.rank(results, keyword)
        } else {
            FuzzyMatch.exactRank(results, keyword)
        }
        // v1.5.8.0：18+ 成人源的结果统一沉底（稳定排序，不打乱上面的匹配度顺序）
        val finalResults = rankedResults.sortedBy { it.sourceId in effectiveAdultIds }
        emit(
            SearchState(
                results = finalResults,
                searchedCount = total,
                totalCount = effectiveTotal,
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
            if (adapter == null) {
                // v1.7.2.0：此前这里静默返回原条目，UI 便以为「源没给数据」。
                // 记一笔真实原因，详情页才能把「源没加载」和「源没内容」分开说。
                SourceErrorLog.record(
                    item.sourceId, "详情",
                    IllegalStateException("源未加载（可能已在源管理里被删除 / 禁用）"),
                )
                return@withContext item
            }
            val result = runCatching { adapter.detail(item) }
                .onSuccess { SourceErrorLog.clear(item.sourceId) }
                .onFailure { SourceErrorLog.record(item.sourceId, "详情", it) }
                .getOrDefault(item)
            // v1.7.4.0：有些源列表页不给封面、详情页才给（52PO小说 / 海马读书的
            // ruleSearch / ruleExplore 都无 coverUrl，ruleBookInfo 却有）。记下来，
            // 同一会话内回到列表时就能把封面补回去，不用再让用户看占位块。
            if (result.cover.isNullOrBlank().not()) {
                com.novelreader.core.util.CoverMemory.remember(
                    item.sourceId,
                    item.id.ifBlank { item.detailUrl },
                    result.cover,
                )
            }
            result
        }

    override suspend fun chapterList(item: MediaItem): List<ChapterItem> =
        withContext(Dispatchers.IO) {
            val adapter = resolveAdapter(item.sourceId)
            if (adapter == null) {
                // 同上：适配器拿不到时会返回空目录，若不记原因，UI 会误报
                // 「该源没有返回任何章节，可能源已失效」——把用户引到错误的排查方向。
                SourceErrorLog.record(
                    item.sourceId, "目录",
                    IllegalStateException("源未加载（可能已在源管理里被删除 / 禁用）"),
                )
                return@withContext emptyList()
            }
            // v1.7.1.0：失败时记一笔真实异常，让 UI 能把「源报错」与「确实没目录」区分开
            runCatching { adapter.chapterList(item) }
                .onSuccess { SourceErrorLog.clear(item.sourceId) }
                .onFailure { SourceErrorLog.record(item.sourceId, "目录", it) }
                .getOrDefault(emptyList())
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
            // v1.7.1.0：同 chapterList，失败原因记进 SourceErrorLog 供 UI 提示
            runCatching { adapter.content(chapter) }
                .onSuccess { SourceErrorLog.clear(chapter.sourceId) }
                .onFailure { SourceErrorLog.record(chapter.sourceId, "图片", it) }
                .getOrDefault(ContentResult())
        }

    /** 图片失败回退：目前只有 Venera 源支持（onImageLoad → onLoadFailed 多镜像） */
    override suspend fun fallbackImage(
        sourceId: String,
        url: String,
    ): Pair<String, Map<String, String>>? = withContext(Dispatchers.IO) {
        val adapter = resolveAdapter(sourceId)
        if (adapter is com.novelreader.source.venera.VeneraSourceAdapter) {
            runCatching { adapter.fallbackImage(url) }.getOrNull()
        } else {
            null
        }
    }

    // ===== 发现页（v1.2.3.0 对标 legado exploreUrl/ruleExplore） =====

    override suspend fun exploreSources(
        type: SourceType,
        includeDisabled: Boolean,
    ): List<Pair<SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>> =
        enumerateSources(type, includeDisabled, limit = 0).items

    override suspend fun exploreSourcesLimited(
        type: SourceType,
        limit: Int,
        includeDisabled: Boolean,
    ): List<Pair<SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>> =
        enumerateSources(type, includeDisabled, limit = limit).items

    /** 首页推荐专用：慢源用静态分类表兜底，不整源丢弃（详见 [Repository.exploreSourcesFast]） */
    override suspend fun exploreSourcesFast(
        type: SourceType,
        limit: Int,
        includeDisabled: Boolean,
    ): SourceEnumeration =
        enumerateSources(type, includeDisabled, limit = limit, fast = true)

    /**
     * 枚举各源的发现分类（v1.7.5.0 重写为**两段式**）。
     *
     * 背景：Venera 源的分类要在隐藏 WebView 里真实评估（引擎全应用一个 JS 线程，
     * 一次只放一个调用），25 个漫画源串行约 20~75s。旧结构是「先运行时探测、
     * 超时再静态兜底」，整轮共用一个截止（[ENUM_DEADLINE_MS]）—— 截止一到，
     * **还没轮到探测的源直接整体出局**（实测 34 个漫画源只有 28 个进首页计划），
     * 用户看到的「聚合 N 个源」永远差一截。
     *
     * 现在顺序倒过来，两段各自有截止：
     *
     * 0. **先等源恢复完成**（冷启动竞态防护）—— 源由后台协程从数据库异步恢复，
     *    首页推荐可能在恢复完成前就发起枚举，那时源表里只有零星几个源，
     *    结果退化成「聚合 1 个源」并被落盘缓存**固化成常态**。等待信号与
     *    [searchWithProgress] 同款，恢复已完成时立即通过、零开销。
     * 1. **第一段：全量静态 / 本地分类** —— 所有源一起拿「不碰引擎」的分类表
     *    （Venera 解析源码里的 `category.parts`，legado 解析 `ruleExplore`，
     *    都是毫秒级的纯本地活）。**这一段就决定哪些源参与聚合，一个源都不丢**；
     *    连静态表都没有的源挂一组虚拟题材搜索入口（见 [virtualCategories]）。
     * 2. **第二段：Venera 源升级真分类页入口** —— 仅 [SourceFormat.VENERA] 的源，
     *    **串行**跑运行时评估（`exploreCategories`），把第一段的「按分类名搜索」
     *    入口换成 `vcat::` 真分类页（走源自己的 `categoryComics.load`）。
     *    串行是因为引擎一次只放一个调用，并发只会让排后面的源把预算烧在排队上
     *    （旧代码 8 路并发实测只有排头一两个源够得着运行时评估）。
     *    每源独立预算 [perSourceTimeoutMs]，整段截止 [ENUM_UPGRADE_DEADLINE_MS]
     *    （[fast] 模式）或 [deadlineMs]（发现页 / 标签池）；没升级的源保留第一段
     *    的静态入口，只是首页排退化为搜索，绝不丢弃。
     *
     * @param limit 最多枚举几个源（<= 0 = 全部）。首页推荐已不再用它做「防 ANR 截断」，
     *   只作为极端情况下的保险丝（源列表异常膨胀时）。
     */
    private suspend fun enumerateSources(
        type: SourceType,
        includeDisabled: Boolean,
        limit: Int,
        fast: Boolean = false,
        perSourceTimeoutMs: Long = ENUM_PER_SOURCE_MS,
        deadlineMs: Long = ENUM_DEADLINE_MS,
    ): SourceEnumeration =
        withContext(Dispatchers.IO) {
            // 0) 冷启动竞态防护：源还没恢复完就枚举，只会拿到「当时已注册的那几个源」。
            //    这是「聚合 1 个源」的根因 —— 结果还会被 HomeViewModel 落盘，固化下来。
            if (!sourceManager.restoredFlow.value) {
                runCatching { sourceManager.restoredFlow.filter { it }.first() }
            }
            // v1.6.5.0：includeDisabled=true 时涵盖每一个源（含禁用源），标签池收集用；
            // 默认只取已启用源（发现页等场景，禁用的源不该出现）。
            // 顺序由 SourceManager 按「手动排序序号 + 名称」给出，即用户意图的优先级。
            val all = if (includeDisabled) sourceManager.getSourcesByType(type)
            else sourceManager.getEnabledSourcesByType(type)
            val infos = if (limit > 0) all.take(limit) else all
            val started = System.currentTimeMillis()

            // ---- 第一段：全量静态 / 本地分类（毫秒级，不碰引擎）----
            val semaphore = Semaphore(8)
            val entries = coroutineScope {
                infos.map { info -> async {
                    semaphore.withPermit {
                        val adapter = sourceManager.getAdapter(info.id) ?: return@withPermit null
                        val left = deadlineMs - (System.currentTimeMillis() - started)
                        if (left <= 0) return@withPermit null
                        // runCatchingCc：withTimeoutOrNull 到点时取消必须照常传播，
                        // stdlib runCatching 会吞掉它，让已放弃的活继续占着引擎。
                        val cats = runCatchingCc {
                            withTimeoutOrNull(minOf(ENUM_FALLBACK_MS, left)) {
                                adapter.fastExploreCategories()
                            }
                        }.getOrNull().orEmpty()
                        // v1.7.4.0：一个分类都没枚举出来的源，挂一组「按题材搜索」
                        // 的虚拟栏目，而不是整源丢弃。
                        //
                        // 实测 138 个小说源里 **36 个（26%）`ruleExplore` 为空**：
                        // 枚举出 0 个分类 = 推荐计划里根本没有它 = 首页一排都不会打给
                        // 它，用户只有到搜索页才够得着 —— 导入的源越多，首页反而越窄。
                        // 而这些源**全都配了 ruleSearch**（138/138），且 `search::<词>`
                        // 伪入口在 [exploreFiltered] 里会自动转成源自己的搜索请求，
                        // 于是给它们挂上跨源通用的题材词当栏目即可复用整条聚合链路。
                        val effective = if (cats.isNotEmpty()) cats
                        else if (type == SourceType.NOVEL || type == SourceType.COMIC) {
                            virtualCategories().also {
                                android.util.Log.i(
                                    "SourceEnum",
                                    "源「${info.name}」无发现分类，改用 ${it.size} 个题材词搜索入口参与聚合",
                                )
                            }
                        } else emptyList()
                        if (effective.isEmpty()) null else EnumEntry(info, adapter, effective)
                    }
                } }.awaitAll().filterNotNull()
            }

            // ---- 第二段：Venera 源「静态搜索入口 → 真分类页入口」升级 ----
            val veneraCount = entries.count { it.info.format == SourceFormat.VENERA }
            if (veneraCount > 0) {
                val upgradeDeadline =
                    started + if (fast) ENUM_UPGRADE_DEADLINE_MS else deadlineMs
                var upgraded = 0
                for (e in entries) {
                    if (e.info.format != SourceFormat.VENERA) continue
                    val left = upgradeDeadline - (System.currentTimeMillis() - started)
                    if (left <= 0) break
                    val t0 = System.currentTimeMillis()
                    // exploreCategories = 运行时评估优先，失败退回静态（与第一段等价，
                    // 无害）；只有非空才原地替换，绝不用空结果覆盖第一段的成果。
                    val cats = runCatchingCc {
                        withTimeoutOrNull(minOf(perSourceTimeoutMs, left)) {
                            e.adapter.exploreCategories()
                        }
                    }.getOrNull()
                    if (!cats.isNullOrEmpty()) {
                        e.cats = cats
                        upgraded++
                        android.util.Log.i(
                            "SourceEnum",
                            "源「${e.info.name}」升级真分类页入口：${cats.size} 个，${System.currentTimeMillis() - t0}ms",
                        )
                    }
                }
                android.util.Log.i(
                    "SourceEnum",
                    "枚举完成：${entries.size} 源（Venera $veneraCount 个升级 $upgraded 个），总耗时 ${System.currentTimeMillis() - started}ms",
                )
            }

            SourceEnumeration(
                items = entries.map { it.info to it.cats },
                enumerated = infos.size,
            )
        }

    /** 枚举中间产物：源的静态分类，第二段可被原地升级成真分类页入口 */
    private class EnumEntry(
        val info: SourceInfo,
        val adapter: SourceAdapter,
        var cats: List<ExploreCategory>,
    )

    /**
     * 无发现分类的源的兜底栏目（v1.7.4.0）。
     *
     * 选词标准：**跨源通用 + 站点普遍有内容**。太冷门的题材词大多数站搜不出东西，
     * 挂上去只是徒增一排空排；也不宜过多 —— 每个词都是一个潜在请求。
     *
     * 生成的是 `search::<词>` 伪入口（[com.novelreader.core.util.TagEntryUrl]），
     * 在 [exploreFiltered] 里会被还原成源自己的搜索请求。
     */
    private fun virtualCategories(): List<com.novelreader.domain.model.ExploreCategory> =
        FALLBACK_SEARCH_TAGS.map {
            com.novelreader.domain.model.ExploreCategory(
                title = it,
                url = com.novelreader.core.util.TagEntryUrl.search(it),
            )
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
                .let { com.novelreader.core.util.CoverMemory.fill(it) }
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

        /**
         * 无发现分类的源改走的「按题材搜索」兜底词（v1.7.4.0）。
         *
         * 只收**跨源通用**的题材：138 个小说源里 36 个没有 `ruleExplore`，它们的
         * 内容只能靠搜索够到；给它们挂上这些词，首页聚合才认得它们。
         */
        val FALLBACK_SEARCH_TAGS = listOf(
            "玄幻", "都市", "武侠", "仙侠", "科幻", "游戏", "历史", "言情",
        )

        /**
         * 枚举时**单个源**的取分类超时。
         *
         * legado 源是纯本地解析（毫秒级）；Venera 源要在隐藏 WebView 里跑一次
         * `evaluateJavascript`（实测几十到几百毫秒）。8 秒只用来兜住「源里有
         * `type:"dynamic"` 的 loader 正在联网」或 JS 卡死这两种异常。
         */
        const val ENUM_PER_SOURCE_MS = 8_000L

        /**
         * `fast` 模式下第二段（Venera 源运行时升级）的整段截止。
         *
         * 第一段静态分类毫秒级出结果、覆盖率 100%，所以第二段可以放心给足时间：
         * 预热过的引擎上 25 个 Venera 源串行评估约 20s（快的源几十毫秒，
         * `type:"dynamic"` loader 要联网的源 1~7s）。40s 留足余量；
         * 没升完的源保留静态入口参与聚合，绝不丢弃。调用方
         * （HomeViewModel 的 [FEED_PROBE_TIMEOUT_MS]）按这个数放宽到 70s。
         */
        const val ENUM_UPGRADE_DEADLINE_MS = 40_000L

        /** 静态表兜底的超时（纯字符串解析，实际上毫秒级） */
        const val ENUM_FALLBACK_MS = 1_500L

        /**
         * 枚举的全局截止：第一段（静态分类）的保险丝，以及非 fast 模式
         * （发现页 / 标签池）第二段升级的截止。
         *
         * 第一段是毫秒级的纯本地解析，15 秒对 167 个源也绰绰有余；
         * 非 fast 模式到点就停止升级，没升的源用静态入口，不会把页面拖住。
         */
        const val ENUM_DEADLINE_MS = 15_000L
    }
}
