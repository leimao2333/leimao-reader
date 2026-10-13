package com.novelreader

import com.novelreader.ui.screens.video.formatDuration
import com.novelreader.ui.screens.video.trimSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视频播放器工具函数的单元测试（v1.5.0）。
 */
class VideoUtilsTest {

    @Test
    fun formatDuration_underOneHour() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:09", formatDuration(9_400))
        assertEquals("01:00", formatDuration(60_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun formatDuration_overOneHour() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("2:05:07", formatDuration(7_507_000))
    }

    @Test
    fun formatDuration_negativeOrUnknownTreatedAsZero() {
        assertEquals("00:00", formatDuration(-1_000))
    }

    @Test
    fun trimSpeed_dropsTrailingZeros() {
        assertEquals("1", trimSpeed(1f))
        assertEquals("2", trimSpeed(2f))
        assertEquals("0.5", trimSpeed(0.5f))
        assertEquals("1.25", trimSpeed(1.25f))
        assertEquals("1.5", trimSpeed(1.5f))
        assertEquals("0.75", trimSpeed(0.75f))
    }
}
