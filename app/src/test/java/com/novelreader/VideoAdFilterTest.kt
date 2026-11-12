package com.novelreader

import com.novelreader.core.media.VideoAdFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频广告过滤（v1.9.6.0）。
 *
 * 这套用例的核心不是「能不能删掉广告」，而是**会不会误删正片** ——
 * 过滤掉一段正常剧情，用户只会看到剧情莫名跳一段，比看广告难受得多。
 */
class VideoAdFilterTest {

    private fun playlist(vararg lines: String): String =
        lines.joinToString("\n", postfix = "\n")

    /** 一集正片：4 个 10 秒分片 */
    private val normalBody = playlist(
        "#EXTM3U",
        "#EXT-X-VERSION:3",
        "#EXT-X-TARGETDURATION:10",
        "#EXT-X-PLAYLIST-TYPE:VOD",
        "#EXTINF:10.0,",
        "seg1.ts",
        "#EXTINF:10.0,",
        "seg2.ts",
        "#EXTINF:10.0,",
        "seg3.ts",
        "#EXTINF:10.0,",
        "seg4.ts",
        "#EXT-X-ENDLIST",
    )

    @Test
    fun `普通剧情分片不会被当成广告`() {
        assertNull(VideoAdFilter.filterPlaylist(normalBody))
    }

    @Test
    fun `名称含 ad 的正常分片不能误伤`() {
        // load / download / header / shadow / upload 里都含 "ad"，整串匹配才安全
        listOf("load.ts", "download_1.ts", "header.ts", "shadow-2.ts", "upload.ts", "radish.ts")
            .forEach { name ->
                assertFalse("$name 被误判成广告", VideoAdFilter.isAdUri("https://cdn.example.com/v/$name"))
            }
    }

    @Test
    fun `广告目录与广告文件名能被识别`() {
        assertTrue(VideoAdFilter.isAdUri("https://cdn.example.com/ad/0.ts"))
        assertTrue(VideoAdFilter.isAdUri("https://cdn.example.com/v/ads/seg-1.ts"))
        assertTrue(VideoAdFilter.isAdUri("https://cdn.example.com/v/guanggao/x.ts"))
        assertTrue(VideoAdFilter.isAdUri("https://cdn.example.com/v/preroll.ts"))
        assertTrue(VideoAdFilter.isAdUri("https://cdn.example.com/v/midroll_2.ts"))
        assertTrue(VideoAdFilter.isAdUri("ad1.ts"))
        // 查询串里出现 ad 不算（广告分片的判定只看路径）
        assertFalse(VideoAdFilter.isAdUri("https://cdn.example.com/v/seg1.ts?from=ad"))
    }

    @Test
    fun `CUE-OUT 区间内的分片被摘掉且补 DISCONTINUITY`() {
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:10",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg1.ts",
            "#EXT-X-CUE-OUT:20.0",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg2.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg3.ts",
            "#EXT-X-CUE-IN",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg4.ts",
            "#EXT-X-ENDLIST",
        )
        val result = VideoAdFilter.filterPlaylist(text) ?: error("应当识别出广告")

        assertEquals(2, result.removedSegments)
        assertEquals(20_000L, result.removedMs)
        assertEquals(4, result.totalSegments)

        val out = result.playlist
        assertFalse("广告分片仍在清单里", out.contains("seg2.ts"))
        assertFalse(out.contains("seg3.ts"))
        assertTrue(out.contains("seg1.ts"))
        assertTrue(out.contains("seg4.ts"))
        // 中间挖掉一段，必须声明时间戳断层，否则从这里开始音画错位
        assertTrue(out.contains("#EXT-X-DISCONTINUITY"))
        // CUE 标记已无意义，应当一并清掉
        assertFalse(out.contains("CUE-OUT"))
        assertFalse(out.contains("CUE-IN"))
        // 尾部标签不能丢
        assertTrue(out.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun `首段是广告时清单头不能被一起删掉`() {
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:10",
            "#EXTINF:5.0,",
            "https://cdn.example.com/ad/0.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg1.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg2.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg3.ts",
            "#EXT-X-ENDLIST",
        )
        val out = VideoAdFilter.filterPlaylist(text)?.playlist ?: error("应当识别出广告")

        assertTrue(out.trimStart().startsWith("#EXTM3U"))
        assertFalse(out.contains("/ad/0.ts"))
        // 开头的广告删掉后，正片第一段就是时间轴起点，不需要额外的 DISCONTINUITY
        assertFalse(out.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(out.contains("seg1.ts"))
    }

    @Test
    fun `广告时长超过一半时放弃过滤`() {
        // 三分之二被判成广告 —— 只可能是规则误伤，宁可原样放过
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-TARGETDURATION:10",
            "#EXTINF:10.0,",
            "https://cdn.example.com/ad/a.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/ad/b.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg1.ts",
        )
        assertNull(VideoAdFilter.filterPlaylist(text))
    }

    @Test
    fun `全被判成广告时放弃过滤`() {
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-TARGETDURATION:10",
            "#EXTINF:10.0,",
            "https://cdn.example.com/ad/a.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/ad/b.ts",
        )
        assertNull(VideoAdFilter.filterPlaylist(text))
    }

    @Test
    fun `主清单不参与过滤`() {
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720",
            "https://cdn.example.com/720/index.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080",
            "https://cdn.example.com/1080/index.m3u8",
        )
        assertNull(VideoAdFilter.filterPlaylist(text))
    }

    @Test
    fun `不是播放列表的文本原样返回 null`() {
        assertNull(VideoAdFilter.filterPlaylist("<html><body>not a playlist</body></html>"))
        assertNull(VideoAdFilter.filterPlaylist(""))
    }

    @Test
    fun `加密与 init 标签在删段后仍然保留`() {
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-TARGETDURATION:10",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example.com/key.bin\"",
            "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg1.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg2.ts",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example.com/key.bin\"",
            "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXTINF:10.0,",
            "https://cdn.example.com/ad/x.ts",
            "#EXTINF:10.0,",
            "https://cdn.example.com/v/seg3.ts",
        )
        val out = VideoAdFilter.filterPlaylist(text)?.playlist ?: error("应当识别出广告")

        // AES-128 / fMP4 靠这两个标签解密解码，删段时必须留着
        assertTrue(out.contains("#EXT-X-KEY"))
        assertTrue(out.contains("#EXT-X-MAP"))
        assertFalse(out.contains("/ad/x.ts"))
        assertTrue(out.contains("seg3.ts"))
    }
}
