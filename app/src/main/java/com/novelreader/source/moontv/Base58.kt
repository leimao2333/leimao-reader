package com.novelreader.source.moontv

import java.math.BigInteger

/**
 * Base58 编解码（Bitcoin 字母表）。
 *
 * MoonTV / LunaTV 的订阅链接有两种形态：
 * - 原始 JSON（`*.json`）
 * - **Base58 编码的 JSON**（`*.txt`、`?format=2&source=xxx`）
 *
 * 后者正文是一串 Base58 字符（不含 `{` / `"` 等 JSON 符号），
 * 直接当 JSON / 书源解析必然失败——这是「MoonTV 视频源导入不了」的根因之一。
 */
object Base58 {

    /** Bitcoin Base58 字母表（去掉 0 O I l 这四个易混字符） */
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private val INDEX = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    /**
     * 是否「可能是 Base58 文本」。
     *
     * 判据：忽略空白后，全部字符都属于 Base58 字母表，且长度达到 [minLength]。
     * JSON / URL / legado 书源都含 `{` `"` `:` `/` 等表外字符，会被直接排除。
     */
    fun looksLikeBase58(text: String, minLength: Int = 64): Boolean {
        var count = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            if (c.code >= 128 || INDEX[c.code] < 0) return false
            count++
        }
        return count >= minLength
    }

    /**
     * 解码 Base58 文本为字节数组。
     *
     * @throws IllegalArgumentException 含非 Base58 字符或内容为空
     */
    fun decode(text: String): ByteArray {
        val s = text.filterNot { it.isWhitespace() }
        require(s.isNotEmpty()) { "Base58 内容为空" }

        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (c in s) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            require(v >= 0) { "非法 Base58 字符：$c" }
            num = num.multiply(base).add(BigInteger.valueOf(v.toLong()))
        }

        // 前导 '1' 对应前导 0x00 字节（BigInteger 会丢掉）
        val leadingZeros = s.takeWhile { it == '1' }.length
        var bytes = num.toByteArray()
        // BigInteger.toByteArray 在高位为 1 时会补一个符号位 0x00，需去掉
        if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        if (leadingZeros == 0) return bytes
        return ByteArray(leadingZeros) + bytes
    }

    /** 解码失败返回 null（不抛异常） */
    fun decodeOrNull(text: String): ByteArray? = runCatching { decode(text) }.getOrNull()

    /** 解码为字符串，失败返回 null */
    fun decodeToStringOrNull(text: String, charset: java.nio.charset.Charset = Charsets.UTF_8): String? =
        decodeOrNull(text)?.toString(charset)
}
