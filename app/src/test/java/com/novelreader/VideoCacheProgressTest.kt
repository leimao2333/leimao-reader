package com.novelreader

import com.novelreader.core.util.VideoCacheProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进度条「缓存/缓冲」展示口径（v1.7.7.0）。
 *
 * 这些边界在界面上很难手动验（要把缓冲卡在 99%、或者起播瞬间只有几十毫秒缓冲），
 * 所以抽成纯逻辑在单测里锁住。
 */
class VideoCacheProgressTest {

    @Test
    fun `百分比按时间轴线性换算并夹取`() {
        assertEquals(0, VideoCacheProgress.percent(0, 100_000))
        assertEquals(50, VideoCacheProgress.percent(50_000, 100_000))
        assertEquals(100, VideoCacheProgress.percent(100_000, 100_000))
        // 超出总时长（HLS 偶尔会给出略大的 bufferedPosition）不越界
        assertEquals(100, VideoCacheProgress.percent(120_000, 100_000))
        // 负数保护
        assertEquals(0, VideoCacheProgress.percent(-5_000, 100_000))
    }

    @Test
    fun `时长未知时不显示也不换算`() {
        assertEquals(0, VideoCacheProgress.percent(10_000, 0))
        assertNull(VideoCacheProgress.label(10_000, 0))
        assertEquals(0f, VideoCacheProgress.bufferedFraction(10_000, 0), 0.0001f)
        assertEquals(0f, VideoCacheProgress.playedFraction(10_000, 0), 0.0001f)
    }

    @Test
    fun `还没开始缓冲时不显示文案`() {
        assertNull(VideoCacheProgress.label(0, 100_000))
        assertNull(VideoCacheProgress.label(-1, 100_000))
    }

    @Test
    fun `缓冲满显示已缓存`() {
        assertEquals("已缓存", VideoCacheProgress.label(100_000, 100_000))
        // 略超过总时长同样按已缓存处理
        assertEquals("已缓存", VideoCacheProgress.label(130_000, 100_000))
    }

    @Test
    fun `刚开始的那一点点缓冲至少显示一趴`() {
        // 起播时 bufferedPosition 常常只有几十毫秒，直接算出来是 0%，
        // 但进度条明明动了一点 —— 显示 0% 会像坏了
        assertEquals("缓存 1%", VideoCacheProgress.label(50, 100_000))
        assertEquals("缓存 37%", VideoCacheProgress.label(37_000, 100_000))
    }

    @Test
    fun `进度比例夹在 0 到 1 之间`() {
        assertEquals(0f, VideoCacheProgress.playedFraction(0, 10_000), 0.0001f)
        assertEquals(0.5f, VideoCacheProgress.playedFraction(5_000, 10_000), 0.0001f)
        assertEquals(1f, VideoCacheProgress.playedFraction(20_000, 10_000), 0.0001f)
        assertEquals(1f, VideoCacheProgress.bufferedFraction(20_000, 10_000), 0.0001f)
    }
}
