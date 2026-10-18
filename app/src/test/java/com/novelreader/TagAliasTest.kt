package com.novelreader

import com.novelreader.core.util.TagAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `未登记标签返回规范化后的自身`() {
        assertEquals(setOf("newgenre"), TagAlias.aliasesOf(" New   Genre "))
    }

    @Test
    fun `每个别名只属于一个等价组`() {
        // groupOf 构建时「后写覆盖前写」，同一个词出现在两组会让 aliasesOf 变成非对称
        // （例如「日语」含 raw、而「生肉」不含日语），复合搜索还可能把不同概念误合并。
        // 这里把跨组重复直接拦在测试里，新增词条时一重复就红。
        val seen = HashMap<String, Int>()
        val dups = mutableListOf<String>()
        TagAlias.GROUPS.forEachIndexed { index, group ->
            group.forEach { name ->
                val key = name.filterNot { it.isWhitespace() }.lowercase()
                val prev = seen.put(key, index)
                if (prev != null && prev != index) dups.add(name)
            }
        }
        assertTrue("跨组重复别名：$dups", dups.isEmpty())
    }

    @Test
    fun `同一组内互为译名`() {
        // 逐组校验 aliasesOf 能覆盖整组：若某词被另一组覆盖，这里的期望值就对不上。
        TagAlias.GROUPS.forEach { group ->
            val normalized = group.map { it.filterNot { c -> c.isWhitespace() }.lowercase() }.toSet()
            group.forEach { name ->
                assertEquals("「$name」的译名应覆盖整组", normalized, TagAlias.aliasesOf(name))
            }
        }
    }

    // ===== 展示归一（v1.7.9.0）=====

    @Test
    fun `同义写法归一成规范名`() {
        // 源里同一题材的四种写法都该收成「精灵」
        assertEquals("精灵", TagAlias.canonicalOf("妖精"))
        assertEquals("精灵", TagAlias.canonicalOf("elf"))
        assertEquals("精灵", TagAlias.canonicalOf("エルフ"))
        assertEquals("精灵", TagAlias.canonicalOf("요정"))
        // 首尾空白不影响
        assertEquals("精灵", TagAlias.canonicalOf(" 精灵 "))
    }

    @Test
    fun `语义相邻但不同义的组保持原词`() {
        // 「恶魔/天使」「妹妹/姐姐」「性转/变身」只是常一起出现，
        // 归一展示会把意思改掉（用户看到「恶魔」片被打上「天使」标签）
        assertEquals("恶魔", TagAlias.canonicalOf("恶魔"))
        assertEquals("妹妹", TagAlias.canonicalOf("妹妹"))
        assertEquals("性转", TagAlias.canonicalOf("性转"))
        assertEquals("格斗", TagAlias.canonicalOf("格斗"))
    }

    @Test
    fun `未登记的词原样返回`() {
        assertEquals("本周推荐", TagAlias.canonicalOf("本周推荐"))
        assertEquals("", TagAlias.canonicalOf(""))
        assertFalse(TagAlias.isKnownAlias("本周推荐"))
        assertTrue(TagAlias.isKnownAlias("妖精"))
    }

    @Test
    fun `日韩英文别名都能归一到中文规范名`() {
        assertEquals("魔王", TagAlias.canonicalOf("마왕"))
        assertEquals("勇者", TagAlias.canonicalOf("용사"))
        assertEquals("女仆", TagAlias.canonicalOf("メイド"))
        assertEquals("兽人", TagAlias.canonicalOf("orc"))
        assertEquals("公主", TagAlias.canonicalOf("princess"))
        assertEquals("恋爱", TagAlias.canonicalOf("연애"))
    }

    @Test
    fun `无码无修正繁简日韩写法归一并只算一个搜索条件`() {
        assertEquals("无码", TagAlias.canonicalOf("無碼"))
        assertEquals("无码", TagAlias.canonicalOf("无修正"))
        assertEquals("无码", TagAlias.canonicalOf("無修正"))
        assertEquals("无码", TagAlias.canonicalOf("무수정"))
        assertEquals(listOf("无码"), TagAlias.dedupeAliases(listOf("无码", "无修正", "無修正")))
        assertTrue(TagAlias.isExplicitAdultIntent("无修正"))
    }

    @Test
    fun `全彩跨中英日韩写法归一到中文`() {
        listOf(
            "彩色版", "Full Colored", "Official Colored", "Fan Colored", "colour",
            "フルカラー", "컬러",
        ).forEach {
            assertEquals("全彩", TagAlias.canonicalOf(it))
        }
        assertFalse(TagAlias.isExplicitAdultIntent("全彩"))
    }

    @Test
    fun `原始标签和百分号转义标签同时匹配且保留各自入口`() {
        val escapedFullColor = "%E5%85%A8%E5%BD%A9"
        val escapedUncensored = "%E6%97%A0%E7%A0%81"
        val pool = listOf("全彩", escapedFullColor, "无码", escapedUncensored)

        assertEquals("全彩", TagAlias.canonicalOf(escapedFullColor))
        assertEquals("无码", TagAlias.canonicalOf(escapedUncensored))
        assertEquals(listOf("全彩", escapedFullColor), TagAlias.matchesInPool("全彩", pool))
        assertEquals(listOf("无码", escapedUncensored), TagAlias.matchesInPool("无码", pool))
    }
}
