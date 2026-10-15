package com.novelreader

import com.novelreader.core.util.TagAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TagAlias] 中英标签互译（v1.7.2.0）。
 *
 * 这条线的意义在于：标签池里明明有 `Yuri` / `英語 ENG`，中文用户输「百合」「英语」
 * 却搜不到任何东西，看起来就像「标签搜索坏了」。所以这里盯住的是**能不能跨语言命中**，
 * 以及**会不会误命中**（把不相干的标签拉进搜索，结果是噪声）。
 */
class TagAliasTest {

    private val pool = listOf(
        "Yuri", "百合", "BL", "Sci-Fi", "Isekai",
        "英語 ENG", "日本語 Raw", "Slice of Life", "Completed",
        "異世界転生", "校园",
    )

    @Test
    fun `中文标签命中英文标签`() {
        val hits = TagAlias.matchesInPool("百合", pool)
        assertEquals(listOf("百合", "Yuri"), hits)
    }

    @Test
    fun `英文标签反向命中中文标签`() {
        val hits = TagAlias.matchesInPool("yuri", pool)
        // 自身不在池里（池里是 Yuri），归一化后等价 → 应当命中且排在前面
        assertEquals(listOf("Yuri", "百合"), hits)
    }

    @Test
    fun `中文命中含多段的复合标签`() {
        // 「英語 ENG」按空白拆出 eng，输「英语」才能命中；只比整体会漏
        assertTrue(TagAlias.matchesInPool("英语", pool).contains("英語 ENG"))
        assertTrue(TagAlias.matchesInPool("日语", pool).contains("日本語 Raw"))
    }

    @Test
    fun `连字符与空格写法等价`() {
        assertEquals(listOf("Sci-Fi"), TagAlias.matchesInPool("sci fi", pool))
        assertEquals(listOf("Sci-Fi"), TagAlias.matchesInPool("SCIFI", pool))
    }

    @Test
    fun `池里没有且无译名时不硬凑`() {
        assertEquals(listOf("校园"), TagAlias.matchesInPool("校园", pool))
        assertEquals(emptyList<String>(), TagAlias.matchesInPool("不存在的词", pool))
    }

    @Test
    fun `不把同组但池里没有的词编出来`() {
        // 「魔法」与「奇幻」不同组；「奇幻」的译名 fantasy 不在池里 → 只能空
        assertEquals(emptyList<String>(), TagAlias.matchesInPool("奇幻", pool))
    }

    @Test
    fun `自身命中永远排最前`() {
        val hits = TagAlias.matchesInPool("百合", pool)
        assertEquals("百合", hits.first())
    }

    @Test
    fun `译名集合含自身且不重复`() {
        val aliases = TagAlias.aliasesOf("百合")
        assertTrue("yuri" in aliases)
        assertTrue("百合" in aliases)
        assertEquals(aliases.size, aliases.distinct().size)
    }
}
