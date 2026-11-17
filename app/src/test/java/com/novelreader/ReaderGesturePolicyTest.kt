package com.novelreader

import com.novelreader.core.reader.ReaderGesturePolicy
import com.novelreader.core.reader.ReaderGesturePolicy.TapAction
import com.novelreader.core.reader.ReaderGesturePolicy.TapZoneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A8 阅读页手势策略 单测。 */
class ReaderGesturePolicyTest {

    private val width = 900f

    @Test
    fun `三区模式左中右各司其职`() {
        // 左 1/3 = 0..300
        assertEquals(TapAction.PREV_PAGE, ReaderGesturePolicy.resolveTap(100f, width))
        // 中 1/3 = 300..600
        assertEquals(TapAction.TOGGLE_MENU, ReaderGesturePolicy.resolveTap(450f, width))
        // 右 1/3 = 600..900
        assertEquals(TapAction.NEXT_PAGE, ReaderGesturePolicy.resolveTap(800f, width))
    }

    @Test
    fun `两区模式没有呼菜单区域`() {
        assertEquals(TapAction.PREV_PAGE, ReaderGesturePolicy.resolveTap(100f, width, TapZoneMode.TWO_ZONE))
        assertEquals(TapAction.NEXT_PAGE, ReaderGesturePolicy.resolveTap(800f, width, TapZoneMode.TWO_ZONE))
        // 正中点归右区（>= width/2）
        assertEquals(TapAction.NEXT_PAGE, ReaderGesturePolicy.resolveTap(450f, width, TapZoneMode.TWO_ZONE))
    }

    @Test
    fun `分区比例可调`() {
        // 左区放大到 50%：450 以内都是上一页
        assertEquals(
            TapAction.PREV_PAGE,
            ReaderGesturePolicy.resolveTap(440f, width, TapZoneMode.THREE_ZONE, leftRatio = 0.5f),
        )
    }

    @Test
    fun `越界与非法输入安全返回`() {
        assertEquals(TapAction.NONE, ReaderGesturePolicy.resolveTap(10f, 0f))
        assertEquals(TapAction.NONE, ReaderGesturePolicy.resolveTap(Float.NaN, width))
        // x 超出宽度会被夹取，不应抛异常
        assertEquals(TapAction.NEXT_PAGE, ReaderGesturePolicy.resolveTap(9999f, width))
        assertEquals(TapAction.PREV_PAGE, ReaderGesturePolicy.resolveTap(-50f, width))
    }

    @Test
    fun `下拉超过阈值触发加书签`() {
        assertTrue(ReaderGesturePolicy.shouldPullBookmark(dy = 80f, dx = 2f))
        assertTrue(ReaderGesturePolicy.shouldPullBookmark(dy = 72f, dx = 0f))
    }

    @Test
    fun `未达阈值或超过上限不触发`() {
        assertFalse(ReaderGesturePolicy.shouldPullBookmark(dy = 40f, dx = 0f))
        assertFalse("拉太远视为普通滚动", ReaderGesturePolicy.shouldPullBookmark(dy = 200f, dx = 0f))
    }

    @Test
    fun `斜滑不触发`() {
        // 横向位移超过竖直一半 → 判为斜滑
        assertFalse(ReaderGesturePolicy.shouldPullBookmark(dy = 90f, dx = 60f))
        // 轻微横向抖动仍算下拉
        assertTrue(ReaderGesturePolicy.shouldPullBookmark(dy = 90f, dx = 10f))
    }

    @Test
    fun `同一次手势只加一次书签`() {
        assertFalse(
            ReaderGesturePolicy.shouldPullBookmark(dy = 100f, dx = 0f, alreadyTriggered = true)
        )
    }

    @Test
    fun `手柄与键盘按键映射`() {
        assertEquals(-1, ReaderGesturePolicy.gamepadDirection(19)) // DPAD_UP
        assertEquals(1, ReaderGesturePolicy.gamepadDirection(20))  // DPAD_DOWN
        assertEquals(-1, ReaderGesturePolicy.gamepadDirection(21)) // DPAD_LEFT
        assertEquals(1, ReaderGesturePolicy.gamepadDirection(22))  // DPAD_RIGHT
        assertEquals(-1, ReaderGesturePolicy.gamepadDirection(92)) // PAGE_UP
        assertEquals(1, ReaderGesturePolicy.gamepadDirection(93))  // PAGE_DOWN
        assertEquals(1, ReaderGesturePolicy.gamepadDirection(96))  // BUTTON_A
        assertEquals(-1, ReaderGesturePolicy.gamepadDirection(97)) // BUTTON_B
        assertEquals(0, ReaderGesturePolicy.gamepadDirection(4))   // BACK 不处理
    }

    @Test
    fun `翻页键判定与方向一致`() {
        assertTrue(ReaderGesturePolicy.isPageTurnKey(20))
        assertTrue(ReaderGesturePolicy.isPageTurnKey(97))
        assertFalse(ReaderGesturePolicy.isPageTurnKey(4))
        assertFalse(ReaderGesturePolicy.isPageTurnKey(0))
    }
}
