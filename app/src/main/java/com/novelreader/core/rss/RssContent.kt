package com.novelreader.core.rss

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 订阅文章正文的渲染模型。
 */
sealed interface RssBlock {
    /** 段落（已去标签的纯文本，保留原文换行语义） */
    data class Paragraph(val text: String) : RssBlock

    /** 配图（已补全为绝对地址） */
    data class Image(val url: String) : RssBlock
}

/**
 * 订阅文章 HTML → 阅读块（v1.3.1.0）。
 *
 * RSS 的正文 HTML 千奇百怪（WordPress 的 `<p>`、微信式 `<section>`、
 * 老博客的 `<div><br>`），统一按「块级元素分段 + 图片独立成块」处理，
 * 阅读页据此渲染成干净的段落 + 图片，不再是一坨挤在一起的文字。
 */
object RssContent {

    /** 需要整体成段的块级标签 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "blockquote", "aside", "main", "header", "footer",
        "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dt", "dd",
        "table", "tr", "td", "th", "pre", "figure", "figcaption", "hr", "center",
    )

    /** 需要整段丢弃的标签 */
    private const val DROP_SELECTOR = "script, style, iframe, noscript, form, svg"

    /**
     * HTML → 阅读块列表。
     *
     * @param html 正文 HTML（也接受纯文本，纯文本会按换行分段）
     * @param baseUrl 相对地址基准（通常为文章链接或站点地址）
     */
    fun blocks(html: String, baseUrl: String = ""): List<RssBlock> {
        val src = html.trim()
        if (src.isEmpty()) return emptyList()

        // 纯文本（无标签）直接按空行分段，避免 jsoup 把换行吃掉
        if (!src.contains('<')) return splitPlainText(src)

        val doc = Jsoup.parse(src, baseUrl)
        doc.select(DROP_SELECTOR).remove()

        val out = mutableListOf<RssBlock>()
        val buffer = StringBuilder()
        collect(doc.body(), baseUrl, out, buffer)
        flushBuffer(buffer, out)
        if (out.isEmpty()) {
            val fallback = doc.text().trim()
            if (fallback.isNotEmpty()) out.add(RssBlock.Paragraph(fallback))
        }
        return out
    }

    /**
     * HTML → 纯文本（段落间空行分隔）。
     *
     * @param limit 截断长度，0 表示不截断
     */
    fun plainText(html: String, limit: Int = 0): String {
        val text = blocks(html).filterIsInstance<RssBlock.Paragraph>()
            .joinToString("\n\n") { it.text }
            .trim()
        if (limit > 0 && text.length > limit) return text.take(limit).trimEnd() + "…"
        return text
    }

    /** 正文首图地址（列表缩略图用） */
    fun firstImage(html: String, baseUrl: String = ""): String {
        blocks(html, baseUrl).filterIsInstance<RssBlock.Image>().firstOrNull()?.let { return it.url }
        return ""
    }

    // ==================== 遍历实现 ====================

    /** 深度优先遍历：文本累积成段，块级边界与图片触发分段 */
    private fun collect(
        node: Node,
        baseUrl: String,
        out: MutableList<RssBlock>,
        buffer: StringBuilder,
    ) {
        node.childNodes().forEach { child ->
            when (child) {
                is TextNode -> appendInline(buffer, child.text())
                is Element -> when (val tag = child.tagName().lowercase()) {
                    "img" -> {
                        flushBuffer(buffer, out)
                        imageUrl(child, baseUrl).takeIf { it.isNotBlank() }
                            ?.let { out.add(RssBlock.Image(it)) }
                    }
                    "br", "hr" -> flushBuffer(buffer, out)
                    else -> {
                        if (tag in BLOCK_TAGS) {
                            val hasBlockChild = child.children().any {
                                val t = it.tagName().lowercase()
                                t in BLOCK_TAGS || t == "img"
                            }
                            if (hasBlockChild) {
                                flushBuffer(buffer, out)
                                collect(child, baseUrl, out, buffer)
                                flushBuffer(buffer, out)
                            } else {
                                flushBuffer(buffer, out)
                                val t = child.text().trim()
                                if (t.isNotEmpty()) out.add(RssBlock.Paragraph(t))
                            }
                        } else {
                            appendInline(buffer, child.text())
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun appendInline(buffer: StringBuilder, text: String) {
        val t = text.replace(Regex("[\\r\\n\\t\\u00a0]+"), " ").trim()
        if (t.isEmpty()) return
        if (buffer.isNotEmpty()) buffer.append(' ')
        buffer.append(t)
    }

    private fun flushBuffer(buffer: StringBuilder, out: MutableList<RssBlock>) {
        val t = buffer.toString().replace(Regex("[ \\t\\u00a0]+"), " ").trim()
        buffer.setLength(0)
        if (t.isNotEmpty()) out.add(RssBlock.Paragraph(t))
    }

    /** 图片地址：优先懒加载属性，其次 src，最后 srcset 第一项 */
    private fun imageUrl(img: Element, baseUrl: String): String {
        val attrs = listOf("data-src", "data-original", "data-lazy-src", "data-actualsrc", "src")
        attrs.forEach { attr ->
            val raw = img.attr(attr).trim()
            if (raw.isNotEmpty() && !raw.startsWith("data:") && raw != "about:blank") {
                return resolve(baseUrl, raw)
            }
        }
        val srcset = img.attr("srcset").trim()
        if (srcset.isNotEmpty()) {
            val first = srcset.split(',').firstOrNull()?.trim()?.substringBefore(' ')?.trim().orEmpty()
            if (first.isNotEmpty()) return resolve(baseUrl, first)
        }
        return ""
    }

    /** 纯文本按空行/换行分段 */
    private fun splitPlainText(text: String): List<RssBlock> =
        text.replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n|\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { RssBlock.Paragraph(it) }

    private fun resolve(base: String, url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (base.isBlank()) return u
        return runCatching { java.net.URI(base).resolve(u).toString() }.getOrDefault(u)
    }
}
