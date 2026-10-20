package com.novelreader

import com.novelreader.core.pdf.PdfRenderSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * PDF 页面渲染尺寸单测（v1.8.5.1）。
 *
 * 三个约束都不能破：
 * 1. **比例**必须跟着页面走 —— A4 是 595×842 点，只给宽度会让页面被拉伸；
 * 2. **像素预算**必须守住 —— 1080 宽的一页 ARGB_8888 约 6.5MB，高 DPI 平板
 *    按屏宽能算出 3000+ 宽（一页 25MB），低端机直接 OOM；
 * 3. **非法页面尺寸**不能返回 1×1 去喂渲染器（那会得到一张黑图）。
 */
class PdfRenderSizingTest {

    private val a4Width = 595
    private val a4Height = 842

    @Test
    fun `A4 页面按屏宽渲染时保持比例`() {
        val size = PdfRenderSizing.compute(a4Width, a4Height, targetWidthPx = 1080)
        assertEquals(1080, size.width)
        // 1080 × 842 / 595 = 1528.3
        assertEquals(1528, size.height)
        assertTrue(size.isValid)
    }

    @Test
    fun `横向页面按自身比例算高度`() {
        val size = PdfRenderSizing.compute(842, 595, targetWidthPx = 1080)
        assertEquals(1080, size.width)
        assertEquals(763, size.height)
    }

    @Test
    fun `目标宽度为 0 时退回页面原始点数`() {
        val size = PdfRenderSizing.compute(a4Width, a4Height, targetWidthPx = 0)
        assertEquals(a4Width, size.width)
        assertEquals(a4Height, size.height)
    }

    @Test
    fun `超出像素预算时按面积等比回缩`() {
        val size = PdfRenderSizing.compute(a4Width, a4Height, targetWidthPx = 2400)
        assertTrue("像素 ${size.pixels} 超过预算", size.pixels <= PdfRenderSizing.MAX_PIXELS)
        // 回缩后比例仍然对（±1px 舍入）
        val expected = a4Width.toDouble() / a4Height
        val actual = size.width.toDouble() / size.height
        assertTrue("比例失真：$actual vs $expected", abs(actual - expected) < 0.002)
        assertTrue(size.width < 2400)
    }

    @Test
    fun `宽度硬上限压住超高 DPI 设备`() {
        val size = PdfRenderSizing.compute(a4Width, a4Height, targetWidthPx = 5000)
        assertTrue("宽度 ${size.width} 超过上限", size.width <= PdfRenderSizing.MAX_WIDTH)
        assertTrue(size.pixels <= PdfRenderSizing.MAX_PIXELS)
    }

    @Test
    fun `非法页面尺寸返回 0 乘 0`() {
        assertFalse(PdfRenderSizing.compute(0, 842, 1080).isValid)
        assertFalse(PdfRenderSizing.compute(595, 0, 1080).isValid)
        assertFalse(PdfRenderSizing.compute(-595, 842, 1080).isValid)
    }

    @Test
    fun `极端宽高比也不会算出 0 高度`() {
        // 1000×1 点的「一条线」页面：1080 × 1 / 1000 舍入后可能变成 0，
        // 高度为 0 的 Bitmap 会直接抛 IllegalArgumentException
        val size = PdfRenderSizing.compute(1000, 1, targetWidthPx = 1080)
        assertEquals(1080, size.width)
        assertEquals(1, size.height)
        assertTrue(size.isValid)
    }

    @Test
    fun `自定义预算生效`() {
        val size = PdfRenderSizing.compute(a4Width, a4Height, targetWidthPx = 1080, maxPixels = 500_000)
        assertTrue(size.pixels <= 500_000)
    }

    @Test
    fun `字节估算按 ARGB 8888 每像素 4 字节`() {
        val size = PdfRenderSizing.compute(a4Width, a4Height, targetWidthPx = 1080)
        assertEquals(size.pixels * 4, size.bytes)
    }
}
