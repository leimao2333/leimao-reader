package com.novelreader.data.cache

import android.content.Context
import com.novelreader.core.pdf.PdfSource
import java.io.File

/**
 * 论文 PDF 缓存（v1.8.5.1）的统计/清理入口。
 *
 * 抽成接口的理由同 [ImageCacheStore]：真实实现要碰 `Context.filesDir`，
 * JVM 单测跑不了；注入假实现才能断言「刷新读到占用、清理后归零、
 * 提示带上释放的容量、一键清空也覆盖它」。
 */
interface PdfCacheStore {

    /** 当前磁盘占用（字节） */
    fun sizeBytes(): Long

    /** 清空并返回清理前的占用（字节） */
    fun clear(): Long
}

/**
 * 应用内 PDF 阅读器的磁盘缓存。
 *
 * 位置是 `filesDir/pdf_cache/` 而**不是** `cacheDir`：
 * 系统在磁盘吃紧时会清空 cacheDir，一篇论文刚读完、第二天再点又得重下几 MB。
 * 代价是要自己管上限，所以带一个按「最久未打开」淘汰的 [prune]。
 *
 * 文件名用 URL 的 SHA-256（见 [PdfSource.cacheKey]）：同一篇论文从不同入口
 * （阅读页 / 浏览器回跳）打开时 URL 一致，就能命中同一份缓存。
 */
class PdfFileCache(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : PdfCacheStore {

    private val dir = File(context.applicationContext.filesDir, "pdf_cache")

    /** 某篇论文的缓存文件（不保证存在） */
    fun fileFor(url: String): File = File(dir, PdfSource.cacheKey(url) + ".pdf")

    /** 是否已有可用缓存（存在且非空） */
    fun has(url: String): Boolean = fileFor(url).let { it.isFile && it.length() > 0L }

    override fun sizeBytes(): Long =
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    override fun clear(): Long {
        val before = sizeBytes()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return before
    }

    /**
     * 按「最久未打开」淘汰，直到总占用不超过上限。
     *
     * 注意用 `lastModified` 而不是创建时间：`File` 没有可靠的创建时间，
     * 而读取时会主动 `setLastModified` 刷新（见 [touch]），语义正好是 LRU。
     */
    fun prune(maxBytes: Long = this.maxBytes) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return@forEach
            val len = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= len
        }
    }

    /** 打开一次就刷新一次时间戳，让 LRU 淘汰认得「最近在读」的那几篇 */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /** 缓存文件所在目录（调试 / 备份用） */
    fun directory(): File = dir

    companion object {
        /**
         * 上限 300MB。
         *
         * 一篇期刊 PDF 通常 2~10MB，300MB 能留三四十篇最近读过的 ——
         * 超过这个量说明用户其实不会回头翻，淘汰掉不心疼。
         */
        const val DEFAULT_MAX_BYTES: Long = 300L * 1024 * 1024
    }
}
