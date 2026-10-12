package com.novelreader

import com.novelreader.core.image.ComicImageScramble
import com.novelreader.domain.model.ContentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 漫画图片「分段乱序」还原回归测试（纯离线，不依赖 android.graphics）。
 *
 * 背景：禁漫（JM）等站点把每页图片**按 N 段纵向切开再打乱**后下发，
 * 站点规则以 JS 片段（Venera 的 `comic.onImageLoad → modifyImage`）的形式给出。
 * 真机实测：不还原时读者看到的就是一页页乱序画面（用户反馈「图片是乱序的」）。
 *
 * Bitmap 在 JVM 单测里不可用，所以这里只钉住**段数识别**与**分段几何**这两处
 * 最容易写错的纯逻辑；像素搬运的正确性由「倒序入位」这一条不变量保证。
 */
class ComicImageScrambleTest {

    /** 与 jm.js 里 `onImageLoad` 生成的脚本逐字同源（只把 num 换成变量） */
    private fun jmScript(num: Int) = """
        let modifyImage = (image) => {
            const num = $num
            let blockSize = Math.floor(image.height / num)
            let remainder = image.height % num
            let blocks = []
            for(let i = 0; i < num; i++) {
                let start = i * blockSize
                let end = start + blockSize + (i !== num - 1 ? 0 : remainder)
                blocks.push({
                    start: start,
                    end: end
                })
            }
            let res = Image.empty(image.width, image.height)
            let y = 0
            for(let i = blocks.length - 1; i >= 0; i--) {
                let block = blocks[i]
                let currentHeight = block.end - block.start
                res.fillImageRangeAt(0, y, image, 0, block.start, image.width, currentHeight)
                y += currentHeight
            }
            return res
        }
    """.trimIndent()

    // ==================== 脚本识别 ====================

    @Test
    fun `真实禁漫脚本能被识别并取到段数`() {
        // 真机抓到的这一话，每页段数各不相同（4/14/16/12/8/2 …）
        assertEquals(4, ComicImageScramble.bandCountFromScript(jmScript(4)))
        assertEquals(16, ComicImageScramble.bandCountFromScript(jmScript(16)))
        assertEquals(20, ComicImageScramble.bandCountFromScript(jmScript(20)))
    }

    @Test
    fun `无脚本时不还原`() {
        assertNull(ComicImageScramble.bandCountFromScript(null))
        assertNull(ComicImageScramble.bandCountFromScript(""))
        assertNull(ComicImageScramble.bandCountFromScript("   "))
    }

    @Test
    fun `段数为 1 时不还原`() {
        // jm.js 对 num <= 1 直接返回 {}（gif 与老章节都是这种情况）
        assertNull(ComicImageScramble.bandCountFromScript(jmScript(1)))
    }

    @Test
    fun `段数超出合理范围时不还原`() {
        assertNull(ComicImageScramble.bandCountFromScript(jmScript(0)))
        assertNull(ComicImageScramble.bandCountFromScript(jmScript(999)))
    }

    @Test
    fun `形态不符的像素操作不被误判`() {
        // 只有 num 字面量，但没有分段/倒序拼接的语义 → 不能当成「倒序 N 段」
        assertNull(ComicImageScramble.bandCountFromScript("const num = 6"))
        // 有分段但没有倒序（不是这套算法）→ 同样不识别
        assertNull(
            ComicImageScramble.bandCountFromScript(
                "const num = 6; let blockSize = 10; res.fillImageRangeAt(0, 0, image, 0, 0, 1, 1);",
            ),
        )
    }

    // ==================== 分段几何（与站点脚本逐字一致） ====================

    @Test
    fun `能整除时各段等高`() {
        assertEquals(listOf(250, 250, 250, 250), ComicImageScramble.bandHeights(1000, 4))
    }

    @Test
    fun `余数全部并入最后一段`() {
        // 1000 / 6 = 166 余 4 → 末段 170（而不是各段 ±1 摊平）
        assertEquals(listOf(166, 166, 166, 166, 166, 170), ComicImageScramble.bandHeights(1000, 6))
    }

    @Test
    fun `段数大于高度时前面的段为空`() {
        assertEquals(listOf(0, 0, 0, 0, 3), ComicImageScramble.bandHeights(3, 5))
    }

    @Test
    fun `任意高度与段数下分段之和恒等于图片高度`() {
        for (h in 1..64) {
            for (n in 2..20) {
                val sum = ComicImageScramble.bandHeights(h, n).sum()
                assertEquals("height=$h count=$n 分段高度之和必须等于原图高度", h, sum)
            }
        }
    }

    // ==================== 倒序入位 ====================

    @Test
    fun `还原就是纯粹的段倒序`() {
        assertEquals(listOf(5, 4, 3, 2, 1, 0), ComicImageScramble.reverseBandOrder(6))
        assertEquals(listOf(1, 0), ComicImageScramble.reverseBandOrder(2))
    }

    @Test
    fun `倒序后原图最后一段落到图片顶部`() {
        // 用字符代表各段的画面内容（原图自上而下 A..F），还原后应为 F..A
        val source = listOf("A", "B", "C", "D", "E", "F")
        val restored = ComicImageScramble.reverseBandOrder(source.size).map { source[it] }
        assertEquals(listOf("F", "E", "D", "C", "B", "A"), restored)
    }

    @Test
    fun `段数小于等于 1 时不变换`() {
        assertEquals(listOf(0), ComicImageScramble.reverseBandOrder(1))
        assertEquals(emptyList<Int>(), ComicImageScramble.reverseBandOrder(0))
    }

    // ==================== 领域模型贯通 ====================

    @Test
    fun `ContentResult 工厂会把逐图脚本带上`() {
        val scripts = mapOf("https://a/1.webp" to jmScript(6))
        val result = ContentResult.comic(listOf("https://a/1.webp"), emptyMap(), scripts)
        assertEquals(scripts, result.comicImageScripts)
        assertEquals(1, result.comicImages?.size)
    }

    @Test
    fun `没有脚本时默认为空且不影响其它字段`() {
        val result = ContentResult.comic(listOf("https://a/1.jpg"), mapOf("Referer" to "https://a/"))
        assertEquals(emptyMap<String, String>(), result.comicImageScripts)
        assertEquals("https://a/", result.comicHeaders["Referer"])
    }

    // ==================== 与 runtime.js 的契约 ====================

    /**
     * runtime.js 的 `serializeImages` 必须把 `scripts` 带出来，
     * 否则 app 侧拿不到段数（本轮修的就是这个断链）。
     * 直接读资产文件做一次字符串级断言，避免以后被误删。
     */
    @Test
    fun `运行时资产确实产出了 scripts 字段`() {
        val asset = java.io.File("src/main/assets/venera/runtime.js")
        if (!asset.isFile) return // 从模块根目录以外的 cwd 跑时跳过
        val text = asset.readText()
        assertEquals(
            "serializeImages 必须返回 scripts（逐图乱序还原脚本）",
            true,
            text.contains("return { images: urls, headers: headers, scripts: scripts };"),
        )
        assertEquals(
            "必须把 onImageLoad 的 modifyImage 收进 scripts",
            true,
            text.contains("scripts[finalUrl] = r.modifyImage"),
        )
    }
}
