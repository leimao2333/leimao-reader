package com.novelreader

import com.novelreader.core.rss.RssBlock
import com.novelreader.core.rss.RssContent
import com.novelreader.core.rss.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * RSS 解析内核单测（v1.3.1.0）。
 *
 * 覆盖三种订阅格式（RSS 2.0 / Atom / RDF）、OPML、页面订阅地址发现、
 * 时间解析与正文块转换 —— 这些都是「真实订阅源五花八门」的容错点。
 */
class RssParserTest {

    // ==================== 格式识别 ====================

    @Test
    fun `识别 RSS 2_0 根节点`() {
        assertEquals("rss", RssParser.detectRootTag(RSS2))
        assertTrue(RssParser.isFeed(RSS2))
    }

    @Test
    fun `识别 Atom 与 RDF 根节点`() {
        assertEquals("feed", RssParser.detectRootTag(ATOM))
        assertEquals("rdf", RssParser.detectRootTag(RDF))
        assertTrue(RssParser.isFeed(ATOM))
        assertTrue(RssParser.isFeed(RDF))
    }

    @Test
    fun `识别 OPML 与普通网页`() {
        assertEquals("opml", RssParser.detectRootTag(OPML))
        assertTrue(RssParser.isOpml(OPML))
        assertEquals("html", RssParser.detectRootTag("<html><body>hi</body></html>"))
        assertTrue(!RssParser.isFeed("<html><body>hi</body></html>"))
    }

    @Test
    fun `带 XML 声明与注释时仍能识别根节点`() {
        val raw = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- feed -->\n<rss version=\"2.0\"><channel></channel></rss>"
        assertEquals("rss", RssParser.detectRootTag(raw))
    }

    // ==================== RSS 2.0 ====================

    @Test
    fun `解析 RSS 2_0 频道与文章`() {
        val feed = RssParser.parse(RSS2, "https://blog.example.com/feed.xml")

        assertEquals("示例博客", feed.title)
        assertEquals("rss2", feed.format)
        assertEquals("https://blog.example.com/", feed.siteUrl)
        assertEquals(2, feed.articles.size)

        val first = feed.articles[0]
        assertEquals("第一篇：中文标题", first.title)
        assertEquals("https://blog.example.com/p/1", first.link)
        assertEquals("guid-1", first.guid)
        assertEquals("小明", first.author)
        assertTrue(first.pubDate > 0)
        // CDATA 包裹的 content:encoded 原样取出
        assertTrue(first.contentHtml.contains("<p>正文第一段</p>"))
        assertTrue(first.summary.contains("正文第一段"))
        assertTrue(!first.summary.contains("<p>"))
        assertEquals("https://blog.example.com/img/cover.jpg", first.imageUrl)
    }

    @Test
    fun `相对地址按源地址补全`() {
        val feed = RssParser.parse(RSS2_RELATIVE, "https://blog.example.com/feed.xml")
        val article = feed.articles.first()
        assertEquals("https://blog.example.com/p/rel", article.link)
        assertEquals("https://blog.example.com/img/rel.png", article.imageUrl)
    }

    @Test
    fun `description 实体转义时也能解析出正文`() {
        val feed = RssParser.parse(RSS2_ESCAPED, "https://blog.example.com/feed.xml")
        val article = feed.articles.first()
        assertTrue(article.contentHtml.contains("<p>转义正文</p>"))
        assertTrue(article.summary.contains("转义正文"))
    }

    @Test
    fun `guid 为链接且无 link 时回退用 guid`() {
        val feed = RssParser.parse(RSS2_GUID_ONLY, "https://blog.example.com/feed.xml")
        assertEquals("https://blog.example.com/p/guid", feed.articles.first().link)
    }

    @Test
    fun `标题缺失时用正文前段兜底`() {
        val feed = RssParser.parse(RSS2_NO_TITLE, "https://blog.example.com/feed.xml")
        assertTrue(feed.articles.first().title.startsWith("无标题文章"))
    }

    @Test
    fun `频道图标缺失时回退站点 favicon`() {
        val feed = RssParser.parse(RSS2_RELATIVE, "https://blog.example.com/feed.xml")
        assertEquals("https://blog.example.com/favicon.ico", feed.iconUrl)
    }

    // ==================== Atom / RDF ====================

    @Test
    fun `解析 Atom 条目`() {
        val feed = RssParser.parse(ATOM, "https://atom.example.com/feed")

        assertEquals("Atom 示例", feed.title)
        assertEquals("atom", feed.format)
        assertEquals("https://atom.example.com/", feed.siteUrl)
        assertEquals(1, feed.articles.size)

        val entry = feed.articles.first()
        assertEquals("Atom 文章", entry.title)
        assertEquals("https://atom.example.com/p/1", entry.link)   // rel=alternate 优先
        assertEquals("tag:atom.example.com,2026:1", entry.guid)
        assertEquals("作者A", entry.author)
        assertTrue(entry.pubDate > 0)
        assertTrue(entry.contentHtml.contains("Atom 正文"))
    }

    @Test
    fun `解析 RDF 顶层 item 与 dc 命名空间字段`() {
        val feed = RssParser.parse(RDF, "https://rdf.example.com/feed.rdf")

        assertEquals("RDF 示例", feed.title)
        assertEquals("rdf", feed.format)
        assertEquals(1, feed.articles.size)

        val item = feed.articles.first()
        assertEquals("RDF 文章", item.title)
        assertEquals("https://rdf.example.com/p/1", item.link)
        assertEquals("RDF 作者", item.author)
        assertTrue(item.pubDate > 0)
    }

    // ==================== OPML 与页面发现 ====================

    @Test
    fun `解析 OPML 订阅列表`() {
        val outlines = RssParser.parseOpml(OPML)
        assertEquals(2, outlines.size)
        assertEquals("博客A", outlines[0].title)
        assertEquals("https://a.example.com/feed", outlines[0].xmlUrl)
        assertEquals("https://b.example.com/feed", outlines[1].xmlUrl)
    }

    @Test
    fun `从网页发现订阅地址`() {
        val html = """
            <html><head>
              <link rel="stylesheet" href="/style.css">
              <link rel="alternate" type="application/rss+xml" title="RSS" href="/feed.xml">
            </head><body></body></html>
        """.trimIndent()
        assertEquals(
            "https://blog.example.com/feed.xml",
            RssParser.discoverFeedUrl(html, "https://blog.example.com/post/1"),
        )
    }

    @Test
    fun `网页没有订阅链接时返回空`() {
        val html = "<html><head><link rel=\"stylesheet\" href=\"/a.css\"></head><body>hi</body></html>"
        assertEquals(null, RssParser.discoverFeedUrl(html, "https://blog.example.com/"))
    }

    // ==================== 时间解析 ====================

    @Test
    fun `解析 RFC1123 格式时间`() {
        val millis = RssParser.parseDate("Tue, 13 Oct 2026 04:00:00 GMT")
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.parse("2026-10-13 04:00:00")!!.time
        assertEquals(expected, millis)
    }

    @Test
    fun `解析 ISO8601 时间`() {
        val millis = RssParser.parseDate("2026-10-13T04:00:00Z")
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.parse("2026-10-13 04:00:00")!!.time
        assertEquals(expected, millis)
    }

    @Test
    fun `解析纯数字时间戳与简单格式`() {
        assertEquals(1_760_000_000_000L, RssParser.parseDate("1760000000"))
        assertEquals(1_760_000_000_000L, RssParser.parseDate("1760000000000"))
        assertTrue(RssParser.parseDate("2026-10-13 12:30:00") > 0)
        assertEquals(0L, RssParser.parseDate(null))
        assertEquals(0L, RssParser.parseDate("不是时间"))
    }

    // ==================== 正文块 ====================

    @Test
    fun `正文按块拆分并保留段落与图片顺序`() {
        val html = """
            <div>
              <p>第一段</p>
              <p>第二段<br>换行</p>
              <img src="/img/a.png">
              <h2>小标题</h2>
            </div>
        """.trimIndent()
        val blocks = RssContent.blocks(html, "https://blog.example.com/p/1")

        val paragraphs = blocks.filterIsInstance<RssBlock.Paragraph>().map { it.text }
        assertTrue(paragraphs.contains("第一段"))
        assertTrue(paragraphs.any { it.contains("第二段") && it.contains("换行") })
        assertTrue(paragraphs.contains("小标题"))

        val images = blocks.filterIsInstance<RssBlock.Image>().map { it.url }
        assertEquals(listOf("https://blog.example.com/img/a.png"), images)
    }

    @Test
    fun `图片优先取懒加载属性`() {
        val html = """<p>正文</p><img src="placeholder.gif" data-src="/img/real.png">"""
        val images = RssContent.blocks(html, "https://blog.example.com/p/1")
            .filterIsInstance<RssBlock.Image>()
        assertEquals(listOf("https://blog.example.com/img/real.png"), images.map { it.url })
    }

    @Test
    fun `script 与 style 被丢弃`() {
        val html = "<div><p>正文</p><script>var a=1;</script><style>p{color:red}</style></div>"
        val text = RssContent.plainText(html)
        assertEquals("正文", text)
    }

    @Test
    fun `纯文本正文按换行分段`() {
        val blocks = RssContent.blocks("第一行\n\n第二行")
        assertEquals(2, blocks.size)
        assertEquals("第一行", (blocks[0] as RssBlock.Paragraph).text)
    }

    @Test
    fun `纯文本摘要按长度截断`() {
        val html = "<p>${"字".repeat(300)}</p>"
        val text = RssContent.plainText(html, 200)
        assertEquals(201, text.length)   // 200 字 + 省略号
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun `首图提取`() {
        assertEquals(
            "https://blog.example.com/img/x.jpg",
            RssContent.firstImage("<p>a</p><img src=\"/img/x.jpg\">", "https://blog.example.com/p/1"),
        )
        assertEquals("", RssContent.firstImage("<p>没有图</p>", "https://blog.example.com/p/1"))
    }

    @Test
    fun `非法内容不抛异常`() {
        assertTrue(RssContent.blocks("").isEmpty())
        assertTrue(RssContent.blocks("<div").size <= 1)
    }

    // ==================== 异常路径 ====================

    @Test
    fun `非订阅源解析抛可读异常`() {
        val error = runCatching { RssParser.parse("<html><body>hi</body></html>") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
    }

    /**
     * 空的 RSS 是**合法**的，不能当成解析失败（v1.8.4.2）。
     *
     * 线上原型：PubMed 的 `rss/search/<id>/` 返回 200 + 合法 `<rss><channel>`，
     * 但一个 `<item>` 都没有 —— 检索式本身没结果（E-utilities 查同一个词也是
     * `count: 0`）。用户看到的就是「订阅成功但没文章」，若把这种源判成失败，
     * 反而会引导他去反复重订阅。
     */
    @Test
    fun `空频道不抛异常且保留源标题`() {
        val feed = RssParser.parse(EMPTY_RSS2, "https://pubmed.ncbi.nlm.nih.gov/rss/search/x/")
        assertEquals("rss2", feed.format)
        assertTrue(feed.articles.isEmpty())
        assertEquals("Intrinsic physicochemical activity at microplastic-water interfaces", feed.title)
        assertTrue(feed.description.contains("Latest results from PubMed"))
        // 没有 <link> 也不该崩，站点地址留空即可
        assertEquals("", feed.siteUrl)
    }

    companion object {
        /** PubMed 空 feed 的真实响应（2026-10-20 抓取，1366 字节，0 个 item） */
        private val EMPTY_RSS2 = """
            <?xml version='1.0' encoding='UTF-8'?>
            <rss xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:atom="http://www.w3.org/2005/Atom"
                 xmlns:content="http://purl.org/rss/1.0/modules/content/" version="2.0">
              <channel>
                <title>Intrinsic physicochemical activity at microplastic-water interfaces</title>
                <description>Intrinsic physicochemical activity at microplastic-water interfaces: Latest results from PubMed</description>
                <docs>http://www.rssboard.org/rss-specification</docs>
                <generator>PubMed RSS feeds (2.20.1)</generator>
                <language>en</language>
                <lastBuildDate>Tue, 20 Oct 2026 05:43:57 +0000</lastBuildDate>
                <ttl>120</ttl>
              </channel>
            </rss>
        """.trimIndent()

        private val RSS2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>示例博客</title>
                <link>https://blog.example.com/</link>
                <description>博客描述</description>
                <image><url>https://blog.example.com/logo.png</url></image>
                <item>
                  <title>第一篇：中文标题</title>
                  <link>https://blog.example.com/p/1</link>
                  <guid>guid-1</guid>
                  <dc:creator>小明</dc:creator>
                  <pubDate>Tue, 13 Oct 2026 04:00:00 GMT</pubDate>
                  <description><![CDATA[<p>正文第一段</p><p>正文第二段</p>]]></description>
                  <content:encoded><![CDATA[<p>正文第一段</p><p>正文第二段</p><img src="https://blog.example.com/img/cover.jpg">]]></content:encoded>
                </item>
                <item>
                  <title>第二篇</title>
                  <link>https://blog.example.com/p/2</link>
                  <guid>guid-2</guid>
                  <pubDate>Tue, 13 Oct 2026 05:00:00 GMT</pubDate>
                  <description>纯文本描述</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        private val RSS2_RELATIVE = """
            <rss version="2.0"><channel>
              <title>相对地址博客</title>
              <link>/</link>
              <item>
                <title>相对地址文章</title>
                <link>/p/rel</link>
                <guid>rel-1</guid>
                <pubDate>Tue, 13 Oct 2026 04:00:00 GMT</pubDate>
                <description><![CDATA[<p>正文</p><img src="/img/rel.png">]]></description>
              </item>
            </channel></rss>
        """.trimIndent()

        private val RSS2_ESCAPED = """
            <rss version="2.0"><channel>
              <title>转义源</title>
              <item>
                <title>转义文章</title>
                <link>https://blog.example.com/p/esc</link>
                <guid>esc-1</guid>
                <description>&lt;p&gt;转义正文&lt;/p&gt;</description>
              </item>
            </channel></rss>
        """.trimIndent()

        private val RSS2_GUID_ONLY = """
            <rss version="2.0"><channel>
              <title>guid 源</title>
              <item>
                <title>guid 文章</title>
                <guid isPermaLink="true">https://blog.example.com/p/guid</guid>
                <description>正文</description>
              </item>
            </channel></rss>
        """.trimIndent()

        private val RSS2_NO_TITLE = """
            <rss version="2.0"><channel>
              <title>无标题源</title>
              <item>
                <link>https://blog.example.com/p/notitle</link>
                <guid>nt-1</guid>
                <description>无标题文章的第一段内容在这里</description>
              </item>
            </channel></rss>
        """.trimIndent()

        private val ATOM = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom 示例</title>
              <subtitle>订阅描述</subtitle>
              <link rel="alternate" href="https://atom.example.com/"/>
              <link rel="self" href="https://atom.example.com/feed"/>
              <icon>https://atom.example.com/icon.png</icon>
              <entry>
                <title>Atom 文章</title>
                <link rel="self" href="https://atom.example.com/api/1"/>
                <link rel="alternate" href="https://atom.example.com/p/1"/>
                <id>tag:atom.example.com,2026:1</id>
                <updated>2026-10-13T04:00:00Z</updated>
                <author><name>作者A</name></author>
                <content type="html">&lt;p&gt;Atom 正文&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        private val RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns="http://purl.org/rss/1.0/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://rdf.example.com/">
                <title>RDF 示例</title>
                <link>https://rdf.example.com/</link>
                <description>RDF 描述</description>
              </channel>
              <item rdf:about="https://rdf.example.com/p/1">
                <title>RDF 文章</title>
                <link>https://rdf.example.com/p/1</link>
                <dc:creator>RDF 作者</dc:creator>
                <dc:date>2026-10-13T04:00:00Z</dc:date>
                <description><![CDATA[<p>RDF 正文</p>]]></description>
              </item>
            </rdf:RDF>
        """.trimIndent()

        private val OPML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <head><title>我的订阅</title></head>
              <body>
                <outline text="分组">
                  <outline type="rss" text="博客A" title="博客A" xmlUrl="https://a.example.com/feed" htmlUrl="https://a.example.com/"/>
                  <outline type="rss" text="博客B" title="博客B" xmlUrl="https://b.example.com/feed" htmlUrl="https://b.example.com/"/>
                </outline>
              </body>
            </opml>
        """.trimIndent()
    }
}
