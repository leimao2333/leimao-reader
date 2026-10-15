package com.novelreader.data.cache

import com.google.gson.Gson
import java.io.File

/**
 * 视频预加载磁盘缓存（v1.6.0.0）。
 *
 * 此前 [com.novelreader.ui.screens.video.VideoPlayerViewModel] 的预取只活在内存里
 * （`prefetchCache: MutableMap`），App 进程一死就全部清空。现在每解析出一集的播放地址
 * 就**落盘**到这里，方便：
 *
 * 1. **会话内加速**：下一次冷启动 / 进入播放页时，热数据已在磁盘上，串成 URL 比
 *    重新解析快。
 * 2. **可清理**：缓存管理页能列出当前占用的预加载大小，一键清理（来源：
 *    解析过的播放地址只是「指针」，过期 / 失效随时可能发生，留着只会占空间）。
 *
 * - 一个 episode 一个文件：`v1__<sourceId>__<itemId>__<chapterIndex>__<lineIndex>.json`
 *   （避免文件冲突与特殊字符问题），整文件替换；
 * - 写入先落临时文件再改名，中途被杀不会留下半截内容；
 * - 只缓存「成功且非空」的结果；不做过期时间（产品语义由用户手里的清理按钮决定）；
 * - 换源 / 切集时 `removeKey` 删掉失效条目（避免脏数据命中）。
 *
 * 构造参数用 [File] 而不是 Context：单测可直接指到临时目录，无需 Robolectric。
 */
class VideoPrefetchCache(private val dir: File) {

    init {
        runCatching { dir.mkdirs() }
    }

    private val gson = Gson()

    /** 单条预加载条目（播放地址 + 请求头） */
    data class Entry(
        val videoUrl: String,
        val headers: Map<String, String>,
    )

    /** 读预加载缓存（不存在 / 损坏 → null） */
    fun load(key: String): Entry? = runCatching {
        val f = fileOf(key)
        if (f.isFile) gson.fromJson(f.readText(), Entry::class.java) else null
    }.getOrNull()

    /**
     * 写预加载缓存。
     *
     * @param key 同 [load] 的 key
     * @param entry 待写入的条目
     */
    fun save(key: String, entry: Entry) {
        runCatching {
            dir.mkdirs()
            // 临时文件也走 sanitize（Windows 上不能含 `:` `/` 等字符），避免在某些文件系统上写不动
            tmpOf(key).delete()
            val target = fileOf(key)
            val tmp = tmpOf(key)
            tmp.writeText(gson.toJson(entry))
            if (!tmp.renameTo(target)) {
                // 个别文件系统 / Windows 下覆盖已存在文件会改名失败，退化为直接写
                target.writeText(gson.toJson(entry))
                tmp.delete()
            }
        }
    }

    private fun tmpOf(key: String) = File(dir, "${sanitize(key)}.tmp")

    /** 删除单条预加载（换源 / 切集清旧） */
    fun removeKey(key: String) {
        runCatching { fileOf(key).delete() }
    }

    /**
     * 枚举当前所有预加载条目（key -> Entry）。
     *
     * 用于冷启动 / 进入播放页时把磁盘上的条目暖到内存。读取失败的文件跳过，
     * 损坏内容由 Gson 解析失败自然回 null（与 [load] 同策略）。
     */
    fun listAll(): Map<String, Entry> = runCatching {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".tmp") }
            .mapNotNull { f ->
                val key = f.name.removePrefix("v1__").removeSuffix(".json")
                val entry = runCatching { gson.fromJson(f.readText(), Entry::class.java) }.getOrNull()
                if (entry != null) key to entry else null
            }.toMap()
    }.getOrDefault(emptyMap())

    /** 清空全部预加载（缓存管理页「清空」用） */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /** 当前预加载总占用（字节） */
    fun totalSize(): Long = runCatching {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** 当前预加载文件数（每文件一集） */
    fun count(): Int = dir.listFiles()?.count { it.isFile && it.name.endsWith(".json") } ?: 0

    private fun fileOf(key: String) = File(dir, "v1__${sanitize(key)}.json")

    /**
     * 路径安全化：把 `/` 等不能作为文件名的字符替换为 `_`，避免不同节目源之间
     * 因为包含分隔符的文件名撞到同一文件。
     */
    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(200)
}
