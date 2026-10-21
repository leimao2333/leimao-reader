package com.novelreader

import com.novelreader.core.pdf.PdfPageJump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PDF 跳页输入解析单测（v1.8.7.2 待办 #23）。
 *
 * 这里的每一条「无效」都对应输入法 / 用户手上真实会发生的一种写法：
 * 全角数字、手滑打上单位、负数、小数、超出总页数。解析失败必须返回 null
 * 让按钮保持禁用，而不是猜一个近似值跳过去。
 */
class PdfPageJumpTest {

    @Test
    fun `正常页码转成 0 基`() {
        assertEquals(0, PdfPageJump.parse("1", 10))
        assertEquals(6, PdfPageJump.parse("7", 10))
        assertEquals(9, PdfPageJump.parse("10", 10))
    }

    @Test
    fun `首尾空白容忍`() {
        assertEquals(6, PdfPageJump.parse("  7  ", 10))
        assertEquals(6, PdfPageJump.parse("\n7\t", 10))
    }

    @Test
    fun `前导零按数值算`() {
        assertEquals(6, PdfPageJump.parse("007", 10))
    }

    @Test
    fun `空输入无效`() {
        assertNull(PdfPageJump.parse("", 10))
        assertNull(PdfPageJump.parse("   ", 10))
    }

    @Test
    fun `越界无效`() {
        assertNull(PdfPageJump.parse("0", 10))
        assertNull(PdfPageJump.parse("11", 10))
        assertNull(PdfPageJump.parse("-1", 10))
        assertNull(PdfPageJump.parse("99999999999", 10))
    }

    @Test
    fun `带单位或非数字无效`() {
        assertNull(PdfPageJump.parse("7页", 10))
        assertNull(PdfPageJump.parse("p.7", 10))
        assertNull(PdfPageJump.parse("1.5", 10))
        assertNull(PdfPageJump.parse("七", 10))
        assertNull(PdfPageJump.parse("7 8", 10))
    }

    @Test
    fun `文档未打开时一律无效`() {
        assertNull(PdfPageJump.parse("1", 0))
        assertNull(PdfPageJump.parse("1", -3))
    }
}
