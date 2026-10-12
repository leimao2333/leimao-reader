package com.novelreader.core.util

/**
 * 日志脱敏。
 *
 * 应用日志里不应出现**具体的源、站点地址、设备型号** —— 日志一旦被截图或粘贴到外部
 * （issue、聊天），就等于把「用户在看哪个源、哪个站点」一起带了出去。这里把可以
 * 用规则识别的部分统一折叠：
 *
 * - 完整 URL（含协议、域名、路径、查询串）→ `<url>`
 * - 裸 IPv4（可带端口）→ `<ip>`
 * - 邮箱 → `<mail>`
 *
 * 源自己打出的日志（JS 里的 `log()` / `toast()`）内容完全不可控，往往直接带站点名，
 * 因此那些转发点**不再输出内容**，只记录长度（见各 JS 桥的 log 实现）。
 */
object LogSanitizer {

    private val URL_PATTERN =
        Regex("""(https?|wss?)://[^\s"'<>()（）【】,，、;；]+""", RegexOption.IGNORE_CASE)

    private val IPV4_PATTERN = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")

    private val EMAIL_PATTERN = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    /** 折叠掉文本里的地址类信息；空文本返回空串 */
    fun sanitize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var out = URL_PATTERN.replace(text, "<url>")
        out = EMAIL_PATTERN.replace(out, "<mail>")
        out = IPV4_PATTERN.replace(out, "<ip>")
        return out
    }

    /** 只保留文本长度，用于完全不可控的外部文本（源日志等） */
    fun lengthOnly(text: String?): String = "${text?.length ?: 0} chars"
}
