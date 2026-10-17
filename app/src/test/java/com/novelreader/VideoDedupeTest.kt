package com.novelreader

import com.novelreader.core.util.VideoDedupe
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视频条目按标题去重（v1.7.6.4）。
 *
 * 回归背景：首页视频网格此前只按 `(sourceId,id)` 去重，同一部片被多个采集源
 * 收录时同一张海报会重复出现好几遍（用户实测「童年阴影」连出 4 张）。
 */
class VideoDedupeTest {

    private fun item(
        id: String,
        title: String,
        source: String = "s1",
        cover: String? = "http://cover/$id.jpg",
        year: String? = null,
    ) = MediaItem(
        id = id,
        title = title,
        cover = cover,
        type = SourceType.VIDEO,
        sourceId = source,
        sourceName = source,
        extras = if (year != null) mapOf("year" to year) else emptyMap(),
    )

    @Test
    fun `同名跨源合并为一条`() {
        val items = listOf(
            item("1", "童年阴影", "s1"),
            item("2", "童年阴影", "s2"),
            item("3", "童年阴影", "s3"),
        )
        val out = VideoDedupe.byTitle(items)
        assertEquals(1, out.size)
        assertEquals("1", out[0].id)
    }

    @Test
    fun `书名号与空白差异也能合并`() {
        val items = listOf(item("1", "《童年阴影》"), item("2", "童年 阴影"))
        assertEquals(1, VideoDedupe.byTitle(items).size)
    }

    @Test
    fun `不同标题都保留`() {
        val items = listOf(item("1", "童年阴影"), item("2", "极度深寒"))
        assertEquals(2, VideoDedupe.byTitle(items).size)
    }

    @Test
    fun `同名不同年不合并`() {
        val items = listOf(
            item("1", "同名电影", year = "1998"),
            item("2", "同名电影", year = "2020"),
        )
        assertEquals(2, VideoDedupe.byTitle(items).size)
    }

    @Test
    fun `同标题优先保留有封面的`() {
        val items = listOf(
            item("1", "童年阴影", cover = null),
            item("2", "童年阴影", cover = "http://cover/2.jpg"),
        )
        val out = VideoDedupe.byTitle(items)
        assertEquals(1, out.size)
        assertEquals("2", out[0].id)
    }

    @Test
    fun `空标题不参与合并`() {
        val items = listOf(item("1", ""), item("2", ""), item("3", "童年阴影"))
        assertEquals(3, VideoDedupe.byTitle(items).size)
    }
}
