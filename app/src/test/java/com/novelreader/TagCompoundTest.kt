package com.novelreader

import com.novelreader.core.util.TagCompound
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复合搜索决策逻辑测试（v1.7.0.0）。
 *
 * 覆盖「参与的源」（同时拥有全部标签入口）与「同源交集」两条核心规则：
 * 跨源同名**不算**同一部作品，这是与旧口径（全局按标题求交）最本质的区别。
 */
class TagCompoundTest {

    private fun item(title: String, sourceId: String, id: String = title) = MediaItem(
        id = id,
        title = title,
        type = SourceType.COMIC,
        sourceId = sourceId,
    )

    // ===== participants =====

    @Test
    fun `只有同时拥有全部标签入口的源才参与`() {
        val tagA = listOf("s1" to "/a1", "s2" to "/a2", "s3" to "/a3")
        val tagB = listOf("s2" to "/b2", "s3" to "/b3")
        assertEquals(listOf("s2", "s3"), TagCompound.participants(listOf(tagA, tagB)))
    }

    @Test
    fun `单标签时全部源都参与`() {
        val tagA = listOf("s1" to "/a1", "s1" to "/a1b", "s2" to "/a2")
        assertEquals(listOf("s1", "s2"), TagCompound.participants(listOf(tagA)))
    }

    @Test
    fun `没有公共源时返回空`() {
        val tagA = listOf("s1" to "/a")
        val tagB = listOf("s2" to "/b")
        assertTrue(TagCompound.participants(listOf(tagA, tagB)).isEmpty())
    }

    @Test
    fun `参与源顺序跟随第一个标签`() {
        val tagA = listOf("s9" to "/a9", "s1" to "/a1", "s5" to "/a5")
        val tagB = listOf("s1" to "/b1", "s5" to "/b5", "s9" to "/b9")
        assertEquals(listOf("s9", "s1", "s5"), TagCompound.participants(listOf(tagA, tagB)))
    }

    // ===== intersectBySource =====

    @Test
    fun `同源内同时命中才保留`() {
        val tagA = mapOf(
            "s1" to listOf(item("鸣龙少年", "s1"), item("炸薯条", "s1")),
            "s2" to listOf(item("时差", "s2")),
        )
        val tagB = mapOf(
            "s1" to listOf(item("炸 薯 条", "s1"), item("别的", "s1")),
            "s2" to listOf(item("时差", "s2")),
        )
        val out = TagCompound.intersectBySource(listOf(tagA, tagB))
        // s1 命中「炸薯条」（归一化后相等），s2 命中「时差」；按第一个标签的源顺序拼接
        assertEquals(listOf("炸薯条", "时差"), out.map { it.title })
    }

    @Test
    fun `跨源同名不算命中`() {
        val tagA = mapOf("s1" to listOf(item("同名作品", "s1")))
        val tagB = mapOf("s2" to listOf(item("同名作品", "s2")))
        assertTrue(TagCompound.intersectBySource(listOf(tagA, tagB)).isEmpty())
    }

    @Test
    fun `某标签在该源没有结果则这个源不产出`() {
        val tagA = mapOf(
            "s1" to listOf(item("甲", "s1")),
            "s2" to listOf(item("乙", "s2")),
        )
        val tagB = mapOf(
            "s1" to emptyList(),
            "s2" to listOf(item("乙", "s2")),
        )
        assertEquals(listOf("乙"), TagCompound.intersectBySource(listOf(tagA, tagB)).map { it.title })
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(TagCompound.intersectBySource(emptyList()).isEmpty())
    }

    // ===== merge =====

    @Test
    fun `merge 按源与条目 id 去重保序`() {
        val a = listOf(item("甲", "s1", "1"), item("乙", "s1", "2"))
        val b = listOf(item("乙", "s1", "2"), item("丙", "s2", "3"))
        assertEquals(
            listOf("甲", "乙", "丙"),
            TagCompound.merge(listOf(a, b)).map { it.title },
        )
    }

    @Test
    fun `复合标签无交集时回退到去重并集避免空结果`() {
        val tagA = listOf(item("无码作品", "s1", "1"))
        val tagB = listOf(item("全彩作品", "s2", "2"))
        val result = TagCompound.withUnionFallback(
            strictMatches = emptyList(),
            resultsPerTag = listOf(tagA, tagB),
        )
        assertTrue(result.usedUnionFallback)
        assertEquals(listOf("无码作品", "全彩作品"), result.items.map { it.title })
    }

    @Test
    fun `复合标签优先保留严格交集结果`() {
        val strict = listOf(item("同一作品", "s1", "1"))
        val result = TagCompound.withUnionFallback(
            strictMatches = strict,
            resultsPerTag = listOf(strict, listOf(item("其他作品", "s2", "2"))),
        )
        assertFalse(result.usedUnionFallback)
        assertEquals(strict, result.items)
    }
}
