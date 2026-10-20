package com.novelreader.core.pdf

import java.io.File
import java.security.MessageDigest

/**
 * PDF 来源识别与缓存键（v1.8.5.1）。
 *
 * 应用内读 PDF 的第一步不是渲染，而是**确认拿到的真是 PDF**。
 * 学术出版方的 PDF 地址在没有机构订阅 / 被反爬拦下时会返回 **200 + 一张网页**
 * （Cloudflare 挑战页、登录页、"Subscribe to access"），下载器看不出区别，
 * 交给 PdfRenderer 只会抛一句「文件损坏」—— 用户以为 App 坏了，其实是出版方在拦。
 *
 * 所以这里做三件事，全部是纯函数（可离线单测）：
 * - [cacheKey]：URL → 稳定文件名（同一篇论文重复打开命中同一份缓存）
 * - [looksLikePdf]：按魔数判断
 * - [rejectReason]：不是 PDF 时给出**人话原因**
 */
object PdfSource {

    /** PDF 文件头。规范要求 `%PDF-` 出现在前 1024 字节内（前面可能被塞 BOM / 空白） */
    private const val MAGIC = "%PDF-"

    /** 嗅探窗口：只看开头 4KB，够认魔数也够认出挑战页的标题 */
    const val SNIFF_BYTES = 4096

    /**
     * 缓存键：URL 的 SHA-256 前 16 字节（32 位十六进制）。
     *
     * 不用 `hashCode()`：那是 32 位且有碰撞，两份不同的 PDF 撞到同一个文件名时
     * 用户会看到**别人的论文**，比缓存失效糟糕得多。
     */
    fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.take(16).forEach { b ->
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    /** 字节是否以 PDF 魔数开头（允许前 1KB 内有 BOM / 空白） */
    fun looksLikePdf(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val end = minOf(bytes.size, 1024)
        return String(bytes, 0, end, Charsets.ISO_8859_1).contains(MAGIC)
    }

    /**
     * 不是 PDF 时返回**给用户看的原因**，是 PDF 时返回空串。
     *
     * 只认几种最常见的拦截形态：Cloudflare 挑战页、登录 / 机构订阅页、
     * 一般网页、接口 JSON。认不出来也要说清「不是 PDF」，别让渲染器去背锅。
     */
    fun rejectReason(bytes: ByteArray): String {
        if (looksLikePdf(bytes)) return ""
        if (bytes.isEmpty()) return "下载到的文件是空的（0 字节）"

        val head = String(
            bytes,
            0,
            minOf(bytes.size, SNIFF_BYTES),
            Charsets.UTF_8,
        ).lowercase()

        val isHtml = head.contains("<html") || head.contains("<!doctype") ||
            head.contains("<head") || head.contains("<body")
        if (isHtml) {
            return when {
                CLOUDFLARE_MARKERS.any { head.contains(it) } ->
                    "出版方返回的是人机验证页（Cloudflare），不是 PDF —— 请用浏览器打开"
                LOGIN_MARKERS.any { head.contains(it) } ->
                    "需要登录或机构订阅才能下载这篇 PDF —— 请用浏览器打开"
                else ->
                    "出版方返回的是网页而不是 PDF（多半需要机构订阅）—— 请用浏览器打开"
            }
        }
        if (head.trimStart().startsWith("{") || head.trimStart().startsWith("[")) {
            return "出版方返回的是接口数据，不是 PDF"
        }
        return "下载到的不是 PDF 文件"
    }

    /** 读文件开头若干字节（读不到时返回空数组，交给 [rejectReason] 报「空文件」） */
    fun head(file: File, maxBytes: Int = SNIFF_BYTES): ByteArray {
        if (!file.isFile || file.length() <= 0L) return ByteArray(0)
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(minOf(maxBytes.toLong(), file.length()).toInt())
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
    }

    private const val HEX = "0123456789abcdef"

    /** Cloudflare 挑战页的典型字样（大小写不敏感，调用前已 lowercase） */
    private val CLOUDFLARE_MARKERS = listOf(
        "just a moment",
        "cf-browser-verification",
        "challenge-platform",
        "attention required",
        "enable javascript and cookies to continue",
    )

    /** 登录墙 / 付费墙的典型字样 */
    private val LOGIN_MARKERS = listOf(
        "sign in",
        "signin",
        "log in",
        "login",
        "institutional",
        "access denied",
        "subscribe to access",
        "purchase access",
        "get access",
    )
}
