package com.novelreader.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #27 标签输入提示：纯函数逻辑回归。
 * 排序规则一旦错位（前缀不优先 / 同分不按覆盖源数 / 提示了已选标签），
 * 用户就会看到「打了一半的标签排在有意义的补全后面」，靠肉眼很难回归。
 */
class TagSuggestionsTest {

    @Test
    fun `currentWord 剥离 减号 与 题材冒号 前缀`() {
        assertEquals("无码", TagSuggestions.currentWord("无码"))
        assertEquals("无码", TagSuggestions.currentWord("-无码"))
        assertEquals("无码", TagSuggestions.currentWord("题材:无码"))
        assertEquals("无码", TagSuggestions.currentWord("-题材:无码"))
        assertEquals("", TagSuggestions.currentWord(""))
        assertEquals("", TagSuggestions.currentWord("-"))
        assertEquals("", TagSuggestions.currentWord("题材:"))
    }

    @Test
    fun `空输入 或 空池子 返回空`() {
        assertTrue(TagSuggestions.of(emptyList(), "无").isEmpty())
        assertTrue(TagSuggestions.of(listOf("无码"), "").isEmpty())
        assertTrue(TagSuggestions.of(listOf("无码"), "   ").isEmpty())
    }

    @Test
    fun `前缀命中 优先于 包含命中`() {
        // pool 故意把包含命中的「大魔法」排在最前、前缀命中的「魔法使」排在最后，
        // 验证排序不看池子原始位置，而看匹配类型。
        // 注意：词「魔法」本身刻意不在 pool 里 —— 它一旦是完整标签，同根词会整族屏蔽
        //（见「已选标签 不再提示」），那样就测不出排序了。
        val pool = listOf("大魔法", "魔法少女", "魔法使")
        val out = TagSuggestions.of(pool, "魔法")
        assertEquals(listOf("魔法使", "魔法少女", "大魔法"), out)
    }

    @Test
    fun `同分时 按覆盖源数（pool 顺序）排`() {
        // 两个都是前缀命中，池子里靠前的（覆盖源更多）先出
        val pool = listOf("无码流出", "无码", "无码漫画")
        val out = TagSuggestions.of(pool, "无")
        assertEquals("无码", out.first())
    }

    @Test
    fun `同分同覆盖源数 时 词更短 优先`() {
        val pool = listOf("全彩本", "全彩漫画", "全彩")
        val out = TagSuggestions.of(pool, "全")
        assertEquals("全彩", out.first())
    }

    @Test
    fun `只提示 正在输入的最后一个词`() {
        val pool = listOf("无码", "全彩", "无码漫画", "全彩漫画")
        // 已写「无码」，正在输入「全」→ 只应出现全*，不出现无*
        val out = TagSuggestions.of(pool, "无码 全")
        assertTrue(out.all { it.contains("全") })
        assertTrue(out.none { it.contains("无") })
    }

    @Test
    fun `已选标签 不再提示`() {
        val pool = listOf("无码", "无码漫画", "全彩")
        // 查询里已经有「无码」，提示不应再给无*（避免「无码 无码」）
        val out = TagSuggestions.of(pool, "无码")
        assertTrue(out.none { it.contains("无") })
    }

    @Test
    fun `完全相同的词 不提示自己`() {
        val pool = listOf("无码", "无码漫画", "全彩")
        val out = TagSuggestions.of(pool, "无码")
        assertTrue(out.none { it == "无码" })
    }

    @Test
    fun `大小写不敏感`() {
        val pool = listOf("YURI", "百合", "YURI动漫")
        val out = TagSuggestions.of(pool, "yur")
        assertEquals(listOf("YURI", "YURI动漫"), out)
    }

    @Test
    fun `limit 生效`() {
        val pool = (1..20).map { "词$it" }
        assertEquals(TagSuggestions.DEFAULT_LIMIT, TagSuggestions.of(pool, "词").size)
        assertEquals(3, TagSuggestions.of(pool, "词", limit = 3).size)
    }

    @Test
    fun `applyToQuery 替换最后一个 token 其余保留`() {
        assertEquals("无码 全彩", TagSuggestions.applyToQuery("无码 全", "全彩"))
        assertEquals("无 无码", TagSuggestions.applyToQuery("无 无", "无码"))
    }

    @Test
    fun `applyToQuery 保留 减号 前缀`() {
        assertEquals("-无码", TagSuggestions.applyToQuery("-无", "无码"))
    }

    @Test
    fun `applyToQuery 保留 题材冒号 前缀`() {
        assertEquals("题材:无码", TagSuggestions.applyToQuery("题材:无", "无码"))
    }

    @Test
    fun `applyToQuery 空 query 时 直接返回 tag`() {
        assertEquals("无码", TagSuggestions.applyToQuery("", "无码"))
    }

    @Test
    fun `applyToQuery 与 of 协同 不会产生重复条件`() {
        // 输入「无」，点选提示「无码」后查询变「无码」，再次计算提示不应含无*
        val next = TagSuggestions.applyToQuery("无", "无码")
        val pool = listOf("无码", "无码漫画", "全彩")
        val out = TagSuggestions.of(pool, next)
        assertTrue(out.none { it.contains("无") })
    }
}
