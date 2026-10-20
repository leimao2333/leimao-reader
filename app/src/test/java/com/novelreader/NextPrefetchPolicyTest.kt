package com.novelreader

import com.novelreader.core.media.NextPrefetchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尾段预缓冲判定的单测（v1.8.4.4）。
 *
 * 关注点不是「20s 这个数字」，而是**短路顺序与边界**：
 * 预缓存关档要先于「仅 WiFi」被拦下（不探测网络），
 * 同一集已触发优先于一切（同一集只下一次），
 * 触发点本身要放行（`>` 不是 `>=`）。
 */
class NextPrefetchPolicyTest {

    private fun go(
        remainingMs: Long = 5_000L,
        hasNext: Boolean = true,
        alreadyTriggered: Boolean = false,
        bufferSeconds: Int = 120,
        wifiOnly: Boolean = false,
        onWifi: Boolean = false,
    ) = NextPrefetchPolicy.shouldPrefetch(
        remainingMs = remainingMs,
        hasNext = hasNext,
        alreadyTriggered = alreadyTriggered,
        bufferSeconds = bufferSeconds,
        wifiOnly = wifiOnly,
        onWifi = onWifi,
    )

    @Test
    fun `剩余 5 秒且有下一集时放行`() {
        assertTrue(go())
    }

    @Test
    fun `触发点本身放行 超出 1ms 就被拦`() {
        assertTrue(go(remainingMs = NextPrefetchPolicy.TRIGGER_MS))
        assertFalse(go(remainingMs = NextPrefetchPolicy.TRIGGER_MS + 1))
    }

    @Test
    fun `时长未知 剩余为负 被拦`() {
        assertFalse(go(remainingMs = -1L))
        assertEquals("时长未知", blockReason(-1L))
    }

    @Test
    fun `没有下一集时被拦`() {
        assertFalse(go(hasNext = false))
    }

    @Test
    fun `同一集已触发过时被拦 优先于其他条件`() {
        // 即使满足触发条件（剩余 5s、有下一集、预缓存开着），同一集也只下一次
        assertFalse(go(alreadyTriggered = true))
        assertEquals("这一集已经预缓冲过", blockReason(remainingMs = 5_000L, alreadyTriggered = true))
    }

    @Test
    fun `预缓存时长关闭时被拦 且先于仅WiFi判定`() {
        // 关档时即使开了仅 WiFi 且不在 WiFi，拦下的原因也是「预缓存已关闭」
        // —— 否则日志会让人以为该把设置关掉
        assertFalse(go(bufferSeconds = 0, wifiOnly = true, onWifi = false))
        assertEquals("预缓存时长已关闭", blockReason(remainingMs = 5_000L, bufferSeconds = 0, wifiOnly = true, onWifi = false))
    }

    @Test
    fun `仅WiFi开启且不在WiFi时被拦`() {
        assertFalse(go(wifiOnly = true, onWifi = false))
        assertEquals("仅 WiFi 预缓冲，当前是移动网络", blockReason(remainingMs = 5_000L, wifiOnly = true, onWifi = false))
    }

    @Test
    fun `仅WiFi开启但在WiFi上时放行`() {
        assertTrue(go(wifiOnly = true, onWifi = true))
    }

    @Test
    fun `仅WiFi关闭时不再关心当前网络`() {
        // wifiOnly = false 时 onWifi = false 也不该拦住（调用方可以直接传 true 省一次系统调用）
        assertTrue(go(wifiOnly = false, onWifi = false))
    }

    private fun blockReason(
        remainingMs: Long = 5_000L,
        hasNext: Boolean = true,
        alreadyTriggered: Boolean = false,
        bufferSeconds: Int = 120,
        wifiOnly: Boolean = false,
        onWifi: Boolean = false,
    ) = NextPrefetchPolicy.blockReason(
        remainingMs = remainingMs,
        hasNext = hasNext,
        alreadyTriggered = alreadyTriggered,
        bufferSeconds = bufferSeconds,
        wifiOnly = wifiOnly,
        onWifi = onWifi,
    )
}
