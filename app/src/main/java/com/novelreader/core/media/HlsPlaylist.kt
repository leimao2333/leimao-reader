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

    /**
     * 取标签里 `KEY=value` 形式的属性值（属性顺序不固定，不能按位置切）。
     *
     * 两种写法都要认：
     * - `URI="key.bin"` —— 地址类属性一律带引号；
     * - `BANDWIDTH=800000` / `METHOD=AES-128` —— 数值与枚举**不带引号**。
     *
     * 早先只实现了带引号那种，结果挑码率变体时 BANDWIDTH 一律取不到、
     * 加密方式一律判成「没写」，两个功能同时静默失效（单测里一眼可见）。
     */
    private fun attribute(line: String, key: String): String? {
        var searchFrom = 0
        while (true) {
            val found = line.indexOf(key, searchFrom, ignoreCase = true)
            if (found < 0) return null
            // 左边必须是分隔符，否则 `BANDWIDTH` 会命中 `AVERAGE-BANDWIDTH` 的后半段
            val leftOk = found == 0 ||
                line[found - 1].let { it == ',' || it == ':' || it.isWhitespace() }
            var i = found + key.length
            while (i < line.length && line[i].isWhitespace()) i++
            if (leftOk && i < line.length && line[i] == '=') {
                i++
                while (i < line.length && line[i].isWhitespace()) i++
                if (i < line.length && line[i] == '"') {
                    val end = line.indexOf('"', i + 1)
                    if (end < 0) return null
                    return line.substring(i + 1, end).trim().ifEmpty { null }
                }
                val end = line.indexOf(',', i).let { if (it < 0) line.length else it }
                return line.substring(i, end).trim().ifEmpty { null }
            }
            searchFrom = found + key.length
        }
    }

    // ===== AI 字幕用（v1.8.7.4）=====
    //
    // 预缓冲只需要「前几个分片」，所以上面那套够用。但要把整集音轨拿下来做离线识别，
    // 就得挑对变体、认得出加密、算得出时长 —— 下面这几个是为此补的。

    /**
     * 主清单里**码率最低**的变体地址。
     *
     * 字幕只要音轨，画质高低对识别没有任何帮助，但文件体积差好几倍：
     * 同一集 1080p 变体可能 500 MB，360p 只有几十 MB。挑最低码率是纯粹的省钱省时间。
     */
    fun lowestBandwidthVariant(text: String, baseUrl: String): String? {
        var bestBandwidth = Long.MAX_VALUE
        var best: String? = null
        var pendingBandwidth: Long? = null
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                pendingBandwidth = attribute(t, "BANDWIDTH")?.toLongOrNull()
                return@forEach
            }
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val bw = pendingBandwidth
            pendingBandwidth = null
            // 没写 BANDWIDTH 的变体按「未知」处理，只有它是唯一选择时才用
            if (bw == null) {
                if (best == null) best = resolve(baseUrl, t)
                return@forEach
            }
            if (bw < bestBandwidth) {
                bestBandwidth = bw
                best = resolve(baseUrl, t)
            }
        }
        return best
    }

    /** 加密信息（`#EXT-X-KEY`） */
    data class KeyInfo(
        /** `NONE` 表示不加密 */
        val method: String,
        /** 取密钥的地址（已补全）；`NONE` 时为 null */
        val uri: String?,
        /** 显式 IV（十六进制，不含 `0x`）；为 null 时按媒体序号推导 */
        val ivHex: String?,
    )

    /**
     * 媒体清单的加密信息。
     *
     * 只取第一条 `#EXT-X-KEY`：VOD 场景下密钥一般写在清单开头、整集共用一个。
     * 中途换密钥（`EXT-X-KEY` 出现在分片之间）属于直播/防盗链的高级用法，这里不处理 ——
     * 遇到就按「解不开」失败，而不是解出一堆噪声喂给识别模型。
     */
    fun keyInfo(text: String, baseUrl: String): KeyInfo? {
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (!t.startsWith("#EXT-X-KEY", ignoreCase = true)) return@forEach
            val method = attribute(t, "METHOD")?.uppercase() ?: return@forEach
            if (method == "NONE") return KeyInfo("NONE", null, null)
            val uri = attribute(t, "URI")?.let { resolve(baseUrl, it) }
            val iv = attribute(t, "IV")?.removePrefix("0x")?.removePrefix("0X")
            return KeyInfo(method, uri, iv)
        }
        return null
    }

    /** 媒体序号（`#EXT-X-MEDIA-SEQUENCE`，缺省 0）—— 没显式 IV 时用它推导 */
    fun mediaSequence(text: String): Long =
        text.lineSequence().firstOrNull { it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE", true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.toLongOrNull()
            ?: 0L

    /** 清单声明的总时长（毫秒），各 `#EXTINF` 之和；一条都没有时返回 0 */
    fun totalDurationMs(text: String): Long {
        var seconds = 0.0
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (!t.startsWith("#EXTINF:", ignoreCase = true)) return@forEach
            // 形如 `#EXTINF:9.009,` 或 `#EXTINF:9.009,title`
            val value = t.substringAfter(':').substringBefore(',').trim()
            seconds += value.toDoubleOrNull() ?: 0.0
        }
        return (seconds * 1000).toLong()
    }

    /** 是否 HLS 地址（按扩展名粗判；真正的判定还要看响应内容是否 `#EXTM3U`） */
    fun looksLikeHls(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
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
