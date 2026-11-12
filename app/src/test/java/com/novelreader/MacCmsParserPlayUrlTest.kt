package com.novelreader

import com.novelreader.source.moontv.MacCmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放页 → 真实流地址（v1.9.5.1） */
class MacCmsParserPlayUrlTest {

    @Test
    fun `extracts m3u8 from inline player script`() {
        val html = """
            <script>
              const vid = 'https://play.example.com/play/abc/index.m3u8';
              const videoConfig = { url: vid, type: 'hls' };
            </script>
        """.trimIndent()
        assertEquals(
            "https://play.example.com/play/abc/index.m3u8",
            MacCmsParser.extractPlayUrl(html, "https://play.example.com/play/abc"),
        )
    }

    @Test
    fun `handles escaped slashes and protocol relative url`() {
        val escaped = """var u = "https:\/\/cdn.example.com\/hls\/a.m3u8?t=1";"""
        assertEquals(
            "https://cdn.example.com/hls/a.m3u8?t=1",
            MacCmsParser.extractPlayUrl(escaped, "https://play.example.com"),
        )
        val relative = """const vid = "//cdn.example.com/hls/b.m3u8";"""
        assertEquals(
            "https://cdn.example.com/hls/b.m3u8",
            MacCmsParser.extractPlayUrl(relative, "https://play.example.com/x"),
        )
    }

    @Test
    fun `resolves relative path against the page url`() {
        val html = """const vid = '/play/abc/index.m3u8';"""
        assertEquals(
            "https://play.example.com/play/abc/index.m3u8",
            MacCmsParser.extractPlayUrl(html, "https://play.example.com/play/abc"),
        )
    }

    @Test
    fun `returns null when page has no media url`() {
        assertNull(MacCmsParser.extractPlayUrl("<html><body>just a moment...</body></html>", "https://a.com"))
    }

    /**
     * 停放的域名页 / 反盗链页里常带统计地址，真实地址被编码进查询串：
     * `//l.cdn-fileserver.com/bping.php?…&requrl=https%3A%2F%2Fhost%2Fx%2Findex.m3u8`
     * 整串以 `.m3u8` 结尾，会被误当成流地址再播一次（等于把死链又试一遍）。
     * 判据必须是**路径**以媒体后缀结尾。
     */
    @Test
    fun `ignores tracking url that only ends with media extension`() {
        val html = """
            <html><head><title>example.com</title>
            <script src="https://l.cdn-fileserver.com/bping.php?prid=8PR&requrl=https%3A%2F%2Fhost.com%2Fa%2Findex.m3u8"></script>
            </head><body><h1>This domain may be for sale.</h1></body></html>
        """.trimIndent()
        assertNull(
            MacCmsParser.extractPlayUrl(html, "https://host.com/a/index.m3u8"),
        )
    }

    @Test
    fun `still finds real media url when page also has tracking url first`() {
        val html = """
            <script src="https://stat.example.net/beacon.gif?u=https%3A%2F%2Fcdn.example.com%2Fx%2Findex.m3u8"></script>
            <script>player.src = "https://cdn.example.com/real/index.m3u8?sign=1";</script>
        """.trimIndent()
        assertEquals(
            "https://cdn.example.com/real/index.m3u8?sign=1",
            MacCmsParser.extractPlayUrl(html, "https://play.example.com/x"),
        )
    }

    /**
     * 停放页会把原地址原样抄进查询参数：`?requrl=https://host/x/index.m3u8`。
     * 这类「作为参数值出现的地址」必须跳过，否则取回来还是同一个死地址，
     * 播放→失败→再解析→再播放，白白多绕一圈。
     */
    @Test
    fun `skips media url that only appears as a query parameter value`() {
        val html = """
            <html><body><h1>example.com</h1>
            <p>This domain may be for sale.</p>
            <script>var u="https://stat.example.net/ping.php?requrl=https://host.com/a/index.m3u8";</script>
            </body></html>
        """.trimIndent()
        assertNull(MacCmsParser.extractPlayUrl(html, "https://host.com/a/index.m3u8"))
    }

    @Test
    fun `direct media detection`() {
        assertTrue(MacCmsParser.looksLikeDirectMedia("https://a.com/x/index.m3u8?t=1"))
        assertTrue(MacCmsParser.looksLikeDirectMedia("https://a.com/x.mp4"))
        assertFalse(MacCmsParser.looksLikeDirectMedia("https://a.com/play/abc"))
    }
}
