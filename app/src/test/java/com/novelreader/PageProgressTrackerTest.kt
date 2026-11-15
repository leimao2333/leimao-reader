package com.novelreader

import com.novelreader.core.search.PageProgressTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页判据（v1.9.6.0）：核心是「已收录」不能再被当成「翻到底」。
 *
 * 这里固定住 2026-11-15 18:05 那个真实回归：连翻翻到第 2、3 页，各取回 58 / 40 条，
 * 但**全部都是本地索引里已有的**，旧判据（看结果集有没有变大）因此判成「源站反复
 * 返回同一批」，三页就收工。
 */
class PageProgressTrackerTest {

    @Test
    fun `first page is always progress`() {
        val t = PageProgressTracker()
        assertFalse(t.observe(listOf("a:1", "a:2")))
        assertEquals(0, t.consecutiveRepeats)
        assertEquals(2, t.seenCount)
    }

    @Test
    fun `same page twice counts as repeat`() {
        val t = PageProgressTracker()
        t.observe(listOf("a:1", "a:2"))
        assertTrue(t.observe(listOf("a:1", "a:2")))
        assertEquals(1, t.consecutiveRepeats)
    }

    /** 回归点：内容全新（只是「已收录」）必须算进展，不能被判成重复页 */
    @Test
    fun `already recorded but never fetched is progress`() {
        val t = PageProgressTracker()
        t.observe(listOf("a:1", "a:2"))
        // 第 2 页的这三条虽然都在本地索引里（因此「新增 0 行」），但本轮确实没见过
        assertFalse(t.observe(listOf("a:3", "a:4", "a:5")))
        assertEquals(0, t.consecutiveRepeats)
    }

    @Test
    fun `partially new page is progress`() {
        val t = PageProgressTracker()
        t.observe(listOf("a:1", "a:2"))
        assertFalse(t.observe(listOf("a:2", "a:9")))
        assertEquals(0, t.consecutiveRepeats)
    }

    @Test
    fun `five repeats in a row reach the tolerance used by the scan`() {
        val t = PageProgressTracker()
        t.observe(listOf("a:1"))
        repeat(5) { assertTrue(t.observe(listOf("a:1"))) }
        assertEquals(5, t.consecutiveRepeats)
    }

    @Test
    fun `progress resets the repeat streak`() {
        val t = PageProgressTracker()
        t.observe(listOf("a:1"))
        t.observe(listOf("a:1"))
        t.observe(listOf("a:1"))
        assertEquals(2, t.consecutiveRepeats)

        t.observe(listOf("a:2"))
        assertEquals(0, t.consecutiveRepeats)
    }

    @Test
    fun `empty page does not count as repeat nor progress`() {
        val t = PageProgressTracker()
        assertFalse(t.observe(emptyList()))
        assertEquals(0, t.consecutiveRepeats)
        assertEquals(0, t.seenCount)
    }
}
