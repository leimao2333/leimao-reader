package com.novelreader

import com.novelreader.core.source.SourceErrorLog
import com.novelreader.data.repository.ContentRepositoryImpl
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.manager.SourceManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文返回路径接入流水线（B8）的仓库层单测。
 *
 * 覆盖：
 * - 小说正文经流水线净化（末尾广告、连续空行、换行符统一）；
 * - 低质量正文**不打断阅读**：照样返回正文，只额外记账；
 * - **漫画 / 视频路径完全不受影响**（`novelText == null` 直接原样返回）。
 */
class ContentRepositoryPipelineTest {

    private val sourceId = "test-source-b8"

    private val chapter = ChapterItem(
        id = "c1",
        title = "第一章",
        url = "https://example.com/1",
        index = 0,
        sourceId = sourceId,
    )

    /** 只回固定正文的假适配器 */
    private class FakeAdapter(
        override val info: SourceInfo,
        private val payload: ContentResult,
    ) : SourceAdapter {
        override suspend fun search(keyword: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun detail(item: MediaItem): MediaItem = item
        override suspend fun chapterList(item: MediaItem): List<ChapterItem> = emptyList()
        override suspend fun content(chapter: ChapterItem): ContentResult = payload
    }

    private fun repository(payload: ContentResult): ContentRepositoryImpl {
        val manager = SourceManager()
        manager.registerSource(
            SourceInfo(id = sourceId, name = "测试源", type = SourceType.NOVEL),
            FakeAdapter(
                SourceInfo(id = sourceId, name = "测试源", type = SourceType.NOVEL),
                payload,
            ),
        )
        return ContentRepositoryImpl(manager)
    }

    @Test
    fun `小说正文去掉末尾广告并折叠多余空行`() = runTest {
        val raw = "他沿着河岸慢慢走着，风把书页吹得哗哗响，远处传来几声狗叫。\r\n" +
            "\r\n\r\n\r\n第二段也是正文内容，风里带着青草味。\r\n请记住本站最新网址"
        val repo = repository(ContentResult.novel(raw))

        val text = repo.content(chapter).novelText

        assertEquals(
            "他沿着河岸慢慢走着，风把书页吹得哗哗响，远处传来几声狗叫。\n\n" +
                "第二段也是正文内容，风里带着青草味。",
            text,
        )
    }

    @Test
    fun `小说正文清掉控制字符与 BOM`() = runTest {
        val raw = "他沿着河岸慢慢走着，风把书页吹得哗哗响，远处传来几声狗叫。\u0007\uFEFF"
        val repo = repository(ContentResult.novel(raw))

        val text = repo.content(chapter).novelText

        assertEquals("他沿着河岸慢慢走着，风把书页吹得哗哗响，远处传来几声狗叫。", text)
    }

    @Test
    fun `空白正文原样返回且不记账`() = runTest {
        SourceErrorLog.clear(sourceId)
        val repo = repository(ContentResult.novel("   \n \t "))

        val text = repo.content(chapter).novelText

        assertEquals("   \n \t ", text)
        assertNull("空白正文不该记账", SourceErrorLog.describe(sourceId))
    }

    @Test
    fun `低质量正文照样返回并记账`() = runTest {
        SourceErrorLog.clear(sourceId)
        val repo = repository(ContentResult.novel("太短"))

        val text = repo.content(chapter).novelText

        assertEquals("正文再差也要交回上层", "太短", text)
        val recorded = SourceErrorLog.describe(sourceId)
        assertNotNull("低质量应记一笔诊断", recorded)
        assertTrue("诊断里应说明是正文质量问题", recorded!!.contains("正文质量"))
    }

    @Test
    fun `漫画路径完全不受影响`() = runTest {
        SourceErrorLog.clear(sourceId)
        val comic = ContentResult.comic(
            images = listOf("https://img.example.com/1.jpg", "https://img.example.com/2.jpg"),
            headers = mapOf("Referer" to "https://example.com"),
        )
        val repo = repository(comic)

        val result = repo.content(chapter)

        assertEquals("漫画结果应原样返回", comic, result)
        assertNull("漫画没有小说正文", result.novelText)
        assertEquals(comic.comicImages, result.comicImages)
        assertEquals(comic.comicHeaders, result.comicHeaders)
        assertNull("漫画不该记正文质量", SourceErrorLog.describe(sourceId))
    }

    @Test
    fun `视频路径完全不受影响`() = runTest {
        SourceErrorLog.clear(sourceId)
        val video = ContentResult.video(
            url = "https://video.example.com/1.m3u8",
            headers = mapOf("User-Agent" to "test"),
        )
        val repo = repository(video)

        val result = repo.content(chapter)

        assertEquals("视频结果应原样返回", video, result)
        assertNull("视频没有小说正文", result.novelText)
        assertEquals(video.videoUrl, result.videoUrl)
        assertEquals(video.videoHeaders, result.videoHeaders)
        assertNull("视频不该记正文质量", SourceErrorLog.describe(sourceId))
    }
}
