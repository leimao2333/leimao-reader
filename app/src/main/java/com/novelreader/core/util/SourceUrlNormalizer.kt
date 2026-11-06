package com.novelreader.core.util

/**
 * 源基础地址规范化（v1.9.3.0）。
 *
 * 聚合源包里 `bookSourceUrl` 常常被人手工改过，末尾 / 开头挂着非 URL 的标记：
 *
 * ```
 * [图片]http://www.example.com
 * http://www.example.com已校验
 * book.example.com已整理
 * https://book.example.com##发现
 * http://s.example.com#🎃
 * ```
 *
 * 原实现只裁掉 `#` 之后的片段。剩下几种里，`java.net.URI` 要么解析不出 host，
 * 要么把中文并进 host —— 于是 `{{baseUrl}}/path` 模板、目录 / 正文的相对地址、
 * 源级请求头按 host 注册（防盗链）全部失效，表现就是「这个源搜得到但点进去没内容」。
 *
 * 纯函数、不依赖 Android，便于单测。规范化的目标是**尽量保住原地址**，只在
 * 明确不合法时裁掉噪声；解析不出合法域名时返回空串，绝不凭空造一个站点。
 */
object SourceUrlNormalizer {

    /** URL 合法字符集（RFC 3986 主体，够覆盖源包里见到的写法） */
    private val URL_BODY = Regex("""^[A-Za-z0-9\-._~:/?\[\]@!$&'()*+,;=%]+""")

    /** 无协议时，只接受「看起来像域名」的连续片段，避免把一句中文说明当 URL */
    private val BARE_HOST = Regex("""^[A-Za-z0-9\-._]+(:\d+)?(/[A-Za-z0-9\-._~:/?\[\]@!$&'()*+,;=%]*)?$""")

    /**
     * 归一化源地址：
     * 1. 去掉 `#` 及之后的备注（legado 约定，同原实现）；
     * 2. 从第一个 `http(s)://` 开始截取，丢掉前面的「[图片]」等标记；
     * 3. 截断到第一个非 URL 字符（中文「已校验 / 已整理」等后缀）；
     * 4. 没有协议时补 `https://`；
     * 5. 校验 host 合法（含点、纯 ASCII），去掉结尾斜杠。
     */
    fun normalize(raw: String): String {
        var u = raw.trim().substringBefore('#').trim()
        if (u.isEmpty()) return ""

        val lower = u.lowercase()
        val httpsIdx = lower.indexOf("https://")
        val httpIdx = lower.indexOf("http://")
        val schemeIdx = when {
            httpsIdx >= 0 -> httpsIdx
            httpIdx >= 0 -> httpIdx
            else -> -1
        }
        if (schemeIdx > 0) u = u.substring(schemeIdx)
        u = URL_BODY.find(u)?.value ?: return ""

        if (schemeIdx < 0) {
            if (!BARE_HOST.matches(u)) return ""
            u = "https://$u"
        }
        val host = runCatching { java.net.URI(u).host }.getOrNull()
        if (host.isNullOrBlank() || !host.contains('.') || host.any { it.code > 127 }) return ""
        return u.trimEnd('/')
    }

    /** 取根域（用于同源站点判定 / 按站点归类）；解析不出来时返回空串 */
    fun rootDomain(raw: String): String {
        val host = runCatching { java.net.URI(normalize(raw)).host }.getOrNull()
            ?.lowercase()?.removePrefix("www.") ?: return ""
        val parts = host.split('.')
        if (parts.size < 2) return ""
        // 常见的二级公共后缀（com.cn / co.jp 等）保留三级，否则取最后两级
        val twoLevel = setOf("com", "net", "org", "gov", "edu", "co", "io")
        return if (parts.size >= 3 && parts[parts.size - 2] in twoLevel && parts[parts.size - 1].length == 2) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }
}
