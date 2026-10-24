package com.novelreader

import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.adaptiveGridColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平板 / 大屏宫格自适应列数（v1.8.8.3）。
 *
 * 覆盖：手机竖屏维持原口径（视频 3 / 其余 2）、各宽度档位阶梯加列、
 * 上限 8 列、用户手动列数不参与（那是在调用处短路）。
 */
class GridColumnsTest {

    @Test
    fun `手机竖屏维持原口径`() {
        assertEquals(2, adaptiveGridColumns(360, SourceType.NOVEL))
        assertEquals(2, adaptiveGridColumns(411, SourceType.COMIC))
        assertEquals(3, adaptiveGridColumns(392, SourceType.VIDEO))
    }

    @Test
    fun `平板与横屏按档位加列`() {
        // 480dp 起 +1
        assertEquals(3, adaptiveGridColumns(480, SourceType.NOVEL))
        // 600dp（平板分界）+2
        assertEquals(4, adaptiveGridColumns(600, SourceType.NOVEL))
        assertEquals(5, adaptiveGridColumns(600, SourceType.VIDEO))
        // 800dp +3
        assertEquals(5, adaptiveGridColumns(800, SourceType.NOVEL))
        // 1200dp +5
        assertEquals(7, adaptiveGridColumns(1200, SourceType.NOVEL))
    }

    @Test
    fun `列数封顶（小说 7 视频 8）`() {
        assertEquals(7, adaptiveGridColumns(2000, SourceType.NOVEL))
        assertEquals(8, adaptiveGridColumns(3000, SourceType.VIDEO))
    }

    @Test
    fun `宽度单调不减`() {
        var prev = adaptiveGridColumns(1, SourceType.NOVEL)
        for (w in 2..1400 step 7) {
            val cur = adaptiveGridColumns(w, SourceType.NOVEL)
            assertTrue("宽度 $w 处列数变小：$prev -> $cur", cur >= prev)
            prev = cur
        }
    }
}
