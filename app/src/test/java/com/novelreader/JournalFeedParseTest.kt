package com.novelreader

import com.novelreader.core.rss.PaperMeta
import com.novelreader.core.rss.PaperMetaParser
import com.novelreader.core.rss.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 期刊 RSS 元信息解析单测（v1.8.5.0）。
 *
 * 样例是**真实抓来的**学术出版社 feed（Science / Nature / PNAS，Prism RDF），
 * 存放在 `src/test/resources/rss/`。用真实报文比手写 fixture 更靠得住 ——
 * 这些源的字段名、实体转义、CDATA 写法都极其不统一。
 */
class JournalFeedParseTest {

    private fun load(name: String): String =
        java.io.File("src/test/resources/rss/$name").readText()

    // ==================== Science（Prism 字段最全） ====================

    @Test
    fun `Science feed 解析出卷期页 DOI 作者 类型`() {
        val feed = RssParser.parse(load("science.xml"), "https://www.science.org/loi/science?af=R")
        assertEquals("rdf", feed.format)
        assertTrue("Science feed 应该有条目", feed.articles.size >= 3)

        val first = feed.articles.first()
        assertEquals("An elegy for the unsung world-builders", first.title)
        assertTrue(first.link.contains("10.1126/science.aek0152"))

        val meta = first.paperMeta
        assertEquals("10.1126/science.aek0152", meta.doi)
        assertEquals("Science", meta.journal)
        assertEquals("394", meta.volume)
        assertEquals("6821", meta.issue)
        assertEquals("278", meta.firstPage)
        assertEquals("2026", meta.year.toString())
        assertEquals("Books et al.", meta.articleType)
        assertEquals(listOf("Mary Ellen Hannibal"), meta.authors)
        assertEquals("Science, 394(6821): 278 (2026)", meta.citationLine())
    }

    @Test
    fun `Science 第二条取起始结束页`() {
        val feed = RssParser.parse(load("science.xml"), "https://www.science.org/loi/science?af=R")
        val withPages = feed.articles.firstOrNull { it.paperMeta.firstPage.isNotBlank() && it.paperMeta.lastPage.isNotBlank() && it.paperMeta.firstPage != it.paperMeta.lastPage }
        if (withPages != null) {
            assertTrue(withPages.paperMeta.pages.contains("-"))
        }
        // 每条都应解析出 DOI（Science feed 每条都带 prism:doi）
        assertTrue(feed.articles.all { it.paperMeta.doi.startsWith("10.1126/") })
    }

    // ==================== Nature（引用行兜底，无 prism 卷期） ====================

    @Test
    fun `Nature feed 从引用行抠出期刊 年 与 DOI`() {
        val feed = RssParser.parse(load("nature.xml"), "https://www.nature.com/nature.rss")
        assertTrue(feed.articles.size >= 3)

        val first = feed.articles.first()
        val meta = first.paperMeta
        // Nature 的 d41586 条目没有 prism:volume，期刊名与 DOI 来自引用行
        assertEquals("10.1038/d41586-026-03317-3", meta.doi)
        assertEquals("Nature", meta.journal)
        assertEquals(2026, meta.year)
        assertTrue(first.title.contains("Toxic chemicals in fentanyl"))
    }

    // ==================== PNAS（卷期 + 引用行） ====================

    @Test
    fun `PNAS feed 解析出全称期刊与卷期`() {
        val feed = RssParser.parse(load("pnas.xml"), "https://www.pnas.org/action/showFeed?type=etoc&feed=rss&jc=pnas")
        assertTrue(feed.articles.size >= 3)

        val first = feed.articles.first()
        val meta = first.paperMeta
        assertEquals("10.1073/iti4126123", meta.doi)
        assertEquals("Proceedings of the National Academy of Sciences", meta.journal)
        assertEquals("123", meta.volume)
        assertEquals("41", meta.issue)
        assertEquals(2026, meta.year)
    }

    // ==================== 引用行解析（纯逻辑） ====================

    @Test
    fun `引用行 卷期页 与 年月`() {
        val meta = PaperMetaParser.fromCitationLine(
            "Science, Volume 394, Issue 6821, Page 278-278, October 2026. <br/>"
        )
        assertEquals("Science", meta.journal)
        assertEquals("394", meta.volume)
        assertEquals("6821", meta.issue)
        assertEquals("278", meta.firstPage)
        assertEquals("278", meta.lastPage)
        assertEquals(2026, meta.year)
    }

    @Test
    fun `引用行 Pages 复数 与 页码区间`() {
        val meta = PaperMetaParser.fromCitationLine(
            "Proceedings of the National Academy of Sciences, Volume 123, Issue 41, Pages 1234-1256, October 2026."
        )
        assertEquals("1234", meta.firstPage)
        assertEquals("1256", meta.lastPage)
        assertEquals("1234-1256", meta.pages)
    }

    @Test
    fun `Nature 引用行 Published online 与内嵌 DOI`() {
        val meta = PaperMetaParser.fromCitationLine(
            "Nature, Published online: 19 October 2026; doi:10.1038/d41586-026-03317-3"
        )
        assertEquals("Nature", meta.journal)
        assertEquals(2026, meta.year)
        assertEquals("10.1038/d41586-026-03317-3", meta.doi)
    }

    @Test
    fun `期刊名带 The 前缀会被剥掉`() {
        val meta = PaperMetaParser.fromCitationLine(
            "The Lancet, Volume 6736, Issue 1, Pages 1-10, 2026."
        )
        assertEquals("Lancet", meta.journal)
    }

    // ==================== DOI 抽取（纯逻辑） ====================

    @Test
    fun `DOI 从链接 引用行 文本 抽取`() {
        assertEquals("10.1126/science.aek0152", PaperMetaParser.doiFrom("https://www.science.org/doi/abs/10.1126/science.aek0152?af=R"))
        assertEquals("10.1038/d41586-026-03317-3", PaperMetaParser.doiFrom("doi:10.1038/d41586-026-03317-3</a>"))
        assertEquals("10.1073/pnas.1234567123", PaperMetaParser.doiFrom("see 10.1073/pnas.1234567123."))
        assertEquals("", PaperMetaParser.doiFrom("没有 DOI 的普通文章"))
    }

    @Test
    fun `DOI 规范化 剥离前缀 尾标点 与追踪参数`() {
        assertEquals("10.1126/sciadv.abc1234", PaperMetaParser.normalizeDoi("doi:10.1126/sciadv.abc1234."))
        assertEquals("10.1126/sciadv.abc1234", PaperMetaParser.normalizeDoi("https://doi.org/10.1126/sciadv.abc1234?"))
        assertEquals("10.1126/sciadv.abc1234", PaperMetaParser.normalizeDoi("10.1126/sciadv.abc1234?af=R)"))
        assertEquals("10.1038/s41586-026-00001-1", PaperMetaParser.normalizeDoi("DOI:10.1038/s41586-026-00001-1"))
    }

    // ==================== 作者拆分 ====================

    @Test
    fun `作者按分号 换行 and 拆分`() {
        assertEquals(listOf("Zhang San", "Li Si"), PaperMetaParser.splitAuthors("Zhang San; Li Si"))
        assertEquals(listOf("Zhang San", "Li Si"), PaperMetaParser.splitAuthors("Zhang San\nLi Si"))
        assertEquals(listOf("Zhang San", "Li Si"), PaperMetaParser.splitAuthors("Zhang San and Li Si"))
        assertEquals(listOf("Zhang San", "Li Si"), PaperMetaParser.splitAuthors("Zhang San & Li Si"))
        assertEquals(listOf("Zhang, San", PaperMetaParser.splitAuthors("Zhang, San").size.toString()), listOf("Zhang, San", "1"))
    }

    @Test
    fun `作者为空或超长时安全`() {
        assertEquals(emptyList<String>(), PaperMetaParser.splitAuthors(""))
        assertEquals(emptyList<String>(), PaperMetaParser.splitAuthors("   "))
        assertTrue(PaperMetaParser.splitAuthors("x".repeat(200)).isEmpty())
    }

    // ==================== 组合入口 ====================

    @Test
    fun `fromFeedFields 字段优先 引用行兜底`() {
        val meta = PaperMetaParser.fromFeedFields(
            doi = "10.1126/sciadv.abc1234",
            publicationName = " Science ",
            volume = "12",
            issue = "3",
            firstPage = "e100",
            lastPage = "e115",
            articleType = "Research Article",
            creator = "Zhang San; Li Si",
            citationText = "",
            link = "https://www.science.org/doi/10.1126/sciadv.abc1234",
            pubDateMs = 0L,
        )
        assertEquals("10.1126/sciadv.abc1234", meta.doi)
        assertEquals("Science", meta.journal)
        assertEquals("12", meta.volume)
        assertEquals("e100-e115", meta.pages)
        // 没有年份来源时应为 0，不猜
        assertEquals(0, meta.year)
        assertTrue(meta.hasContent)
    }

    @Test
    fun `非期刊条目不产生题录`() {
        val meta = PaperMetaParser.fromFeedFields(
            doi = "",
            publicationName = "",
            creator = "路人甲",
            citationText = "今天天气不错，适合写代码。",
            link = "https://blog.example.com/post/1",
            pubDateMs = 1_700_000_000_000L,
        )
        assertFalse(meta.hasContent)
        assertEquals(emptyList<String>(), listOf(meta.journal, meta.volume, meta.issue).filter { it.isNotBlank() })
    }

    @Test
    fun `空 PaperMeta 判定`() {
        assertFalse(PaperMeta().hasContent)
        assertTrue(PaperMeta(doi = "10.1/x").hasContent)
        assertTrue(PaperMeta(journal = "Nature").hasContent)
        // 只有年份 / 作者不算题录：博客文章也有这两项
        assertFalse(PaperMeta(year = 2026, authors = listOf("某人")).hasContent)
    }
}
