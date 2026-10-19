package com.novelreader

import com.novelreader.core.util.TagQuerySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查询语法解析测试（v1.8.1.3）。
 *
 * 覆盖：`-` / `!` 排除、`大类:标签` 命名空间、`*` 通配、未知前缀不吞输入、
 * 全角符号与去重。
 */
class TagQuerySpecTest {

    @Test
    fun `普通标签原样解析`() {
        val clauses = TagQuerySpec.parse("无码 全彩")
        assertEquals(listOf("无码", "全彩"), clauses.map { it.name })
        assertTrue(clauses.none { it.exclude || it.wildcard || it.category != null })
    }

    @Test
    fun `减号前缀是排除`() {
        val clauses = TagQuerySpec.parse("无码 -有码")
        assertEquals(listOf("无码", "有码"), clauses.map { it.name })
        assertEquals(listOf(false, true), clauses.map { it.exclude })
    }

    @Test
    fun `感叹号与全角减号同样是排除`() {
        val clauses = TagQuerySpec.parse("!有码 －有码")
        assertTrue(clauses.all { it.exclude && it.name == "有码" })
        assertEquals(1, clauses.size) // 完全相同的条件去重
    }

    @Test
    fun `大类前缀被识别`() {
        val clause = TagQuerySpec.parse("题材:热血").single()
        assertEquals("题材", clause.category)
        assertEquals("热血", clause.name)
        assertFalse(clause.exclude)
    }

    @Test
    fun `大类英文与别名写法`() {
        assertEquals("题材", TagQuerySpec.parse("genre:热血").single().category)
        assertEquals("画风", TagQuerySpec.parse("style:厚涂").single().category)
        assertEquals("成人", TagQuerySpec.parse("r18:无码").single().category)
    }

    @Test
    fun `全角冒号也可用`() {
        val clause = TagQuerySpec.parse("题材：热血").single()
        assertEquals("题材", clause.category)
        assertEquals("热血", clause.name)
    }

    @Test
    fun `未知前缀整体当作标签名`() {
        val clause = TagQuerySpec.parse("https://example.com").single()
        assertEquals(null, clause.category)
        assertEquals("https://example.com", clause.name)
    }

    @Test
    fun `排除也能带大类`() {
        val clause = TagQuerySpec.parse("-成人:无码").single()
        assertTrue(clause.exclude)
        assertEquals("成人", clause.category)
        assertEquals("无码", clause.name)
    }

    @Test
    fun `星号与问号被识别为通配`() {
        assertEquals(true, TagQuerySpec.parse("魔法*").single().wildcard)
        assertEquals(true, TagQuerySpec.parse("魔?少女").single().wildcard)
        assertEquals(false, TagQuerySpec.parse("魔法").single().wildcard)
    }

    @Test
    fun `空串与纯前缀返回空`() {
        assertTrue(TagQuerySpec.parse("   ").isEmpty())
        assertTrue(TagQuerySpec.parse("-").isEmpty())
    }

    @Test
    fun `通配前缀展开`() {
        val vocab = listOf("魔法", "魔法少女", "魔法师", "热血")
        assertEquals(
            listOf("魔法", "魔法少女", "魔法师"),
            TagQuerySpec.expandWildcard("魔法*", vocab),
        )
    }

    @Test
    fun `通配后缀与中缀展开`() {
        val vocab = listOf("无码", "有码", "破解版", "限定版")
        assertEquals(
            listOf("无码", "有码"),
            TagQuerySpec.expandWildcard("*码", vocab),
        )
        assertEquals(listOf("全彩"), TagQuerySpec.expandWildcard("*彩", listOf("全彩", "彩色")))
    }

    @Test
    fun `通配大小写不敏感`() {
        val vocab = listOf("Full Color", "fullcolor", "Monochrome")
        assertEquals(2, TagQuerySpec.expandWildcard("full*", vocab).size)
    }

    @Test
    fun `通配没有命中返回空`() {
        assertTrue(TagQuerySpec.expandWildcard("不存在*", listOf("魔法", "热血")).isEmpty())
    }

    @Test
    fun `describe 带上排除与大类`() {
        val clause = TagQuerySpec.parse("-题材:热血").single()
        assertEquals("排除 题材:热血", TagQuerySpec.describe(clause))
    }
}
