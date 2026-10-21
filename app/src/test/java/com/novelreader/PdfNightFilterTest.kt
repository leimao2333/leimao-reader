package com.novelreader

import com.novelreader.core.pdf.PdfNightFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 夜间反色滤镜单测（v1.8.7.2 待办 #23）。
 *
 * [PdfNightFilter.apply] 要走 Skia，JVM 单测跑不了；所以把同一条算式抽成
 * [PdfNightFilter.transformArgb] 在这里逐像素验证。要守住的四条性质：
 *
 * 1. **白纸必须变黑** —— 这是「夜间」的全部意义，压不暗就等于没做；
 * 2. **黑字不能变成纯白** —— 纯反色下正文 255，暗环境里比白底更晃眼；
 * 3. **灰度保持单调** —— 深浅关系翻转但不能乱序，否则灰阶图会糊成一片；
 * 4. **alpha 原样透传** —— 渲染出来的位图是不透明的，但算式本身不能吞掉 alpha。
 */
class PdfNightFilterTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `白底反色后变黑`() {
        val out = PdfNightFilter.transformArgb(white)
        assertEquals(0xFF000000.toInt(), out)
    }

    @Test
    fun `黑字反色后被压暗而不是纯白`() {
        val out = PdfNightFilter.transformArgb(black)
        val gray = out and 0xFF
        // DIM = 0.82 → 255 × 0.82 = 209
        assertEquals(209, gray)
        assertTrue("不能到纯白", gray < 255)
        assertEquals(gray, (out ushr 16) and 0xFF)
        assertEquals(gray, (out ushr 8) and 0xFF)
    }

    @Test
    fun `灰度单调性翻转但保持有序`() {
        val inputs = listOf(0x00, 0x40, 0x80, 0xC0, 0xFF)
        val outputs = inputs.map { PdfNightFilter.transformArgb(gray(it)) and 0xFF }
        // 输入越亮 → 输出越暗，严格递减（没有两个档位压成同一个值）
        outputs.zipWithNext { a, b -> assertTrue("$outputs 不是严格递减", a > b) }
    }

    @Test
    fun `alpha 原样透传`() {
        assertEquals(0x80, (PdfNightFilter.transformArgb(0x80FF0000.toInt()) ushr 24) and 0xFF)
        assertEquals(0x00, (PdfNightFilter.transformArgb(0x00FFFFFF) ushr 24) and 0xFF)
    }

    @Test
    fun `反色逐通道生效`() {
        // 纯红 → 青（R 变 0，G/B 变亮），说明三个通道各自独立反色，
        // 不是把整幅图当灰度处理
        val out = PdfNightFilter.transformArgb(0xFFFF0000.toInt())
        assertEquals(0, (out ushr 16) and 0xFF)
        assertEquals(209, (out ushr 8) and 0xFF)
        assertEquals(209, out and 0xFF)
    }

    @Test
    fun `压暗系数在合理区间`() {
        // 太低正文发灰、太高又回到一片亮白，0.75~0.9 是能用的一档
        assertTrue(PdfNightFilter.DIM in 0.75f..0.9f)
    }

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
}
