package com.novelreader.source.engine

import com.novelreader.core.util.UrlJoin
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * 选择器解析引擎。
 *
 * 负责执行「提取表达式」，从 HTML 中解析出目标数据。
 *
 * 提取表达式语法：
 * - `selector@attr`  选择器 + 提取属性（如 `a@href`、`img@src`）
 * - `selector@text`  选择器 + 提取文本（`div.title@text`）
 * - `selector@html`  选择器 + 提取内部 HTML（`#content@html`）
 * - `selector@attr(data-src)` 显式属性名（属性名含 `-`、与关键字重名时用）
 * - `selector[0]` / `selector[-1]` 多节点时取第 n 个 / 倒数第 n 个
 *
 * 基于 jsoup 实现，支持 CSS 选择器。
 */
object HtmlSelectorEngine {

    /**
     * 提取表达式解析结果。
     */
    data class Expression(
        val selector: String,
        val attr: String, // href / src / text / html / 任意属性名
        /** 多节点时的下标（负数表示从末尾数），null = 取第一个 */
        val index: Int? = null,
    )

    /** 选择器尾部的下标语法：`div.item[0]`、`a[-1]`（不做贪婪匹配，`[href]` 不会被误判） */
    private val INDEX_SUFFIX = Regex("""^(.*)\[(-?\d+)]$""")

    /**
     * 解析提取表达式。
     *
     * @param expr 如 "a@href"、"div.title@text"、"#content@html"、".list li[-1]@text"
     */
    fun parseExpression(expr: String): Expression {
        val parts = expr.split("@", limit = 2)
        var selector = parts[0].trim()
        val attr = normalizeAttr(if (parts.size > 1) parts[1].trim() else "text")
        val index = INDEX_SUFFIX.find(selector)?.let { match ->
            selector = match.groupValues[1].trim()
            match.groupValues[2].toIntOrNull()
        }
        return Expression(selector, attr, index)
    }

    /**
     * 从 HTML 中提取单个值。
     *
     * @param html 页面 HTML
     * @param expr 提取表达式
     * @return 提取结果（文本或属性值），未匹配返回 null
     */
    fun extract(html: String, expr: String): String? {
        val expression = parseExpression(expr)
        if (expression.selector.isEmpty()) return null
        val doc = Jsoup.parse(html)
        val element = pick(doc.select(expression.selector), expression.index) ?: return null
        return extractValue(element, expression.attr)
    }

    /**
     * 从 HTML 中提取列表。
     *
     * @param html 页面 HTML
     * @param listSelector 列表项选择器（支持尾部 `[n]` 下标）
     * @param fields 字段映射（字段名 -> 提取表达式）
     * @return 列表结果，每项为字段名 -> 值的映射
     */
    fun extractList(
        html: String,
        listSelector: String,
        fields: Map<String, String>,
    ): List<Map<String, String>> {
        if (listSelector.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        // 列表选择器只支持尾部下标，不按 `@` 切分（列表规则里可能含属性选择器）
        var selector = listSelector.trim()
        val index = INDEX_SUFFIX.find(selector)?.let { match ->
            selector = match.groupValues[1].trim()
            match.groupValues[2].toIntOrNull()
        }
        if (selector.isEmpty()) return emptyList()
        val items: Elements = doc.select(selector)
        if (items.isEmpty()) return emptyList()
        val picked = if (index == null) items.toList() else listOfNotNull(pick(items, index))
        if (picked.isEmpty()) return emptyList()

        // 预解析字段表达式，避免重复解析
        val parsedFields = fields.mapValues { (_, expr) -> parseExpression(expr) }

        return picked.map { element ->
            parsedFields.mapValues { (_, expression) ->
                extractFromElement(element, expression)
            }
        }
    }

    /**
     * 从元素中提取值（支持相对选择器，如 ".title" 在元素内查找）。
     */
    private fun extractFromElement(element: Element, expression: Expression): String {
        if (expression.selector.isEmpty()) {
            return extractValue(element, expression.attr)
        }
        val target = pick(element.select(expression.selector), expression.index) ?: return ""
        return extractValue(target, expression.attr)
    }

    /**
     * 按属性类型提取值。
     *
     * `attr` 已在 [parseExpression] 中归一化（`attr(xxx)` → `xxx`），
     * 因此这里只需处理几个语义化关键字，其余按原始属性名读取。
     */
    private fun extractValue(element: Element, attr: String): String {
        return when (attr.lowercase()) {
            "text" -> element.text()
            "html" -> element.html()
            "ownhtml" -> element.ownText()
            "outerhtml" -> element.outerHtml()
            "href" -> element.attr("href")
            "src" -> element.absUrl("src").ifEmpty { element.attr("src") }
            "data-src", "data-original" -> element.attr(attr).ifEmpty {
                element.attr("src")
            }
            else -> element.attr(attr)
        }
    }

    /** 从节点集合中按下标取值（负数从末尾数） */
    private fun pick(elements: List<Element>, index: Int?): Element? {
        if (elements.isEmpty()) return null
        if (index == null) return elements.first()
        val actual = if (index < 0) elements.size + index else index
        return elements.getOrNull(actual)
    }

    /** `attr(xxx)` → `xxx`；空值兜底为 text */
    private fun normalizeAttr(raw: String): String {
        val attr = raw.trim()
        if (attr.startsWith("attr(", ignoreCase = true) && attr.endsWith(")")) {
            return attr.substring(5, attr.length - 1).trim().ifEmpty { "text" }
        }
        return attr.ifEmpty { "text" }
    }

    /**
     * 移除指定选择器的元素（用于正文净化）。
     */
    fun removeElements(html: String, selectors: List<String>): String {
        if (selectors.isEmpty()) return html
        val doc = Jsoup.parse(html)
        selectors.forEach { selector ->
            doc.select(selector).remove()
        }
        return doc.body()?.html() ?: html
    }

    /**
     * 将相对 URL 转为绝对 URL（协议相对 / 根相对 / 普通相对都支持）。
     *
     * 实现委托给 [UrlJoin.normalizeUrl]，保留本方法以兼容既有调用方。
     */
    fun toAbsoluteUrl(baseUrl: String, url: String?): String? = UrlJoin.normalizeUrl(baseUrl, url)

    /** 测试用：解析 HTML 为 Document */
    fun parse(html: String): Document = Jsoup.parse(html)
}
