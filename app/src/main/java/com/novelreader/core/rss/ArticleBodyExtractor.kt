package com.novelreader.core.rss

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 从论文页 / 博客页里抽正文（v1.8.5.0）。
 *
 * 场景：期刊 RSS 只给标题和一句引用（Science / Nature / PNAS 的 feed 就是这样，
 * 摘要压根不在 feed 里），读者点进阅读页看到的是一张空页面。这里按三层兜底：
 *
 * 1. **Highwire / Google Scholar 的 `citation_*` meta 标签** —— Science、Nature、Cell、
 *    PNAS、ACS、RSC、Elsevier 全都埋了这套标签（`citation_doi` / `citation_journal_title` /
 *    `citation_volume` / `citation_firstpage` / `citation_author` …），
 *    比 RSS 里的 Prism 字段还全，顺手把题录补全；
 * 2. **摘要**：先找 `section[data-title=Abstract]` / `.abstract` 这类容器，
 *    退到 `meta[name=description]` / `og:description`；
 * 3. **正文**：找 `article` / `[role=main]` / `.article__body` 里文本量最大的容器，
 *    去掉导航、推荐位、参考文献。
 *
 * 付费墙下拿不到全文是正常的（返回摘要即算成功）—— 不报错、不抛异常，
 * 让阅读页安静地显示「只拿到摘要」。
 */
object ArticleBodyExtractor {

    /** 抽取结果 */
    data class Extraction(
        /** 可渲染的正文 HTML（空 = 什么都没抽到） */
        val html: String = "",
        /** 抽到的题录（页面 meta 比 RSS 更全，用于补全） */
        val meta: PaperMeta = PaperMeta(),
        /** 抽到的标题（空 = 页面没给） */
        val title: String = "",
        /** 正文长度（纯文本字符数），供 UI 判断是「全文」还是「摘要」 */
        val textLength: Int = 0,
    ) {
        val isEmpty: Boolean get() = html.isBlank()
        /** 是否像全文（阈值取 1200 字：摘要通常 800~1500，正文远大于此） */
        val looksFullText: Boolean get() = textLength >= 1200
    }

    /** 正文容器的候选选择器（按优先级；最后再按文本量兜底） */
    private val BODY_SELECTORS = listOf(
        "article",
        "div.article__body",
        "div#bodymatter",
        "section[data-title=Body]",
        "div[role=main]",
        "main",
        "div.entry-content",
        "div.post-content",
        "div.content",
    )

    /** 摘要容器的候选选择器 */
    private val ABSTRACT_SELECTORS = listOf(
        "section[data-title=Abstract]",
        "div.abstract",
        "section.abstract",
        "div#abstract",
        "div.article__abstract",
        "div.summary",
        "blockquote.abstract",
    )

    /** 必须整块删掉的噪声 */
    private const val NOISE_SELECTOR =
        "script, style, noscript, iframe, form, nav, header, footer, aside, " +
            ".references, #references, .related, .recommended, .advertisement, .ad, " +
            ".cookie, .newsletter, .social, .share, .breadcrumb, .pagination, " +
            "figure.reference, .ref-list, .citation-list"

    /**
     * 抽取。
     *
     * @param html 页面 HTML
     * @param url 页面地址（补全相对链接 + 兜底题录）
     */
    fun extract(html: String, url: String = ""): Extraction {
        if (html.isBlank()) return Extraction()
        val doc = runCatching { Jsoup.parse(html, url) }.getOrNull() ?: return Extraction()

        val meta = metaOf(doc, url)
        val title = titleOf(doc)
        doc.select(NOISE_SELECTOR).remove()

        // 1) 先试正文容器：文本量最大且超过摘要长度的那个
        val body = BODY_SELECTORS.asSequence()
            .mapNotNull { sel -> runCatching { doc.select(sel).maxByOrNull { it.text().length } }.getOrNull() }
            .filter { it.text().length >= 400 }
            .maxByOrNull { it.text().length }

        val bodyText = body?.text()?.length ?: 0
        val abstractEl = ABSTRACT_SELECTORS.asSequence()
            .mapNotNull { sel -> runCatching { doc.select(sel).maxByOrNull { it.text().length } }.getOrNull() }
            .firstOrNull { it.text().length >= 80 }

        // 2) 正文够长就用正文；否则退到摘要；再退到 meta description
        val chosen: Element? = when {
            body != null && bodyText >= 1200 -> body
            abstractEl != null -> abstractEl
            body != null -> body
            else -> null
        }

        val htmlOut = when {
            chosen != null -> clean(chosen)
            else -> {
                val desc = metaDescription(doc)
                if (desc.isBlank()) "" else "<p>${escape(desc)}</p>"
            }
        }

        return Extraction(
            html = htmlOut,
            meta = meta,
            title = title,
            textLength = Jsoup.parse(htmlOut).text().length,
        )
    }

    /**
     * 从页面的 Highwire `citation_*` / Dublin Core meta 标签抽题录。
     *
     * 这套标签是学术出版的行业惯例，覆盖 Science / Nature / Cell / PNAS / ACS / RSC / Elsevier，
     * 比 RSS 里的 Prism 字段更完整（含完整作者列表）。
     */
    fun metaOf(doc: org.jsoup.nodes.Document, url: String = ""): PaperMeta {
        fun meta(vararg names: String): String {
            names.forEach { name ->
                val v = doc.selectFirst("meta[name=$name]")?.attr("content")?.trim().orEmpty()
                if (v.isNotEmpty()) return v
                val p = doc.selectFirst("meta[property=$name]")?.attr("content")?.trim().orEmpty()
                if (p.isNotEmpty()) return p
            }
            return ""
        }

        val authors = doc.select("meta[name=citation_author], meta[name=DC.Creator], meta[name=dc.creator]")
            .mapNotNull { it.attr("content").trim().takeIf { a -> a.isNotEmpty() } }
            .distinct()

        val date = meta("citation_publication_date", "citation_date", "citation_online_date", "DC.Date")
        val year = Regex("""\b(19|20)\d{2}\b""").find(date)?.value?.toIntOrNull() ?: 0

        return PaperMeta(
            doi = PaperMetaParser.normalizeDoi(
                meta("citation_doi", "DC.Identifier", "dc.identifier").ifBlank { PaperMetaParser.doiFrom(url) }
            ),
            journal = meta("citation_journal_title", "citation_conference_title", "DC.Source"),
            volume = meta("citation_volume"),
            issue = meta("citation_issue"),
            firstPage = meta("citation_firstpage"),
            lastPage = meta("citation_lastpage"),
            year = year,
            articleType = meta("citation_article_type", "DC.Type"),
            authors = authors.ifEmpty {
                PaperMetaParser.splitAuthors(meta("citation_author", "author"))
            },
            publisher = meta("citation_publisher", "DC.Publisher"),
        )
    }

    // ==================== 内部工具 ====================

    private fun titleOf(doc: org.jsoup.nodes.Document): String {
        val candidates = listOf(
            doc.selectFirst("meta[name=citation_title]")?.attr("content"),
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("h1")?.text(),
            doc.title(),
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun metaDescription(doc: org.jsoup.nodes.Document): String =
        listOf(
            doc.selectFirst("meta[name=description]")?.attr("content"),
            doc.selectFirst("meta[property=og:description]")?.attr("content"),
            doc.selectFirst("meta[name=citation_abstract]")?.attr("content"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    /** 容器 → 干净 HTML：只留段落 / 标题 / 图片 / 列表 */
    private fun clean(el: Element): String {
        val sb = StringBuilder()
        val blocks = el.select("p, h2, h3, h4, blockquote, li, figcaption, img")
        if (blocks.isEmpty()) {
            val t = el.text().trim()
            return if (t.isEmpty()) "" else "<p>${escape(t)}</p>"
        }
        blocks.forEach { b ->
            when (b.tagName().lowercase()) {
                "img" -> {
                    val src = b.attr("data-src").ifBlank { b.attr("src") }
                    if (src.isNotBlank() && !src.startsWith("data:")) {
                        val abs = b.absUrl("src").ifBlank { src }
                        sb.append("<p><img src=\"").append(abs).append("\"/></p>")
                    }
                }
                "li" -> b.text().trim().takeIf { it.isNotEmpty() }?.let {
                    sb.append("<p>· ").append(escape(it)).append("</p>")
                }
                "h2", "h3", "h4" -> b.text().trim().takeIf { it.isNotEmpty() }?.let {
                    sb.append("<h3>").append(escape(it)).append("</h3>")
                }
                else -> b.text().trim().takeIf { it.isNotEmpty() }?.let {
                    // 太短的段落多半是按钮 / 标签，丢掉
                    if (it.length >= 2) sb.append("<p>").append(escape(it)).append("</p>")
                }
            }
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
