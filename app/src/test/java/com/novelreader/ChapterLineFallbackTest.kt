package com.novelreader

import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ChapterLine
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单线路源的「换源」兜底（v1.3.6.0）。
 *
 * `SourceAdapter.chapterLines` 的默认实现要把 [SourceAdapter.chapterList] 包成一条线路 ——
 * 否则小说 / 漫画以及只提供单线路的采集源进播放页时，「换源」Tab 会是空的。
 */
class ChapterLineFallbackTest {

    private class FakeAdapter(private val chapters: List<ChapterItem>) : SourceAdapter {
        override val info = SourceInfo(id = "src", name = "单线路源", type = SourceType.VIDEO)
        override suspend fun search(keyword: String, page: Int) = emptyList<MediaItem>()
        override suspend fun detail(item: MediaItem) = item
        override suspend fun chapterList(item: MediaItem) = chapters
        override suspend fun content(chapter: ChapterItem) = ContentResult()
    }

    private fun ep(i: Int) = ChapterItem(id = "$i", title = "第${i + 1}集", url = "u$i", index = i)

    private fun videoItem() = MediaItem(id = "1", title = "示例片", type = SourceType.VIDEO)

    @Test
    fun `剧集为空时不产生空线路`() = kotlinx.coroutines.test.runTest {
        val lines = FakeAdapter(emptyList()).chapterLines(videoItem())
        assertTrue("空目录不该产出空线路（UI 会显示「换源（0）」）", lines.isEmpty())
    }

    @Test
    fun `默认线路名与剧集原样带出`() = kotlinx.coroutines.test.runTest {
        val chapters = listOf(ep(0), ep(1))
        val lines = FakeAdapter(chapters).chapterLines(videoItem())

        assertEquals(1, lines.size)
        assertEquals(ChapterLine.DEFAULT_NAME, lines[0].name)
        assertEquals(chapters, lines[0].chapters)
    }
}
