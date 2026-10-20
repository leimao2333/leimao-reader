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
}
