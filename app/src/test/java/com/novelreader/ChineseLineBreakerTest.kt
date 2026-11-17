package com.novelreader

import com.novelreader.core.text.ChineseLineBreaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 中文断行 / 标点避头尾 单测。
 *
 * 核心断言只有两条：
 * 1. 切分后**下一段的首字符**不能是避头标点（句读不会悬在行首）；
 * 2. 切分后**上一段的末字符**不能是避尾标点（开括号不会孤零零留在行尾）。
 */
class ChineseLineBreakerTest {

    private fun assertValidCut(text: String, cut: Int) {
        assertTrue("切分点必须在 1..len-1：cut=$cut len=${text.length}", cut in 1..(text.length - 1))
        assertFalse(
            "行首不能是避头标点：'${text[cut]}' at cut=$cut",
            ChineseLineBreaker.isForbiddenAtLineStart(text[cut]),
        )
        assertFalse(
            "行尾不能是避尾标点：'${text[cut - 1]}' at cut=$cut",
            ChineseLineBreaker.isForbiddenAtLineEnd(text[cut - 1]),
        )
    }

    @Test
    fun `避头标点会被前移，不落在行首`() {
        // 理想切分点正好落在「，」之前 → 必须调整，逗号不能成为下一行的首字符
        val text = "今天天气很好，我们出去走走吧"
        val cut = text.indexOf('，') // 6
        val adjusted = ChineseLineBreaker.adjustCut(text, cut)
        assertValidCut(text, adjusted)
        // 关键断言：切分后下一段的首字符不是避头标点（逗号可以在行内，但不能在行首）
        assertFalse(
            "逗号不应成为行首字符",
            ChineseLineBreaker.isForbiddenAtLineStart(text[adjusted]),
        )
        assertTrue("切分点应被前移到标点之前", adjusted < cut)
    }

    @Test
    fun `连续避头标点一起前移`() {
        val text = "他说完了？！我愣住了"
        val cut = text.indexOf('？')
        val adjusted = ChineseLineBreaker.adjustCut(text, cut)
        assertValidCut(text, adjusted)
        // 结果必须落在「？！」之前，即行首不是问号也不是叹号
        assertFalse(ChineseLineBreaker.isForbiddenAtLineStart(text[adjusted]))
    }

    @Test
    fun `开括号不会留在行尾`() {
        val text = "他翻开书（那本旧书）读了起来"
        val cut = text.indexOf('（') + 1 // 切在开括号之后 → 开括号会落在行尾
        val adjusted = ChineseLineBreaker.adjustCut(text, cut)
        assertValidCut(text, adjusted)
    }

    @Test
    fun `普通位置不做无谓调整`() {
        val text = "春眠不觉晓处处闻啼鸟夜来风雨声花落知多少"
        val cut = 7 // 两侧都是普通汉字
        assertEquals(cut, ChineseLineBreaker.adjustCut(text, cut))
    }

    @Test
    fun `越界切分点会被夹取到合法范围`() {
        val text = "一二三四五"
        assertEquals(1, ChineseLineBreaker.adjustCut(text, -10))
        assertEquals(text.length - 1, ChineseLineBreaker.adjustCut(text, 999))
    }

    @Test
    fun `文本过短返回 0 表示无法切分`() {
        assertEquals(0, ChineseLineBreaker.adjustCut("", 3))
        assertEquals(0, ChineseLineBreaker.adjustCut("字", 3))
    }

    @Test
    fun `优先断在句末标点之后`() {
        val text = "第一句结束了。第二句开始然后继续写下去"
        val ideal = text.indexOf('。') + 6 // 略过句号
        val cut = ChineseLineBreaker.adjustCutPreferPunctuation(text, ideal)
        assertEquals("应断在句号之后", '。', text[cut - 1])
    }

    @Test
    fun `避头尾集合判定正确`() {
        assertTrue(ChineseLineBreaker.isForbiddenAtLineStart('，'))
        assertTrue(ChineseLineBreaker.isForbiddenAtLineStart('）'))
        assertTrue(ChineseLineBreaker.isForbiddenAtLineStart('…'))
        assertFalse(ChineseLineBreaker.isForbiddenAtLineStart('中'))

        assertTrue(ChineseLineBreaker.isForbiddenAtLineEnd('（'))
        assertTrue(ChineseLineBreaker.isForbiddenAtLineEnd('“'))
        assertFalse(ChineseLineBreaker.isForbiddenAtLineEnd('中'))
    }

    @Test
    fun `任意切分点都返回合法结果（模糊遍历）`() {
        val text = "他说：“这（本）书真好！”，然后（笑）着走开了……"
        for (cut in -2..(text.length + 2)) {
            val adjusted = ChineseLineBreaker.adjustCut(text, cut)
            // 只要文本够长，结果必须是合法可切分点
            assertTrue("cut=$cut → $adjusted", adjusted in 1..(text.length - 1))
        }
    }
}
