package com.novelreader.source.engine

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
 *
 * 基于 jsoup 实现，支持 CSS 选择器。
 */
object HtmlSelectorEngine {

    /**
     * 提取表达式解析结果。
     */
    data class Expression(
        val selector: String,
        val attr: String, // href / src / text / html
    )

    /**
     * 解析提取表达式。
     *
     * @param expr 如 "a@href"、"div.title@text"、"#content@html"
     */
    fun parseExpression(expr: String): Expression {
        val parts = expr.split("@", limit = 2)
        val selector = parts[0].trim()
        val attr = if (parts.size > 1) parts[1].trim().ifEmpty { "text" } else "text"
        return Expression(selector, attr)
    }

    /**
     * 从 HTML 中提取单个值。
     *
     * @param html 页面 HTML
     * @param expr 提取表达式
     * @return 提取结果（文本或属性值），未匹配返回 null
     */
    fun extract(html: String, expr: String): String? {
        val (selector, attr) = parseExpression(expr)
        val doc = Jsoup.parse(html)
        val element = doc.selectFirst(selector) ?: return null
        return extractValue(element, attr)
    }

    /**
     * 从 HTML 中提取列表。
     *
     * @param html 页面 HTML
     * @param listSelector 列表项选择器
     * @param fields 字段映射（字段名 -> 提取表达式）
     * @return 列表结果，每项为字段名 -> 值的映射
     */
    fun extractList(
        html: String,
        listSelector: String,
        fields: Map<String, String>,
    ): List<Map<String, String>> {
        val doc = Jsoup.parse(html)
        val items: Elements = doc.select(listSelector)
        if (items.isEmpty()) return emptyList()

        // 预解析字段表达式，避免重复解析
        val parsedFields = fields.mapValues { (_, expr) -> parseExpression(expr) }

        return items.map { element ->
            parsedFields.mapValues { (_, expression) ->
                extractFromElement(element, expression)
            }
        }
    }

    /**
     * 从元素中提取值（支持相对选择器，如 ".title" 在元素内查找）。
     */
    private fun extractFromElement(element: Element, expression: Expression): String {
        val target = if (expression.selector.isEmpty()) {
            element
        } else {
            element.selectFirst(expression.selector) ?: return ""
        }
        return extractValue(target, expression.attr)
    }

    /**
     * 从单个元素按属性类型提取值。
     */
    private fun extractValue(element: Element, attr: String): String {
        return when (attr.lowercase()) {
            "text" -> element.text()
            "html" -> element.html()
            "ownhtml" -> element.ownText()
            "href" -> element.attr("href")
            "src" -> element.absUrl("src").ifEmpty { element.attr("src") }
            "data-src", "data-original" -> element.attr(attr).ifEmpty {
                element.attr("src")
            }
            else -> element.attr(attr)
        }
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
     * 将相对 URL 转为绝对 URL。
     */
    fun toAbsoluteUrl(baseUrl: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return runCatching {
            java.net.URL(java.net.URL(baseUrl), url).toString()
        }.getOrNull()
    }

    /** 测试用：解析 HTML 为 Document */
    fun parse(html: String): Document = Jsoup.parse(html)
}
