package com.novelreader

import com.novelreader.core.util.SourceSiteLimiter
import org.junit.Assert.assertEquals
import org.junit.Test

/** 同站点搜索限流（v1.9.4.0） */
class SourceSiteLimiterTest {

    private data class S(val id: String, val site: String, val quality: Int)

    private fun limit(list: List<S>, perSite: Int) = SourceSiteLimiter.limit(
        sources = list,
        perSite = perSite,
        siteOf = { it.site },
        qualityOf = { it.quality },
    ).map { it.id }

    @Test
    fun keepsBestSourcesPerSite() {
        val list = listOf(
            S("a1", "example.com", 100),
            S("a2", "example.com", 900),
            S("a3", "example.com", 500),
        )
        assertEquals(listOf("a2", "a3"), limit(list, 2))
    }

    @Test
    fun differentSitesDoNotAffectEachOther() {
        val list = listOf(
            S("a1", "a.com", 10), S("a2", "a.com", 20),
            S("b1", "b.com", 10), S("b2", "b.com", 20),
        )
        assertEquals(listOf("a2", "b2"), limit(list, 1))
    }

    @Test
    fun sourcesWithoutSiteAreAllKept() {
        // Venera / 内置源没有站点地址，不能互相挤掉
        val list = listOf(
            S("v1", "", 1), S("v2", "", 1), S("v3", "", 1),
            S("a1", "a.com", 5),
        )
        assertEquals(listOf("v1", "v2", "v3", "a1"), limit(list, 1))
    }

    @Test
    fun zeroOrNegativeMeansNoLimit() {
        val list = listOf(S("a1", "a.com", 1), S("a2", "a.com", 2), S("a3", "a.com", 3))
        assertEquals(listOf("a1", "a2", "a3"), limit(list, 0))
        assertEquals(listOf("a1", "a2", "a3"), limit(list, -1))
    }

    @Test
    fun outputKeepsOriginalOrder() {
        val list = listOf(
            S("a1", "a.com", 1),
            S("b1", "b.com", 1),
            S("a2", "a.com", 9),
            S("b2", "b.com", 9),
        )
        assertEquals(listOf("a2", "b2"), limit(list, 1))
    }

    @Test
    fun interleaveKeepsEverySourceButSpreadsDifferentSitesFirst() {
        val list = listOf(
            S("a1", "a.com", 9), S("a2", "a.com", 5), S("a3", "a.com", 1),
            S("b1", "b.com", 9), S("b2", "b.com", 5),
        )
        val out = SourceSiteLimiter.interleave(
            list, siteOf = { it.site }, qualityOf = { it.quality },
        ).map { it.id }
        // 第 1 轮各站最优源，第 2 轮各站次优……一个源都没丢
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), out)
        assertEquals(list.size, out.size)
    }

    @Test
    fun interleaveKeepsSourcesWithoutSite() {
        val list = listOf(
            S("a1", "a.com", 9), S("a2", "a.com", 1),
            S("v1", "", 1), S("v2", "", 1),
        )
        val out = SourceSiteLimiter.interleave(
            list, siteOf = { it.site }, qualityOf = { it.quality },
        ).map { it.id }
        assertEquals(4, out.size)
        assertEquals(setOf("a1", "a2", "v1", "v2"), out.toSet())
    }

    @Test
    fun siteNameIsTrimmedAndCaseInsensitive() {
        val list = listOf(
            S("a1", " Example.COM ", 1),
            S("a2", "example.com", 9),
        )
        assertEquals(listOf("a2"), limit(list, 1))
    }
}
