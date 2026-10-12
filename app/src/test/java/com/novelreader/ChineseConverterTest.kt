package com.novelreader

import com.novelreader.core.text.ChineseConverter
import org.junit.Assert.assertEquals
import org.junit.Test

/** 简繁转换（ChineseConverter）单元测试 */
class ChineseConverterTest {

    @Test
    fun `模式0原文返回`() {
        val text = "这是一个简体文本"
        assertEquals(text, ChineseConverter.convert(text, 0))
    }

    @Test
    fun `空串与空白不报错`() {
        assertEquals("", ChineseConverter.convert("", 1))
        assertEquals("", ChineseConverter.convert("", 2))
    }

    @Test
    fun `简转繁转换常用字`() {
        // 「这里是我们的学校」→ 简转繁后应出现对应繁体字
        val out = ChineseConverter.toTraditional("我们是学生，这里有个问题")
        // '学'->'學', '这'->'這', '个'->'個', '问'->'問'(未收录则不变), '题'->'題'
        assert(out.contains("學")) { "「学」应转为「學」，实际：$out" }
        assert(out.contains("這")) { "「这」应转为「這」，实际：$out" }
        assert(out.contains("個")) { "「个」应转为「個」，实际：$out" }
        assert(out.contains("題")) { "「题」应转为「題」，实际：$out" }
    }

    @Test
    fun `繁转简转换常用字`() {
        val out = ChineseConverter.toSimplified("我們是學生，這裡有個問題")
        assert(out.contains("我")) { "「我」应转简，实际：$out" }
        assert(out.contains("学")) { "「學」应转为「学」，实际：$out" }
        assert(out.contains("这")) { "「這」应转为「这」，实际：$out" }
        assert(out.contains("个")) { "「個」应转为「个」，实际：$out" }
    }

    @Test
    fun `转换保持长度不变_逐字映射`() {
        val text = "我们是学生这里有个问题关兴兴"
        assertEquals(text.length, ChineseConverter.toTraditional(text).length)
        assertEquals(text.length, ChineseConverter.toSimplified(text).length)
    }

    @Test
    fun `未收录字原样保留`() {
        // 使用对照表中没有的字（如生僻字）应保持原样
        val text = "𠮷㐀" // 生僻字，未收录
        assertEquals(text, ChineseConverter.toTraditional(text))
        assertEquals(text, ChineseConverter.toSimplified(text))
    }

    @Test
    fun `简繁互转可往返`() {
        val original = "这里是我们的学校"
        val trad = ChineseConverter.toTraditional(original)
        val back = ChineseConverter.toSimplified(trad)
        // 往返后应能回到简体（收录的字）
        assertEquals("往返转换不一致：$trad -> $back", original, back)
    }

    @Test
    fun `转换不影响非中文字符`() {
        val text = "Chapter 1: Hello, World! 123"
        assertEquals(text, ChineseConverter.toTraditional(text))
        assertEquals(text, ChineseConverter.toSimplified(text))
    }
}
