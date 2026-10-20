package com.novelreader.core.media

import java.net.URI

/**
 * HLS 播放列表的最小解析（v1.8.4.3）。
 *
 * 只做「尾段预缓冲下一集」需要的那点事：从 m3u8 里挑出**前几个分片地址**，
 * 提前下到磁盘缓存里，用户点「下一集」时播放器直接从缓存读，省掉起播等待。
 *
 * 为什么不能只按字节截取：采集站的视频大多是 HLS，`.m3u8` 本身只有几百字节
 * （分片清单），把清单缓存下来对起播毫无帮助 —— 真正要下的是清单里列的分片。
 *
 * 刻意不追求完整实现（不处理 `EXT-X-BYTERANGE` / 加密 `EXT-X-KEY` / 多码率择优）：
 * 预缓冲是**纯增强**，看不懂的清单直接返回空，播放器照常自己拉。
 */
object HlsPlaylist {

    /** 是否 HLS 播放列表（以 `#EXTM3U` 开头） */
    fun isPlaylist(text: String): Boolean = text.trimStart().startsWith("#EXTM3U")

    /** 是否主清单（含多码率变体，本身不含分片） */
    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    /**
     * 初始化分片地址（`#EXT-X-MAP:URI="init.mp4"`）。
     *
     * fMP4 的流没有它解不出来，所以预缓冲要把这条也拉下来。
     */
    fun initSegment(text: String, baseUrl: String): String? {
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (!t.startsWith("#EXT-X-MAP", ignoreCase = true)) return@forEach
            val uri = attribute(t, "URI") ?: return@forEach
            return resolve(baseUrl, uri)
        }
        return null
    }

    /**
     * 清单里的资源地址（主清单 → 变体清单地址；媒体清单 → 分片地址）。
     *
     * 规则很简单：跳过 `#` 开头的标签行与空行，剩下的每行都是一个地址；
     * 相对地址按 [baseUrl] 补全。最多返回 [limit] 个。
     */
    fun uris(text: String, baseUrl: String, limit: Int = Int.MAX_VALUE): List<String> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<String>(minOf(limit, 32))
        text.lineSequence().forEach { line ->
            if (out.size >= limit) return out
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val abs = resolve(baseUrl, t) ?: return@forEach
            out += abs
        }
        return out
    }

    /** 取标签里 `KEY="value"` 形式的属性值（属性顺序不固定，不能按位置切） */
    private fun attribute(line: String, key: String): String? {
        val marker = "$key=\""
        val start = line.indexOf(marker, ignoreCase = true)
        if (start < 0) return null
        val from = start + marker.length
        val end = line.indexOf('"', from)
        if (end <= from) return null
        return line.substring(from, end).trim().ifEmpty { null }
    }

    /**
     * 相对地址补全。
     *
     * `baseUrl` 为空 / 不合法、或补全结果不是 http(s) 时返回 null ——
     * 宁可不下，也不要拿一个假地址去请求（预缓冲失败无所谓，下错地址是纯浪费）。
     */
    private fun resolve(baseUrl: String, uri: String): String? {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (baseUrl.isBlank()) return null
        val abs = runCatching { URI(baseUrl).resolve(uri).toString() }.getOrNull() ?: return null
        return abs.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
