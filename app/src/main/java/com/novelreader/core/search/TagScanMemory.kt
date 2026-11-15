package com.novelreader.core.search

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 「翻页记忆」（v1.9.6.0）。
 *
 * 解决的问题：攒到上万条收录之后，同一个标签的前几十页全是老面孔。用户开着「只看新」
 * 一路连翻，翻到第 40 页终于看到新东西；退出应用再进来，一切归零 —— 又从第 1 页
 * 开始把那些老面孔重翻一遍。翻页记忆就是把这根「书签」存下来：下次开「只看新」
 * 直接从上次停下的那一页往后问。
 *
 * 存什么：**每个标签组合**一个「下一页页码」。
 * - 键 = `类型|标签（排序后）`，例如 `VIDEO|里番组`、`COMIC|无码+全彩`；
 * - 值 = 上次翻到的页码（下一页）+ 更新时间 + 累计翻过的页数。
 *
 * 什么时候清：
 * - **一路翻到底**：说明这个标签真的没有更多了，书签作废 —— 下次从头开始，
 *   源站可能已经上了新内容；
 * - 超过 [MAX_AGE_MS] 没用过：站点的分页顺序会变，太旧的书签不如没有；
 * - 用户点「从头翻」。
 *
 * 只存页码，不存任何作品信息，也不落任何站点标识。
 */
object TagScanMemory {

    private const val TAG = "TagScanMemory"

    /** 书签最多留这么久（7 天）：更久的书签对应的分页早就错位了 */
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    /** 最多记多少个标签组合：超出按最久未用淘汰，避免无限长 */
    private const val MAX_ENTRIES = 300

    /** 一条书签 */
    data class Entry(
        /** 下次该从第几页开始问 */
        val nextPage: Int = 1,
        /** 最后更新时间（毫秒） */
        val updatedAt: Long = 0L,
        /** 累计翻过多少页（只用于显示「上次翻到第 N 页」） */
        val scannedPages: Int = 0,
    )

    private val gson = Gson()

    private var file: File? = null

    /** 内存缓存（LRU：常用标签组合放前面） */
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {}

    private var loaded = false

    /** 应用启动时指一下存储位置；目录不存在时自动创建 */
    fun init(dir: File) {
        synchronized(this) {
            val target = File(dir, "tag_scan_memory.json")
            file = target
            loaded = false
            cache.clear()
        }
    }

    /**
     * 标签组合的键。
     *
     * 标签**排序后**拼接：`无码 全彩` 与 `全彩 无码` 是同一个组合，不该各存一份。
     */
    fun keyOf(type: String, tags: List<String>): String {
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        return "$type|${normalized.joinToString("+")}"
    }

    /** 读一条书签（过期/不存在都返回 null） */
    fun peek(key: String): Entry? {
        ensureLoaded()
        val entry = synchronized(this) { cache[key] } ?: return null
        if (System.currentTimeMillis() - entry.updatedAt > MAX_AGE_MS) {
            synchronized(this) { cache.remove(key) }
            return null
        }
        return entry
    }

    /** 上次翻到的下一页；没有书签返回 1 */
    fun nextPageOf(key: String): Int = peek(key)?.nextPage?.coerceAtLeast(1) ?: 1

    /** 记下进度（每翻完一页调一次） */
    fun remember(key: String, nextPage: Int, scannedPages: Int = 0) {
        if (key.isBlank() || nextPage <= 1) return
        ensureLoaded()
        synchronized(this) {
            val previous = cache[key]
            cache[key] = Entry(
                nextPage = nextPage,
                updatedAt = System.currentTimeMillis(),
                scannedPages = maxOf(scannedPages, previous?.scannedPages ?: 0),
            )
            if (cache.size > MAX_ENTRIES) {
                // LinkedHashMap(accessOrder=true) 的头部就是最久没用的
                val oldest = cache.keys.firstOrNull() ?: return@synchronized
                cache.remove(oldest)
            }
        }
        persist()
    }

    /** 作废一条书签（翻到底 / 用户点「从头翻」） */
    fun clear(key: String) {
        if (key.isBlank()) return
        ensureLoaded()
        val removed = synchronized(this) { cache.remove(key) != null }
        if (removed) persist()
    }

    // ===== 落盘 =====

    private fun ensureLoaded() {
        synchronized(this) {
            if (loaded) return
            loaded = true
            val target = file ?: return
            if (!target.exists()) return
            runCatching {
                val type = object : TypeToken<Map<String, Entry>>() {}.type
                val map: Map<String, Entry> =
                    gson.fromJson(target.readText(), type) ?: emptyMap()
                val now = System.currentTimeMillis()
                map.entries
                    .filter { now - it.value.updatedAt <= MAX_AGE_MS && it.value.nextPage > 1 }
                    .sortedBy { it.value.updatedAt }
                    .forEach { cache[it.key] = it.value }
            }.onFailure { Log.w(TAG, "读取翻页记忆失败：${it.javaClass.simpleName}") }
        }
    }

    /** 写盘：文件很小（几百条 × 几十字节），同步写不会拖住界面 */
    private fun persist() {
        val target = file ?: return
        val snapshot = synchronized(this) { LinkedHashMap(cache) }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(gson.toJson(snapshot))
        }.onFailure { Log.w(TAG, "保存翻页记忆失败：${it.javaClass.simpleName}") }
    }
}
