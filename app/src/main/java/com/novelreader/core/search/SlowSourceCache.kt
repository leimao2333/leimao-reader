package com.novelreader.core.search

import com.novelreader.domain.model.MediaItem

/**
 * 慢源结果缓存（v1.8.9.1）。
 *
 * 解决的是「第一次搜 partial，第二次还是 partial」——慢源每次都要重新等 8 秒超时。
 * 现在按 (源 × 写法 × 页码) 缓存上次拿到的内容，[TTL_MS] 内再搜直接命中，
 * 慢源的内容能立刻参与首包，剩下的时间用来刷新。
 *
 * 三条约束：
 * - **只缓存非空结果**。空结果可能是「这次超时了」，缓存它等于把慢源永久判空。
 * - **LRU 上限** [MAX_ENTRIES] 条，标签搜索一次就有几十个源 × 多个写法，不设上限会一直涨。
 * - **进程内**，不落盘：内容会变，跨进程复用的收益远小于「看到过期内容」的风险。
 */
object SlowSourceCache {

    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_ENTRIES = 256

    private data class Entry(val items: List<MediaItem>, val atMs: Long)

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    fun key(sourceId: String, term: String, page: Int): String = "$sourceId|$term|$page"

    @Synchronized
    fun get(key: String, nowMs: Long = System.currentTimeMillis()): List<MediaItem>? {
        val entry = map[key] ?: return null
        if (nowMs - entry.atMs > TTL_MS) {
            map.remove(key)
            return null
        }
        return entry.items
    }

    @Synchronized
    fun put(key: String, items: List<MediaItem>, nowMs: Long = System.currentTimeMillis()) {
        if (items.isEmpty()) return
        map[key] = Entry(items, nowMs)
    }

    @Synchronized
    fun clear() = map.clear()
}

/**
 * 源健康度（v1.8.9.1）。
 *
 * 只用来**调整调度顺序**，绝不用来过滤结果 —— 这是和旧 `deadSources` 的本质区别：
 * 旧实现把「这个源这轮别问了」当成结论，于是一个源认不出英文写法就被整轮拉黑，
 * 它其它写法的结果一起丢掉（「结果越搜越少」的主因之一）。
 * 现在的口径是：慢源排到后一波（让快源先把首包填满），但它照样会被问到。
 */
object SourceHealth {

    private const val MIN_SAMPLES = 3
    private const val SLOW_TIMEOUT_RATE = 0.5

    private data class Stat(var timeout: Int = 0, var samples: Int = 0, var latencySumMs: Long = 0)

    private val stats = java.util.concurrent.ConcurrentHashMap<String, Stat>()

    fun record(sourceId: String, outcome: RecallOutcome, latencyMs: Long) {
        val stat = stats.getOrPut(sourceId) { Stat() }
        synchronized(stat) {
            stat.samples++
            stat.latencySumMs += latencyMs
            if (outcome == RecallOutcome.TIMEOUT) stat.timeout++
        }
    }

    /** 历史超时率过高 → 排到后一波 */
    fun isSlow(sourceId: String): Boolean {
        val stat = stats[sourceId] ?: return false
        synchronized(stat) {
            if (stat.samples < MIN_SAMPLES) return false
            return stat.timeout.toDouble() / stat.samples >= SLOW_TIMEOUT_RATE
        }
    }

    fun averageLatencyMs(sourceId: String): Long {
        val stat = stats[sourceId] ?: return 0
        synchronized(stat) {
            if (stat.samples == 0) return 0
            return stat.latencySumMs / stat.samples
        }
    }

    fun clear() = stats.clear()
}
