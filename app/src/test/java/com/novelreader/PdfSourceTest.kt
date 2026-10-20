package com.novelreader

import com.novelreader.core.pdf.PdfSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PDF 来源识别单测（v1.8.5.1）。
 *
 * 这一层是「应用内读 PDF」的第一道闸：出版方在没有机构订阅 / 被反爬拦下时
 * 返回的是 **200 + 一张网页**，下载器看不出任何异常。识别错了有两种后果 ——
 * 把网页当 PDF 交给渲染器（用户看到「文件损坏」），或者把真 PDF 拒掉
 * （明明能读却说打不开）。所以魔数与拦截形态都要覆盖。
 */
class PdfSourceTest {

    // ===== cacheKey =====

    @Test
    fun `同一个 URL 的缓存键稳定`() {
        val url = "https://www.nature.com/articles/s41586-026-01234-5.pdf"
        assertEquals(PdfSource.cacheKey(url), PdfSource.cacheKey(url))
    }

    @Test
    fun `两侧空白不影响缓存键`() {
        val url = "https://www.science.org/doi/pdf/10.1126/science.adx1234"
        assertEquals(PdfSource.cacheKey(url), PdfSource.cacheKey("  $url\n"))
    }

    @Test
    fun `不同 URL 的缓存键不同`() {
        val a = PdfSource.cacheKey("https://www.science.org/doi/pdf/10.1126/science.a")
        val b = PdfSource.cacheKey("https://www.science.org/doi/pdf/10.1126/science.b")
        assertNotEquals(a, b)
    }

    @Test
    fun `缓存键是 32 位十六进制且不含路径分隔符`() {
        val key = PdfSource.cacheKey("https://example.com/a/b c.pdf?x=1&y=2")
        assertEquals(32, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
    }

    // ===== looksLikePdf =====

    @Test
    fun `标准 PDF 头能认出`() {
        val bytes = "%PDF-1.7\n1 0 obj\n".toByteArray()
        assertTrue(PdfSource.looksLikePdf(bytes))
    }

    @Test
    fun `前面被塞 BOM 与空白也能认出`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "\n\n  %PDF-1.4".toByteArray()
        assertTrue(PdfSource.looksLikePdf(bytes))
    }

    @Test
    fun `空字节不是 PDF`() {
        assertFalse(PdfSource.looksLikePdf(ByteArray(0)))
    }

    @Test
    fun `HTML 不是 PDF`() {
        assertFalse(PdfSource.looksLikePdf("<html><body>hello</body></html>".toByteArray()))
    }

    // ===== rejectReason =====

    @Test
    fun `真 PDF 不报错`() {
        assertEquals("", PdfSource.rejectReason("%PDF-1.6 ...".toByteArray()))
    }

    @Test
    fun `空文件说清是空文件`() {
        assertTrue(PdfSource.rejectReason(ByteArray(0)).contains("空"))
    }

    @Test
    fun `Cloudflare 挑战页给出人机验证原因`() {
        val html = """
            <!DOCTYPE html><html><head><title>Just a moment...</title></head>
            <body>Checking your browser before accessing nature.com</body></html>
        """.trimIndent().toByteArray()
        val reason = PdfSource.rejectReason(html)
        assertTrue(reason.contains("人机验证"))
        assertTrue(reason.contains("浏览器"))
    }

    @Test
    fun `登录墙给出订阅原因`() {
        val html = """
            <html><head><title>Sign in</title></head>
            <body>Please sign in through your institutional account to access this PDF.</body></html>
        """.trimIndent().toByteArray()
        assertTrue(PdfSource.rejectReason(html).contains("登录"))
    }

    @Test
    fun `普通网页也说清不是 PDF`() {
        val html = "<html><body>Article landing page</body></html>".toByteArray()
        val reason = PdfSource.rejectReason(html)
        assertTrue(reason.contains("不是 PDF"))
        assertTrue(reason.contains("浏览器"))
    }

    @Test
    fun `接口 JSON 被认出来`() {
        val json = """{"status":"error","message":"forbidden"}""".toByteArray()
        assertTrue(PdfSource.rejectReason(json).contains("接口数据"))
    }

    @Test
    fun `二进制垃圾不假装是 PDF`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        assertTrue(PdfSource.rejectReason(bytes).contains("不是 PDF"))
    }

    // ===== head =====

    @Test
    fun `读文件头不会一次读进整个文件`() {
        val file = File.createTempFile("nr_pdf_head_", ".pdf")
        try {
            file.writeBytes(ByteArray(200_000) { 0x41 })
            assertEquals(PdfSource.SNIFF_BYTES, PdfSource.head(file).size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `短文件按实际长度读`() {
        val file = File.createTempFile("nr_pdf_short_", ".pdf")
        try {
            file.writeBytes("%PDF-1.7".toByteArray())
            assertEquals(8, PdfSource.head(file).size)
            assertTrue(PdfSource.looksLikePdf(PdfSource.head(file)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `不存在的文件返回空数组`() {
        val file = File(System.getProperty("java.io.tmpdir"), "nr_not_exists_${System.nanoTime()}.pdf")
        assertEquals(0, PdfSource.head(file).size)
    }
}
