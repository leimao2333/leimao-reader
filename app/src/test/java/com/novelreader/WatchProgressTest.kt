package com.novelreader

import com.novelreader.core.util.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「继续观看」进度换算单测（v1.3.4.0）。
 *
 * 这段逻辑的失败模式是静默的 —— 进度条永远空着或永远满格，不会报错，
 * 所以边界必须靠单测钉住。
 */
class WatchProgressTest {

    private val eps = 0.0001f

    @Test
    fun `有时间进度时按时间算`() {
        // 看了 30 分钟 / 共 120 分钟 = 25%
        assertEquals(
            0.25f,
            WatchProgress.fraction(
                playPositionMs = 30 * 60_000L,
                durationMs = 120 * 60_000L,
                chapterIndex = 3,
                totalChapter = 40,
            ),
            eps,
        )
    }

    @Test
    fun `没有总时长时退回集数进度`() {
        // 第 5 集（下标 4）/ 共 20 集 = 25%
        assertEquals(
            0.25f,
            WatchProgress.fraction(
                playPositionMs = 600_000L,
                durationMs = 0L,
                chapterIndex = 4,
                totalChapter = 20,
            ),
            eps,
        )
    }

    @Test
    fun `直播流时长未知且只有一集时无进度`() {
        // 直播流：duration 一直是 0 / TIME_UNSET；单片电影也没有集数概念
        assertEquals(
            0f,
            WatchProgress.fraction(
                playPositionMs = 900_000L,
                durationMs = 0L,
                chapterIndex = 0,
                totalChapter = 1,
            ),
            eps,
        )
        assertEquals(
            0f,
            WatchProgress.fraction(0L, 0L, 0, 0),
            eps,
        )
    }

    @Test
    fun `进度超出范围被夹到 0 到 1`() {
        // 采集站偶尔给出比总时长还大的进度（占位时长不准）
        assertEquals(
            1f,
            WatchProgress.fraction(9_999_999L, 1_000L, 0, 1),
            eps,
        )
        assertEquals(
            0f,
            WatchProgress.fraction(-5L, 1_000L, 0, 1),
            eps,
        )
        // 集号越界同理
        assertEquals(
            1f,
            WatchProgress.fraction(0L, 0L, 99, 10),
            eps,
        )
    }

    @Test
    fun `单集不显示集数角标`() {
        assertEquals("", WatchProgress.episodeLabel(chapterIndex = 0, totalChapter = 1))
        assertEquals("", WatchProgress.episodeLabel(chapterIndex = 0, totalChapter = 0))
        // 集号从 0 起，展示时 +1
        assertEquals("1/24", WatchProgress.episodeLabel(chapterIndex = 0, totalChapter = 24))
        assertEquals("7/24", WatchProgress.episodeLabel(chapterIndex = 6, totalChapter = 24))
    }
}
