package com.novelreader.core.rss

/**
 * 「虚拟期刊源」：把 Crossref 的论文列表伪装成一个 RSS 订阅（v1.8.5.0）。
 *
 * 好处是**零新增数据链路**：订阅、增量入库、未读计数、分组、收藏、阅读页、
 * 全部沿用现有 RSS 那一套（`rss_feeds` / `rss_articles` 表），只是抓取方式不同。
 *
 * 地址形态：`crossref:issn:0028-0836`。
 * 为什么不用 `https://` 假地址：用户一眼能看出这不是普通订阅，
 * 而且 `HttpClient` 也不会误把它当真实地址去请求。
 */
object JournalFeed {

    private const val SCHEME = "crossref:issn:"

    /** ISSN → 虚拟订阅地址 */
    fun urlOf(issn: String): String = SCHEME + issn.trim()

    /** 虚拟订阅地址 → ISSN（不是期刊源则返回 null） */
    fun issnOf(url: String): String? {
        val u = url.trim()
        if (!u.startsWith(SCHEME)) return null
        val issn = u.removePrefix(SCHEME).trim()
        return issn.takeIf { it.isNotBlank() }
    }

    /** 是否期刊源 */
    fun isJournalFeed(url: String): Boolean = issnOf(url) != null

    /**
     * Crossref 论文列表 → 标准 [ParsedFeed]，交给现有入库逻辑。
     *
     * 入库要点：
     * - **guid 用 DOI**：同一篇论文无论从哪个入口进来都只存一条，刷新不会重复；
     * - **link 用 doi.org**：点开就是出版社原文页，重定向交给系统浏览器；
     * - **contentHtml 用摘要**：Crossref 有 abstract 的刊（部分 Elsevier / RSC）直接可读，
     *   没有的留空，阅读页会提示「点『获取全文』」；
     * - **paperMeta 全量带上**：卷期页 / 作者 / DOI 都从 Crossref 来，题录卡与引用导出直接可用。
     */
    fun toParsedFeed(journal: JournalRef, works: List<WorkRef>): ParsedFeed {
        val articles = works.map { w ->
            ParsedArticle(
                guid = w.doi.ifBlank { "${w.title}|${w.publishedMs}" },
                title = w.title,
                link = w.url.ifBlank { if (w.doi.isNotBlank()) "https://doi.org/${w.doi}" else "" },
                author = w.authors.joinToString("; "),
                pubDate = w.publishedMs,
                summary = if (w.abstractHtml.isBlank()) {
                    w.toPaperMeta().citationLine()
                } else {
                    RssContent.plainText(w.abstractHtml, 200)
                },
                contentHtml = w.abstractHtml,
                imageUrl = "",
                paperMeta = w.toPaperMeta(),
            )
        }
        return ParsedFeed(
            title = journal.title,
            siteUrl = "",
            description = journal.publisher,
            iconUrl = "",
            format = "crossref",
            articles = articles,
        )
    }
}
