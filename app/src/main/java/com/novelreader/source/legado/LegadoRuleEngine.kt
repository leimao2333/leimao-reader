package com.novelreader.source.legado

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * legado 规则引擎。
 *
 * 解析并执行 legado 书源的规则表达式，从 HTML / JSON 中提取数据。
 *
 * 支持的核心语法：
 * - 默认规则（短式）：`.class`、`.class.0`（数字为索引，负数从末尾取）、`#id`
 * - 显式前缀：`class.xxx`、`id.xxx`、`tag.xxx`、`text.关键词`
 * - 链式：`选择器1@选择器2@...@提取`（`@` 逐级向下选择，最后一段为提取符）
 * - 提取符：`@text`、`@html`、`@href`、`@src`、`@data-src` 等；缺省为 text
 * - `@css:选择器` 直接使用 CSS 选择器
 * - `@json:路径` 从 JSON 结果中提取字段
 * - `##正则` 或 `##正则##替换` 净化 / 替换（替换中可用 $1 分组引用）
 * - `||` 多个选择器（取第一个非空结果）
 *
 * 参考阅读 App（legado）的默认规则语法实现核心子集。
 */
object LegadoRuleEngine {

    /** 常见 HTML 标签名（用于把无前缀的裸 token 判定为 tag） */
    private val KNOWN_TAGS = setOf(
        "a", "abbr", "article", "aside", "b", "body", "br", "button", "dd", "div", "dl", "dt",
        "em", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "i", "img",
        "input", "li", "main", "nav", "ol", "option", "p", "section", "select", "small",
        "span", "strong", "table", "tbody", "td", "textarea", "tfoot", "th", "thead", "tr", "u", "ul",
    )

    /** legado 提取符（属性关键字） */
    private val ATTR_KEYS = setOf(
        "text", "html", "all", "ownhtml", "owntext", "ownText", "textNodes", "textnodes",
        "href", "src", "data-src", "data-original", "content", "value", "title", "alt", "data",
    )

    /** legado 索引区间语法（如 `1:6`、`-3:-1`） */
    private val RANGE_REGEX = Regex("-?\\d+:-?\\d+")

    /** 选择器步骤 */
    private sealed class Step {
        data class Cls(val v: String) : Step()
        data class Id(val v: String) : Step()
        data class Tag(val v: String) : Step()
        data class Text(val v: String) : Step()
        data class Index(val i: Int) : Step()
        /** 索引区间（legado `a:b`，含首含尾，支持负数从末尾取） */
        data class Range(val from: Int, val to: Int) : Step()
    }

    // ===== 字符串提取 =====

    /**
     * 从 HTML 中按规则提取字符串值。
     *
     * @param content HTML 或 JSON 文本
     * @param rule 规则表达式
     * @param baseUrl 相对地址转换用的基础地址
     */
    fun extractString(content: String, rule: String, baseUrl: String = ""): String? {
        if (rule.isBlank()) return null
        // 支持 || 多规则（取第一个非空）；任何异常都不得向外抛出
        for (r in rule.split("||")) {
            val result = runCatching { extractFromDocument(content.trim(), r.trim(), baseUrl) }.getOrNull()
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    /** 从完整 HTML/JSON 文档按规则提取单个字符串 */
    private fun extractFromDocument(content: String, rule: String, baseUrl: String): String? {
        // 顶层 @json: 规则：从 JSON 内容提取（内容本身可能不是 JSON，先尝试）
        if (rule.trimStart().startsWith("@json:")) {
            val path = rule.trimStart().substringAfter("@json:")
            return runCatching {
                extractJsonPath(com.google.gson.JsonParser.parseString(content), path)
            }.getOrNull()
        }
        val doc = Jsoup.parse(content)
        return extractFromElement(doc.body() ?: doc, rule, baseUrl)
    }

    /**
     * 从单个元素按规则提取值（统一入口，支持 ## 正则、@css:、@ 链式）。
     */
    private fun extractFromElement(element: Element, rule: String, baseUrl: String): String? {
        if (rule.isBlank()) return element.text()

        // 先剥离 ## 正则（作用于最终取值）
        var expr = rule
        var replacePart: String? = null
        val hashIndex = expr.indexOf("##")
        if (hashIndex >= 0) {
            replacePart = expr.substring(hashIndex + 2)
            expr = expr.substring(0, hashIndex)
        }

        val value = selectAndExtract(element, expr.trim(), baseUrl)
        return if (replacePart != null) applyReplace(value, replacePart) else value
    }

    /** 按 `@` 分段执行：前面各段逐级向下选择，最后一段若是属性关键字则取属性，否则作为选择器取文本 */
    private fun selectAndExtract(root: Element, expr: String, baseUrl: String): String? {
        if (expr.isBlank()) return root.text()

        // `&&` 组合规则：各子规则结果依次拼接（legado 语义）
        if (expr.contains("&&")) {
            val parts = expr.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val sb = StringBuilder()
                for (p in parts) {
                    val v = selectAndExtract(root, p, baseUrl)
                    if (!v.isNullOrEmpty()) sb.append(v)
                }
                return sb.toString()
            }
        }

        // @css: 直接选择器
        if (expr.startsWith("@css:")) {
            val sel = expr.substringAfter("@css:").trim()
            return runCatching { root.selectFirst(sel)?.text() }.getOrNull()
        }
        // @json: 元素文本中解析 JSON
        if (expr.startsWith("@json:")) {
            val path = expr.substringAfter("@json:")
            return runCatching {
                extractJsonPath(com.google.gson.JsonParser.parseString(root.text()), path)
            }.getOrNull()
        }

        val segs = expr.split('@').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return root.text()

        var cur: List<Element> = listOf(root)
        for (i in 0 until segs.size - 1) {
            cur = walkSteps(cur, segs[i])
            if (cur.isEmpty()) return null
        }

        val last = segs.last()
        // 最后一段是属性关键字 → 取第一个元素的对应属性
        if (isAttrKey(last)) {
            return cur.firstOrNull()?.let { extractTextOrAttr(it, last, baseUrl) }
        }
        // 否则最后一段是选择器 → 取第一个匹配元素的文本
        val els = walkSteps(cur, last)
        return els.firstOrNull()?.text()
    }

    private fun isAttrKey(s: String): Boolean {
        val key = s.trim().lowercase()
        return key in ATTR_KEYS.map { it.lowercase() }
    }

    /**
     * 在一组元素内按 legado 选择器继续向下选择。
     *
     * 若选择器是纯 CSS（含空格 / `>` / `[` / `:` / `,`），直接用 Jsoup 选择；
     * 否则按 legado 默认规则解析（`.class`、`#id`、`class.xx`、`tag.xx`、`text.xx`、数字索引）。
     *
     * 健壮性：任何非法选择器都不得抛出异常（否则会中断整条书源的解析），
     * 统一返回空列表；CSS 解析失败时回退到 legado 语法。
     */
    private fun walkSteps(roots: List<Element>, selector: String): List<Element> {
        val sel = selector.trim()
        if (sel.isEmpty()) return roots

        // 暂不支持：JS 规则（@js: / <js>...</js>）→ 返回空，避免当作 CSS 抛异常
        if (sel.startsWith("@js:") || sel.startsWith("<js>") || sel.contains("<js>")) return emptyList()

        // 显式 CSS
        if (sel.startsWith("@css:")) {
            val css = sel.substringAfter("@css:").trim()
            return safeSelect(roots) { it.select(css) }
        }

        // XPath（legado 语法：以 // 或 (/ 开头）
        if (sel.startsWith("//") || sel.startsWith("(/")) {
            return safeSelect(roots) { it.selectXpath(sel) }
        }

        // 纯 CSS 选择器（失败则回退到 legado 语法）
        if (looksLikeCss(sel)) {
            val cssResult = safeSelectOrNull(roots) { it.select(sel) }
            if (cssResult != null) return cssResult
        }

        val steps = parseLegadoSelector(sel)
        if (steps.isEmpty()) return roots

        var cur: List<Element> = roots
        for (step in steps) {
            cur = when (step) {
                is Step.Cls -> {
                    val v = step.v.trim()
                    if (v.isEmpty()) cur
                    // 支持复合类名（class.xxx yyy → .xxx.yyy）
                    else safeSelect(cur) { el ->
                        val css = if (v.contains(' ')) {
                            v.split(Regex("\\s+")).joinToString("") { ".$it" }
                        } else ".$v"
                        el.select(css)
                    }
                }
                is Step.Id -> step.v.trim().takeIf { it.isNotEmpty() }
                    ?.let { id -> safeSelect(cur) { it.select("#$id") } } ?: cur
                is Step.Tag -> step.v.trim().takeIf { it.isNotEmpty() }
                    ?.let { tag -> safeSelect(cur) { it.select(tag) } } ?: cur
                is Step.Text -> safeSelect(cur) { el ->
                    Elements(el.select("*").filter { c -> c.ownText().contains(step.v) })
                }
                is Step.Index -> {
                    val list = cur
                    val idx = if (step.i < 0) list.size + step.i else step.i
                    if (idx in list.indices) listOf(list[idx]) else emptyList()
                }
                is Step.Range -> {
                    val list = cur
                    val size = list.size
                    val from = if (step.from < 0) size + step.from else step.from
                    val to = if (step.to < 0) size + step.to else step.to
                    if (size == 0) emptyList()
                    else {
                        val f = from.coerceIn(0, size - 1)
                        val t = to.coerceIn(0, size - 1)
                        if (f <= t) list.subList(f, t + 1).toList() else emptyList()
                    }
                }
            }
            if (cur.isEmpty()) break
        }
        return cur
    }

    /** 逐个元素执行选择，忽略个别元素上的解析异常 */
    private inline fun safeSelect(roots: List<Element>, crossinline f: (Element) -> Elements): List<Element> {
        val out = mutableListOf<Element>()
        for (r in roots) {
            val els = runCatching { f(r) }.getOrNull() ?: continue
            out.addAll(els)
        }
        return out
    }

    /** 同上，但任一异常返回 null（用于判断「CSS 解析失败需回退」） */
    private inline fun safeSelectOrNull(roots: List<Element>, crossinline f: (Element) -> Elements): List<Element>? {
        val out = mutableListOf<Element>()
        for (r in roots) {
            val els = runCatching { f(r) }.getOrNull() ?: return null
            out.addAll(els)
        }
        return out
    }

    private fun looksLikeCss(sel: String): Boolean {
        // 显式带 legado 前缀 → 一律走 legado 语法（即使类名里含空格）
        if (sel.startsWith("class.") || sel.startsWith("id.") ||
            sel.startsWith("tag.") || sel.startsWith("text.")
        ) {
            return false
        }
        return sel.indexOf(' ') >= 0 || sel.indexOf('>') >= 0 || sel.indexOf('[') >= 0 ||
            sel.indexOf(':') >= 0 || sel.indexOf(',') >= 0
    }

    /**
     * 解析 legado 默认规则选择器为步骤序列。
     *
     * 例：
     * - `.section-list.1`   → [Cls("section-list"), Index(1)]
     * - `.s2`               → [Cls("s2")]
     * - `class.book-list.0` → [Cls("book-list"), Index(0)]
     * - `tag.div.2`         → [Tag("div"), Index(2)]
     * - `p.0`               → [Tag("p"), Index(0)]
     * - `#content`          → [Id("content")]
     */
    private fun parseLegadoSelector(sel: String): List<Step> {
        val leadingDot = sel.startsWith(".")
        val parts = sel.split('.')
        val steps = mutableListOf<Step>()
        var i = 0
        var first = true
        while (i < parts.size) {
            val p = parts[i]
            if (p.isEmpty()) { i++; continue }
            when {
                // 显式前缀：class./id./tag./text. → 绑定下一个 token 作为值
                (p == "class" || p == "id" || p == "tag" || p == "text") && i + 1 < parts.size -> {
                    val v = parts[i + 1]
                    when (p) {
                        "class" -> steps.add(Step.Cls(v))
                        "id" -> steps.add(Step.Id(v))
                        "tag" -> steps.add(Step.Tag(v))
                        else -> steps.add(Step.Text(v))
                    }
                    i += 2
                    first = false
                    continue
                }
                p.startsWith("#") -> steps.add(Step.Id(p.substring(1)))
                RANGE_REGEX.matches(p) -> {
                    val a = p.substringBefore(':').toIntOrNull() ?: 0
                    val b = p.substringAfter(':').toIntOrNull() ?: 0
                    steps.add(Step.Range(a, b))
                }
                p.toIntOrNull() != null -> steps.add(Step.Index(p.toInt()))
                // 无前缀裸 token：首段且在无前导点、且是常见标签名 → tag；否则按 class
                first && !leadingDot && p.lowercase() in KNOWN_TAGS -> steps.add(Step.Tag(p))
                else -> steps.add(Step.Cls(p))
            }
            first = false
            i++
        }
        return steps
    }

    // ===== 列表提取 =====

    /**
     * 从 HTML 中按规则提取列表。
     *
     * @param content HTML/JSON 文本
     * @param listRule 列表项选择器（如 `class.book-list@li`、`.section-list.1@li a` 或 `@json:$.data`）
     * @param fieldRules 字段名 -> 字段规则
     */
    fun extractList(
        content: String,
        listRule: String,
        fieldRules: Map<String, String>,
        baseUrl: String = "",
    ): List<Map<String, String>> {
        if (listRule.isBlank()) return emptyList()

        // 处理 @json: 列表
        if (listRule.trimStart().startsWith("@json:")) {
            return extractJsonList(content, listRule.trim(), fieldRules)
        }

        val doc = Jsoup.parse(content)
        val items = selectListElements(doc, listRule)
        if (items.isEmpty()) return emptyList()

        return items.map { el ->
            fieldRules.mapValues { (_, rule) ->
                runCatching { extractFromElement(el, rule, baseUrl) }.getOrNull() ?: ""
            }
        }
    }

    /** 在一段规则上选取列表元素（支持 || 备选） */
    private fun selectListElements(doc: Document, listRule: String): Elements {
        for (r in listRule.split("||")) {
            val trimmed = r.trim()
            if (trimmed.isEmpty()) continue
            val elements = selectListElementsSingle(doc, trimmed)
            if (elements.isNotEmpty()) return elements
        }
        return Elements()
    }

    /** 选取单个列表规则的元素：`容器1@容器2@...@子选择器`（多段 @ 逐级向下） */
    private fun selectListElementsSingle(doc: Document, rule: String): Elements {
        if (rule.startsWith("@css:")) {
            return runCatching { doc.select(rule.substringAfter("@css:").trim()) }.getOrDefault(Elements())
        }

        // 去掉非标准索引标记（如 `li!0` 中的 `!0`）
        val sanitized = rule.replace(Regex("![0-9]+"), "")
        val segs = sanitized.split('@').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return Elements()

        val itemPart = if (segs.size > 1) segs.last() else ""
        val containerSegs = if (segs.size > 1) segs.dropLast(1) else segs

        var containers = walkSteps(listOf(doc.body() ?: doc), containerSegs.first())
        for (i in 1 until containerSegs.size) {
            containers = walkSteps(containers, containerSegs[i])
            if (containers.isEmpty()) break
        }
        if (containers.isEmpty()) return Elements()

        val out = Elements()
        if (itemPart.isBlank()) {
            containers.forEach { out.add(it) }
        } else {
            containers.forEach { c ->
                if (looksLikeCss(itemPart)) {
                    val els = runCatching { c.select(itemPart) }.getOrNull()
                    if (els != null) out.addAll(els) else walkSteps(listOf(c), itemPart).forEach { out.add(it) }
                } else {
                    walkSteps(listOf(c), itemPart).forEach { out.add(it) }
                }
            }
        }
        return out
    }

    /** 从 JSON 内容按 @json: 规则提取列表 */
    private fun extractJsonList(json: String, listRule: String, fieldRules: Map<String, String>): List<Map<String, String>> {
        val jsonPath = listRule.substringAfter("@json:").trim()
        return runCatching {
            val root = com.google.gson.JsonParser.parseString(json)
            val arr = selectJsonArray(root, jsonPath)
            arr.map { node ->
                fieldRules.mapValues { (_, rule) ->
                    extractFromJsonNode(node, rule) ?: ""
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 从元素按属性类型提取值 */
    private fun extractTextOrAttr(element: Element, attr: String, baseUrl: String): String {
        return when (attr.trim().lowercase()) {
            "text" -> element.text()
            "textnodes" -> element.text()
            "html", "all" -> element.html()
            "ownhtml", "owntext" -> element.ownText()
            "href" -> element.attr("href")
            "src" -> element.attr("src").ifEmpty { element.attr("data-src") }.ifEmpty { element.attr("data-original") }
            "data-src" -> element.attr("data-src").ifEmpty { element.attr("src") }
            "data-original" -> element.attr("data-original").ifEmpty { element.attr("src") }
            else -> element.attr(attr.trim())
        }
    }

    /** 正则替换（支持 `##正则##替换` 或 `##正则` 简写） */
    private fun applyReplace(value: String?, replaceRule: String): String? {
        if (value == null) return null
        return runCatching {
            if (replaceRule.contains("##")) {
                val parts = replaceRule.split("##", limit = 2)
                value.replace(Regex(parts[0]), parts.getOrElse(1) { "" })
            } else {
                value.replace(Regex(replaceRule), "")
            }
        }.getOrDefault(value)
    }

    // ===== JSON 提取 =====

    private fun extractFromJsonNode(node: com.google.gson.JsonElement, rule: String): String? {
        val path = if (rule.startsWith("@json:")) rule.substringAfter("@json:") else rule
        return extractJsonPath(node, path)
    }

    private fun extractJsonPath(node: com.google.gson.JsonElement, path: String): String? {
        return runCatching {
            val target = selectJsonPath(node, path) ?: return null
            when {
                target.isJsonPrimitive -> target.asString
                target.isJsonNull -> null
                else -> target.toString()
            }
        }.getOrNull()
    }

    /** 按 JSONPath 子集选择节点 */
    private fun selectJsonPath(node: com.google.gson.JsonElement, path: String): com.google.gson.JsonElement? {
        var current: com.google.gson.JsonElement = node
        val cleaned = path.removePrefix("$").removePrefix(".").trim()
        if (cleaned.isEmpty()) return current

        val tokens = cleaned.split(Regex("[.\\[\\]]")).filter { it.isNotBlank() }
        for (token in tokens) {
            current = when {
                current.isJsonObject -> current.asJsonObject.get(token) ?: return null
                current.isJsonArray -> {
                    val idx = token.toIntOrNull() ?: return null
                    val arr = current.asJsonArray
                    if (idx < 0 || idx >= arr.size()) return null
                    arr.get(idx)
                }
                else -> return null
            }
        }
        return current
    }

    private fun selectJsonArray(root: com.google.gson.JsonElement, path: String): List<com.google.gson.JsonElement> {
        val target = selectJsonPath(root, path)
        return when {
            target == null -> emptyList()
            target.isJsonArray -> target.asJsonArray.toList()
            else -> listOf(target)
        }
    }

    // ===== URL 工具 =====

    /** 相对 URL 转绝对 URL */
    private fun absUrl(baseUrl: String, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return sanitizeUrl(url)
        if (baseUrl.isBlank()) return sanitizeUrl(url)
        val resolved = runCatching {
            java.net.URL(java.net.URL(baseUrl), url).toString()
        }.getOrElse { url }
        return sanitizeUrl(resolved)
    }

    /**
     * 清洗 URL：编码空格、中文等非法字符，保留既有 `%XX` 转义。
     * 避免 `Illegal character in URL` 之类的异常导致整条链路失败。
     */
    private fun sanitizeUrl(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return u
        // 已经合法则原样返回
        runCatching { java.net.URI(u) }.onSuccess { return u }
        var pre = u
        if (pre.contains(' ')) pre = pre.replace(" ", "%20")
        runCatching { java.net.URI(pre) }.onSuccess { return it.toASCIIString() }
        // 退化为逐字节百分号编码（保留 URL 结构字符）
        val keep = "-._~:/?#[]@!$&'()*+,;=%"
        return buildString {
            for (b in pre.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 128 && (ch.isLetterOrDigit() || keep.indexOf(ch) >= 0)) append(ch)
                else append('%').append(String.format("%02X", c))
            }
        }
    }

    /** 公开的 URL 转换工具 */
    fun toAbsoluteUrl(baseUrl: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        return absUrl(baseUrl, url)
    }
}
