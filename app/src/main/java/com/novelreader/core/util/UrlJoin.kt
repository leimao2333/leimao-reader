package com.novelreader.core.util

import java.net.URI

/**
 * URL 补全工具（v1.9.x）。
 *
 * 书源规则里的封面 / 图片地址常见三种写法，直接用 `java.net.URL(base, raw)` 处理
 * 会踩坑：
 * - 协议相对：`//img.example.com/c.jpg`（`URL` 会按 base 的协议补，但 base 为空就炸）；
 * - 根相对：`/cover/1.jpg`（应拼到 host 根，而不是 base 自带路径的目录）；
 * - 普通相对：`cover/1.jpg` / `../a.jpg`（应按 base 的目录解析）。
 *
 * 另外源包里的值常带换行、前后空格、`undefined` 之类的脏尾巴，这里统一清掉。
 * 纯函数、不依赖 Android，便于单测。
 */
object UrlJoin {

    /** 无需补全、原样保留的协议（本地文件 / data URL / 流媒体） */
    private val PASS_THROUGH_PREFIXES = listOf(
        "data:", "content:", "file:", "blob:", "android.resource:", "asset:",
        "rtmp:", "rtsp:", "magnet:",
    )

    /** 只有主机名（可带端口 / 路径）的裸地址 */
    private val BARE_HOST = Regex("""^[A-Za-z0-9\-._]+(:\d+)?(/.*)?$""")

    /** 控制字符 */
    private val CONTROL_CHARS = Regex("""[\u0000-\u001F\u007F]""")

    /**
     * 把 [raw] 补全为绝对地址。
     *
     * @param base 站点基址（如 `https://example.com`，可带路径）
     * @param raw 规则里取到的原始地址（绝对 / 协议相对 / 根相对 / 相对）
     * @return 绝对地址；无法补全（空值、`javascript:`、纯锚点等）返回 null
     */
    fun normalizeUrl(base: String, raw: String?): String? {
        val value = clean(raw) ?: return null
        val lower = value.lowercase()

        // JS 伪协议 / 纯锚点：对图片与章节地址没有意义
        if (lower.startsWith("javascript:") || lower.startsWith("about:")) return null
        if (value.startsWith("#")) return null

        // 已经是完整地址 / 本地资源
        if (lower.startsWith("http://") || lower.startsWith("https://")) return value
        if (PASS_THROUGH_PREFIXES.any { lower.startsWith(it) }) return value

        val site = clean(base).orEmpty()
        val baseScheme = when {
            site.startsWith("https://", ignoreCase = true) -> "https:"
            site.startsWith("http://", ignoreCase = true) -> "http:"
            else -> null
        }

        // 协议相对：//host/path → 借用 base 的协议
        if (value.startsWith("//")) return "${baseScheme ?: "https:"}$value"

        // base 缺失：只有裸域名才敢补 https，其余宁可返回 null（不凭空造站点）
        if (site.isEmpty()) {
            return if (BARE_HOST.matches(value) && value.contains('.')) "https://$value" else null
        }

        val normalizedBase = if (baseScheme == null && BARE_HOST.matches(site)) {
            "https://${site.trimEnd('/')}"
        } else {
            site
        }

        // 根相对：/cover/1.jpg → 拼到 host 根（丢掉 base 自带路径）
        if (value.startsWith("/")) {
            val origin = originOf(normalizedBase)
            return if (origin != null) "$origin$value" else value
        }

        // 查询串 / 片段单独出现时无法定位资源
        if (value.startsWith("?") || value.startsWith("#")) return null

        return resolve(normalizedBase, value)
    }

    /** 取 `scheme://host[:port]`；解析失败返回 null */
    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme
        val authority = uri.rawAuthority
        if (!scheme.isNullOrEmpty() && !authority.isNullOrEmpty()) "$scheme://$authority" else null
    }.getOrNull()

    /** 按 RFC 3986 解析相对地址；失败时退化为「目录 + 相对路径」（保留中文等字符） */
    private fun resolve(base: String, relative: String): String? {
        runCatching { URI(base).resolve(relative).toString() }
            .getOrNull()
            ?.let { return it }
        // 含空格 / 中文等非法字符时先做最小转义再试一次
        val escaped = relative.replace(" ", "%20")
        runCatching { URI(base).resolve(escaped).toString() }
            .getOrNull()
            ?.let { return it }
        // URI 不接受中文路径：退化为字符串拼接（与原 java.net.URL 行为一致）
        if (!base.contains("://")) return null
        val dir = if (base.endsWith("/")) base else base.substringBeforeLast('/', base) + "/"
        return dir + escaped
    }

    /** 清洗原始值：取首个非空行、去控制字符与 `undefined` 脏尾巴 */
    private fun clean(raw: String?): String? {
        val firstLine = raw?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null
        val value = CONTROL_CHARS.replace(firstLine, "").trim()
        if (value.isEmpty()) return null
        if (value.equals("undefined", true) || value.equals("null", true)) return null
        return value
    }
}
