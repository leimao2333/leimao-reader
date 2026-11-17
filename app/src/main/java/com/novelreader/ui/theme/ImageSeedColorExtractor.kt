package com.novelreader.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面主色提取（对标 legado 的 `ImageSeedColorExtractor`）。
 *
 * 只吃 [Bitmap]：**调用方负责把封面解码成 Bitmap**（本项目的 Coil 拿到 drawable 后
 * `.toBitmap()` 即可）。这里刻意不依赖 Coil / 网络 / 磁盘 —— 提取逻辑保持纯本地、可在任意
 * 线程跑，主题层才不会跟图片加载框架耦合。
 *
 * 算法（刻意做得很轻，32x32 采样，单张封面 < 1ms）：
 * 1. 缩到 32x32，避免对大图逐像素扫描；
 * 2. 丢掉透明、近黑、近白、低饱和的像素 —— 这几类是「墨黑 / 纸白 / 灰边」，拿来当主题色很难看；
 * 3. RGB 各取高 4 bit 量化成桶计数，每个像素按饱和度加权，让鲜艳色更容易成为主色；
 * 4. 取权重最高的桶，并用桶内像素的真实均值回填，消除量化色偏。
 *
 * 一个像素都没通过过滤时（纯黑白封面、线稿）回落到「全图最高频桶」而不是 null —— 黑封面
 * 就该得到深色主题，这比没有主题色更有用。只有 bitmap 不可用时才返回 null。
 */
object ImageSeedColorExtractor {

    /** 采样边长：32x32 = 1024 像素，足够稳定且极快 */
    private const val SAMPLE_SIZE = 32

    /** 近黑阈值（HSV 的 value），低于它的像素按墨色丢掉 */
    private const val MIN_VALUE = 0.12f

    /** 近白阈值：value 高于它且饱和度低于 [MAX_VALUE_SATURATION] 视为纸白 */
    private const val MAX_VALUE = 0.96f
    private const val MAX_VALUE_SATURATION = 0.10f

    /** 低饱和阈值：灰阶像素（文字、阴影、留白）不参与主色竞争 */
    private const val MIN_SATURATION = 0.12f

    /** 有效像素权重下限：低于它说明只剩噪点，改用全图兜底 */
    private const val MIN_WEIGHT = 4f

    /**
     * 提取主色（挂起版）。切到 [Dispatchers.Default]，不会卡主线程。
     *
     * @return alpha 恒为 0xFF 的 argb Int；bitmap 已回收或尺寸非法时返回 null。
     */
    suspend fun extract(bitmap: Bitmap): Int? = withContext(Dispatchers.Default) { extractSync(bitmap) }

    /**
     * 提取主色（同步版）。已在后台线程，或只是处理缩略图时用它。
     *
     * @return alpha 恒为 0xFF 的 argb Int；bitmap 已回收或尺寸非法时返回 null。
     */
    fun extractSync(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        // 不保持宽高比：颜色统计与几何形状无关，方形采样更均匀（长条形封面不会因裁边而偏色）。
        val scaled = if (bitmap.width == SAMPLE_SIZE && bitmap.height == SAMPLE_SIZE) {
            bitmap
        } else {
            runCatching { Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true) }
                .getOrNull() ?: return null
        }

        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        try {
            scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        } catch (e: IllegalArgumentException) {
            // 尺寸/配置异常的图（如硬件位图未拷贝）读不出像素，按失败处理
            return null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }

        // 过滤后的桶（主路径）与不过滤的桶（黑白封面兜底）
        val preferred = HashMap<Int, Bucket>()
        val all = HashMap<Int, Bucket>()
        val hsv = FloatArray(3)

        for (px in pixels) {
            if (AndroidColor.alpha(px) < 128) continue
            val r = AndroidColor.red(px)
            val g = AndroidColor.green(px)
            val b = AndroidColor.blue(px)
            val key = bucketKey(r, g, b)

            all.getOrPut(key) { Bucket() }.add(r, g, b)

            AndroidColor.RGBToHSV(r, g, b, hsv)
            val saturation = hsv[1]
            val value = hsv[2]
            val usable = saturation >= MIN_SATURATION &&
                value >= MIN_VALUE &&
                !(value > MAX_VALUE && saturation < MAX_VALUE_SATURATION)
            if (usable) {
                preferred.getOrPut(key) { Bucket() }.add(r, g, b, 1f + saturation)
            }
        }

        val winner = preferred.entries
            .filter { it.value.weight >= MIN_WEIGHT }
            .maxByOrNull { it.value.weight }
            ?: all.maxByOrNull { it.value.count }

        return winner?.value?.averageColor()
    }

    /** 量化桶：RGB 各取高 4 bit，组成 12 bit 键 */
    private fun bucketKey(r: Int, g: Int, b: Int): Int =
        ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)

    /** 单个颜色桶的累计统计 */
    private class Bucket {
        var count = 0
        var weight = 0f
        private var sumR = 0L
        private var sumG = 0L
        private var sumB = 0L

        fun add(r: Int, g: Int, b: Int, w: Float = 1f) {
            count++
            weight += w
            sumR += r
            sumG += g
            sumB += b
        }

        /** 桶内真实像素均值；桶为空时返回 null */
        fun averageColor(): Int? {
            if (count <= 0) return null
            val r = (sumR / count).toInt().coerceIn(0, 255)
            val g = (sumG / count).toInt().coerceIn(0, 255)
            val b = (sumB / count).toInt().coerceIn(0, 255)
            return AndroidColor.rgb(r, g, b)
        }
    }
}
