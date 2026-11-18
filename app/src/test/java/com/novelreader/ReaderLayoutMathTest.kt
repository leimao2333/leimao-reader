package com.novelreader

import com.novelreader.core.reader.ReaderLayoutMath
import com.novelreader.core.text.ChineseLineBreaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版纯数学单测（A1 / A6）。
 *
 * 重点验证：二分边界、空 / 超长 / 多段落输入、中文断行点、半页判定各档、
 * 字符区间连续不重叠、非法宽度与重复调用一致性。全部脱离 Compose 运行。
 */
class ReaderLayoutMathTest {

    private val identityCut: (String, Int) -> Int = { _, i -> i }

    // ---------- splitParagraphs ----------

    @Test
    fun splitParagraphsKeepsParagraphsAndTrimsTrailingWhitespace() {
        assertEquals(listOf("甲", "乙"), ReaderLayoutMath.splitParagraphs("甲\n乙"))
        assertEquals(
            listOf("甲", "乙"),
            ReaderLayoutMath.splitParagraphs("甲  \n乙\t \r"),
        )
    }

    @Test
    fun splitParagraphsKeepsEmptyLinesForSpacing() {
        // 空行必须保留，否则测量文本与渲染文本不一致会裁掉末行
        assertEquals(listOf("甲", "", "乙"), ReaderLayoutMath.splitParagraphs("甲\n\n乙"))
    }

    @Test
    fun splitParagraphsEmptyTextReturnsEmpty() {
        assertTrue(ReaderLayoutMath.splitParagraphs("").isEmpty())
        assertEquals(listOf("单段"), ReaderLayoutMath.splitParagraphs("单段"))
    }

    // ---------- maxFittingLength（二分测量边界）----------

    @Test
    fun maxFittingLengthFindsLargestPrefix() {
        val result = ReaderLayoutMath.maxFittingLength(10) { it <= 6 }
        assertEquals(6, result)
    }

    @Test
    fun maxFittingLengthAllFitReturnsFullLength() {
        assertEquals(10, ReaderLayoutMath.maxFittingLength(10) { true })
    }

    @Test
    fun maxFittingLengthNoneFitReturnsZero() {
        assertEquals(0, ReaderLayoutMath.maxFittingLength(10) { false })
        // 连 1 个字都放不下
        assertEquals(0, ReaderLayoutMath.maxFittingLength(8) { it <= 0 })
    }

    @Test
    fun maxFittingLengthWithZeroOrNegativeLengthReturnsZero() {
        assertEquals(0, ReaderLayoutMath.maxFittingLength(0) { true })
        assertEquals(0, ReaderLayoutMath.maxFittingLength(-5) { true })
    }

    @Test
    fun maxFittingLengthIsDeterministicAcrossRepeatedCalls() {
        val lengths = (1..20).map { ReaderLayoutMath.maxFittingLength(37) { n -> n <= 13 } }
        assertTrue(lengths.all { it == 13 })
    }

    // ---------- naturalCut（中文断行点）----------

    @Test
    fun naturalCutPrefersSentenceEndWithinWindow() {
        // "今天天气很好。我们出去玩吧。剩下的文字"
        // 理想点 15，向前搜索命中第二个句号后的下标 14
        val text = "今天天气很好。我们出去玩吧。剩下的文字"
        assertEquals(14, ReaderLayoutMath.naturalCut(text, 15, identityCut))
    }

    @Test
    fun naturalCutIgnoresPunctuationOutsideWindow() {
        // 句号在下标 10，理想点 60、窗口起点 20 —— 窗外标点不得影响切分
        val text = "a".repeat(10) + "。" + "b".repeat(89)
        assertEquals(60, ReaderLayoutMath.naturalCut(text, 60, identityCut))
    }

    @Test
    fun naturalCutFallsBackToLimitWhenNoPunctuation() {
        val text = "无标点".repeat(20)
        assertEquals(30, ReaderLayoutMath.naturalCut(text, 30, identityCut))
    }

    @Test
    fun naturalCutLimitBeyondTextReturnsWholeLength() {
        val text = "短文本"
        assertEquals(text.length, ReaderLayoutMath.naturalCut(text, 999, identityCut))
        assertEquals(text.length, ReaderLayoutMath.naturalCut(text, text.length, identityCut))
    }

    @Test
    fun naturalCutLimitZeroReturnsZero() {
        assertEquals(0, ReaderLayoutMath.naturalCut("任意文本", 0, identityCut))
        assertEquals(0, ReaderLayoutMath.naturalCut("任意文本", -3, identityCut))
    }

    @Test
    fun naturalCutAppliesInjectedAdjustCut() {
        val text = "甲乙（丙丁"
        // 桩：把切分点强制前移 1，验证注入的 adjuster 确实被调用
        val stub: (String, Int) -> Int = { _, i -> i - 1 }
        assertEquals(2, ReaderLayoutMath.naturalCut(text, 3, stub))
        // 不注入时（identity）理想点就是 3
        assertEquals(3, ReaderLayoutMath.naturalCut(text, 3, identityCut))
    }

    @Test
    fun naturalCutWithRealChineseLineBreakerAvoidsOpeningBracketAtLineEnd() {
        // "甲乙（丙丁" 理想点 3 会让开括号 "（" 落在行尾；
        // 真实避尾规则应把切分点前移到 2
        val text = "甲乙（丙丁"
        val cut = ReaderLayoutMath.naturalCut(text, 3) { t, i -> ChineseLineBreaker.adjustCut(t, i) }
        assertEquals(2, cut)
    }

    // ---------- pageRanges（字符区间不重叠且连续）----------

    @Test
    fun pageRangesAreContiguousAndNonOverlapping() {
        val ranges = ReaderLayoutMath.pageRanges(listOf("abc", "de", "f"))
        assertEquals(3, ranges.size)
        assertEquals(0, ranges[0].start)
        assertEquals(3, ranges[0].end)
        assertEquals(3, ranges[1].start)
        assertEquals(5, ranges[1].end)
        assertEquals(5, ranges[2].start)
        assertEquals(6, ranges[2].end)
        for (i in 1 until ranges.size) {
            assertEquals(ranges[i - 1].end, ranges[i].start)
        }
    }

    @Test
    fun pageRangesHandlesEmptyAndSinglePage() {
        assertTrue(ReaderLayoutMath.pageRanges(emptyList()).isEmpty())
        assertEquals(
            listOf(ReaderLayoutMath.TextRange(0, "单页".length)),
            ReaderLayoutMath.pageRanges(listOf("单页")),
        )
    }

    // ---------- 残页 / 半页判定（A6）----------

    @Test
    fun partialPageStrategyCoversAllBands() {
        val s = ReaderLayoutMath.partialPageStrategy(lastPageChars = 35, fullPageChars = 100)
        assertEquals(ReaderLayoutMath.PartialStrategy.NORMAL, s)

        val c = ReaderLayoutMath.partialPageStrategy(lastPageChars = 20, fullPageChars = 100)
        assertEquals(ReaderLayoutMath.PartialStrategy.KEEP_CENTERED, c)

        val m = ReaderLayoutMath.partialPageStrategy(lastPageChars = 10, fullPageChars = 100)
        assertEquals(ReaderLayoutMath.PartialStrategy.MERGE_UP, m)
    }

    @Test
    fun isPartialPageRespectsThresholdAndInvalidCapacity() {
        assertTrue(ReaderLayoutMath.isPartialPage(20, 100))
        assertFalse(ReaderLayoutMath.isPartialPage(30, 100))
        assertFalse(ReaderLayoutMath.isPartialPage(20, 0))
        assertFalse(ReaderLayoutMath.isPartialPage(0, 100))
        // 自定义阈值
        assertTrue(ReaderLayoutMath.isPartialPage(40, 100, threshold = 0.5f))
    }

    @Test
    fun partialPageStrategyWithInvalidInputsStaysNormal() {
        assertEquals(
            ReaderLayoutMath.PartialStrategy.NORMAL,
            ReaderLayoutMath.partialPageStrategy(10, 0),
        )
        assertEquals(
            ReaderLayoutMath.PartialStrategy.NORMAL,
            ReaderLayoutMath.partialPageStrategy(0, 100),
        )
    }
}
