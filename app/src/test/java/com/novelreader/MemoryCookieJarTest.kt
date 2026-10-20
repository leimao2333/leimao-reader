package com.novelreader

import com.novelreader.core.network.MemoryCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存 Cookie 容器单测（v1.8.5.2）。
 *
 * 两个回归点，都是模拟器实测抓出来的（Nature PDF 直链报
 * `Too many follow-up requests: 21`）：
 *
 * 1. **域级 Cookie 必须跨子域共享**：`idp.nature.com` 种下 `Domain=.nature.com`
 *    的会话 Cookie，`www.nature.com` 必须能带上它 —— 按请求 host 分区就永远
 *    送不过去，于是「设 Cookie → 跳回来校验」的链条死循环。
 * 2. **跨响应合并**：每次 `Set-Cookie` 只带本次新设的那几条，整份替换会把
 *    上一条冲掉。
 */
class MemoryCookieJarTest {

    private val pdfUrl = "https://www.nature.com/articles/d41586-026-03314-6.pdf".toHttpUrl()
    private val idpUrl = "https://idp.nature.com/transit?code=abc".toHttpUrl()

    /**
     * @param domainWide true 生成**域级** Cookie（`Domain=.nature.com` 的等价物，
     *   OkHttp 里用 `domain("nature.com")`）；false 生成 host-only Cookie
     *   （只发给 [domain] 这一台主机）。
     */
    private fun cookie(
        name: String,
        value: String = "v-$name",
        path: String = "/",
        domain: String = "www.nature.com",
        ttlSeconds: Long = 3600,
        domainWide: Boolean = false,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .path(path)
        .apply { if (domainWide) domain(domain) else hostOnlyDomain(domain) }
        .expiresAt(System.currentTimeMillis() + ttlSeconds * 1000)
        .build()

    @Test
    fun `idp 种下的域级 cookie 会发给主站`() {
        val jar = MemoryCookieJar()
        // idp.nature.com 的 302 上种了 Domain=.nature.com 的会话 Cookie
        jar.saveFromResponse(idpUrl, listOf(cookie("idp_session", domain = "nature.com", domainWide = true)))

        // 回到 www.nature.com 时必须带上，否则出版方会一直把人踢回 idp
        val sent = jar.loadForRequest(pdfUrl).map { it.name }
        assertEquals(listOf("idp_session"), sent)
    }

    @Test
    fun `两次响应设置的 cookie 会合并而不是互相覆盖`() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(pdfUrl, listOf(cookie("csrf-token")))
        jar.saveFromResponse(pdfUrl, listOf(cookie("CONTENT_USAGE_SESSIONID")))

        val sent = jar.loadForRequest(pdfUrl).map { it.name }.toSet()
        assertEquals(setOf("csrf-token", "CONTENT_USAGE_SESSIONID"), sent)
    }

    @Test
    fun `同名 cookie 后写覆盖前写`() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(pdfUrl, listOf(cookie("session", "old")))
        jar.saveFromResponse(pdfUrl, listOf(cookie("session", "new")))

        val cookies = jar.loadForRequest(pdfUrl)
        assertEquals(1, cookies.size)
        assertEquals("new", cookies.first().value)
        assertEquals(1, jar.count())
    }

    @Test
    fun `过期指令会删掉同名 cookie`() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(pdfUrl, listOf(cookie("idp_session")))
        // 服务端用「已过期」的 Set-Cookie 表示删除
        jar.saveFromResponse(pdfUrl, listOf(cookie("idp_session", ttlSeconds = -60)))

        assertTrue(jar.loadForRequest(pdfUrl).isEmpty())
        assertEquals(0, jar.count())
    }

    @Test
    fun `host-only cookie 不会被别的域名拿到`() {
        val jar = MemoryCookieJar()
        val science = "https://www.science.org/doi/pdf/10.1126/science.adx1234".toHttpUrl()
        jar.saveFromResponse(pdfUrl, listOf(cookie("nature_only")))
        jar.saveFromResponse(science, listOf(cookie("science_only", domain = "www.science.org")))

        assertEquals(listOf("nature_only"), jar.loadForRequest(pdfUrl).map { it.name })
        assertEquals(listOf("science_only"), jar.loadForRequest(science).map { it.name })
    }

    @Test
    fun `path 不匹配的 cookie 不下发`() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(pdfUrl, listOf(cookie("scoped", path = "/articles")))
        jar.saveFromResponse(pdfUrl, listOf(cookie("global", path = "/")))

        val onArticle = jar.loadForRequest("https://www.nature.com/articles/x".toHttpUrl())
            .map { it.name }.toSet()
        assertEquals(setOf("scoped", "global"), onArticle)

        val onOther = jar.loadForRequest("https://www.nature.com/about".toHttpUrl())
            .map { it.name }.toSet()
        assertEquals(setOf("global"), onOther)
    }

    @Test
    fun `空 Set-Cookie 不影响已有 cookie`() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(pdfUrl, listOf(cookie("keep_me")))
        jar.saveFromResponse(pdfUrl, emptyList())

        assertEquals(listOf("keep_me"), jar.loadForRequest(pdfUrl).map { it.name })
    }
}
