package com.novelreader

import com.novelreader.core.media.VideoBufferOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频预缓存时长档位单测（v1.8.4.0，待办 #8）。
 *
 * `DefaultLoadControl` 对四个毫秒值有硬约束：
 * `bufferForPlaybackMs <= minBufferMs <= maxBufferMs`，给错了直接抛
 * `IllegalArgumentException`（崩在播放页）。这里把每个档位都过一遍。
 */
class VideoBufferOptionsTest {

    @Test
    fun `每个档位都满足 DefaultLoadControl 的约束`() {
        VideoBufferOptions.CHOICES.forEach { sec ->
            val d = VideoBufferOptions.durationsMs(sec)
            val (min, max, forPlayback, afterRebuffer) = d.toList()
            assertTrue("档位 $sec: forPlayback($forPlayback) <= min($min)", forPlayback <= min)
            assertTrue("档位 $sec: min($min) <= max($max)", min <= max)
            assertTrue("档位 $sec: afterRebuffer($afterRebuffer) <= min($min)", afterRebuffer <= min)
        }
    }

    @Test
    fun `档位越大缓冲越久`() {
        val maxes = VideoBufferOptions.CHOICES.map { VideoBufferOptions.durationsMs(it)[1] }
        assertEquals(maxes.sorted(), maxes)
    }

    @Test
    fun `关闭档仍保留最小缓冲`() {
        val d = VideoBufferOptions.durationsMs(VideoBufferOptions.OFF)
        assertTrue("关闭不等于不缓冲", d[1] >= 15_000)
    }

    @Test
    fun `脏数据对齐到最近的合法档位`() {
        assertEquals(0, VideoBufferOptions.normalize(-5))
        assertEquals(30, VideoBufferOptions.normalize(28))
        // v1.8.7.9（待办 #32）：最长档位从 120 提到 600，超出范围的脏数据对齐到 600
        assertEquals(600, VideoBufferOptions.normalize(9999))
        assertEquals(300, VideoBufferOptions.normalize(280))
    }

    @Test
    fun `文案`() {
        assertEquals("关闭", VideoBufferOptions.label(0))
        assertEquals("30s", VideoBufferOptions.label(30))
        // 5 分钟以上按分钟展示
        assertEquals("5min", VideoBufferOptions.label(300))
        assertEquals("10min", VideoBufferOptions.label(600))
    }

    /**
     * v1.8.7.9（待办 #32）：新加的长档位必须真的落到 `maxBufferMs`。
     *
     * 这一条专门防「只往 CHOICES 里塞了数字、`durationsMs` 的 when 没加分支」——
     * 那种情况下所有长档位都会静默按 120s 处理，设置看着生效、实际没变。
     */
    @Test
    fun `长档位按档位值换算而不是统一退回 120 秒`() {
        assertEquals(180_000, VideoBufferOptions.durationsMs(180)[1])
        assertEquals(300_000, VideoBufferOptions.durationsMs(300)[1])
        assertEquals(600_000, VideoBufferOptions.durationsMs(600)[1])
        // 最长档位与偏好层钳制上界要一致，否则写不进去
        assertEquals(600, VideoBufferOptions.MAX_SECONDS)
    }
}
