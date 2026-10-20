package com.novelreader

import com.novelreader.core.rss.ArticleBodyExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原文页「被拦原因」识别单测（v1.8.5.0）。
 *
 * 样例是**真实抓取的报文**：`blocked_cloudflare.html` 是 science.org 返回的
 * Cloudflare 挑战页（`curl` 带完整浏览器 UA 仍然 403），
 * `blocked_nature_404.html` 是 nature.com 对无 cookie 客户端返回的
 * `error=cookies_not_supported` 404 页。
 *
 * 为什么值得单测：这几个页面都有合法 `<html>`、有 `<title>`、有正文容器，
 * 直接丢给抽取器会走完整套流程然后报「没抽出正文」—— 用户以为 App 坏了，
 * 实际是出版方在拦爬虫。识别出来才能给出真实原因。
 */
class BlockedPageReasonTest {

    private fun load(name: String): String =
        java.io.File("src/test/resources/rss/$name").readText()

    @Test
    fun `Cloudflare 挑战页被识别`() {
        val reason = ArticleBodyExtractor.blockedReason(load("blocked_cloudflare.html"))
        assertEquals("出版方有反爬验证（Cloudflare），抓不到正文", reason)
    }

    @Test
    fun `Nature 无 cookie 的 404 页被识别`() {
        val reason = ArticleBodyExtractor.blockedReason(load("blocked_nature_404.html"))
        assertEquals("原文页返回 404（可能需登录，或 DOI 尚未指向正式文章页）", reason)
    }

    @Test
    fun `正常文章页不被误判`() {
        // 用真实 RSS 报文之外的合成页：有正文、无挑战标记
        val html = """
            <html><head><title>A real paper</title></head>
            <body><article><h1>A real paper</h1>
            <p>We report a synthesis of compound 1 starting from commercially
            available materials. The reaction proceeds in good yield and the
            product was characterised by NMR spectroscopy and mass spectrometry.</p>
            <p>Further experiments confirmed the assignment beyond reasonable doubt,
            and control experiments ruled out alternative pathways entirely.</p>
            <p>In conclusion, we have demonstrated a practical and scalable route
            to this class of compounds under mild conditions.</p>
            </article></body></html>
        """.trimIndent()
        assertEquals("", ArticleBodyExtractor.blockedReason(html))
        // 同一份 HTML 也要能抽出正文
        val ex = ArticleBodyExtractor.extract(html)
        assertTrue(ex.html.isNotBlank())
        assertTrue(ex.textLength > 100)
    }

    @Test
    fun `空 html 有明确原因`() {
        assertEquals("页面为空", ArticleBodyExtractor.blockedReason(""))
        assertEquals("页面为空", ArticleBodyExtractor.blockedReason("   "))
    }

    @Test
    fun `登录墙页面被识别`() {
        val html = """
            <html><head><title>Sign in</title></head><body>
            <div role="main"><p>Please sign in to continue reading this article.</p></div>
            </body></html>
        """.trimIndent()
        assertEquals("这篇需要登录或订阅才能阅读全文", ArticleBodyExtractor.blockedReason(html))
    }

    @Test
    fun `被拦页面必须先判原因 再抽取`() {
        // Cloudflare 挑战页是极简页，抽取结果为空 —— 与「被拦」结论一致
        val cf = ArticleBodyExtractor.extract(load("blocked_cloudflare.html"))
        assertTrue(cf.isEmpty)
        assertTrue(ArticleBodyExtractor.blockedReason(load("blocked_cloudflare.html")).isNotEmpty())

        // Nature 的 404 页是**整站渲染出来的**，抽取器照样能掏出几百字站点噪音 ——
        // 这正是 loadFullText 必须先问 blockedReason、再抽正文的原因：
        // 只靠「抽到东西了吗」判断，用户会读到一屏「订阅、登录、关于我们」。
        val nf = ArticleBodyExtractor.extract(load("blocked_nature_404.html"))
        assertTrue(ArticleBodyExtractor.blockedReason(load("blocked_nature_404.html")).isNotEmpty())
        assertTrue("404 页应被判定为抽不出有效正文或内容是噪音", nf.isEmpty || !nf.looksFullText)
    }
}
