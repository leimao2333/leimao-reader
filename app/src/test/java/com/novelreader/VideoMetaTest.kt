package com.novelreader

import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.components.VideoMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视元信息行测试（v1.3.7.0）。
 *
 * 聚合卡片的元信息行来自这里；字段全缺时必须返回空串，
 * 让调用方隐藏整行（而不是留一行空白）。
 */
class VideoMetaTest {

    private fun video(extras: Map<String, String>, author: String? = null) = MediaItem(
        id = "1",
        title = "流浪地球3",
        type = SourceType.VIDEO,
        author = author,
        extras = extras,
    )

    @Test
    fun `完整字段按固定顺序拼接`() {
        val line = VideoMeta.summaryLine(
            video(
                mapOf(
                    "score" to "9.2",
                    "typeName" to "连续剧",
                    "year" to "2026",
                    "area" to "大陆",
                    "remarks" to "更新至24集",
                )
            )
        )
        assertEquals("9.2 · 连续剧 · 2026 · 大陆 · 更新至24集", line)
    }

    @Test
    fun `缺失字段自动跳过不留空档`() {
        val line = VideoMeta.summaryLine(
            video(mapOf("typeName" to "电影", "year" to "2026"))
        )
        assertEquals("电影 · 2026", line)
    }

    @Test
    fun `全部缺失时返回空串`() {
        assertEquals("", VideoMeta.summaryLine(video(emptyMap())))
    }

    @Test
    fun `无评分不显示0分`() {
        // 采集站「无评分」有 0 / 0.0 / "" 三种写法，都不能冒出来
        assertNull(VideoMeta.scoreText(video(mapOf("score" to "0"))))
        assertNull(VideoMeta.scoreText(video(mapOf("score" to "0.0"))))
        assertNull(VideoMeta.scoreText(video(mapOf("score" to ""))))
        assertNull(VideoMeta.scoreText(video(mapOf("score" to "-1"))))
        assertEquals("9.2", VideoMeta.scoreText(video(mapOf("score" to "9.2"))))
    }

    @Test
    fun `整数评分不显示多余小数位`() {
        assertEquals("8", VideoMeta.scoreText(video(mapOf("score" to "8.0"))))
        assertEquals("8", VideoMeta.scoreText(video(mapOf("score" to "8"))))
    }

    @Test
    fun `非数字评分视为缺失`() {
        assertNull(VideoMeta.scoreText(video(mapOf("score" to "暂无"))))
    }

    @Test
    fun `年份只接受四位数字`() {
        assertEquals("2026", VideoMeta.year(video(mapOf("year" to "2026"))))
        assertEquals("2026", VideoMeta.year(video(mapOf("year" to " 2026 "))))
        assertNull(VideoMeta.year(video(mapOf("year" to "26"))))
        assertNull(VideoMeta.year(video(mapOf("year" to "未知"))))
        assertNull(VideoMeta.year(video(emptyMap())))
    }

    @Test
    fun `地区为未知视为缺失`() {
        assertNull(VideoMeta.area(video(mapOf("area" to "未知"))))
        assertNull(VideoMeta.area(video(mapOf("area" to " "))))
        assertEquals("韩国", VideoMeta.area(video(mapOf("area" to "韩国"))))
    }

    @Test
    fun `分类名缺失时回退到 author`() {
        // MacCmsParser 把 type_name 同时写进 author 与 extras.typeName
        assertEquals("连续剧", VideoMeta.typeName(video(emptyMap(), author = "连续剧")))
    }

    @Test
    fun `showScore为false时不显示评分`() {
        val line = VideoMeta.summaryLine(
            video(mapOf("score" to "9.2", "typeName" to "电影")),
            showScore = false,
        )
        assertTrue("不应包含评分", !line.contains("9.2"))
        assertEquals("电影", line)
    }
}
