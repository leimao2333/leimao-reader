package com.novelreader.core.rss

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 一个期刊（Crossref `/journals` 检索结果）。
 */
data class JournalRef(
    /** 首选 ISSN（print 优先，取不到用 online） */
    val issn: String,
    /** 全部 ISSN（含电子版） */
    val issns: List<String> = emptyList(),
    val title: String,
    val publisher: String = "",
    /** Crossref 里该刊收录的 DOI 总数（用于把「正牌刊」排在野鸡刊前面） */
    val totalDois: Int = 0,
)

/**
 * 一篇论文（Crossref `/works` 记录）。
 */
data class WorkRef(
    val doi: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val journal: String = "",
    val volume: String = "",
    val issue: String = "",
    val firstPage: String = "",
    val lastPage: String = "",
    val year: Int = 0,
    /** Crossref type（journal-article / posted-content …） */
    val type: String = "",
    /** 摘要（JATS XML，已转成可读 HTML；很多出版社不提供，为空） */
    val abstractHtml: String = "",
    val url: String = "",
    val publishedMs: Long = 0L,
    val publisher: String = "",
) {
    /** 转成题录（阅读页题录卡 / 引用导出直接吃这个） */
    fun toPaperMeta(): PaperMeta = PaperMeta(
        doi = doi,
        journal = journal,
        volume = volume,
        issue = issue,
        firstPage = firstPage,
        lastPage = lastPage,
        year = year,
        articleType = type,
        authors = authors,
        publisher = publisher,
    )
}

/**
 * Crossref REST API 的响应解析（纯函数，v1.8.5.0）。
 *
 * 为什么单独抽出来：网络层没法单测，JSON 结构却是最容易踩坑的地方
 * （`title` 是数组、`author` 缺 `family`、`published` 可能只有 `date-parts`
 * 甚至只有 `published-online`）。这里用**真实抓下来的响应**做样例单测。
 *
 * API 文档：<https://api.crossref.org/swagger-ui/index.html>
 */
object CrossrefJson {

    /**
     * 解析 `/journals?query=` 响应。
     *
     * 只保留有 ISSN 的条目 —— 没有 ISSN 就没法按刊拉论文，订阅了也是空源。
     */
    fun parseJournals(json: String): List<JournalRef> {
        val root = parseObject(json) ?: return emptyList()
        val items = root.obj("message")?.arr("items") ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val issns = o.arr("ISSN")?.mapNotNull { it.asStringOrNull() }?.filter { it.isNotBlank() }
                ?: emptyList()
            if (issns.isEmpty()) return@mapNotNull null
            val title = o.string("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            JournalRef(
                issn = issns.first(),
                issns = issns,
                title = title,
                publisher = o.string("publisher"),
                totalDois = o.obj("counts")?.int("total-dois") ?: 0,
            )
        }
    }

    /**
     * 解析 `/works/{doi}` 单篇响应（`message` 是对象而不是列表）。
     */
    fun parseWork(json: String): WorkRef? {
        val root = parseObject(json) ?: return null
        val o = root.obj("message") ?: return null
        return toWork(o)
    }

    /**
     * 解析 `/journals/{issn}/works` 或 `/works?filter=doi:` 响应。
     */
    fun parseWorks(json: String): List<WorkRef> {
        val root = parseObject(json) ?: return emptyList()
        val items = root.obj("message")?.arr("items") ?: return emptyList()
        return items.mapNotNull { el -> (el as? JsonObject)?.let { toWork(it) } }
    }

    /** 单条 Crossref work → [WorkRef]；没有标题的返回 null */
    private fun toWork(o: JsonObject): WorkRef? {
        val doi = PaperMetaParser.normalizeDoi(o.string("DOI"))
        val title = o.arr("title")?.firstOrNull()?.asStringOrNull()?.trim().orEmpty()
        // 没有标题的记录（部分数据集 / 预印本）在列表里没有意义
        if (title.isEmpty()) return null

        val (first, last) = splitPages(o.string("page"))
        val dateMs = dateOf(o)
        return WorkRef(
            doi = doi,
            title = title,
            authors = parseAuthors(o.arr("author")),
            journal = o.arr("container-title")?.firstOrNull()?.asStringOrNull().orEmpty(),
            volume = o.string("volume"),
            issue = o.string("issue"),
            firstPage = first,
            lastPage = last,
            year = yearOf(o, dateMs),
            type = o.string("type"),
            abstractHtml = jatsToHtml(o.string("abstract")),
            url = o.string("URL").ifBlank { if (doi.isNotBlank()) "https://doi.org/$doi" else "" },
            publishedMs = dateMs,
            publisher = o.string("publisher"),
        )
    }

    /**
     * JATS 摘要 → 可读 HTML。
     *
     * Crossref 的 `abstract` 是 JATS XML（`<jats:p>` / `<jats:italic>` / `<jats:sub>`），
     * 直接丢进 WebView 会显示成一堆标签；只保留段落与行内强调，其余标签剥掉。
     *
     * 开标签与闭标签必须**分开替换**：`</?jats:italic>` 这种带 `?` 的写法会把
     * `</jats:italic>` 也替成 `<i>`，得到 `<i>in vivo<i>` —— 浏览器看到的是
     * 嵌套的同名开标签，斜体永远不结束，后面整段都跟着歪。
     */
    fun jatsToHtml(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        s = s.replace(Regex("(?is)<jats:title[^>]*>.*?</jats:title>"), "")
        // 行内标签：开 → 开、闭 → 闭，一一对应
        listOf("italic" to "i", "i" to "i", "bold" to "b", "b" to "b", "sub" to "sub", "inf" to "sub", "sup" to "sup")
            .forEach { (jats, html) ->
                s = s.replace(Regex("(?i)<jats:$jats(\\s[^>]*)?>"), "<$html>")
                s = s.replace(Regex("(?i)</jats:$jats(\\s[^>]*)?>"), "</$html>")
            }
        s = s.replace(Regex("(?i)<jats:p(\\s[^>]*)?>"), "<p>")
        s = s.replace(Regex("(?i)</jats:p(\\s[^>]*)?>"), "</p>")
        s = s.replace(Regex("(?i)</?jats:sec(\\s[^>]*)?>"), "")
        // 其余 jats:* 标签一律剥掉（保留内容）
        s = s.replace(Regex("(?i)</?jats:[a-z0-9-]+(\\s[^>]*)?>"), "")
        // 裸露的 <p> 之外没有段落的摘要，补一层
        if (!s.contains("<p")) s = "<p>$s</p>"
        return s.trim()
    }

    // ==================== 内部工具 ====================

    private fun parseObject(json: String): JsonObject? =
        runCatching { JsonParser.parseString(json) as? JsonObject }.getOrNull()

    /** `[{given, family}]` → `["San Zhang"]`（family + given 的顺序，引用导出时再反转） */
    private fun parseAuthors(arr: JsonArray?): List<String> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val family = o.string("family").trim()
            val given = o.string("given").trim()
            when {
                family.isNotBlank() && given.isNotBlank() -> "$given $family"
                family.isNotBlank() -> family
                given.isNotBlank() -> given
                else -> o.string("name").trim().takeIf { it.isNotEmpty() }
            }
        }.distinct()
    }

    /** 页码 `"1234-1256"` / `"e100"` / `"1094-1094"` */
    private fun splitPages(raw: String): Pair<String, String> {
        val s = raw.trim()
        if (s.isEmpty()) return "" to ""
        val normalized = s.replace(Regex("""\s*[-–—]\s*"""), "-")
        val first = normalized.substringBefore('-').trim()
        val last = normalized.substringAfter('-', "").trim()
        return first to last
    }

    /** 发布时间：published → published-online → published-print → issued */
    private fun dateOf(o: JsonObject): Long {
        listOf("published", "published-online", "published-print", "issued", "created")
            .forEach { key ->
                val parts = o.obj(key)?.arr("date-parts")?.firstOrNull()?.asJsonArray ?: return@forEach
                val y = parts.at(0)?.asIntOrNull() ?: return@forEach
                val m = parts.at(1)?.asIntOrNull() ?: 1
                val d = parts.at(2)?.asIntOrNull() ?: 1
                return millisOf(y, m, d)
            }
        return 0L
    }

    /** JsonArray 按下标取（`org.json` 的 getOrNull 在 Gson 的 JsonArray 上不存在） */
    private fun JsonArray.at(index: Int): JsonElement? =
        if (index in 0 until size()) get(index) else null

    private fun yearOf(o: JsonObject, dateMs: Long): Int {
        if (dateMs > 0L) {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = dateMs
            return cal.get(java.util.Calendar.YEAR)
        }
        return 0
    }

    private fun millisOf(y: Int, m: Int, d: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(y, (m - 1).coerceIn(0, 11), d.coerceIn(1, 28), 0, 0, 0)
        return cal.timeInMillis
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive) runCatching { asString }.getOrNull() else null

    private fun JsonElement.asIntOrNull(): Int? =
        if (isJsonPrimitive) runCatching { asInt }.getOrNull() else null
}
