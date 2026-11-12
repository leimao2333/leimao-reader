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

    @Test
    fun `direct media detection`() {
        assertTrue(MacCmsParser.looksLikeDirectMedia("https://a.com/x/index.m3u8?t=1"))
        assertTrue(MacCmsParser.looksLikeDirectMedia("https://a.com/x.mp4"))
        assertFalse(MacCmsParser.looksLikeDirectMedia("https://a.com/play/abc"))
    }
}
