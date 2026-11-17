package com.novelreader

import com.novelreader.core.source.HealthVerdict
import com.novelreader.core.source.SourceHealth
import com.novelreader.core.source.SourceHealthChecker
import org.junit.Assert.assertEquals
import org.junit.Test

/** B5 书源健康判定 单测（只测纯函数，不触网）。 */
class SourceHealthCheckerTest {

    @Test
    fun `成功且快速判定为健康`() {
        assertEquals(HealthVerdict.HEALTHY, SourceHealthChecker.verdict(latencyMs = 800, ok = true))
        assertEquals(HealthVerdict.HEALTHY, SourceHealthChecker.verdict(latencyMs = 2_999, ok = true))
    }

    @Test
    fun `成功但超阈值判定为偏慢`() {
        assertEquals(HealthVerdict.SLOW, SourceHealthChecker.verdict(latencyMs = 3_000, ok = true))
        assertEquals(HealthVerdict.SLOW, SourceHealthChecker.verdict(latencyMs = 30_000, ok = true))
    }

    @Test
    fun `自检失败判定为失效`() {
        assertEquals(HealthVerdict.DEAD, SourceHealthChecker.verdict(latencyMs = 500, ok = false))
        assertEquals(HealthVerdict.DEAD, SourceHealthChecker.verdict(latencyMs = 9_999, ok = false))
    }

    @Test
    fun `未知耗时判定为无法判断`() {
        assertEquals(HealthVerdict.UNKNOWN, SourceHealthChecker.verdict(latencyMs = -1, ok = true))
        assertEquals(HealthVerdict.UNKNOWN, SourceHealthChecker.verdict(latencyMs = -1, ok = false))
    }

    @Test
    fun `统计摘要四档恒存在`() {
        val list = listOf(
            SourceHealth("a", "A", ok = true, latencyMs = 100, checkedAt = 0),
            SourceHealth("b", "B", ok = true, latencyMs = 5_000, checkedAt = 0),
            SourceHealth("c", "C", ok = false, latencyMs = 300, checkedAt = 0, reason = "超时"),
            SourceHealth("a", "A", ok = true, latencyMs = 200, checkedAt = 0),
        )
        val summary = SourceHealthChecker.summarize(list)

        // summarize 按条目计数（不按 sourceId 去重）：HEALTHY 2、SLOW 1、DEAD 1、UNKNOWN 0
        assertEquals(2, summary[HealthVerdict.HEALTHY])
        assertEquals(1, summary[HealthVerdict.SLOW])
        assertEquals(1, summary[HealthVerdict.DEAD])
        assertEquals(0, summary[HealthVerdict.UNKNOWN])
        // 四档都必须有键，避免 UI 取 null
        HealthVerdict.entries.forEach { assertEquals("缺少分档 $it", true, summary.containsKey(it)) }
    }

    @Test
    fun `空列表摘要全为 0`() {
        val summary = SourceHealthChecker.summarize(emptyList())
        HealthVerdict.entries.forEach { assertEquals(0, summary[it]) }
    }
}
