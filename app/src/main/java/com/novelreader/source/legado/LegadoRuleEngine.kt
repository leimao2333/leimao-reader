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
 * - 选择器链：`class.title.0@tag.a@text`（用 `.` 分层，`tag.` 表示取标签，`class.` 取类，`id.` 取 id，`text.` 取文本）
 * - `@text` 取文本、`@href`/`@src` 等取属性、`@html` 取内部 HTML
 * - `@css:选择器` 在已提取结果上继续用 CSS 选择器提取
 * - `@json:路径` 从 JSON 结果中提取字段
 * - `##正则` 正则替换/净化
 * - `||` 多个选择器（取第一个非空结果）
 * - `&&` 拼接多个结果
 *
 * 参考阅读 App（legado）的规则语法实现核心子集。
 */
object LegadoRuleEngine {

    /**
     * 从 HTML 中按规则提取字符串值。
     *
     * @param content HTML 或 JSON 文本
     * @param rule 规则表达式
     * @param isJson 内容是否为 JSON（用于 @json 提取）
     */
    fun extractString(content: String, rule: String, baseUrl: String = ""): String? {
        if (rule.isBlank()) return null
        // 支持 || 多规则（取第一个非空）
        val rules = rule.split("||")
        for (r in rules) {
            val result = extractFromDocument(content.trim(), r.trim(), baseUrl)
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    /** 从完整 HTML/JSON 文档按规则提取单个字符串 */
    private fun extractFromDocument(content: String, rule: String, baseUrl: String): String? {
        // @json: 规则：从 JSON 内容提取
        if (rule.startsWith("@json:")) {
            val path = rule.substringAfter("@json:")
            return runCatching {
                val root = com.google.gson.JsonParser.parseString(content)
                extractJsonPath(root, path)
            }.getOrNull()
        }

        val doc = Jsoup.parse(content)
        // 处理 ## 正则
        val hashIndex = rule.indexOf("##")
        if (hashIndex >= 0) {
            val exprPart = rule.substring(0, hashIndex)
            val replacePart = rule.substring(hashIndex + 2)
            val value = extractBySelector(doc.body() ?: doc, exprPart, baseUrl)
            return applyReplace(value, replacePart)
        }
        return extractBySelector(doc.body() ?: doc, rule, baseUrl)
    }

    /**
     * 从 HTML 中按规则提取列表。
     *
     * 规则中的列表项选择器 + 各字段子规则。
     * @param content HTML/JSON 文本
     * @param listRule 列表项选择器（如 `class.book-list@li` 或 `@json:$.data`）
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
        val items = selectElements(doc, listRule)
        if (items.isEmpty()) return emptyList()

        return items.map { el ->
            fieldRules.mapValues { (_, rule) ->
                extractFromElement(el, rule, baseUrl) ?: ""
            }
        }
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

    /** 从单个元素按规则提取值 */
    private fun extractFromElement(element: Element, rule: String, baseUrl: String): String? {
        var value: String? = null
        var remaining = rule

        // 处理 @css: 前缀（在元素内继续选择）
        if (remaining.startsWith("@css:")) {
            val selector = remaining.substringAfter("@css:")
            val sub = element.selectFirst(selector) ?: return null
            return extractTextOrAttr(sub, "text")
        }

        // 处理 @json: 前缀（在元素文本中解析 JSON）
        if (remaining.startsWith("@json:")) {
            val path = remaining.substringAfter("@json:")
            return runCatching {
                val json = element.text()
                val root = com.google.gson.JsonParser.parseString(json)
                extractJsonPath(root, path)
            }.getOrNull()
        }

        // 处理 ## 正则替换（净化）
        val hashIndex = remaining.indexOf("##")
        if (hashIndex >= 0) {
            val exprPart = remaining.substring(0, hashIndex)
            val replacePart = remaining.substring(hashIndex + 2)
            value = if (exprPart.isBlank()) element.text() else extractBySelector(element, exprPart, baseUrl)
            value = applyReplace(value, replacePart)
            return value
        }

        // 常规选择器链
        value = extractBySelector(element, remaining, baseUrl)
        return value
    }

    /** 按选择器链提取（如 tag.a@href、class.title@text、id.xx@html） */
    private fun extractBySelector(element: Element, rule: String, baseUrl: String): String? {
        var current: Element? = element
        var textMode = false

        // 拆分选择器链（用 @ 分割：选择器@属性）
        val atIndex = rule.indexOf('@')
        val selectorPart = if (atIndex >= 0) rule.substring(0, atIndex) else rule
        val attrPart = if (atIndex >= 0) rule.substring(atIndex + 1) else "text"

        // 选择器为空则取当前元素
        if (selectorPart.isNotBlank()) {
            current = selectByLegadoSelector(element, selectorPart)
        }
        if (current == null) return null

        return extractTextOrAttr(current, attrPart, baseUrl)
    }

    /**
     * 按 legado 选择器语法选取元素。
     * 支持：`tag.a`、`class.title`、`id.xx`、`text.关键词`、数字索引 `.0`
     */
    private fun selectByLegadoSelector(root: Element, selector: String): Element? {
        var current: Element? = root
        val parts = selector.split('.').filter { it.isNotBlank() }

        for (part in parts) {
            when {
                part.startsWith("tag.") -> {
                    val tag = part.substringAfter("tag.")
                    current = current?.let { it.select(tag).firstOrNull() }
                }
                part.startsWith("class.") -> {
                    val cls = part.substringAfter("class.")
                    current = current?.let { it.select(".$cls").firstOrNull() }
                }
                part.startsWith("id.") -> {
                    val id = part.substringAfter("id.")
                    current = current?.let { it.select("#$id").firstOrNull() }
                }
                part.startsWith("text.") -> {
                    val text = part.substringAfter("text.")
                    current = current?.let { el ->
                        el.select("*").firstOrNull { it.ownText().contains(text) }
                    }
                }
                part.toIntOrNull() != null -> {
                    val idx = part.toInt()
                    current = current?.let { el ->
                        // 数字索引：从同级/子级中取第 idx 个
                        val children = el.children()
                        children.getOrNull(idx)
                    }
                }
                else -> {
                    // 尝试作为 CSS 选择器
                    current = current?.let { it.selectFirst(part) }
                }
            }
            if (current == null) break
        }
        return current
    }

    /** 从元素按属性类型提取值 */
    private fun extractTextOrAttr(element: Element, attr: String, baseUrl: String = ""): String {
        return when (attr.lowercase()) {
            "text" -> element.text()
            "html", "all" -> element.html()
            "ownhtml" -> element.ownText()
            "href" -> element.attr("href")
            "src" -> absUrl(baseUrl, element.attr("src"))
            "data-src" -> element.attr("data-src").ifEmpty { element.attr("src") }
            "data-original" -> element.attr("data-original").ifEmpty { element.attr("src") }
            else -> element.attr(attr)
        }
    }

    /** 正则替换（支持 ##正则##替换 或 ##替换 简写） */
    private fun applyReplace(value: String?, replaceRule: String): String? {
        if (value == null) return null
        return runCatching {
            // 形如 正则##替换 或 直接删除
            if (replaceRule.contains("##")) {
                val parts = replaceRule.split("##", limit = 2)
                value.replace(Regex(parts[0]), parts.getOrElse(1) { "" })
            } else {
                // 整个作为正则，替换为空
                value.replace(Regex(replaceRule), "")
            }
        }.getOrDefault(value)
    }

    /** 从 JSON 节点按路径提取字符串 */
    private fun extractFromJsonNode(node: com.google.gson.JsonElement, rule: String): String? {
        // 规则可能是 @json:xxx 或直接字段名
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
        // 支持 $.a.b[0].c 与 a.b.c 两种
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

    /** 用 legado 列表选择器选取元素（支持 || 和 @css） */
    private fun selectElements(doc: Document, listRule: String): Elements {
        val rules = listRule.split("||")
        for (r in rules) {
            val trimmed = r.trim()
            if (trimmed.isEmpty()) continue
            val elements = when {
                trimmed.startsWith("@css:") -> doc.select(trimmed.substringAfter("@css:"))
                else -> doc.select(legadoSelectorToCss(trimmed))
            }
            if (elements.isNotEmpty()) return elements
        }
        return Elements()
    }

    /**
     * 将 legado 列表选择器转为 CSS 选择器。
     * 如 `class.book-list@li` -> `.book-list li`；`class.xx@a` -> `.xx a`
     */
    private fun legadoSelectorToCss(selector: String): String {
        val atIndex = selector.lastIndexOf('@')
        val listPart = if (atIndex >= 0) selector.substring(0, atIndex) else selector
        val itemPart = if (atIndex >= 0) selector.substring(atIndex + 1) else ""

        val base = listPart
            .split('.')
            .filter { it.isNotBlank() }
            .joinToString(" ") { p ->
                when {
                    p.startsWith("tag.") -> p.substringAfter("tag.")
                    p.startsWith("class.") -> ".${p.substringAfter("class.")}"
                    p.startsWith("id.") -> "#${p.substringAfter("id.")}"
                    else -> p
                }
            }
        return if (itemPart.isNotBlank()) "$base $itemPart" else base
    }

    /** 相对 URL 转绝对 URL */
    private fun absUrl(baseUrl: String, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (baseUrl.isBlank()) return url
        return runCatching {
            java.net.URL(java.net.URL(baseUrl), url).toString()
        }.getOrDefault(url)
    }

    /** 公开的 URL 转换工具 */
    fun toAbsoluteUrl(baseUrl: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        return absUrl(baseUrl, url)
    }
}
