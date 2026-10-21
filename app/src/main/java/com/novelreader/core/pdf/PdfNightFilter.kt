package com.novelreader.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * PDF 页面的「夜间反色」滤镜（v1.8.7.2 待办 #23）。
 *
 * 期刊论文清一色白底：深色主题下整屏白底位图非常刺眼。做法是渲染完位图后
 * 再过一遍 [ColorMatrix]：**先反色、再把亮度压回去**。
 *
 * 为什么不直接 `invert`：
 * - 纯反色下正文会变成 255 的纯白，暗环境下看一行亮字比看白底更累；
 * - 论文里的图表 / 照片本来就是为白底设计的，纯反色等于负片，
 *   压暗之后至少不会「亮到发蓝」。
 *
 * 代价说在明处：反色对**彩色图片仍然近似负片**，只是不刺眼了。
 * 真想「智能保留图片」得做页面区域分割（把图片块挑出来只对文字反色），
 * PC 端都少见这种实现，手机端性价比太低，本版不做。
 *
 * 这里是纯数据 + 一次位图重绘，不碰 PdfRenderer，也不引入新依赖。
 */
object PdfNightFilter {

    /**
     * 反色后的压暗系数（0f~1f）。
     *
     * 0.82 的来历：反色后正文 255 → 255×0.82 ≈ 209，是「清晰但不刺眼」的那一档；
     * 再低（0.7）在小字号下会明显发灰，再高（0.92）又回到了「一片亮白」。
     */
    const val DIM: Float = 0.82f

    /**
     * 合成矩阵：`dim ∘ invert`（先反色再压暗），行主序 4×5。
     *
     * Android 的 [ColorMatrix] 每个通道是
     * `out = m0*R + m1*G + m2*B + m3*A + m4`
     * ——通道值本身就是 0~255 量级，**第 5 列（偏移）直接相加，不再乘 255**。
     * 标准反色矩阵 `-1,0,0,0,255 / …` 就是按这个约定写的。
     */
    fun matrix(): FloatArray = floatArrayOf(
        -DIM, 0f, 0f, 0f, DIM * 255f,
        0f, -DIM, 0f, 0f, DIM * 255f,
        0f, 0f, -DIM, 0f, DIM * 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun colorFilter() = ColorMatrixColorFilter(matrix())

    /**
     * 把 [source] 过一遍夜间滤镜，返回新位图（不修改入参）。
     *
     * 调用方负责回收返回值与入参的生命周期：这里刻意不复用入参，
     * 因为渲染管线可能仍持有它的引用（[com.novelreader.ui.screens.pdf] 的页缓存）。
     */
    fun apply(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // FILTER_BITMAP_FLAG：缩放/重绘时插值，否则文字边缘会出现锯齿
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = colorFilter()
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    /**
     * 纯函数版变换：按 [matrix] 的算术算出一个 ARGB 像素的结果（供单测）。
     *
     * 与 [apply] 走同一条算式，但不需要 Bitmap / Skia —— 这样
     * 「白底→深灰、黑字→浅灰、 alpha 不变、灰度保持单调」这些性质
     * 可以在 JVM 单测里逐像素验证，而不是只能靠真机截图。
     */
    fun transformArgb(argb: Int): Int {
        val m = matrix()
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val channel = { row: Int ->
            val v = m[row * 5 + 0] * r +
                m[row * 5 + 1] * g +
                m[row * 5 + 2] * b +
                m[row * 5 + 3] * a +
                m[row * 5 + 4]
            v.coerceIn(0f, 255f).toInt()
        }
        val outR = channel(0)
        val outG = channel(1)
        val outB = channel(2)
        val outA = channel(3)
        return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
    }
}
