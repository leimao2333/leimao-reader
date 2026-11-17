package com.novelreader.core.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 响应体解码与乱码修复（v1.9.x）。
 *
 * 与 `HttpClient.decode` 的区别：那边只拿到 `Content-Type`，且为了省内存采用
 * 「嗅探 meta → UTF-8 → GBK」的固定顺序；这里给适配器提供**字节级**入口，
 * 用于「Content-Type 没写 charset、正文按 UTF-8 解出替换字符」的场景：
 * 严格 UTF-8 解不出来就按 GBK 再解一次，仍不行才带着替换字符返回。
 *
 * 纯函数、不依赖 Android。
 */
object TextDecoding {

    private val CHARSET_IN_HEADER = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
    private val CHARSET_IN_META = Regex("<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)

    /** GBK 解码失败时的兜底（GB18030 是 GBK 的超集） */
    private const val FALLBACK_CHARSET = "GBK"

    /** `<meta charset>` 必在 head 开头，字节级嗅探只需要看这一小段 */
    private const val META_SNIFF_BYTES = 4096

    /** 乱码特征字：UTF-8 被当 GBK 解出来的典型结果 */
    private val MOJIBAKE_MARKS = listOf("锟斤拷", "烫烫烫", "Ã", "Â", "â€", "ï¿½")

    /** 从 Content-Type 里取 charset；没有返回 null */
    fun charsetOf(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        return CHARSET_IN_HEADER.find(contentType)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * 按「Content-Type charset → HTML meta → 严格 UTF-8 → GBK」的顺序解码。
     *
     * @param bytes 响应体原始字节
     * @param contentType 响应头 Content-Type（可为 null）
     */
    fun detectAndDecode(bytes: ByteArray, contentType: String? = null): String {
        if (bytes.isEmpty()) return ""

        charsetOf(contentType)?.let { declared ->
            decodeStrict(bytes, declared)?.let { return it.stripBom() }
        }

        sniffMetaCharset(bytes)?.let { declared ->
            decodeStrict(bytes, declared)?.let { return it.stripBom() }
        }

        // 严格 UTF-8：能无损解出就是 UTF-8，不会把 GBK 正文误判成 UTF-8（反之亦然）
        decodeStrict(bytes, "UTF-8")?.let { return it.stripBom() }
        decodeStrict(bytes, FALLBACK_CHARSET)?.let { return it.stripBom() }

        // 两种都不合法：按 UTF-8 宽松解码（带替换字符），交给上层展示
        return String(bytes, Charsets.UTF_8).stripBom()
    }

    /**
     * 修复「已经解成字符串」的乱码。
     *
     * 典型的可逆乱码：UTF-8 字节被当成 ISO-8859-1 / GBK 解码（`ä½ å¥½`、`浣犲ソ`）。
     * 这里把字符串按「错误的字符集」重新编码回字节，再按 UTF-8 解一次。
     *
     * 只有**乱码特征明显变少**时才采纳，正常文本不会被改坏；
     * 已含 U+FFFD 的文本说明字节在解码时就被替换掉了，不可逆，原样返回。
     */
    fun repairGarbled(text: String): String {
        if (text.isEmpty() || text.contains('\uFFFD')) return text
        if (!looksGarbled(text)) return text

        var best = text
        var bestScore = mojibakeScore(text)
        listOf("ISO-8859-1", FALLBACK_CHARSET).forEach { from ->
            val candidate = reDecode(text, from) ?: return@forEach
            if (candidate.isEmpty() || candidate.contains('\uFFFD')) return@forEach
            val score = mojibakeScore(candidate)
            if (score < bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /**
     * 字节级嗅探 HTML `meta` 声明的字符集。
     * 用 ISO-8859-1 解读 ASCII 标签（单字节 → 单字符），不受正文编码影响。
     */
    private fun sniffMetaCharset(bytes: ByteArray): String? {
        val n = minOf(bytes.size, META_SNIFF_BYTES)
        if (n <= 0) return null
        val head = String(bytes, 0, n, Charsets.ISO_8859_1)
        return CHARSET_IN_META.find(head)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** 按指定字符集**严格**解码；遇到非法字节返回 null */
    private fun decodeStrict(bytes: ByteArray, charsetName: String): String? {
        val charset = runCatching { Charset.forName(charsetName) }.getOrNull() ?: return null
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** 把字符串按 `from` 重新编码成字节、再按 UTF-8 解码 */
    private fun reDecode(text: String, from: String): String? {
        val charset = runCatching { Charset.forName(from) }.getOrNull() ?: return null
        return runCatching { String(text.toByteArray(charset), Charsets.UTF_8) }.getOrNull()
    }

    /** 去掉 UTF-8 BOM（否则 JSON 解析 / 选择器匹配都会失败） */
    private fun String.stripBom(): String =
        if (startsWith('\uFEFF')) substring(1) else this

    /** 粗判是否像乱码：替换字符或常见乱码特征字 */
    private fun looksGarbled(text: String): Boolean {
        if (text.contains('\uFFFD')) return true
        if (MOJIBAKE_MARKS.any { text.contains(it) }) return true
        // 大量拉丁补充字符（ä / ½ / å）与正文混排，基本可以确定是 UTF-8 被当单字节读
        val latin = text.count { it.code in 0x80..0x24F }
        return latin >= 3 && latin * 10 >= text.length
    }

    /** 乱码程度：越小越像是正常文本 */
    private fun mojibakeScore(text: String): Int {
        var score = text.count { it == '\uFFFD' } * 10
        MOJIBAKE_MARKS.forEach { mark -> if (text.contains(mark)) score += 10 }
        score += text.count { it.code in 0x80..0x24F } / 4
        return score
    }
}
