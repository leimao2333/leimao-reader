package com.novelreader.core.util

/**
 * 封面 URL 清洗（v1.8.3.0）。
 *
 * 背景：JS / legado 类书源的封面规则常常取不到值，引擎把 JS 的 `undefined`
 * **当成普通对象**再拼进 `baseUrl`，得到一串看似合法、实际永远 404 的地址：
 *
 * ```
 * https://ubook.reader.qq.com/org.mozilla.javascript.Undefined@dc3369d
 * ```
 *
 * 危害不只是「图裂」：首页补封面逻辑（`HomeViewModel.backfillFeedCovers`）用
 * `cover.isNullOrBlank()` 判断要不要去详情页捞封面，这条脏串非空，于是整排
 * 企鹅阅读的书**永远补不上封面**，用户看到的就是第一排清一色色块。
 *
 * 所以脏值必须在**入队之前**就清成 null，让补封面逻辑能正常接手。
 */
object CoverUrl {

    /** JS 引擎把 undefined 当对象 toString 出来的痕迹（Rhino / Nashorn / QuickJS 各有写法） */
    private val JS_UNDEFINED_MARKS = listOf(
        "org.mozilla.javascript.Undefined",
        "org.mozilla.javascript.UniqueTag",
    )

    /** 纯字符串化的空值（小写比较） */
    private val EMPTY_LITERALS = setOf(
        "undefined", "null", "nan", "[object object]", "null null",
    )

    /** 允许的图片地址协议；其余（相对路径、垃圾串）一律视为无效 */
    private val ALLOWED_SCHEMES = listOf(
        "http://", "https://", "file://", "content://", "data:image/",
    )

    /**
     * 清洗封面地址：无效返回 null，有效返回 trim 后的原地址。
     *
     * 判定顺序：空 → JS undefined 痕迹 → 纯空值字面量 → 非 http(s)/file 协议。
     */
    fun sanitize(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        val lower = url.lowercase()
        if (EMPTY_LITERALS.contains(lower)) return null
        if (JS_UNDEFINED_MARKS.any { url.contains(it) }) return null
        if (ALLOWED_SCHEMES.none { lower.startsWith(it) }) return null
        // 「http://」后面什么都没有也是脏值
        if (lower == "http://" || lower == "https://") return null
        return url
    }

    /** 是否是可用的封面地址（[sanitize] 的非空简写） */
    fun isValid(raw: String?): Boolean = sanitize(raw) != null

    /**
     * 带站点基址的封面解析（v1.9.6.1）。
     *
     * 面向 **Venera 源**：上游 `Comic.cover` 有时给的是相对路径（`/cover/1.jpg`）
     * 或协议相对地址（`//img.example.com/c.jpg`），而 [sanitize] 只认带协议的地址，
     * 会把它们一律清成 null → 封面整排退化成占位块。Venera 官方客户端会在框架层
     * 用源站点补全过程，本应用此前没有，这里补上。
     *
     * @param raw 源返回的封面值
     * @param base 源站点基址（形如 `https://example.com`，由源源码的 api/url 提取）
     * @return 可加载的绝对地址；补不出来仍返回 null（交给上层 backfill / 占位）
     */
    fun resolve(raw: String?, base: String?): String? {
        sanitize(raw)?.let { return it }
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || base.isNullOrBlank()) return null
        // 关键：脏值字面量必须先拦掉，否则会被拼成 `https://site/undefined`
        // 这种「非空但永远 404」的假地址——正是 sanitize 一直防的那类问题
        // （回归用例见 CoverUrlTest）。
        val lower = value.lowercase()
        if (EMPTY_LITERALS.contains(lower)) return null
        if (JS_UNDEFINED_MARKS.any { value.contains(it) }) return null
        val site = base.trim().trimEnd('/')
        // 协议相对：//host/path → 借用基址协议
        if (value.startsWith("//")) {
            val scheme = site.substringBefore("://", "")
            if (scheme.isNotEmpty()) return sanitize("$scheme:$value")
        }
        // 根相对：/path → 拼到基址的「协议 + 主机」根（丢掉 base 自带的路径）
        if (value.startsWith("/")) {
            val root = runCatching { java.net.URI(site) }.getOrNull()?.let { u ->
                val scheme = u.scheme
                val authority = u.rawAuthority
                if (!scheme.isNullOrEmpty() && !authority.isNullOrEmpty()) "$scheme://$authority" else null
            } ?: site
            return sanitize("$root$value")
        }
        // 普通相对路径：cover/1.jpg → 拼到基址目录后
        return sanitize("$site/$value")
    }
}
