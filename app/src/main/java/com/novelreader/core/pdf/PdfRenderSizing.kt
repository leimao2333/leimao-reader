package com.novelreader.core.pdf

/** 一页 PDF 的渲染像素尺寸（[PdfRenderSizing.compute] 的结果） */
data class PdfPageBitmap(val width: Int, val height: Int) {

    /** 0×0 表示「这页渲染不了」（页面尺寸非法），调用方直接跳过 */
    val isValid: Boolean get() = width > 0 && height > 0

    /** 像素总数，用于内存估算（ARGB_8888 每像素 4 字节） */
    val pixels: Long get() = width.toLong() * height.toLong()

    /** 估算占用的内存字节数 */
    val bytes: Long get() = pixels * 4L
}

/**
 * PDF 页面渲染尺寸计算（v1.8.5.1）。
 *
 * `PdfRenderer` 的渲染分辨率由**目标 Bitmap 的尺寸**决定：给它 1080×1528 的
 * Bitmap 就按这个尺寸光栅化。于是「按屏宽渲染」看起来最省事，实际有两个坑：
 *
 * 1. **A4 纸的比例是固定的**（595×842 点），只给宽度、高度随手取屏高，
 *    页面会被拉伸或裁切 —— 必须按页面自身宽高比算高度。
 * 2. **内存是硬上限**：1080 宽的一页 ARGB_8888 约 6.5MB，翻页预渲染三页就 20MB；
 *    而平板横屏 / 高 DPI 设备按屏宽会算出 3000+ 宽（一页 25MB），
 *    在低端机上直接 OOM。所以要按**总像素预算**回缩，而不是只限宽。
 *
 * 抽成纯函数是为了能离线单测：比例、像素上限、非法输入这三种情况
 * 都没法在真机上稳定复现。
 */
object PdfRenderSizing {

    /**
     * 单页像素预算（约 3.2M 像素 ≈ 12.8MB/页）。
     *
     * 取值依据：A4 在 1500 宽时约 2120 高，正好 3.2M —— 这个分辨率下正文小字
     * （期刊双栏排版 8pt）放大 2 倍仍可辨认，而三页共存也只在 40MB 上下。
     */
    const val MAX_PIXELS: Long = 3_200_000L

    /** 宽度硬上限：再宽对阅读没有帮助，只是白烧内存 */
    const val MAX_WIDTH: Int = 2400

    /**
     * @param pageWidth 页面宽度（PDF 点，`PdfRenderer.Page.getWidth()`）
     * @param pageHeight 页面高度（PDF 点）
     * @param targetWidthPx 期望渲染宽度（像素）；<=0 时退回页面原始点数
     * @param maxPixels 像素预算，超出时按面积等比回缩
     */
    fun compute(
        pageWidth: Int,
        pageHeight: Int,
        targetWidthPx: Int,
        maxPixels: Long = MAX_PIXELS,
    ): PdfPageBitmap {
        // 页面尺寸非法（损坏页 / 0 尺寸）时不要构造 1×1 的位图去喂渲染器 ——
        // 那会得到一张黑图，不如明确告诉调用方「渲染不了」
        if (pageWidth <= 0 || pageHeight <= 0) return PdfPageBitmap(0, 0)

        val budget = if (maxPixels > 0) maxPixels else MAX_PIXELS
        val width = (if (targetWidthPx > 0) targetWidthPx else pageWidth)
            .coerceAtMost(MAX_WIDTH)
            .coerceAtLeast(1)
        var w = width
        var h = Math.round(w.toDouble() * pageHeight / pageWidth).toInt().coerceAtLeast(1)

        val pixels = w.toLong() * h
        if (pixels > budget) {
            val scale = Math.sqrt(budget.toDouble() / pixels.toDouble())
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }
        return PdfPageBitmap(w, h)
    }
}
