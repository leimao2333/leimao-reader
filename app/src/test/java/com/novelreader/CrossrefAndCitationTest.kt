package com.novelreader

import com.novelreader.core.rss.CrossrefJson
import com.novelreader.core.rss.PaperCitation
import com.novelreader.core.rss.PaperMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crossref 响应解析 + 引用导出单测（v1.8.5.0）。
 *
 * JSON 样例是**真实响应**（`src/test/resources/crossref/`），
 * 因为 Crossref 的坑全在细节里：`title` 是数组、`author` 可能只有 `family`、
 * 日期可能是 `published` / `published-online` / `issued` 三种键之一。
 */
class CrossrefAndCitationTest {

    private fun load(name: String): String =
        java.io.File("src/test/resources/crossref/$name").readText()

    // ==================== /works 解析 ====================

    @Test
    fun `真实 Nature works 响应解析出论文题录`() {
        val works = CrossrefJson.parseWorks(load("works_nature.json"))
        assertTrue("应至少解析出 1 篇", works.isNotEmpty())

        val first = works.first()
        assertEquals("10.1038/d41586-026-03351-1", first.doi)
        assertEquals("Nobel-prizewinning breakthroughs can emerge from neglected organisms", first.title)
        assertEquals("Nature", first.journal)
        assertEquals("658", first.volume)
        assertEquals("8137", first.issue)
        assertEquals("1094", first.firstPage)
        assertEquals(2026, first.year)
        assertEquals("journal-article", first.type)
        assertEquals(listOf("Zora Zhang", "Kang Lou", "Zhenlong Wang"), first.authors)
        assertEquals("https://doi.org/10.1038/d41586-026-03351-1", first.url)
    }

    @Test
    fun `works 转题录后可用于引用导出`() {
        val meta = CrossrefJson.parseWorks(load("works_nature.json")).first().toPaperMeta()
        assertEquals("10.1038/d41586-026-03351-1", meta.doi)
        assertEquals("Nature, 658(8137): 1094 (2026)", meta.citationLine())
        assertTrue(meta.hasContent)
    }

    @Test
    fun `works 解析容错 空 JSON 与坏 JSON 不崩`() {
        assertEquals(0, CrossrefJson.parseWorks("").size)
        assertEquals(0, CrossrefJson.parseWorks("not json").size)
        assertEquals(0, CrossrefJson.parseWorks("{}").size)
        assertEquals(0, CrossrefJson.parseWorks("""{"message":{}}""").size)
    }

    @Test
    fun `无标题记录被丢弃`() {
        val json = """
            {"message":{"items":[
              {"DOI":"10.1/aaa","title":["Good title"]},
              {"DOI":"10.1/bbb"},
              {"DOI":"10.1/ccc","title":["  "]}
            ]}}
        """.trimIndent()
        val works = CrossrefJson.parseWorks(json)
        assertEquals(1, works.size)
        assertEquals("10.1/aaa", works.first().doi)
    }

    @Test
    fun `日期键兜底 published-online 与 issued`() {
        val json = """
            {"message":{"items":[
              {"DOI":"10.1/x","title":["A"],"published-online":{"date-parts":[[2025,3,7]]}},
              {"DOI":"10.1/y","title":["B"],"issued":{"date-parts":[[2024]]}},
              {"DOI":"10.1/z","title":["C"]}
            ]}}
        """.trimIndent()
        val works = CrossrefJson.parseWorks(json)
        assertEquals(2025, works[0].year)
        assertEquals(2024, works[1].year)
        assertEquals(0, works[2].year)
        assertEquals(0L, works[2].publishedMs)
    }

    @Test
    fun `作者只有 family 或只有 name 时也能解析`() {
        val json = """
            {"message":{"items":[
              {"DOI":"10.1/x","title":["A"],"author":[
                 {"family":"Zhang"},{"given":"San"},{"name":"某研究组"}
              ]}
            ]}}
        """.trimIndent()
        val works = CrossrefJson.parseWorks(json)
        assertEquals(listOf("Zhang", "San", "某研究组"), works.first().authors)
    }

    // ==================== JATS 摘要 ====================

    @Test
    fun `JATS 摘要转成可读 HTML`() {
        val jats = "<jats:title>Abstract</jats:title><jats:p>We report <jats:italic>in vivo</jats:italic> results with H<jats:sub>2</jats:sub>O.</jats:p>"
        val html = CrossrefJson.jatsToHtml(jats)
        assertTrue(html.contains("<p>"))
        assertTrue(html.contains("<i>in vivo</i>"))
        assertTrue(html.contains("<sub>2</sub>"))
        assertTrue(!html.contains("jats:"))
        assertTrue(!html.contains("Abstract</jats:title>"))
    }

    @Test
    fun `无段落摘要会补 p 标签`() {
        assertEquals("<p>plain text</p>", CrossrefJson.jatsToHtml("plain text"))
        assertEquals("", CrossrefJson.jatsToHtml(""))
    }

    // ==================== /journals 检索 ====================

    @Test
    fun `真实期刊检索响应解析`() {
        val journals = CrossrefJson.parseJournals(load("journals_search.json"))
        assertTrue(journals.isNotEmpty())
        val cs = journals.first()
        assertEquals("Chemical Science", cs.title)
        assertEquals("2041-6520", cs.issn)
        assertEquals("The Royal Society of Chemistry", cs.publisher)
        assertTrue(cs.issns.contains("2041-6539"))
    }

    @Test
    fun `没有 ISSN 的期刊被丢弃`() {
        val json = """
            {"message":{"items":[
              {"title":"No ISSN Journal","ISSN":[]},
              {"title":"Fine","ISSN":["1234-5678"]}
            ]}}
        """.trimIndent()
        val journals = CrossrefJson.parseJournals(json)
        assertEquals(1, journals.size)
        assertEquals("Fine", journals.first().title)
    }

    // ==================== 引用导出 ====================

    private val natureMeta = PaperMeta(
        doi = "10.1038/d41586-026-03351-1",
        journal = "Nature",
        volume = "658",
        issue = "8137",
        firstPage = "1094",
        lastPage = "1094",
        year = 2026,
        articleType = "journal-article",
        authors = listOf("Zora Zhang", "Kang Lou"),
        publisher = "Springer Science and Business Media LLC",
    )

    @Test
    fun `BibTeX 含标题 作者 卷期页 DOI`() {
        val bib = PaperCitation.bibtex(natureMeta, "Nobel-prizewinning breakthroughs", natureMeta.authors)
        assertTrue(bib.startsWith("@article{"))
        assertTrue(bib.contains("title = {Nobel-prizewinning breakthroughs}"))
        assertTrue(bib.contains("author = {Zora Zhang and Kang Lou}"))
        assertTrue(bib.contains("journal = {Nature}"))
        assertTrue(bib.contains("volume = {658}"))
        assertTrue(bib.contains("number = {8137}"))
        assertTrue(bib.contains("pages = {1094}"))
        assertTrue(bib.contains("year = {2026}"))
        assertTrue(bib.contains("doi = {10.1038/d41586-026-03351-1}"))
        // 最后一条字段后不能留逗号，否则 BibTeX 报错
        assertTrue(bib.trimEnd().endsWith("}"))
        assertTrue(!bib.contains(",\n}"))
    }

    @Test
    fun `BibTeX 转义特殊字符`() {
        val bib = PaperCitation.bibtex(
            natureMeta.copy(journal = "Science & Nature"),
            "A 100% test #1",
            listOf("Zhang San"),
        )
        assertTrue(bib.contains("Science \\& Nature"))
        assertTrue(bib.contains("100\\% test \\#1"))
    }

    @Test
    fun `BibTeX 转义反斜杠与下划线`() {
        val bib = PaperCitation.bibtex(
            natureMeta,
            "CO_2 fixation via deep_learning 50\\% faster",
            listOf("Zhang San"),
        )
        // `_` 不转义会被 LaTeX 当成下标命令，化学 / 机器学习标题里很常见
        assertTrue(bib.contains("CO\\_2 fixation via deep\\_learning"))
        assertTrue(bib.contains("\\textbackslash{}"))
    }

    @Test
    fun `cite key 取第一作者姓 与 年`() {
        val key = PaperCitation.citeKey(natureMeta, "Nobel-prizewinning breakthroughs", natureMeta.authors)
        assertEquals("zhang2026nobel", key)
    }

    @Test
    fun `cite key 无作者时退到标题或 DOI`() {
        val noAuthor = PaperCitation.citeKey(natureMeta.copy(doi = "10.1038/abc123"), "Deep learning", emptyList())
        assertTrue(noAuthor.isNotBlank())
        assertTrue(noAuthor.startsWith("2026"))

        val nothing = PaperCitation.citeKey(PaperMeta(), "", emptyList())
        assertEquals("untitled", nothing)
    }

    @Test
    fun `RIS 结构完整 以 TY 开头 ER 结尾`() {
        val ris = PaperCitation.ris(natureMeta, "Nobel-prizewinning breakthroughs", natureMeta.authors)
        assertTrue(ris.startsWith("TY  - JOUR"))
        assertTrue(ris.contains("AU  - Zora Zhang"))
        assertTrue(ris.contains("AU  - Kang Lou"))
        assertTrue(ris.contains("TI  - Nobel-prizewinning breakthroughs"))
        assertTrue(ris.contains("T2  - Nature"))
        assertTrue(ris.contains("PY  - 2026"))
        assertTrue(ris.contains("VL  - 658"))
        assertTrue(ris.contains("IS  - 8137"))
        assertTrue(ris.contains("SP  - 1094"))
        assertTrue(ris.contains("DO  - 10.1038/d41586-026-03351-1"))
        assertTrue(ris.contains("UR  - https://doi.org/10.1038/d41586-026-03351-1"))
        assertTrue(ris.trimEnd().endsWith("ER  -"))
    }

    @Test
    fun `非期刊条目导出为 misc 与 GEN`() {
        val blog = PaperMeta(year = 2026)
        assertTrue(PaperCitation.bibtex(blog, "Hello", emptyList()).startsWith("@misc{"))
        assertTrue(PaperCitation.ris(blog, "Hello", emptyList()).startsWith("TY  - GEN"))
    }

    @Test
    fun `中文作者姓名也能生成 cite key`() {
        val key = PaperCitation.citeKey(natureMeta, "二维材料的能带调控", listOf("张三"))
        assertTrue(key.isNotBlank())
        assertTrue(key != "untitled")
    }

    // ==================== 题录补全（v1.8.5.0 mergeWith） ====================

    @Test
    fun `mergeWith 只补空字段 已有值不被冲掉`() {
        val base = natureMeta.copy(lastPage = "", authors = emptyList())
        val other = natureMeta.copy(
            volume = "999",
            firstPage = "1",
            authors = listOf("A B", "C D"),
            doi = "10.9999/override",
        )
        val merged = base.mergeWith(other)
        // 已有的期刊 / 卷 / 期 / 首页 / 年保留
        assertEquals("Nature", merged.journal)
        assertEquals("658", merged.volume)
        assertEquals("8137", merged.issue)
        assertEquals("1094", merged.firstPage)
        assertEquals(2026, merged.year)
        // 空着的末页 / 作者被补上
        assertEquals("1094", merged.lastPage)
        assertEquals(listOf("A B", "C D"), merged.authors)
        // DOI 已有，不该被覆盖
        assertEquals("10.1038/d41586-026-03351-1", merged.doi)
    }

    @Test
    fun `mergeWith 能补上年份与出版社`() {
        val merged = PaperMeta(journal = "Nature").mergeWith(
            natureMeta.copy(journal = "应被忽略")
        )
        assertEquals(2026, merged.year)
        assertEquals("Springer Science and Business Media LLC", merged.publisher)
        assertEquals("Nature", merged.journal)
    }

    @Test
    fun `mergeWith 补完后引用行可用`() {
        val merged = PaperMeta(doi = "10.1038/x").mergeWith(natureMeta)
        // 起止页相同时只显示一个（`1094-1094` 是噪音）
        assertEquals("Nature, 658(8137): 1094 (2026)", merged.citationLine())
        assertTrue(merged.hasContent)
    }
}
