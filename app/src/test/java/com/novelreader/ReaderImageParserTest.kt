package com.novelreader

import com.novelreader.core.reader.ReaderImageParser
import com.novelreader.core.reader.ReaderImageParser.ImageParagraph
import com.novelreader.core.reader.ReaderImageParser.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A10 正文插图 —— 图片段落识别单测。
 *
 * 覆盖：Markdown 语法、裸 URL、本地路径、查询串、大小写扩展名、行内 URL 不误识别、
 * 空文本 / 纯文本无图片，以及 [ReaderImageParser.strip] 与 [ReaderImageParser.parse] 的一致性。
 */
class ReaderImageParserTest {

    @Test
    fun `识别markdown图片段落`() {
        val text = "前文\n![插图](https://x.com/a.png)\n后文"
        val images = ReaderImageParser.parse(text)
        assertEquals(1, images.size)
        assertEquals(ImageParagraph("插图", "https://x.com/a.png", Kind.MARKDOWN, "![插图](https://x.com/a.png)"), images[0])
        assertTrue(images[0].isNetwork)
    }

    @Test
    fun `识别本地路径markdown图片`() {
        val images = ReaderImageParser.parse("![封面](/sdcard/books/a.jpg)")
        assertEquals(1, images.size)
        assertEquals("/sdcard/books/a.jpg", images[0].source)
        assertFalse(images[0].isNetwork)
    }

    @Test
    fun `识别独立成行图片URL`() {
        val images = ReaderImageParser.parse("正文第一段\nhttps://img.example.com/pic.webp\n正文第二段")
        assertEquals(1, images.size)
        assertEquals("https://img.example.com/pic.webp", images[0].source)
        assertEquals(Kind.BARE_URL, images[0].kind)
    }

    @Test
    fun `识别带查询串的图片URL`() {
        val images = ReaderImageParser.parse("https://cdn.a.com/x.png?w=800&h=600")
        assertEquals(1, images.size)
        assertEquals("https://cdn.a.com/x.png?w=800&h=600", images[0].source)
    }

    @Test
    fun `扩展名大小写不敏感`() {
        val images = ReaderImageParser.parse("https://a.com/IMG.JPEG")
        assertEquals(1, images.size)
        assertTrue(images[0].isNetwork)
    }

    @Test
    fun `行内URL不误识别为图片段落`() {
        // 链接嵌在正文里、不是独立成行 —— 不应被当成图片段落
        val images = ReaderImageParser.parse("请看 https://a.com/b.png 这张图。")
        assertEquals(0, images.size)
    }

    @Test
    fun `非图片扩展名的URL不识别`() {
        val images = ReaderImageParser.parse("https://a.com/page.html")
        assertEquals(0, images.size)
    }

    @Test
    fun `空文本无图片`() {
        assertTrue(ReaderImageParser.parse("").isEmpty())
    }

    @Test
    fun `纯文本无图片`() {
        assertTrue(ReaderImageParser.parse("这是普通正文。\n第二段。").isEmpty())
    }

    @Test
    fun `多张图片保持出现顺序`() {
        val text = "![一](1.png)\n正文\n![二](2.jpg)\nhttps://c.com/3.webp"
        val sources = ReaderImageParser.parse(text).map { it.source }
        assertEquals(listOf("1.png", "2.jpg", "https://c.com/3.webp"), sources)
    }

    @Test
    fun `strip剔除图片行但保留正文`() {
        val text = "第一段\n![插图](https://x.com/a.png)\n第二段\nhttps://x.com/b.jpg\n第三段"
        val stripped = ReaderImageParser.strip(text)
        assertEquals("第一段\n第二段\n第三段", stripped)
        assertEquals(2, ReaderImageParser.parse(text).size)
        assertTrue(ReaderImageParser.parse(stripped).isEmpty())
    }

    @Test
    fun `strip对纯文本原样保留`() {
        val text = "没有图片的正文。\n第二行。"
        assertEquals(text, ReaderImageParser.strip(text))
    }
}
