package com.novelreader

import com.novelreader.core.image.ComicImageScramble
import com.novelreader.core.image.HashBandScramble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「段数由哈希推导」方案的回归测试（纯离线）。
 *
 * 站点前端只给 `aid` 与页序号，段数按 `md5(aid + 页序号)` 现算；
 * 真机抓到的页面里 aid=1487381，本测试的期望值就是用站点自己的
 * `get_num` 逻辑跑出来的同一组数字，改成别的实现必须同步改这里。
 */
class HashBandScrambleTest {

    // ==================== md5 与段数推导 ====================

    @Test
    fun `md5 为小写十六进制且与站点一致`() {
        assertEquals("ba760afdff649b4242de381cb4f5ce69", HashBandScramble.md5Hex("148738100001"))
    }

    @Test
    fun `大号专辑按末位对 8 取模`() {
        // aid ≥ 421926
        val aid = 1_487_381L
        assertEquals(4, HashBandScramble.bandCount(aid, "00001"))
        assertEquals(8, HashBandScramble.bandCount(aid, "00002"))
        assertEquals(14, HashBandScramble.bandCount(aid, "00003"))
        assertEquals(10, HashBandScramble.bandCount(aid, "00004"))
        assertEquals(8, HashBandScramble.bandCount(aid, "00005"))
        assertEquals(14, HashBandScramble.bandCount(aid, "00006"))
        assertEquals(12, HashBandScramble.bandCount(aid, "00010"))
    }

    @Test
    fun `同一话内段数逐页变化`() {
        val aid = 1_487_381L
        val counts = (1..12).map { HashBandScramble.bandCount(aid, it.toString().padStart(5, '0')) }
        assertTrue("段数应当逐页不同，实际为 $counts", counts.distinct().size > 3)
        assertTrue("段数必须落在 2~20", counts.all { it in 2..20 })
    }

    @Test
    fun `专辑号区间边界按站点阈值切换`() {
        // 268850~421925 段用 %10，≥421926 用 %8，其余走默认 10
        assertEquals(10, HashBandScramble.bandCount(220_980L, "00001"))
        assertEquals(6, HashBandScramble.bandCount(268_850L, "00001"))
        assertEquals(18, HashBandScramble.bandCount(421_925L, "00001"))
        assertEquals(14, HashBandScramble.bandCount(421_926L, "00001"))
        assertEquals(12, HashBandScramble.bandCount(500_000L, "00001"))
    }

    // ==================== 标记与解析 ====================

    @Test
    fun `标记能被阅读链路解析`() {
        assertEquals(4, ComicImageScramble.bandCountFromScript(HashBandScramble.script(4)))
        assertEquals(20, ComicImageScramble.bandCountFromScript(HashBandScramble.script(20)))
    }

    @Test
    fun `非法标记不还原`() {
        assertNull(ComicImageScramble.bandCountFromScript("bands:"))
        assertNull(ComicImageScramble.bandCountFromScript("bands:1"))
        assertNull(ComicImageScramble.bandCountFromScript("bands:abc"))
    }

    @Test
    fun `普通图片地址不会被误判成标记`() {
        assertNull(ComicImageScramble.bandCountFromScript("https://example.com/a.jpg"))
    }

    // ==================== 几何等价性 ====================

    /**
     * 站点前端的粘贴循环（逐行照抄）：
     * ```
     * f = s % c; h = floor(s / c)
     * for g in 0 until c:
     *     var bh = h; var u = h * g; var p = s - h * (g + 1) - f
     *     if (g == 0) bh += f else u += f
     *     dst[u, u + bh) <- src[p, p + bh)
     * ```
     * 这里断言它与「按段倒序重排」完全一致：目标从上到下依次取原图最后一段、倒数第二段……
     */
    @Test
    fun `站点粘贴循环等价于分段倒序`() {
        for (height in listOf(1782, 1000, 997, 33, 31)) {
            for (count in 2..20) {
                val f = height % count
                val h = height / count
                val pieces = mutableListOf<Triple<Int, Int, Int>>() // dstY, srcY, height
                for (g in 0 until count) {
                    var bh = h
                    var u = h * g
                    var p = height - h * (g + 1) - f
                    if (g == 0) bh += f else u += f
                    pieces.add(Triple(u, p, bh))
                }

                // 目标区连续、无空洞、覆盖全图
                assertEquals(0, pieces.first().first)
                var expectY = 0
                for ((dstY, _, bh) in pieces) {
                    assertEquals("目标区应连续 h=$height c=$count", expectY, dstY)
                    expectY += bh
                }
                assertEquals("目标区应覆盖全图 h=$height c=$count", height, expectY)

                // 与「分段倒序」逐段对齐
                val heights = ComicImageScramble.bandHeights(height, count)
                val order = ComicImageScramble.reverseBandOrder(count)
                val starts = IntArray(heights.size)
                var acc = 0
                heights.forEachIndexed { i, bh ->
                    starts[i] = acc
                    acc += bh
                }
                var y = 0
                order.forEachIndexed { k, band ->
                    val bh = heights[band]
                    val (dstY, srcY, srcH) = pieces[k]
                    assertEquals("段高不一致 h=$height c=$count k=$k", bh, srcH)
                    assertEquals("源起点不一致 h=$height c=$count k=$k", starts[band], srcY)
                    assertEquals("目标起点不一致 h=$height c=$count k=$k", y, dstY)
                    y += bh
                }
            }
        }
    }
}
