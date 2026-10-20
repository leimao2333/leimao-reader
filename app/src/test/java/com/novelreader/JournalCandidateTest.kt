package com.novelreader

import com.novelreader.core.rss.JournalFeed
import com.novelreader.domain.model.JournalCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 期刊候选与虚拟订阅地址的一致性（v1.8.5.0）。
 *
 * [JournalCandidate.subscribeUrl] 在领域层、[JournalFeed.urlOf] 在 core 层，
 * 两处各写了一遍 `crossref:issn:` 前缀。这里断言两边永远一致 ——
 * 只改一边会让「订阅」按钮写进一个谁也解析不了的地址，而且是运行时才发现的静默故障。
 */
class JournalCandidateTest {

    @Test
    fun `subscribeUrl 与 JournalFeed urlOf 一致`() {
        val c = JournalCandidate(issn = "0028-0836", title = "Nature")
        assertEquals(JournalFeed.urlOf("0028-0836"), c.subscribeUrl)
        assertEquals("crossref:issn:0028-0836", c.subscribeUrl)
    }

    @Test
    fun `issn 两侧的空白都要吃掉`() {
        val c = JournalCandidate(issn = " 0028-0836 ")
        assertEquals("crossref:issn:0028-0836", c.subscribeUrl)
        assertEquals(JournalFeed.urlOf("  0028-0836  "), c.subscribeUrl)
    }

    @Test
    fun `displayName 优先中文简称`() {
        assertEquals(
            "JACS",
            JournalCandidate(issn = "0002-7863", title = "J. Am. Chem. Soc.", shortName = "JACS")
                .displayName,
        )
        // 没有简称时退回期刊全名，连全名都没有才露 ISSN
        assertEquals(
            "Nature",
            JournalCandidate(issn = "0028-0836", title = "Nature").displayName,
        )
        assertEquals(
            "0036-8075",
            JournalCandidate(issn = "0036-8075").displayName,
        )
    }

    @Test
    fun `虚拟地址必须能被 JournalFeed 反向解析`() {
        JournalCatalogPresets.forEach { issn ->
            val url = JournalFeed.urlOf(issn)
            assertTrue("应识别为期刊源：$url", JournalFeed.isJournalFeed(url))
            assertEquals(issn, JournalFeed.issnOf(url))
        }
    }

    @Test
    fun `普通 http 地址不是期刊源`() {
        assertFalse(JournalFeed.isJournalFeed("https://www.nature.com/nature.rss"))
        assertFalse(JournalFeed.isJournalFeed("crossref:issn:"))
        assertFalse(JournalFeed.isJournalFeed("crossref:"))
        assertFalse(JournalFeed.isJournalFeed(""))
    }

    private companion object {
        val JournalCatalogPresets = listOf(
            "0036-8075", // Science
            "0028-0836", // Nature
            "0092-8674", // Cell
            "2041-6520", // Chemical Science
            "0027-8424", // PNAS
            "0002-7863", // JACS
        )
    }
}
