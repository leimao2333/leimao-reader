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
        memCache[type]?.let { return it }
        store.load(type.name)?.let { cached ->
            memCache[type] = cached
            return cached
        }
        // 无任何缓存：现场收集一次（首次使用，UI 应有 loading）
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
        // includeDisabled = true：涵盖每一个源（v1.6.5.0），禁用源的分类也进标签池
        val sources = try {
            contentRepository.exploreSourcesFast(type, includeDisabled = true).items
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("TagRepository", "收集源分类失败，type=$type", error)
            emptyList()
        }
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
                plan.getOrPut(canonical) { mutableListOf() }.add(info.id to cat.url)
            }
        }
        // 通用题材词和同人原著词绑定到搜索入口，避免依赖源恰好配置分类页
        attachSearchTags(type, plan)
        val snapshot = TagPlan(
            collectedAtMs = if (plan.isEmpty()) 0L else System.currentTimeMillis(),
            plan = plan,
        )
        if (snapshot.plan.isNotEmpty()) {
            memCache[type] = snapshot
            store.save(type.name, snapshot)
        }
        return snapshot
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
     * [ComicGenres.MAX_SOURCES_PER_GENRE] 个源 —— 每个源都能分到大量题材词，
     * 而单个标签的搜索源数仍有上界（不会把标签搜索拖到几十秒）。
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
        val pool = try {
            contentRepository.searchableSourceIds(type, 0)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("TagRepository", "获取可搜索源失败，type=$type", error)
            emptyList()
        }.distinct()
        if (pool.isEmpty()) return

        var cursor = 0
        for ((rawLabel, query) in searchTags) {
            val label = TagAlias.canonicalOf(rawLabel)
            val canonicalQuery = TagAlias.canonicalOf(query)
            val entries = plan.getOrPut(label) { mutableListOf() }
            val already = entries.mapTo(HashSet()) { it.first }
            val sourceLimit = when {
                type == SourceType.COMIC && label in WIDE_COMIC_TAG_NAMES -> pool.size
                // 视频的属性标签（无码 / 有码 / 里番 / 成人）同样覆盖全部可搜索源：
                // 这类词在不同站上写法差异极大，只绑 8 个源等于只看到一半结果
                type == SourceType.VIDEO && label in WIDE_VIDEO_TAG_NAMES -> pool.size
                else -> ComicGenres.MAX_SOURCES_PER_GENRE
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
    }
}
