package com.novelreader

import com.novelreader.core.reader.ReaderAutoPagePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A5 自动翻页策略 / 倍速档位 单测。 */
class ReaderAutoPagePolicyTest {

    @Test
    fun `倍速越高间隔越短`() {
        val base = 20_000L
        val slow = ReaderAutoPagePolicy.intervalMs(base, 1.0f)
        val fast = ReaderAutoPagePolicy.intervalMs(base, 4.0f)
        assertEquals(20_000L, slow)
        assertEquals(5_000L, fast)
        assertTrue("倍速必须缩短间隔", fast < slow)
    }

    @Test
    fun `间隔不会低于下限`() {
        // 20s / 100x = 200ms < 300ms 下限
        val clamped = ReaderAutoPagePolicy.intervalMs(20_000L, 100f)
        assertEquals(ReaderAutoPagePolicy.MIN_INTERVAL_MS, clamped)
    }

    @Test
    fun `非法倍速按 1 倍处理`() {
        assertEquals(20_000L, ReaderAutoPagePolicy.intervalMs(20_000L, 0f))
        assertEquals(20_000L, ReaderAutoPagePolicy.intervalMs(20_000L, -3f))
        assertEquals(20_000L, ReaderAutoPagePolicy.intervalMs(20_000L, Float.NaN))
    }

    @Test
    fun `按字数估算停留时间`() {
        // 300 字 / 300 字每分钟 = 60s
        assertEquals(60_000L, ReaderAutoPagePolicy.nextDelayMs(300, 300, 1.0f))
        // 同样字数、2 倍速 = 30s
        assertEquals(30_000L, ReaderAutoPagePolicy.nextDelayMs(300, 300, 2.0f))
    }

    @Test
    fun `无剩余字数可直接翻页`() {
        assertEquals(0L, ReaderAutoPagePolicy.nextDelayMs(0, 300, 1.0f))
        assertEquals(0L, ReaderAutoPagePolicy.nextDelayMs(-5, 300, 1.0f))
    }

    @Test
    fun `字数估算结果不低于下限`() {
        assertEquals(
            ReaderAutoPagePolicy.MIN_INTERVAL_MS,
            ReaderAutoPagePolicy.nextDelayMs(1, 300, 1.0f),
        )
    }

    @Test
    fun `到点判断`() {
        assertTrue(ReaderAutoPagePolicy.shouldAutoTurn(nowMs = 1000, lastTurnMs = 0, intervalMs = 500))
        assertFalse(ReaderAutoPagePolicy.shouldAutoTurn(nowMs = 400, lastTurnMs = 0, intervalMs = 500))
        assertTrue("间隔为 0 视为立即翻页", ReaderAutoPagePolicy.shouldAutoTurn(0, 0, 0))
    }

    @Test
    fun `倍速吸附到最近档位`() {
        assertEquals(1.0f, ReaderAutoPagePolicy.normalizeSpeed(1.1f))
        assertEquals(2.0f, ReaderAutoPagePolicy.normalizeSpeed(1.9f))
        assertEquals(4.0f, ReaderAutoPagePolicy.normalizeSpeed(99f))
        assertEquals(1.0f, ReaderAutoPagePolicy.normalizeSpeed(0f))
        assertEquals(1.0f, ReaderAutoPagePolicy.normalizeSpeed(Float.NaN))
    }

    @Test
    fun `吸附结果一定在档位表内`() {
        var v = 0.1f
        while (v < 10f) {
            val n = ReaderAutoPagePolicy.normalizeSpeed(v)
            assertTrue(
                "normalizeSpeed($v)=$n 不在档位表",
                ReaderAutoPagePolicy.SPEED_CHOICES.contains(n),
            )
            v += 0.13f
        }
    }
}
