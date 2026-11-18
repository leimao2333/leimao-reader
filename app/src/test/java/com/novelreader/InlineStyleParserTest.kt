package com.novelreader

import com.novelreader.core.text.InlineSpan
import com.novelreader.core.text.InlineStyle
import com.novelreader.core.text.InlineStyleParser
import com.novelreader.core.text.StyledRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A11 行内字符级样式解析 单测。
 *
 * 覆盖四类关键行为：
 * 1. 各种标记都能被识别成对应的样式段；
 * 2. 嵌套（加粗里套下划线）能同时解析出两段；
 * 3. 转义与未闭合标记**不会**被当成标记，更不会吞掉后面的正文；
 * 4. span 下标落在纯文本上，边界不重叠、不错位。
 */
class InlineStyleParserTest {

    @Test
    fun `加粗标记解析为 BOLD 段`() {
        val text = "**加粗**"
        val spans = InlineStyleParser.parse(text)
        assertEquals(listOf(InlineSpan(0..1, InlineStyle.BOLD)), spans)
        assertEquals("加粗", InlineStyleParser.plainText(text))
    }

    @Test
    fun `下划线标记解析为 UNDERLINE 段`() {
        val text = "__下划线__"
        val spans = InlineStyleParser.parse(text)
        assertEquals(listOf(InlineSpan(0..2, InlineStyle.UNDERLINE)), spans)
        assertEquals("下划线", InlineStyleParser.plainText(text))
    }

    @Test
    fun `高亮标记解析为 HIGHLIGHT 段`() {
        val text = "==高亮=="
        val spans = InlineStyleParser.parse(text)
        assertEquals(listOf(InlineSpan(0..1, InlineStyle.HIGHLIGHT)), spans)
        assertEquals("高亮", InlineStyleParser.plainText(text))
    }

    @Test
    fun `删除线标记解析为 STRIKETHROUGH 段`() {
        val text = "~~删除线~~"
        val spans = InlineStyleParser.parse(text)
        assertEquals(listOf(InlineSpan(0..2, InlineStyle.STRIKETHROUGH)), spans)
        assertEquals("删除线", InlineStyleParser.plainText(text))
    }

    @Test
    fun `方括号强调解析为 EMPHASIS 段`() {
        val text = "【强调】"
        val spans = InlineStyleParser.parse(text)
        assertEquals(listOf(InlineSpan(0..1, InlineStyle.EMPHASIS)), spans)
        assertEquals("强调", InlineStyleParser.plainText(text))
    }

    @Test
    fun `嵌套时同时解析出加粗与下划线`() {
        val text = "**普通__带下划线__的加粗**"
        val spans = InlineStyleParser.parse(text)
        val plain = InlineStyleParser.plainText(text)
        assertEquals("普通带下划线的加粗", plain)
        // 外层加粗覆盖整段，内层下划线覆盖中间那一小段
        assertEquals(
            listOf(
                InlineSpan(0..8, InlineStyle.BOLD),
                InlineSpan(2..5, InlineStyle.UNDERLINE),
            ),
            spans,
        )
        // 两段下标都落在纯文本上
        assertEquals("普通带下划线的加粗", plain.substring(spans[0].range.first, spans[0].range.last + 1))
        assertEquals("带下划线", plain.substring(spans[1].range.first, spans[1].range.last + 1))
    }

    @Test
    fun `转义星号不会被当成标记起点`() {
        // 正文里字面出现 \* ：既不是加粗标记，也不会把后面的内容吞掉
        val text = "他说：\\*不是加粗\\*，只是星号"
        assertEquals(emptyList<InlineSpan>(), InlineStyleParser.parse(text))
        assertEquals("他说：*不是加粗*，只是星号", InlineStyleParser.plainText(text))
    }

    @Test
    fun `转义可以出现在标记内部`() {
        val text = "**加\\*粗**"
        val spans = InlineStyleParser.parse(text)
        assertEquals(listOf(InlineSpan(0..2, InlineStyle.BOLD)), spans)
        assertEquals("加*粗", InlineStyleParser.plainText(text))
    }

    @Test
    fun `未闭合的标记按普通文本处理`() {
        val text = "**没有结尾"
        assertEquals(emptyList<InlineSpan>(), InlineStyleParser.parse(text))
        assertEquals(text, InlineStyleParser.plainText(text))
    }

    @Test
    fun `未闭合的标记不会吞掉后续内容`() {
        val text = "**没有结尾 但后面还有__下划线__"
        val spans = InlineStyleParser.parse(text)
        val plain = InlineStyleParser.plainText(text)
        // 未闭合的 ** 原样保留，后面的 __ 仍然正常解析
        assertEquals("**没有结尾 但后面还有下划线", plain)
        assertEquals(1, spans.size)
        assertEquals(InlineStyle.UNDERLINE, spans[0].style)
        assertEquals("下划线", plain.substring(spans[0].range.first, spans[0].range.last + 1))
    }

    @Test
    fun `未闭合的方括号强调按普通文本处理`() {
        val text = "【没有结尾"
        assertEquals(emptyList<InlineSpan>(), InlineStyleParser.parse(text))
        assertEquals(text, InlineStyleParser.plainText(text))
    }

    @Test
    fun `空字符串返回空列表`() {
        assertEquals(emptyList<InlineSpan>(), InlineStyleParser.parse(""))
        assertEquals("", InlineStyleParser.plainText(""))
        assertEquals(emptyList<StyledRun>(), InlineStyleParser.toRuns(""))
    }

    @Test
    fun `没有标记的文本返回空列表`() {
        val text = "今天天气不错，适合出去走走。"
        assertEquals(emptyList<InlineSpan>(), InlineStyleParser.parse(text))
        assertEquals(text, InlineStyleParser.plainText(text))
    }

    @Test
    fun `多个标记同时出现且顺序正确`() {
        val text = "**一**和__二__还有==三=="
        val spans = InlineStyleParser.parse(text)
        val plain = InlineStyleParser.plainText(text)
        assertEquals("一和二还有三", plain)
        assertEquals(
            listOf(
                InlineSpan(0..0, InlineStyle.BOLD),
                InlineSpan(2..2, InlineStyle.UNDERLINE),
                InlineSpan(5..5, InlineStyle.HIGHLIGHT),
            ),
            spans,
        )
        // 顺序按出现先后
        assertEquals(
            listOf(InlineStyle.BOLD, InlineStyle.UNDERLINE, InlineStyle.HIGHLIGHT),
            spans.map { it.style },
        )
    }

    @Test
    fun `删除线与高亮混排时各自独立`() {
        val text = "~~删除~~与==高亮=="
        val spans = InlineStyleParser.parse(text)
        val plain = InlineStyleParser.plainText(text)
        assertEquals("删除与高亮", plain)
        assertEquals(
            listOf(
                InlineSpan(0..1, InlineStyle.STRIKETHROUGH),
                InlineSpan(3..4, InlineStyle.HIGHLIGHT),
            ),
            spans,
        )
    }

    @Test
    fun `标记内含中文与数字`() {
        val text = "**第1章**与__3.5节__"
        val spans = InlineStyleParser.parse(text)
        val plain = InlineStyleParser.plainText(text)
        assertEquals("第1章与3.5节", plain)
        assertEquals(
            listOf(
                InlineSpan(0..2, InlineStyle.BOLD),
                InlineSpan(4..7, InlineStyle.UNDERLINE),
            ),
            spans,
        )
        assertEquals("第1章", plain.substring(spans[0].range.first, spans[0].range.last + 1))
        assertEquals("3.5节", plain.substring(spans[1].range.first, spans[1].range.last + 1))
    }

    @Test
    fun `相邻标记的边界不重叠不错位`() {
        val text = "**甲**__乙__"
        val spans = InlineStyleParser.parse(text)
        val plain = InlineStyleParser.plainText(text)
        assertEquals("甲乙", plain)
        assertEquals(
            listOf(
                InlineSpan(0..0, InlineStyle.BOLD),
                InlineSpan(1..1, InlineStyle.UNDERLINE),
            ),
            spans,
        )
        // 两段首尾相接：前一段末尾 + 1 == 后一段开头，且没有重叠
        assertEquals(spans[0].range.last + 1, spans[1].range.first)
        assertTrue(spans[0].range.last < spans[1].range.first)
        // 下标都在纯文本范围内
        spans.forEach { span ->
            assertTrue(span.range.first >= 0)
            assertTrue(span.range.last < plain.length)
        }
    }

    @Test
    fun `analyze 同时返回纯文本与样式段`() {
        val result = InlineStyleParser.analyze("**加粗**与普通")
        assertEquals("加粗与普通", result.text)
        assertEquals(listOf(InlineSpan(0..1, InlineStyle.BOLD)), result.spans)
    }

    @Test
    fun `纯文本会去掉标记并还原转义`() {
        val text = "**加粗**与\\*字面\\*"
        assertEquals("加粗与*字面*", InlineStyleParser.plainText(text))
    }

    @Test
    fun `样式段按边界切分并携带嵌套样式`() {
        val styled = InlineStyleParser.toStyledText("**普通__带下划线__的加粗**")
        assertEquals("普通带下划线的加粗", styled.text)
        assertEquals(3, styled.runs.size)
        assertEquals("普通", styled.runs[0].text)
        assertEquals(setOf(InlineStyle.BOLD), styled.runs[0].styles)
        assertEquals("带下划线", styled.runs[1].text)
        assertEquals(setOf(InlineStyle.BOLD, InlineStyle.UNDERLINE), styled.runs[1].styles)
        assertEquals("的加粗", styled.runs[2].text)
        assertEquals(setOf(InlineStyle.BOLD), styled.runs[2].styles)
        // 片段拼起来等于纯文本
        assertEquals(styled.text, styled.runs.joinToString("") { it.text })
    }

    @Test
    fun `相邻同样式片段会被合并`() {
        val styled = InlineStyleParser.toStyledText("**甲乙丙**")
        assertEquals(1, styled.runs.size)
        assertEquals("甲乙丙", styled.runs[0].text)
    }

    @Test
    fun `大量未闭合标记既不产生样式也不丢字`() {
        // 一章里出现大量未闭合标记（书源抓取很常见）时，既不能解析出样式，
        // 也不能把标记当成标记吞掉
        val text = "【未闭合".repeat(50) + "正文"
        assertEquals(emptyList<InlineSpan>(), InlineStyleParser.parse(text))
        assertEquals(text, InlineStyleParser.plainText(text))
    }

    @Test
    fun `未闭合标记与已闭合标记混排时互不影响`() {
        val text = "**未闭合 ~~删除线~~ 后面"
        val spans = InlineStyleParser.parse(text)
        val plain = InlineStyleParser.plainText(text)
        assertEquals("**未闭合 删除线 后面", plain)
        assertEquals(1, spans.size)
        assertEquals(InlineStyle.STRIKETHROUGH, spans[0].style)
        assertEquals("删除线", plain.substring(spans[0].range.first, spans[0].range.last + 1))
    }
}
