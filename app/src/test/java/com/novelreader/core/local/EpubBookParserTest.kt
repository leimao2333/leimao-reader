package com.novelreader.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 解析回归测试（B9）。
 *
 * 测试里用 [ZipOutputStream] 现造最小 EPUB 包——这是本类「纯逻辑」设计的意义：
 * 不需要真书、不需要 Android 环境，构造字节 → 解析 → 断言。
 */
class EpubBookParserTest {

    // ------------------------------------------------------------------
    // 构造工具
    // ------------------------------------------------------------------

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun text(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun containerXml(opfPath: String = "OEBPS/content.opf"): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun opf(
        title: String = "测试书名",
        author: String = "测试作者",
        extraManifest: String = "",
        spine: String = "<itemref idref=\"ch1\"/><itemref idref=\"ch2\"/>",
    ): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>$title</dc:title>
            <dc:creator>$author</dc:creator>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
            $extraManifest
          </manifest>
          <spine>$spine</spine>
        </package>
    """.trimIndent()

    private fun navDoc(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <body>
        <nav epub:type="toc">
          <ol>
            <li><a href="ch1.xhtml">第一章 开端</a></li>
            <li><a href="ch2.xhtml">第二章 深入</a></li>
          </ol>
        </nav>
        </body>
        </html>
    """.trimIndent()

    private fun chapterHtml(title: String, body: String, encoding: String = "UTF-8"): String = """
        <?xml version="1.0" encoding="$encoding"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head><title>$title</title><style>p{color:red}</style></head>
        <body>
        <h1>$title</h1>
        <p>$body 第一段。</p>
        <p>第二段。</p>
        <script>alert("x")</script>
        </body>
        </html>
    """.trimIndent()

    private fun standardEpub(): ByteArray = zipOf(
        "mimetype" to text("application/epub+zip"),
        "META-INF/container.xml" to text(containerXml()),
        "OEBPS/content.opf" to text(opf()),
        "OEBPS/nav.xhtml" to text(navDoc()),
        "OEBPS/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
        "OEBPS/ch2.xhtml" to text(chapterHtml("第二章 深入", "正文二")),
    )

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    fun `解析出书名作者与章节列表`() {
        val book = EpubBookParser.parse(standardEpub())
        assertNotNull(book)
        assertEquals("测试书名", book!!.title)
        assertEquals("测试作者", book.author)
        assertEquals(2, book.chapters.size)
        assertEquals("第一章 开端", book.chapters[0].title)
        assertEquals("第二章 深入", book.chapters[1].title)
    }

    @Test
    fun `章节 href 保留 OPF 里的原始写法`() {
        val book = EpubBookParser.parse(standardEpub())!!
        assertEquals("ch1.xhtml", book.chapters[0].href)
        assertEquals("ch2.xhtml", book.chapters[1].href)
    }

    @Test
    fun `正文抽出纯文本 并去掉 script 与 style`() {
        val book = EpubBookParser.parse(standardEpub())!!
        val text = book.chapters[0].text
        assertTrue(text.contains("正文一 第一段。"))
        assertTrue(text.contains("第二段。"))
        assertTrue("不应残留脚本：$text", !text.contains("alert"))
        assertTrue("不应残留样式：$text", !text.contains("color:red"))
    }

    @Test
    fun `段落之间用换行分隔`() {
        val book = EpubBookParser.parse(standardEpub())!!
        val lines = book.chapters[0].text.split('\n')
        assertTrue("第一段与第二段应分行", lines.size >= 2)
        assertTrue(lines[0].contains("正文一"))
    }

    @Test
    fun `正文首行与标题重复时不重复展示`() {
        val book = EpubBookParser.parse(standardEpub())!!
        // 章节正文里的 h1 与章节标题相同，应被去掉，避免读者看到两遍标题
        assertTrue(!book.chapters[0].text.startsWith("第一章 开端"))
        assertTrue(book.chapters[0].text.startsWith("正文一"))
    }

    @Test
    fun `没有 nav 目录时用正文第一个 heading 作为标题`() {
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml()),
            "OEBPS/content.opf" to text(opf(spine = "<itemref idref=\"ch1\"/>")),
            "OEBPS/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
        )
        val book = EpubBookParser.parse(epub)!!
        assertEquals(1, book.chapters.size)
        assertEquals("第一章 开端", book.chapters[0].title)
    }

    @Test
    fun `EPUB2 的 NCX 目录也能取到标题`() {
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="np1" playOrder="1">
                  <navLabel><text>序章 楔子</text></navLabel>
                  <content src="ch1.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml()),
            "OEBPS/content.opf" to text(
                opf(
                    extraManifest = "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>",
                    spine = "<itemref idref=\"ch1\"/>",
                )
            ),
            "OEBPS/toc.ncx" to text(ncx),
            "OEBPS/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
        )
        val book = EpubBookParser.parse(epub)!!
        assertEquals("序章 楔子", book.chapters[0].title)
    }

    @Test
    fun `OPF 在子目录时能正确解析相对路径`() {
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml("OEBPS/text/content.opf")),
            "OEBPS/text/content.opf" to text(
                opf(
                    extraManifest = "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>",
                    spine = "<itemref idref=\"ch1\"/>",
                )
            ),
            "OEBPS/text/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
        )
        val book = EpubBookParser.parse(epub)!!
        assertEquals(1, book.chapters.size)
        assertTrue(book.chapters[0].text.contains("正文一"))
    }

    @Test
    fun `声明 GBK 编码的章节按 GBK 解码`() {
        val gbkBytes = chapterHtml("第一章 开端", "正文一", encoding = "GBK")
            .toByteArray(Charset.forName("GBK"))
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml()),
            "OEBPS/content.opf" to text(opf(spine = "<itemref idref=\"ch1\"/>")),
            "OEBPS/ch1.xhtml" to gbkBytes,
        )
        val book = EpubBookParser.parse(epub)!!
        assertTrue("GBK 内容应正确解码", book.chapters[0].text.contains("正文一"))
    }

    // ------------------------------------------------------------------
    // 容错
    // ------------------------------------------------------------------

    @Test
    fun `缺少 container_xml 时返回 null`() {
        val epub = zipOf(
            "OEBPS/content.opf" to text(opf()),
            "OEBPS/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
        )
        assertNull(EpubBookParser.parse(epub))
    }

    @Test
    fun `OPF 损坏时返回 null`() {
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml()),
            "OEBPS/content.opf" to text("<package><manifest>未闭合"),
            "OEBPS/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
        )
        assertNull(EpubBookParser.parse(epub))
    }

    @Test
    fun `空 zip 返回 null`() {
        assertNull(EpubBookParser.parse(zipOf()))
    }

    @Test
    fun `不是 zip 的字节返回 null`() {
        assertNull(EpubBookParser.parse("这不是一个 epub 文件".toByteArray()))
    }

    @Test
    fun `OPF 指向的文件缺失时返回 null`() {
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml("OEBPS/missing.opf")),
            "OEBPS/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
        )
        assertNull(EpubBookParser.parse(epub))
    }

    @Test
    fun `单个章节文件缺失时跳过该章 其余章节照常`() {
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml()),
            "OEBPS/content.opf" to text(opf()),
            "OEBPS/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
            // ch2.xhtml 故意缺失
        )
        val book = EpubBookParser.parse(epub)!!
        assertEquals(1, book.chapters.size)
        assertEquals("第一章 开端", book.chapters[0].title)
    }

    @Test
    fun `所有章节文件都缺失时返回 null`() {
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml()),
            "OEBPS/content.opf" to text(opf()),
        )
        assertNull(EpubBookParser.parse(epub))
    }

    @Test
    fun `spine 为空时返回 null`() {
        val epub = zipOf(
            "META-INF/container.xml" to text(containerXml()),
            "OEBPS/content.opf" to text(opf(spine = "")),
            "OEBPS/ch1.xhtml" to text(chapterHtml("第一章 开端", "正文一")),
        )
        assertNull(EpubBookParser.parse(epub))
    }
}
