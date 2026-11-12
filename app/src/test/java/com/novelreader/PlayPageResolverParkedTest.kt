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
}
