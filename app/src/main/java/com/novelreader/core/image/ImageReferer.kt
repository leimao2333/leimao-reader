package com.novelreader.core.image

/**
 * 图片请求的 `Referer` 兜底口径（v1.9.2.7）。
 *
 * 大量漫画站的图床是**独立子域名 + 防盗链**：`images.example.com` / `img3.example.com`
 * 只接受 `Referer: https://example.com/`（站点自身页面），拿图片自身域名当 Referer
 * 会被安全策略直接拦掉 —— 表现为封面全空白、正文图片全部加载失败，而且**不会**出现
 * 任何 HTTP 错误码（拦的是 CDN 边缘节点，返回的是一张 HTML 拦截页）。
 *
 * 所以「源没声明 Referer」时的兜底不能是 `scheme://<图片 host>/`，而要先去掉常见的
 * 图床子域前缀，还原成站点根域。这里只剥**明确是图床/CDN 的前缀**，宁可漏剥也不错剥：
 * `images.` / `image.` / `img.` / `img<数字>.` / `cdn.` / `pic.` / `static.`
 * （`static.` 只在后面还有两级以上域名时才剥，避免把 `static.io` 这类站点根域剥没）。
 */
object ImageReferer {

    /** 明确属于图床 / CDN 的子域前缀 */
    private val CDN_PREFIXES = listOf("images.", "image.", "img.", "cdn.", "pic.")

    /** 带序号或需额外校验的前缀 */
    private val CDN_PREFIX_REGEX = Regex("""^img\d+\.""")
    private val STATIC_PREFIX = "static."

    /**
     * 由图片地址推出「站点根域」Referer。
     *
     * @return 形如 `https://example.com/`；地址不合法时返回 null（调用方保持不设 Referer）
     */
    fun of(url: String): String? {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        if (host.isBlank()) return null
        if (scheme != "http" && scheme != "https") return null
        return "$scheme://${siteRootOf(host)}/"
    }

    /**
     * 把图床域名还原成站点根域。
     *
     * 剥掉前缀后如果剩下的域名层级不足两级（例如 `img.cn`），就保持原样 —— 说明那个
     * 前缀其实是站点名的一部分。
     */
    fun siteRootOf(host: String): String {
        var h = host
        for (p in CDN_PREFIXES) {
            if (h.startsWith(p, ignoreCase = true)) {
                val rest = h.substring(p.length)
                if (rest.count { it == '.' } >= 1) h = rest
                break
            }
        }
        CDN_PREFIX_REGEX.find(h)?.let {
            val rest = h.substring(it.value.length)
            if (rest.count { it == '.' } >= 1) h = rest
        }
        if (h.startsWith(STATIC_PREFIX, ignoreCase = true)) {
            val rest = h.substring(STATIC_PREFIX.length)
            if (rest.count { it == '.' } >= 2) h = rest
        }
        return h
    }
}
