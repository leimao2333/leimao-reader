package com.novelreader

import com.novelreader.core.util.FeedCachePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页推荐缓存判据（v1.9.5.0）。
 *
 * 回归重点：源很多、枚举受全局时限限制只覆盖一部分时，缓存必须仍然可用 ——
 * 旧判据 `enumerated * 2 < enabled.size` 会让 2700+ 源的设备缓存永远判废，
 * 表现就是「每次切回首页都重新联网加载」。
 */
class FeedCachePolicyTest {

    private fun ids(n: Int): Set<String> = (1..n).map { "s$it" }.toSet()

    private fun check(
        cached: Set<String>,
        enumerated: Int,
        rows: Int,
        enabled: Set<String> = cached,
    ) = FeedCachePolicy.check(
        FeedCachePolicy.Input(
            cachedSourceIds = cached,
            enumerated = enumerated,
            rowCount = rows,
            enabledSourceIds = enabled,
        ),
    )

    @Test
    fun largeLibraryWithPartialEnumerationIsUsable() {
        val enabled = ids(2700)
        // 2700 个源、枚举只覆盖 320 个：旧判据会判废，新判据必须放行
        val verdict = check(enabled, enumerated = 320, rows = 8)
        assertTrue(verdict.reason, verdict.usable)
    }

    @Test
    fun smallSourceSetChangesDoNotInvalidateCache() {
        val cached = ids(2700)
        val enabled = cached - "s1" - "s2" - "s3" + "newA" + "newB"
        val verdict = check(cached, enumerated = 320, rows = 8, enabled = enabled)
        assertTrue(verdict.reason, verdict.usable)
    }

    @Test
    fun largeSourceSetChangeIsReusableButStale() {
        // 源集大变不再判废：旧内容先上屏，避免每次进首页都等一轮全量枚举
        val verdict = check(ids(100), enumerated = 80, rows = 8, enabled = ids(2700))
        assertTrue(verdict.reason, verdict.usable)
        assertTrue(verdict.reason, verdict.stale)
    }

    @Test
    fun unchangedSourceSetIsNotStale() {
        val verdict = check(ids(2700), enumerated = 320, rows = 8)
        assertFalse(verdict.reason, verdict.stale)
    }

    @Test
    fun coldStartPlanCoveringOneSourceIsRejected() {
        val verdict = check(ids(2700), enumerated = 1, rows = 8)
        assertFalse(verdict.reason, verdict.usable)
    }

    @Test
    fun tinyLibraryFullyCoveredIsUsable() {
        val verdict = check(ids(3), enumerated = 3, rows = 4)
        assertTrue(verdict.reason, verdict.usable)
    }

    @Test
    fun emptyRowsOrEmptySourceIdsAreRejected() {
        assertFalse(check(ids(100), enumerated = 50, rows = 0).usable)
        assertFalse(check(emptySet(), enumerated = 50, rows = 4).usable)
    }

    @Test
    fun toleranceScalesWithLibrarySize() {
        // 2700 源允许 5%（135 个）变化；100 个以内则按绝对下限 20
        val large = ids(2700)
        assertTrue(check(large, 300, 8, enabled = large - (1..120).map { "s$it" }.toSet()).usable)
        val small = ids(10)
        assertTrue(check(small, 8, 4, enabled = ids(40)).stale)
    }
}
