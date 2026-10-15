package com.novelreader.data.repository

import com.novelreader.data.cache.TagPlan
import com.novelreader.data.cache.TagStore
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 标签仓库（v1.5.10.0）。
 *
 * **标签自动收集**：把各启用源的发现分类（explore 分类）聚合成本内容的标签池——
 * 小说/漫画的「玄幻 / 都市 / 冒险…」、视频采集站的「电影 / 动作片 / 恋爱…」，
 * 不需要任何人工维护，导入源即得标签。
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
        val sources = runCatching { contentRepository.exploreSources(type) }
            .getOrDefault(emptyList())
        val plan = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for ((info, cats) in sources) {
            for (cat in cats) {
                if (cat.title.isBlank()) continue
                plan.getOrPut(cat.title) { mutableListOf() }.add(info.id to cat.url)
            }
        }
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

    companion object {
        private const val DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
