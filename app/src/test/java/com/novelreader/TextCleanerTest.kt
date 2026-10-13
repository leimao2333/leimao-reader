package com.novelreader

import com.novelreader.core.text.ReplaceRule
import com.novelreader.core.text.TextCleaner
import com.novelreader.core.text.TextReplaceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文净化与替换规则引擎的回归测试（v1.1.0.0 大修新增）。
 */
class TextCleanerTest {

    // ===== TextCleaner =====

    @Test
    fun `clean 统一换行符`() {
        val raw = "第一段\r\n第二段\r第三段"
        assertEquals("第一段\n第二段\n第三段", TextCleaner.clean(raw))
    }

    @Test
    fun `clean 折叠连续空行`() {
        val raw = "a\n\n\n\n\nb"
        assertEquals("a\n\nb", TextCleaner.clean(raw))
    }

    @Test
    fun `clean 去掉行尾空白`() {
        val raw = "第一行   \n第二行\t"
        assertEquals("第一行\n第二行", TextCleaner.clean(raw))
    }

    @Test
    fun `clean 空串原样返回`() {
        assertEquals("", TextCleaner.clean(""))
    }

    @Test
    fun `paragraphs 过滤纯空白段`() {
        val text = "第一段\n\n  \n第二段"
        assertEquals(listOf("第一段", "第二段"), TextCleaner.paragraphs(text))
    }

    @Test
    fun `charCount 统计非空白字符`() {
        assertEquals(4, TextCleaner.charCount("你好 世界"))
    }

    // ===== TextReplaceRules 编解码 =====

    @Test
    fun `encode 后 decode 可还原规则`() {
        val rules = listOf(
            ReplaceRule("广告", "", false),
            ReplaceRule("第\\d+章", "【章节】", true),
        )
        val encoded = TextReplaceRules.encode(rules)
        val decoded = TextReplaceRules.decode(encoded)
        assertEquals(rules, decoded)
    }

    @Test
    fun `decode 空串返回空列表`() {
        assertTrue(TextReplaceRules.decode(null).isEmpty())
        assertTrue(TextReplaceRules.decode("").isEmpty())
    }

    @Test
    fun `decode 容忍脏数据不抛异常`() {
        // 缺失字段的段应被跳过
        val raw = "1" + "\u001F" // 只有 isRegex，缺 pattern
        assertEquals(emptyList<ReplaceRule>(), TextReplaceRules.decode(raw))
    }

    @Test
    fun `apply 字面替换`() {
        val rules = listOf(ReplaceRule("广告", ""))
        assertEquals("正文内容", TextReplaceRules.apply("正文广告内容", rules))
    }

    @Test
    fun `apply 正则替换`() {
        val rules = listOf(ReplaceRule("第\\d+章", "[章]", true))
        assertEquals("[章] 标题", TextReplaceRules.apply("第123章 标题", rules))
    }

    @Test
    fun `apply 非法正则只跳过该条不影响其余`() {
        val rules = listOf(
            ReplaceRule("(未闭合", "", true), // 非法正则
            ReplaceRule("X", "Y", false),
        )
        assertEquals("Y", TextReplaceRules.apply("X", rules))
    }

    @Test
    fun `clean 全链路应用替换规则`() {
        val raw = "第1章\r\n广告内容\r\n\r\n正文"
        val rules = listOf(ReplaceRule("广告内容", "", false))
        val out = TextCleaner.clean(raw, rules)
        assertEquals("第1章\n\n正文", out)
    }
}
