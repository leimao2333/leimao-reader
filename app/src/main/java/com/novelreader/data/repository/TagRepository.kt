package com.novelreader.data.repository

import com.novelreader.core.util.ComicGenres
import com.novelreader.core.util.TagEntryUrl
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
 * 收集涉及逐源请求（视频源要 `?ac=list`），因此：
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

    private suspend fun collect(type: SourceType): TagPlan {
        // includeDisabled = true：涵盖每一个源（v1.6.5.0），禁用源的分类也进标签池
        val sources = runCatching { contentRepository.exploreSources(type, includeDisabled = true) }
            .getOrDefault(emptyList())
        val plan = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for ((info, cats) in sources) {
            for (cat in cats) {
                // v1.6.2.0 清洗：源数据可能带前后空格、内部多空格、
                // \n / \t / 全角空格等噪声。复用 [TagPlan.normalizeTag] 与
                // fromJson 侧清洗保持单一来源，避免「热血漫画」「热血 漫画」
                // 被当成两个标签、热词推荐分散、用户搜不到同名内容。
                val clean = TagPlan.normalizeTag(cat.title)
                if (clean.isEmpty()) continue
                // v1.6.6.0：丢掉「全部 / 最近更新 / 排行榜 / 连载中」这类
                // 入口词与状态词，它们不是内容题材（详见 TagPlan.isNoiseTag）
                if (TagPlan.isNoiseTag(clean)) continue
                plan.getOrPut(clean) { mutableListOf() }.add(info.id to cat.url)
            }
        }
        // v1.6.6.0：漫画补充通用题材词，绑到「按题材搜索」入口
        attachComicGenres(type, plan)
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
     * 给漫画标签池补通用题材词（v1.6.6.0）。
     *
     * 漫画源绝大多数没有发现分类能力（25 个 Venera 源协议层就没有分类浏览，
     * legado 漫画源也大多没配 `exploreUrl`），只靠源分类时标签池只有几十个。
     * 这里把 [ComicGenres.ALL] 里的题材词绑到漫画源的**搜索入口**
     * （[TagEntryUrl.search]），点击标签即跨源按题材搜索——不依赖分类页。
     *
     * 只给排序靠前的 [ComicGenres.MAX_SOURCES_PER_GENRE] 个源绑定：标签搜索是
     * 等全部源返回再展示，绑太多会把等待时间拉到几十秒（见该常量注释）。
     * 与源自带分类同名的词（如「热血」）只合并 entries，不覆盖。
     */
    private suspend fun attachComicGenres(
        type: SourceType,
        plan: LinkedHashMap<String, MutableList<Pair<String, String>>>,
    ) {
        if (type != SourceType.COMIC) return
        val sourceIds = runCatching {
            contentRepository.searchableSourceIds(type, ComicGenres.MAX_SOURCES_PER_GENRE)
        }.getOrDefault(emptyList())
        if (sourceIds.isEmpty()) return
        for (genre in ComicGenres.ALL) {
            val entries = plan.getOrPut(genre) { mutableListOf() }
            sourceIds.forEach { id ->
                val entry = id to TagEntryUrl.search(genre)
                if (entry !in entries) entries.add(entry)
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
