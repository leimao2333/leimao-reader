package com.novelreader.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log

/**
 * 漫画图片「分段乱序」还原。
 *
 * 部分站点会把每页图片**先按 N 段横向切开、再打乱顺序**
 * 后下发，只有把各段重新按倒序拼回去，才是一张正常的漫画页。
 *
 * 站点规则由源以 JS 片段的形式给出（Venera 的 `comic.onImageLoad → modifyImage`），
 * 形如：
 *
 * ```
 * let modifyImage = (image) => {
 *     const num = 6
 *     let blockSize = Math.floor(image.height / num)
 *     let remainder = image.height % num
 *     let blocks = []
 *     for (let i = 0; i < num; i++) { … }
 *     let res = Image.empty(image.width, image.height)
 *     let y = 0
 *     for (let i = blocks.length - 1; i >= 0; i--) {
 *         let block = blocks[i]
 *         let currentHeight = block.end - block.start
 *         res.fillImageRangeAt(0, y, image, 0, block.start, image.width, currentHeight)
 *         y += currentHeight
 *     }
 *     return res
 * }
 * ```
 *
 * 这里的语义就是「把 N 段按倒序重新竖向拼接」，因此不需要真的跑 JS：
 * 只要从脚本里认出这个形态（[bandCountFromScript]）并取出段数 N，
 * 就能在解码后用 [unscramble] 直接在 Bitmap 上完成还原，零额外依赖、可离线单测。
 *
 * 认不出的脚本一律返回 null（不还原），并在日志里留一条告警，避免「悄悄画错」。
 */
object ComicImageScramble {

    private const val TAG = "ComicScramble"

    /** 段数上限：该算法最多产出 20 段（remainder*2+2，remainder ≤ 9）。 */
    private const val MAX_BANDS = 64

    /**
     * 从 `modifyImage` 脚本里识别「按 N 段竖向倒序重排」这一形态，返回段数 N。
     *
     * 识别条件（缺一不可，避免误判成别的像素操作）：
     * - 含 `fillImageRangeAt`（Venera 的按区域拷贝原语）；
     * - 含 `blockSize`（分段高度计算）；
     * - 有一个字面量段数 `num = <数字>`；
     * - 存在对 blocks 的倒序循环（`blocks.length - 1` 与 `i >= 0`）。
     *
     * @return 段数（≥2 才有意义）；无法识别返回 null
     */
    fun bandCountFromScript(script: String?): Int? {
        if (script.isNullOrBlank()) return null
        if (!script.contains("fillImageRangeAt")) return null
        if (!script.contains("blockSize")) return null
        if (!script.contains("blocks.length - 1") || !script.contains("i >= 0")) return null
        val num = NUM_LITERAL.find(script)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        if (num < 2 || num > MAX_BANDS) return null
        return num
    }

    /**
     * 各段在**原图**中的高度（与站点脚本里的 blocks 构造逐字一致）：
     * 等分为 `h / N`，余数全部并给最后一段，保证总和等于原图高度。
     */
    fun bandHeights(height: Int, count: Int): List<Int> {
        if (height <= 0 || count <= 1) return listOf(height.coerceAtLeast(0))
        val blockSize = height / count
        val remainder = height % count
        return List(count) { i -> blockSize + if (i == count - 1) remainder else 0 }
    }

    /**
     * 还原后，图片从上到下应依次填入的**原图段索引**。
     *
     * 站点脚本是把 blocks 从最后一段开始往下拼，所以顺序就是纯粹的倒序；
     * 抽成独立函数是为了让这段语义可以被离线单测钉住（Bitmap 在 JVM 单测里不可用）。
     */
    fun reverseBandOrder(count: Int): List<Int> = (count - 1 downTo 0).toList()

    /**
     * 把 [src] 按 [count] 段竖向倒序重排后返回新图。
     *
     * 段数 ≤ 1 或原图无效时直接返回 [src]（调用方无需做前置判断）。
     */
    fun unscramble(src: Bitmap, count: Int): Bitmap {
        if (count <= 1 || src.width <= 0 || src.height <= 0) return src
        val w = src.width
        val h = src.height
        val heights = bandHeights(h, count)
        if (heights.size <= 1) return src

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // 原图各段的起始 y（前缀和）
        val starts = IntArray(heights.size)
        var acc = 0
        heights.forEachIndexed { i, bh ->
            starts[i] = acc
            acc += bh
        }

        var y = 0
        for (i in reverseBandOrder(heights.size)) {
            val bh = heights[i]
            if (bh <= 0) continue
            val top = starts[i]
            canvas.drawBitmap(
                src,
                Rect(0, top, w, top + bh),
                Rect(0, y, w, y + bh),
                paint,
            )
            y += bh
        }
        return out
    }

    /**
     * 便捷入口：脚本 → 段数 → 还原。识别不出脚本时原样返回，并打一条一次性的告警。
     *
     * 告警按「脚本形态」去重，避免一话几十张图把日志刷满。
     */
    fun unscrambleByScript(src: Bitmap, script: String?): Bitmap {
        val count = bandCountFromScript(script)
        if (count == null) {
            if (!script.isNullOrBlank()) warnOnce(script)
            return src
        }
        return unscramble(src, count)
    }

    private val warned = HashSet<String>()

    private fun warnOnce(script: String) {
        // 去重指纹只在内存里用；脚本原文不写日志（可能带站点特有的地址与参数）
        val fingerprint = script.take(120)
        synchronized(warned) {
            if (!warned.add(fingerprint)) return
        }
        Log.w(TAG, "无法识别的 modifyImage 脚本（${script.length} 字符），已跳过还原")
    }

    /** 匹配脚本里的字面量段数：`const num = 6` / `num = 10` */
    private val NUM_LITERAL = Regex("""\bnum\s*=\s*(\d+)\b""")
}
