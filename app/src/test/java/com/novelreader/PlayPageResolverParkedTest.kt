package com.novelreader

import com.novelreader.core.media.PlayPageResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 域名停放 / 转卖占位页识别（v1.9.5.4）。
 *
 * 这类页面是 HTML、还挂着统计脚本，很容易被当成「播放页」去提取流地址，
 * 结果只能提取到被抄进 `requrl=` 的原地址，播放 → 失败 → 再播放来回绕。
 */
class PlayPageResolverParkedTest {

    @Test
    fun `detects parked domain page`() {
        val body = """
            <!DOCTYPE html><html><head><title>example.com</title></head>
            <body><h1>example.com</h1>
            <div>Find information, resources and relevant links for example.com.
            This domain may be for sale.</div></body></html>
        """.trimIndent()
        assertTrue(PlayPageResolver.isParkedPage(body))
    }

    @Test
    fun `detects chinese parked page`() {
        assertTrue(PlayPageResolver.isParkedPage("<html><body>此域名可出售，欢迎联系。</body></html>"))
    }

    @Test
    fun `normal play page is not treated as parked`() {
        val body = """
            <html><body><div id="player"></div>
            <script>var url = "https://cdn.example.com/x/index.m3u8"; new Hls().loadSource(url);</script>
            </body></html>
        """.trimIndent()
        assertFalse(PlayPageResolver.isParkedPage(body))
    }

    @Test
    fun `same site subdomain swap is not a real fix`() {
        // 停放页把原地址抄进 requrl，只换了子域 —— 换过去还是占位页
        assertTrue(
            PlayPageResolver.sameSite(
                "https://shayubf3.com/20240913/HCkz724H/index.m3u8",
                "https://cdn2.shayubf3.com/20240913/HCkz724H/index.m3u8",
            ),
        )
        // 同源不同 CDN 域名不属于同站，值得真的切过去试
        assertFalse(
            PlayPageResolver.sameSite(
                "https://3sybf.com/20240913/HCkz724H/index.m3u8",
                "https://cdn2.shayubf3.com/20240913/HCkz724H/index.m3u8",
            ),
        )
    }
}
