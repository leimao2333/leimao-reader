package com.novelreader.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 导入的集成测试（B9）。
 *
 * 「摊平成纯文本 + 尾部章节表」是这套实现里最自创的一环，
 * 必须保证两件事：
 * - 摊平出来的章节区间能被**原样还原**（目录不丢）；
 * - 按区间切出来的确实是对应章节的正文（且不含别的章节内容）。
 */
class LocalBookStoreEpubTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun epubBytes(): ByteArray = zipOf(
        "META-INF/container.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent(),
        "OEBPS/content.opf" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>集成测试书</dc:title>
                <dc:creator>集成测试作者</dc:creator>
              </metadata>
              <manifest>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>
        """.trimIndent(),
        "OEBPS/ch1.xhtml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第一章 开端</h1><p>正文一</p></body></html>
        """.trimIndent(),
        "OEBPS/ch2.xhtml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第二章 深入</h1><p>正文二</p></body></html>
        """.trimIndent(),
    )

    private fun flattened(): LocalBookStore.FlattenedEpub {
        val book = EpubBookParser.parse(epubBytes())!!
        return LocalBookStore.flattenEpub(book)
    }

    @Test
    fun `摊平后的章节区间能被原样还原`() {
        val flat = flattened()
        val restored = LocalBookStore.parseEpubMeta(flat.text)
        assertNotNull(restored)
        assertEquals(flat.chapters.size, restored!!.size)
        flat.chapters.forEachIndexed { i, ch ->
            assertEquals(ch.title, restored[i].title)
            assertEquals(ch.start, restored[i].start)
            assertEquals(ch.end, restored[i].end)
        }
    }

    @Test
    fun `按还原出的区间切出的就是对应章节正文`() {
        val flat = flattened()
        val restored = LocalBookStore.parseEpubMeta(flat.text)!!
        val first = flat.text.substring(restored[0].start, restored[0].end)
        assertTrue(first.startsWith("第一章 开端"))
        assertTrue(first.contains("正文一"))
        assertTrue("不应混入下一章内容", !first.contains("正文二"))

        val second = flat.text.substring(restored[1].start, restored[1].end)
        assertTrue(second.startsWith("第二章 深入"))
        assertTrue(second.contains("正文二"))
        assertTrue("不应混入上一章内容", !second.contains("正文一"))
    }

    @Test
    fun `尾部章节元数据不落在任何章节区间内`() {
        val flat = flattened()
        val tail = flat.text.substring(flat.chapters.last().end)
        assertTrue("尾部应带有章节表", tail.contains("#NR-EPUB-META#"))
        assertTrue("尾部不应有正文", !tail.contains("正文二"))
    }

    @Test
    fun `普通文本不会被误判为 EPUB 文本`() {
        assertNull(LocalBookStore.parseEpubMeta("第一章 开端\n正文"))
        assertNull(LocalBookStore.parseEpubMeta(""))
    }
}
