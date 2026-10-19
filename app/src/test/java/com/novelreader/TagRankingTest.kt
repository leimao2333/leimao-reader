package com.novelreader

import com.novelreader.core.util.TagHit
import com.novelreader.core.util.TagRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签置信度排序 / 求交测试（v1.8.1.3）。
 *
 * 覆盖：组间 AND 的条目级求交、得分取各组最小值、按得分降序、空组短路。
 */
class TagRankingTest {

    private fun hit(id: String, score: Int, source: String = "s1") = TagHit(source, id, score)

    @Test
    fun `单组按置信度降序`() {
        val out = TagRanking.intersect(listOf(listOf(hit("a", 60), hit("b", 100), hit("c", 80))))
        assertEquals(listOf("b", "c", "a"), out.map { it.itemId })
    }

    @Test
    fun `组间按条目求交`() {
        val g1 = listOf(hit("a", 100), hit("b", 90), hit("c", 80))
        val g2 = listOf(hit("b", 60), hit("d", 100))
        val out = TagRanking.intersect(listOf(g1, g2))
        assertEquals(listOf("b"), out.map { it.itemId })
    }

    @Test
    fun `得分取各组中的最小值`() {
        val g1 = listOf(hit("a", 100))
        val g2 = listOf(hit("a", 60))
        assertEquals(60, TagRanking.intersect(listOf(g1, g2)).single().score)
    }

    @Test
    fun `跨源同名条目不会互相命中`() {
        val g1 = listOf(hit("a", 100, source = "s1"))
        val g2 = listOf(hit("a", 100, source = "s2"))
        assertTrue(TagRanking.intersect(listOf(g1, g2)).isEmpty())
    }

    @Test
    fun `结果按短板得分降序`() {
        val g1 = listOf(hit("a", 100), hit("b", 100), hit("c", 100))
        val g2 = listOf(hit("a", 50), hit("b", 90), hit("c", 70))
        assertEquals(listOf("b", "c", "a"), TagRanking.intersect(listOf(g1, g2)).map { it.itemId })
    }

    @Test
    fun `任一组为空则交集为空`() {
        assertTrue(TagRanking.intersect(listOf(listOf(hit("a", 100)), emptyList())).isEmpty())
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(TagRanking.intersect(emptyList()).isEmpty())
    }
}
