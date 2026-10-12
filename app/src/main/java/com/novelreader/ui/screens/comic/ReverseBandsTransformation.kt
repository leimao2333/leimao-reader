package com.novelreader.ui.screens.comic

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import com.novelreader.core.image.ComicImageScramble

/**
 * Coil 变换：把「按 [count] 段纵向打乱」的漫画图片还原成正常顺序。
 *
 * 站点规则来自源（见 [ComicImageScramble]），这里只把它落到图片解码链路上，
 * 这样内存缓存 / 磁盘缓存里存的都是**还原后**的图，翻页时不会再闪一下乱序画面。
 */
class ReverseBandsTransformation(private val count: Int) : Transformation {

    override val cacheKey: String = "comic-reverse-bands:$count"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        ComicImageScramble.unscramble(input, count)
}
