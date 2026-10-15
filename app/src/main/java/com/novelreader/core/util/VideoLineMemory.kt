package com.novelreader.core.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 「记住上次用的源（线路）」的**纯逻辑 + 序列化**（v1.6.9.0）。
 *
 * 背景：播放页的「换源」Tab 里通常有 5~10 条线路，用户挑出一条能用的之后，
 * 退出再进来又是从第 0 条起播（`loadLines` 只会定位到「详情页传进来的那一集所在线路」，
 * 也就是第一条）。这里把用户的选择按 **(采集源, 条目)** 记住，下次进入播放页优先用它。
 *
 * 两个设计点：
 * 1. **身份用线路名，不只用下标** —— 线路顺序由源的 `vod_play_url` 决定，
 *    源追加一条线路就会整体错位，光存下标下次会指到别人身上。
 *    所以名字优先、下标兜底（防越界）。
 * 2. **落盘前裁剪** —— 这是纯体验优化，不值得无限增长：超过 [MAX_ENTRIES] 条
 *    按更新时间保留最近的，超过 [MAX_AGE_MS] 没再看的直接丢。
 */
object VideoLineMemory {

    /** 一条记忆：线路名 + 下标 + 更新时间 */
    data class Entry(
        val lineName: String,
        val lineIndex: Int,
        val updatedAt: Long,
    )

    /**
     * 落盘用的中间结构（v1.6.9.0）。
     *
     * 字段全部可空：磁盘上的 JSON 可能是旧版本写的 / 被截断的，
     * Gson 遇到 null 会往非空字段塞 null，Kotlin 读出来就 NPE。
     * 这一层保证「最坏情况 = 丢一条记忆」，绝不打断播放。
     */
    private data class Stored(
        val key: String? = null,
        val lineName: String? = null,
        val lineIndex: Int = 0,
        val updatedAt: Long = 0L,
    )

    /** 记忆条目数上限（按 updatedAt 保留最近的） */
    const val MAX_ENTRIES = 200

    /** 记忆有效期：超过这么久没再看的条目丢弃（180 天） */
    const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

    /** 内部 key 分隔符：不可见控制字符，避免和 sourceId / itemId 里的字符撞上 */
    private const val SEP = '\u0001'

    private val gson by lazy { Gson() }

    /** 记忆 key：同一部片子在同一采集源里共用一条记忆（换集不换源） */
    fun key(sourceId: String, itemId: String): String = "$sourceId$SEP$itemId"

    /**
     * 记忆里的线路落在**当前线路表**的哪个下标。
     *
     * - 先按线路名精确匹配（线路顺序变了也能对上）；
     * - 名字匹配不上（源改了线路名）再看下标，越界则返回 `null`；
     * - 线路表为空时恒返回 `null`。
     */
    fun resolveIndex(entry: Entry?, lineNames: List<String>): Int? {
        if (entry == null || lineNames.isEmpty()) return null
        if (entry.lineName.isNotBlank()) {
            val byName = lineNames.indexOfFirst { it == entry.lineName }
            if (byName >= 0) return byName
        }
        return entry.lineIndex.takeIf { it in lineNames.indices }
    }

    /** 写入一条记忆（不改原 map，返回新 map） */
    fun put(
        map: Map<String, Entry>,
        key: String,
        lineName: String,
        lineIndex: Int,
        now: Long,
    ): Map<String, Entry> {
        val next = LinkedHashMap(map)
        next[key] = Entry(lineName.trim(), lineIndex, now)
        return next
    }

    /**
     * 裁剪：先丢过期条目，再按更新时间保留最近的 [maxEntries] 条。
     *
     * @param now 当前时间戳（由调用方注入，便于单测）
     */
    fun prune(
        map: Map<String, Entry>,
        now: Long,
        maxEntries: Int = MAX_ENTRIES,
        maxAgeMs: Long = MAX_AGE_MS,
    ): Map<String, Entry> {
        val fresh = map.filterValues { now - it.updatedAt <= maxAgeMs }
        if (fresh.size <= maxEntries) return fresh
        return fresh.entries
            .sortedByDescending { it.value.updatedAt }
            .take(maxEntries)
            .associate { it.key to it.value }
    }

    /** 序列化：Gson 数组（纯 JVM 可用，便于离线单测） */
    fun encode(map: Map<String, Entry>): String =
        gson.toJson(map.map { (k, v) -> Stored(k, v.lineName, v.lineIndex, v.updatedAt) })

    /** 反序列化：损坏 / 空串一律当作「没有记忆」，绝不抛异常打断播放 */
    fun decode(json: String?): Map<String, Entry> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<List<Stored?>>() {}.type
            val parsed: List<Stored?>? = gson.fromJson(json, type)
            parsed.orEmpty()
                .mapNotNull { stored ->
                    val key = stored?.key ?: return@mapNotNull null
                    if (key.isBlank()) return@mapNotNull null
                    key to Entry(
                        lineName = stored.lineName.orEmpty(),
                        lineIndex = stored.lineIndex,
                        updatedAt = stored.updatedAt,
                    )
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }
}
