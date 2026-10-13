package com.novelreader.core.rss

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 解析出的订阅源（抓取结果，尚未入库）。
 */
data class ParsedFeed(
    /** 源标题 */
    val title: String,
    /** 站点首页地址 */
    val siteUrl: String,
    /** 源描述 */
    val description: String,
    /** 站点图标 */
    val iconUrl: String,
    /** 识别出的格式：rss2 / atom / rdf */
    val format: String,
    /** 文章列表（按源中出现顺序） */
    val articles: List<ParsedArticle>,
)

/**
 * 解析出的一篇文章。
 */
data class ParsedArticle(
    /** 去重标识（guid / id / 链接 / 标题+时间兜底） */
    val guid: String,
    val title: String,
    val link: String,
    val author: String,
    /** 发布时间（毫秒；无法解析时为 0） */
    val pubDate: Long,
    /** 纯文本摘要（列表页展示，已截断） */
    val summary: String,
    /** 正文 HTML（阅读页渲染用，可能含图片） */
    val contentHtml: String,
    /** 首图（封面 / 列表缩略图） */
    val imageUrl: String,
)

/**
 * OPML 里的一条订阅（批量导入用）。
 */
data class OpmlOutline(
    val title: String,
    val xmlUrl: String,
    val htmlUrl: String = "",
)

/**
 * RSS 订阅源解析器（v1.3.1.0）。
 *
 * 支持三种主流形态，命名空间前缀一律忽略（`content:encoded` / `dc:date` 等按局部名匹配）：
 * - **RSS 2.0**：`<rss><channel><item>`
 * - **Atom 1.0**：`<feed><entry>`
 * - **RDF / RSS 1.0**：`<rdf:RDF><channel>` + 顶层 `<item>`
 *
 * 另提供 OPML 订阅列表解析与「HTML 页面里自动发现订阅地址」
 * （`<link rel="alternate" type="application/rss+xml">`），
 * 用户直接粘贴博客首页也能加上订阅。
 */
object RssParser {

    /** 摘要截断长度（列表页展示用） */
    private const val SUMMARY_LIMIT = 200

    /**
     * 判断一段文本是否是订阅源（XML）。
     * 只做轻量嗅探（看根标签），不做完整解析。
     */
    fun isFeed(raw: String): Boolean = detectRootTag(raw) in setOf("rss", "feed", "rdf")

    /** 判断是否是 OPML 订阅列表 */
    fun isOpml(raw: String): Boolean = detectRootTag(raw) == "opml"

    /**
     * 嗅探根标签（小写局部名）：rss / feed / rdf / opml / html / 未知返回空串。
     */
    fun detectRootTag(raw: String): String {
        val head = raw.take(4096)
        // 去掉 XML 声明与注释，避免误判
        val cleaned = head
            .replace(Regex("(?s)<\\?.*?\\?>"), "")
            .replace(Regex("(?s)<!--.*?-->"), "")
            .trim()
        val start = cleaned.indexOf('<')
        if (start < 0) return ""
        val name = cleaned.substring(start + 1)
            .takeWhile { it.isLetterOrDigit() || it == ':' || it == '_' || it == '-' }
            .lowercase()
        if (name.isEmpty()) return ""
        val local = name.substringAfterLast(':')
        return when {
            local == "rss" -> "rss"
            local == "feed" -> "feed"
            local == "rdf" -> "rdf"
            local == "opml" -> "opml"
            local == "html" -> "html"
            else -> local
        }
    }

    /**
     * 解析订阅源。
     *
     * @param raw 源 XML 原文
     * @param baseUrl 源自身地址（用于把相对链接补全为绝对地址）
     * @throws IllegalArgumentException 不是可识别的订阅源
     */
    fun parse(raw: String, baseUrl: String = ""): ParsedFeed {
        val doc = Jsoup.parse(raw, baseUrl, Parser.xmlParser())
        val root = doc.children().firstOrNull()
            ?: throw IllegalArgumentException("不是有效的订阅源：内容为空")
        val local = root.localNameLower()
        return when (local) {
            "rss" -> parseRss2(root, baseUrl)
            "feed" -> parseAtom(root, baseUrl)
            "rdf" -> parseRdf(root, baseUrl)
            else -> throw IllegalArgumentException("不是有效的订阅源：未找到 rss / feed / rdf 根节点")
        }
    }

    /**
     * 解析 OPML 订阅列表（`<outline xmlUrl="...">`）。
     * 嵌套分组会递归展开。
     */
    fun parseOpml(raw: String): List<OpmlOutline> {
        val doc = runCatching { Jsoup.parse(raw, "", Parser.xmlParser()) }
            .getOrElse { Jsoup.parse(raw) }
        val result = mutableListOf<OpmlOutline>()
        doc.select("outline").forEach { el ->
            val url = firstAttr(el, "xmlUrl", "xmlurl", "xmlURL")
            if (url.isNotBlank()) {
                result.add(
                    OpmlOutline(
                        title = firstAttr(el, "title", "text"),
                        xmlUrl = url,
                        htmlUrl = firstAttr(el, "htmlUrl", "htmlurl"),
                    )
                )
            }
        }
        return result
    }

    /**
     * 从 HTML 页面里发现订阅地址：
     * `<link rel="alternate" type="application/rss+xml" href="/feed">`。
     *
     * @param html 页面 HTML
     * @param baseUrl 页面地址（相对地址据此补全）
     */
    fun discoverFeedUrl(html: String, baseUrl: String = ""): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val candidates = doc.select("link[href]").mapNotNull { link ->
            val rel = link.attr("rel").lowercase()
            if (!rel.contains("alternate")) return@mapNotNull null
            val type = link.attr("type").lowercase()
            val isFeedType = type.contains("rss") || type.contains("atom") || type.contains("xml")
            if (!isFeedType) return@mapNotNull null
            link.absUrl("href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() }
        }
        // 再兜一层：页面上直接写着 feed 链接（常见于国内博客）
        if (candidates.isNotEmpty()) return candidates.first()
        val anchor = doc.select("a[href]").firstOrNull { a ->
            val href = a.attr("href").lowercase()
            href.contains("feed") || href.contains("rss") || href.endsWith(".xml")
        }
        return anchor?.absUrl("href")?.takeIf { it.isNotBlank() }
    }

    // ==================== 各格式解析 ====================

    /** RSS 2.0：`<rss><channel><title>…<item>…` */
    private fun parseRss2(root: Element, baseUrl: String): ParsedFeed {
        val channel = childMatching(root, "channel") ?: root
        val title = text(channel, "title").ifBlank { hostOf(baseUrl) }
        val siteUrl = resolve(baseUrl, text(channel, "link"))
        val description = text(channel, "description")
        val icon = channelIcon(channel, siteUrl, baseUrl)

        val items = channel.children().filter { it.localNameLower() == "item" }
        val articles = items.map { parseRssItem(it, siteUrl.ifBlank { baseUrl }, baseUrl) }

        return ParsedFeed(
            title = title,
            siteUrl = siteUrl,
            description = description,
            iconUrl = icon,
            format = "rss2",
            articles = articles,
        )
    }

    private fun parseRssItem(item: Element, base: String, feedUrl: String): ParsedArticle {
        val rawTitle = text(item, "title")
        val link = resolve(base.ifBlank { feedUrl }, text(item, "link"))
            .ifBlank {
                // guid 声明为链接时可直接当链接用
                val g = text(item, "guid")
                if (g.startsWith("http")) g else ""
            }
        val html = rawInner(item, "encoded", "content")
            .ifBlank { rawInner(item, "description", "summary") }
        val plain = RssContent.plainText(html, SUMMARY_LIMIT)
        val pubDate = parseDate(
            listOfNotNull(
                textOrNull(item, "pubDate"),
                textOrNull(item, "date"),          // dc:date
                textOrNull(item, "published"),
                textOrNull(item, "updated"),
            ).firstOrNull()
        )
        val title = rawTitle.ifBlank { plain.take(30) }.ifBlank { "（无标题）" }
        val author = listOfNotNull(
            textOrNull(item, "creator"),           // dc:creator
            textOrNull(item, "author"),
        ).firstOrNull().orEmpty().let { stripAuthor(it) }

        val guid = text(item, "guid").ifBlank { text(item, "id") }.ifBlank { link }
            .ifBlank { "$title|$pubDate" }

        return ParsedArticle(
            guid = guid,
            title = title,
            link = link,
            author = author,
            pubDate = pubDate,
            summary = plain,
            contentHtml = html.ifBlank { "<p>${escapeHtml(plain)}</p>" },
            imageUrl = itemImage(item, html, base.ifBlank { feedUrl }),
        )
    }

    /** Atom 1.0：`<feed><entry>` */
    private fun parseAtom(root: Element, baseUrl: String): ParsedFeed {
        val title = text(root, "title").ifBlank { hostOf(baseUrl) }
        val siteUrl = atomLink(root) ?: ""
        val description = text(root, "subtitle")
        val icon = listOfNotNull(
            textOrNull(root, "icon"),
            textOrNull(root, "logo"),
        ).firstOrNull().orEmpty().let { resolve(baseUrl, it) }
            .ifBlank { favicon(siteUrl.ifBlank { baseUrl }) }

        val entries = root.children().filter { it.localNameLower() == "entry" }
        val articles = entries.map { entry ->
            val link = atomLink(entry) ?: ""
            val html = rawInner(entry, "content", "encoded")
                .ifBlank { rawInner(entry, "summary") }
            val plain = RssContent.plainText(html, SUMMARY_LIMIT)
            val pubDate = parseDate(
                listOfNotNull(
                    textOrNull(entry, "published"),
                    textOrNull(entry, "updated"),
                ).firstOrNull()
            )
            val authorEl = childMatching(entry, "author")
            val author = authorEl?.let { text(it, "name") }
                ?: listOfNotNull(textOrNull(entry, "creator")).firstOrNull().orEmpty()
            val title2 = text(entry, "title").ifBlank { plain.take(30) }.ifBlank { "（无标题）" }
            val guid = text(entry, "id").ifBlank { link }.ifBlank { "$title2|$pubDate" }

            ParsedArticle(
                guid = guid,
                title = title2,
                link = link,
                author = stripAuthor(author),
                pubDate = pubDate,
                summary = plain,
                contentHtml = html.ifBlank { "<p>${escapeHtml(plain)}</p>" },
                imageUrl = itemImage(entry, html, baseUrl),
            )
        }

        return ParsedFeed(
            title = title,
            siteUrl = siteUrl,
            description = description,
            iconUrl = icon,
            format = "atom",
            articles = articles,
        )
    }

    /** RDF / RSS 1.0：`<rdf:RDF>` 下同时挂着 `<channel>` 与顶层 `<item>` */
    private fun parseRdf(root: Element, baseUrl: String): ParsedFeed {
        val channel = childMatching(root, "channel")
        val title = channel?.let { text(it, "title") }.orEmpty().ifBlank { hostOf(baseUrl) }
        val siteUrl = channel?.let { resolve(baseUrl, text(it, "link")) }.orEmpty()
        val description = channel?.let { text(it, "description") }.orEmpty()
        val icon = channelIcon(channel, siteUrl, baseUrl)

        val items = root.children().filter { it.localNameLower() == "item" }
        val articles = items.map { parseRssItem(it, siteUrl.ifBlank { baseUrl }, baseUrl) }

        return ParsedFeed(
            title = title,
            siteUrl = siteUrl,
            description = description,
            iconUrl = icon,
            format = "rdf",
            articles = articles,
        )
    }

    // ==================== 取值工具 ====================

    /** 标签名是否命中（忽略命名空间前缀，忽略大小写） */
    private fun matches(tag: String, name: String): Boolean {
        val t = tag.lowercase()
        val n = name.lowercase()
        return t == n || t.endsWith(":$n")
    }

    /** 直接子元素里第一个命中任一名称的元素 */
    private fun childMatching(el: Element, vararg names: String): Element? =
        el.children().firstOrNull { c -> names.any { matches(c.tagName(), it) } }

    /** 取元素纯文本（含 CDATA）；多个命中元素时拼接 */
    private fun text(el: Element, vararg names: String): String =
        el.children().filter { c -> names.any { matches(c.tagName(), it) } }
            .joinToString("\n") { it.text().trim() }
            .trim()

    private fun textOrNull(el: Element, name: String): String? =
        text(el, name).takeIf { it.isNotBlank() }

    /**
     * 取元素的「内容原文」：CDATA / 文本节点按原文返回，子元素按 outerHtml 返回。
     *
     * RSS 的正文两种写法都要吃下：
     * - `<description><![CDATA[<p>hi</p>]]></description>`（CDATA 包裹 HTML）
     * - `<description>&lt;p&gt;hi&lt;/p&gt;</description>`（实体转义 HTML）
     */
    private fun rawInner(el: Element, vararg names: String): String {
        val target = childMatching(el, *names) ?: return ""
        val sb = StringBuilder()
        target.childNodes().forEach { node ->
            when (node) {
                // CDataNode 是 TextNode 的子类，两者都取「原文」（不做空白归一化）
                is org.jsoup.nodes.TextNode -> sb.append(node.wholeText)
                is Element -> sb.append(node.outerHtml())
                else -> sb.append(node.outerHtml())
            }
        }
        return sb.toString().trim()
    }

    /** 取第一个非空的属性（OPML 的属性名大小写不统一） */
    private fun firstAttr(el: Element, vararg names: String): String {
        names.forEach { name ->
            val v = el.attr(name).trim()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    /** Atom 的链接：优先 rel="alternate" 或未声明 rel，跳过 self / enclosure */
    private fun atomLink(el: Element): String? {
        val links = el.children().filter { matches(it.tagName(), "link") }
        val preferred = links.firstOrNull { c ->
            val rel = c.attr("rel").lowercase()
            rel.isBlank() || rel == "alternate"
        } ?: return null
        return preferred.absUrl("href").ifBlank { resolve(el.baseUri(), preferred.attr("href")) }
            .takeIf { it.isNotBlank() }
    }

    /** 频道图标：RSS 的 `<image><url>` → Atom 的 icon/logo → 站点 favicon */
    private fun channelIcon(channel: Element?, siteUrl: String, baseUrl: String): String {
        val imageEl = channel?.let { childMatching(it, "image") }
        val fromImage = imageEl?.let { text(it, "url") }.orEmpty()
        if (fromImage.isNotBlank()) return resolve(baseUrl, fromImage)
        val fromChannelIcon = channel?.let { text(it, "icon") }.orEmpty()
        if (fromChannelIcon.isNotBlank()) return resolve(baseUrl, fromChannelIcon)
        return favicon(siteUrl.ifBlank { baseUrl })
    }

    /** 文章配图：enclosure / media:content / media:thumbnail / 正文首图 */
    private fun itemImage(item: Element, contentHtml: String, base: String): String {
        val enclosure = item.children().firstOrNull { matches(it.tagName(), "enclosure") }
        val encUrl = enclosure?.attr("url").orEmpty()
        if (encUrl.isNotBlank() && !enclosure!!.attr("type").lowercase().startsWith("audio")) {
            return resolve(base, encUrl)
        }
        val media = childMatching(item, "content", "thumbnail")
        val mediaUrl = media?.attr("url").orEmpty()
        if (mediaUrl.isNotBlank()) return resolve(base, mediaUrl)

        return RssContent.firstImage(contentHtml, base)
    }

    /** 从站点地址推导 favicon（部分源不提供图标） */
    private fun favicon(siteUrl: String): String {
        if (siteUrl.isBlank()) return ""
        val origin = runCatching {
            val uri = java.net.URI(siteUrl)
            "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        }.getOrNull() ?: return ""
        return "$origin/favicon.ico"
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

    /** 作者字段清洗：RSS 里常见 "xxx@example.com (昵称)" 形态 */
    private fun stripAuthor(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        val paren = Regex("""\(([^)]+)\)""").find(s)?.groupValues?.getOrNull(1)
        if (paren != null) return paren.trim()
        return s.substringBefore(" <").trim()
    }

    /** 相对地址补全 */
    private fun resolve(base: String, url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (base.isBlank()) return u
        return runCatching { java.net.URI(base).resolve(u).toString() }.getOrDefault(u)
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun Element.localNameLower(): String = tagName().substringAfterLast(':').lowercase()

    // ==================== 时间解析 ====================

    private val DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, dd MMM yyyy HH:mm z",
        "dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm Z",
        "EEE MMM dd HH:mm:ss Z yyyy",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
    )

    /**
     * 解析时间。支持 RFC1123（`Tue, 13 Oct 2026 12:00:00 GMT`）、ISO8601、
     * `yyyy-MM-dd HH:mm:ss` 以及纯数字时间戳（秒 / 毫秒）。失败返回 0。
     */
    fun parseDate(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0L
        s.toLongOrNull()?.let { n ->
            return if (n > 100_000_000_000L) n else n * 1000L
        }
        DATE_PATTERNS.forEach { pattern ->
            val parsed = runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.isLenient = true
                fmt.parse(s)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }
}
