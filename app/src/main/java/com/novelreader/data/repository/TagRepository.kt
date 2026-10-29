package com.novelreader.data.repository

import com.novelreader.core.util.ComicGenres
import com.novelreader.core.util.FanworkTags
import com.novelreader.core.util.NovelGenres
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.core.util.VideoGenres
import com.novelreader.data.cache.TagPlan
import com.novelreader.data.cache.TagStore
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 标签仓库（v1.5.10.0；v1.6.2.0 加强标签名清洗；v1.6.5.0 涵盖每一个源；
 * v1.6.6.0 漫画题材词 + 噪声标签过滤）。
 *
 * **标签自动收集**：把各源的发现分类（explore 分类）聚合成本内容的标签池——
 * 小说/漫画的「玄幻 / 都市 / 冒险…」、视频采集站的「电影 / 动作片 / 恋爱…」，
 * 不需要任何人工维护，导入源即得标签。
 *
 * v1.6.5.0：收集范围从「已启用源」扩大到**每一个源**（含被禁用的源，
 * 见 [ContentRepository.exploreSources] 的 includeDisabled）——用户临时禁用
 * 某个源不该让它的分类从标签池消失，重新启用时标签即复得。
 *
 * v1.6.6.0 两处修补：
 * - **漫画补题材词**：[attachComicGenres] 给漫画源绑「按题材搜索」入口，
 *   解决漫画源普遍没有分类页、标签池只有几十个的问题；
 * - **过滤伪标签**：[TagPlan.isNoiseTag] 丢掉「全部 / 最近更新 / 排行榜 /
 *   连载中」这类入口词与状态词。
 *
 * 收集涉及逐源分类枚举（视频源要 `?ac=list`），漫画源优先使用静态分类入口，
 * 不等待 Venera 隐藏 WebView 逐源动态评估，避免标签页长时间停在收集状态。因此：
 * - 结果落盘（[TagStore]），冷启动直接读缓存、毫秒级可用；
 * - 后台刷新用 [Mutex] 收敛，避免多处同时触发重复收集；
 * - 会话内内存缓存，同类型只收集一次（强制刷新除外）。
 */
class TagRepository(
    private val contentRepository: ContentRepository,
    private val store: TagStore,
    /**
     * 单标签搜索源数（v1.9.2.1）：0 = 全部可搜索源。
     *
     * 用 lambda 而不是直接注入 `PreferencesManager` —— 数据层不依赖 DataStore，
     * 单测里给个常量就能跑；调用点每收集一次读一次，改设置后下次收集即生效。
     */
    private val singleSourceLimit: suspend () -> Int = { SINGLE_SOURCES_ALL },
) {

    private val memCache = HashMap<SourceType, TagPlan>()
    private val refreshMutex = Mutex()

    /**
     * 取标签（内存 → 磁盘 → 现场收集）。
     *
     * @param maxAgeMs 缓存有效期（预留参数）；过期后由调用方决定何时 [refresh]。
     *   分类结构基本不变，太频繁收集只会白耗流量（视频源要真发请求）。
     */
    suspend fun tags(type: SourceType, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): TagPlan {
        // v1.9.2.1：设置里的「单标签搜索源数」改了要立刻生效。
        // 缓存里的关键词入口是按旧值绑的，光比对时间戳发现不了 ——
        // 所以把当时用的设置值记进快照，对不上就重建一次（每类型最多一次）。
        val wanted = wantedSourceLimit()
        memCache[type]?.let { if (it.keywordSourceLimit == wanted) return it }
        store.load(type.name)?.let { cached ->
            if (cached.keywordSourceLimit == wanted) {
                memCache[type] = cached
                return cached
            }
        }
        // 无任何缓存 / 设置已变：现场收集一次（首次使用，UI 应有 loading）
        return refresh(type)
    }

    /** 强制重新收集（下拉菜单刷新 / 首次使用）；并发调用收敛为一次 */
    suspend fun refresh(type: SourceType): TagPlan = refreshMutex.withLock {
        withContext(Dispatchers.IO) { collect(type) }
    }

    /**
     * 清空某个类型的标签池缓存（v1.8.9.2）。
     *
     * 只删缓存、不立刻重新收集 —— 由调用方决定下一步（用户可能想先改源配置）。
     * 内存缓存一并清掉，否则下一次 [tags] 还会命中旧对象。
     */
    suspend fun clear(type: SourceType): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            memCache.remove(type)
            store.clear(type.name)
        }
    }

    private suspend fun collect(type: SourceType): TagPlan {
        // v1.9.2.0：先读上一份快照 —— 被用户删掉的标签（[TagPlan.hiddenTags]）
        // 与自动新建的标签都要在本次收集中生效
        val previous = memCache[type] ?: store.load(type.name)
        val hidden = previous?.hiddenTags.orEmpty()
        // includeDisabled = true：涵盖每一个源（v1.6.5.0），禁用源的分类也进标签池
        val sources = try {
            contentRepository.exploreSourcesFast(type, includeDisabled = true).items
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("TagRepository", "收集源分类失败，type=$type", error)
            emptyList()
        }
        val pool = searchablePool(type)
        val plan = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for ((info, cats) in sources) {
            for (cat in cats) {
                // v1.6.2.0 清洗：源数据可能带前后空格、内部多空格、
                // \n / \t / 全角空格等噪声。复用 [TagPlan.normalizeTag] 与
                // fromJson 侧清洗保持单一来源，避免「热血漫画」「热血 漫画」
                // 被当成两个标签、热词推荐分散、用户搜不到同名内容。
                val clean = TagPlan.normalizeTag(cat.title)
                if (clean.isEmpty()) continue
                // v1.7.9.0：同义写法收成一个标签（妖精 / elf / エルフ / 요정 → 精灵）。
                // 以前各源用不同语言声明同一题材时，标签池里会并排出现好几个入口，
                // 每个下面只有一部分源；合并后入口唯一、每个标签覆盖的源也更全。
                val canonical = com.novelreader.core.util.TagAlias.canonicalOf(clean)
                // v1.6.6.0：丢掉「全部 / 最近更新 / 排行榜 / 连载中」这类
                // 入口词与状态词，它们不是内容题材（详见 TagPlan.isNoiseTag）
                if (TagPlan.isNoiseTag(canonical)) continue
                // 用户删掉的标签不再收进来（否则「删了下次刷新又回来」）
                if (canonical in hidden || clean in hidden) continue
                plan.getOrPut(canonical) { mutableListOf() }.add(info.id to cat.url)
                // v1.9.2.0：**组内成员单独成键**。
                // 「全彩」组有六十多个写法，各站往往只把其中一两个做成真分类
                // （「彩绘」「彩色版」…）。以前它们全被并进组首词，组里每个词
                // 自己的分类入口就此丢失 —— 用户想单搜「彩绘」只能靠关键词瞎撞。
                // 现在成员保留自己那一份真实入口，组首词那边照旧收全部入口
                // （[TagPlan.entriesFor] 默认按同义组合并，分组行为一点不变）。
                if (canonical != clean && !TagPlan.isNoiseTag(clean)) {
                    plan.getOrPut(clean) { mutableListOf() }.add(info.id to cat.url)
                }
            }
        }
        // 通用题材词和同人原著词绑定到搜索入口，避免依赖源恰好配置分类页
        val wanted = wantedSourceLimit()
        val keywordLimit = effectiveLimit(pool, wanted)
        attachSearchTags(type, plan, pool, keywordLimit, hidden)
        // v1.9.2.0：把用户搜过、自动新建的标签补回来（源站分类里没有它们）
        val custom = previous?.customTags.orEmpty().filterNot { it in hidden }.toSet()
        restoreCustomTags(plan, custom, pool, keywordLimit, hidden)
        // 兜底：任何路径（含题材词绑定）都不该把已删除的标签带回来
        hidden.forEach { plan.remove(it) }
        val snapshot = TagPlan(
            collectedAtMs = if (plan.isEmpty()) 0L else System.currentTimeMillis(),
            plan = plan,
            customTags = custom,
            hiddenTags = hidden,
            // 记下这次用的设置值，设置一改 [tags] 就能发现并重建
            keywordSourceLimit = wanted,
        )
        if (snapshot.plan.isNotEmpty()) {
            memCache[type] = snapshot
            store.save(type.name, snapshot)
        }
        return snapshot
    }

    /** 可搜索的源 id（按关键词搜索入口的绑定对象）；取不到时返回空表 */
    private suspend fun searchablePool(type: SourceType): List<String> = try {
        contentRepository.searchableSourceIds(type, 0)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w("TagRepository", "获取可搜索源失败，type=$type", error)
        emptyList()
    }.distinct()

    /**
     * 单个标签最多绑多少个源的**关键词**入口（v1.9.2.1）。
     *
     * 设置里给 0（默认）或给到比池子还大 → 全部可搜索源；否则取该值。
     * 只约束「没有源站分类页、只能按关键词搜」的标签；有真分类的标签照旧
     * 有多少源收多少源，不受这个上限影响。
     */
    private suspend fun wantedSourceLimit(): Int =
        runCatching { singleSourceLimit() }.getOrDefault(SINGLE_SOURCES_ALL).coerceAtLeast(0)

    /** 把设置值换算成实际可用的源数：0 / 超池 → 全部 */
    private fun effectiveLimit(pool: List<String>, wanted: Int): Int =
        if (wanted <= 0 || wanted >= pool.size) pool.size else wanted

    /**
     * 重新收集后把**自动新建的标签**补回池子（v1.9.2.0）。
     *
     * 这类标签没有源站分类页，只有「按关键词搜索」入口。不补的话，用户搜过的
     * 生僻标签会在每次刷新标签池后凭空消失 —— 而「搜过就收录」正是这次要的效果。
     */
    private fun restoreCustomTags(
        plan: LinkedHashMap<String, MutableList<Pair<String, String>>>,
        custom: Set<String>,
        pool: List<String>,
        limit: Int,
        hidden: Set<String> = emptySet(),
    ) {
        if (custom.isEmpty() || pool.isEmpty()) return
        for (name in custom) {
            if (name in hidden) continue
            if (plan[name]?.isNotEmpty() == true) continue
            plan[name] = pool.take(limit)
                .map { it to TagEntryUrl.search(name) }
                .toMutableList()
        }
    }

    /**
     * 确保这些标签在池子里存在；不存在就**自动新建并绑定搜索入口**（v1.9.2.0）。
     *
     * 两个使用场景：
     * 1. 用户搜了一个池子里没有的词（「如果没有这个标签自动新建并搜索」）；
     * 2. 搜索过程中冒出来的新标签（「搜索出的标签同步收录」）。
     *
     * 新建的标签会落盘并记进 [TagPlan.customTags]，下次刷新标签池仍在。
     * 已有入口的标签只登记不改写 —— 源站真分类永远优先于关键词搜索。
     *
     * @return 本次**新建**的标签名（已存在的不会出现在返回值里）
     */
    suspend fun ensureTags(type: SourceType, names: Collection<String>): List<String> =
        refreshMutex.withLock {
            withContext(Dispatchers.IO) {
                val current = memCache[type] ?: store.load(type.name) ?: TagPlan.EMPTY
                val wanted = names.map(TagPlan::normalizeTag)
                    .filter { it.isNotEmpty() && !TagPlan.isNoiseTag(it) }
                    // 用户删掉的标签不再自动新建，否则「删了又被搜回来」
                    .filterNot { it in current.hiddenTags }
                    .distinct()
                if (wanted.isEmpty()) return@withContext emptyList()
                // 池子还没收集过（plan 为空）时不做自动新建：这份快照一旦落盘，
                // 会盖掉「下次进页面重新收集」的完整池子，只剩这几个新建标签。
                if (current.plan.isEmpty()) return@withContext emptyList()
                val pool = searchablePool(type)
                val limit = effectiveLimit(pool, wantedSourceLimit())
                val plan = LinkedHashMap(current.plan)
                val custom = current.customTags.toMutableSet()
                val added = ArrayList<String>()
                for (name in wanted) {
                    // 已有入口（真分类或此前建过的搜索入口）→ 什么都不用做
                    if (plan[name]?.isNotEmpty() == true) continue
                    // 组首词可能以同义写法存着（如池里是「全彩」而用户搜「全彩版」）：
                    // 那属于同组已有标签，不该再新建一个重复入口
                    val coveredByGroup = com.novelreader.core.util.TagAlias.isGroupHead(name) &&
                        plan.any { (key, entries) ->
                            entries.isNotEmpty() &&
                                com.novelreader.core.util.TagAlias.canonicalOf(key) ==
                                com.novelreader.core.util.TagAlias.canonicalOf(name)
                        }
                    if (coveredByGroup) continue
                    if (pool.isEmpty()) break
                    plan[name] = pool.take(limit)
                        .map { it to TagEntryUrl.search(name) }
                    custom += name
                    added += name
                }
                if (added.isNotEmpty()) {
                    val snapshot = current.copy(
                        plan = plan,
                        customTags = custom,
                        hiddenTags = current.hiddenTags,
                        collectedAtMs = current.collectedAtMs.takeIf { it > 0 }
                            ?: System.currentTimeMillis(),
                    )
                    memCache[type] = snapshot
                    store.save(type.name, snapshot)
                }
                added
            }
        }

    /**
     * 删除（隐藏）标签（v1.9.2.0）。
     *
     * 三件事一起做，缺一不可：
     * 1. 从 [TagPlan.plan] 里摘掉入口 —— 立刻从热门 / 全部标签里消失；
     * 2. 记进 [TagPlan.hiddenTags] —— 否则下次收集源分类、或自动新建时又回来了；
     * 3. 落盘。
     *
     * 本地索引里的收录记录由 [com.novelreader.data.repository.TagIndexRepository.deleteTag] 清，
     * 两处都要清，否则 chip 上还会挂着一个已经删掉的计数。
     *
     * @return 实际生效的标签名（被删除的那些）
     */
    suspend fun deleteTags(type: SourceType, names: Collection<String>): List<String> =
        refreshMutex.withLock {
            withContext(Dispatchers.IO) {
                val wanted = names.map(TagPlan::normalizeTag)
                    .filter { it.isNotEmpty() }
                    .distinct()
                if (wanted.isEmpty()) return@withContext emptyList()
                val current = memCache[type] ?: store.load(type.name) ?: return@withContext emptyList()
                val plan = LinkedHashMap(current.plan)
                val hidden = current.hiddenTags.toMutableSet()
                val removed = ArrayList<String>()
                wanted.forEach { name ->
                    plan.remove(name)
                    hidden += name
                    removed += name
                }
                val snapshot = current.copy(
                    plan = plan,
                    customTags = current.customTags - hidden,
                    hiddenTags = hidden,
                )
                memCache[type] = snapshot
                store.save(type.name, snapshot)
                removed
            }
        }

    /**
     * 给漫画标签池补通用题材词（v1.6.6.0；v1.7.6.3 改为「全源轮转」）。
     *
     * 漫画源里有一部分**没有分类能力**：legado 漫画源大多没配 `exploreUrl`，
     * Venera 源也常解析不出 `category.parts`。这里把 [ComicGenres.ALL] 里的题材词
     * 绑到源的**搜索入口**（[TagEntryUrl.search]），点击标签即按题材搜索。
     *
     * v1.7.6.3：原来只给「没有分类能力的前 8 个源」绑定，绝大多数漫画源拿不到
     * 新增题材词（用户实测标签池里没有「精灵」之类）。现在改为**全源轮转**：
     * 用游标在全部可搜索漫画源之间依次分配，每个题材词最多绑
     * [effectiveLimit] 个源 —— 每个源都能分到大量题材词，
     * 而单个标签的搜索源数仍有上界（不会把标签搜索拖到几十秒）。
     *
     * v1.9.2.1：这个上界不再写死 8，改由设置决定（默认 = 全部可搜索源）。
     * 写死 8 的那版让「整组搜全部源、单标签只搜 8 个源」，同一组词两种口径。
     *
     * 「无码」和「全彩」是用户明确要求的跨源入口，不受普通标签源数上限限制，
     * 覆盖全部可搜索源；标签页执行时仍只请求已启用的源，并受并发限制。
     *
     * 源已经有同名真实分类时**跳过**该源的搜索入口：既避免同一标签下同一个源
     * 出现两条（真分类 + 标题搜索），也避免把搜标题撞出来的无关作品混进分类结果。
     */
    private suspend fun attachSearchTags(
        type: SourceType,
        plan: LinkedHashMap<String, MutableList<Pair<String, String>>>,
        pool: List<String>,
        limit: Int,
        hidden: Set<String> = emptySet(),
    ) {
        val searchTags = when (type) {
            SourceType.COMIC ->
                ComicGenres.ALL.map { it to it } + FanworkTags.searchTags + WIDE_COMIC_SEARCH_TAGS
            SourceType.NOVEL -> NovelGenres.ALL.map { it to it } + FanworkTags.searchTags
            // 视频（v1.8.7.1）：此前这里直接 return，视频标签池完全由采集站分类名决定 ——
            // 站方没建「无码」分类，标签池里就永远没有这个词，单搜只会「未找到标签」。
            // 现在按 [VideoGenres.ALL] 把题材 / 属性词绑到源的搜索入口：
            // 点「无码」= 按整组同义写法（無修正 / uncensored…）并发搜索，不再依赖分类页。
            SourceType.VIDEO -> VideoGenres.ALL.map { it to it }
        }
        if (pool.isEmpty()) return

        var cursor = 0
        for ((rawLabel, query) in searchTags) {
            val label = TagAlias.canonicalOf(rawLabel)
            if (label in hidden) continue
            val canonicalQuery = TagAlias.canonicalOf(query)
            val entries = plan.getOrPut(label) { mutableListOf() }
            val already = entries.mapTo(HashSet()) { it.first }
            val sourceLimit = when {
                type == SourceType.COMIC && label in WIDE_COMIC_TAG_NAMES -> pool.size
                // 视频的属性标签（无码 / 有码 / 里番 / 成人）同样覆盖全部可搜索源：
                // 这类词在不同站上写法差异极大，只绑 8 个源等于只看到一半结果
                type == SourceType.VIDEO && label in WIDE_VIDEO_TAG_NAMES -> pool.size
                // v1.9.2.1：普通题材词改由设置决定（默认全部源）。
                // 以前写死 [ComicGenres.MAX_SOURCES_PER_GENRE]（8），导致单标签搜索
                // 与整组搜索口径不一致 —— 整组覆盖全部源，单标签只剩 8 个。
                else -> limit
            }
            // 该题材已有足够入口（通常是多个源的真分类）→ 不再补搜索入口
            if (already.size >= sourceLimit) continue
            var need = sourceLimit - already.size
            var scanned = 0
            while (need > 0 && scanned < pool.size) {
                val sid = pool[(cursor + scanned) % pool.size]
                scanned++
                if (sid in already) continue
                entries.add(sid to TagEntryUrl.search(canonicalQuery))
                need--
            }
            cursor = (cursor + scanned) % pool.size
        }
    }

    companion object {
        private val WIDE_COMIC_SEARCH_TAGS = listOf("无码" to "无码", "全彩" to "全彩")
        private val WIDE_COMIC_TAG_NAMES = WIDE_COMIC_SEARCH_TAGS.mapTo(HashSet()) {
            TagAlias.canonicalOf(it.first)
        }

        /** 视频里不限源的属性标签（v1.8.7.1），口径见 [VideoGenres.WIDE_SEARCH_TAGS] */
        private val WIDE_VIDEO_TAG_NAMES = VideoGenres.WIDE_SEARCH_TAGS
            .mapTo(HashSet()) { TagAlias.canonicalOf(it) }

        private const val DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /**
         * 单标签搜索源数的默认值：0 = 全部可搜索源（v1.9.2.1）。
         *
         * v1.9.2.0 这里写死 8，本意是「新标签没有分类页，只能按关键词搜，
         * 绑太多源会把一次搜索拖到几十秒」。但它和整组搜索的口径分裂了：
         * 整组（宽标签）覆盖全部源，单标签只剩 8 个，看起来就是「点单个写法丢结果」。
         * 现在默认全给，真要压请求量由设置项下调。
         */
        private const val SINGLE_SOURCES_ALL = 0
    }
}
