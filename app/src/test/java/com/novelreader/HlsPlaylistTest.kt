package com.novelreader

import com.novelreader.core.media.HlsPlaylist
import com.novelreader.core.media.NextEpisodePrefetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尾段预缓冲的纯逻辑（v1.8.4.3）。
 *
 * 背景：一集快播完时把**下一集开头**拉到磁盘缓存，切过去直接命中、秒起播。
 * 采集站的视频大多是 HLS，`.m3u8` 本身只有几百字节 —— 缓存清单对起播毫无帮助，
 * 真正要下的是清单里列的分片。所以这里的解析规则必须对：
 * 少解析一个分片只是少缓冲一点，**解析错**就会去下根本不存在的地址。
 */
class HlsPlaylistTest {

    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:6.006,
        seg-1.ts
        #EXTINF:6.006,
        https://cdn.example.com/seg-2.ts
        #EXTINF:6.006,

        seg-3.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private val masterPlaylist = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
        https://cdn.example.com/720p/index.m3u8
    """.trimIndent()

    @Test
    fun `识别播放列表与主清单`() {
        assertTrue(HlsPlaylist.isPlaylist(mediaPlaylist))
        assertTrue(HlsPlaylist.isPlaylist(masterPlaylist))
        assertFalse(HlsPlaylist.isPlaylist("<html>not a playlist</html>"))
        assertTrue(HlsPlaylist.isMaster(masterPlaylist))
        assertFalse(HlsPlaylist.isMaster(mediaPlaylist))
    }

    @Test
    fun `媒体清单取出分片并补全相对地址`() {
        val uris = HlsPlaylist.uris(mediaPlaylist, "https://cdn.example.com/vod/1/index.m3u8")
        assertEquals(
            listOf(
                "https://cdn.example.com/vod/1/seg-1.ts",
                "https://cdn.example.com/seg-2.ts",
                "https://cdn.example.com/vod/1/seg-3.ts",
            ),
            uris,
        )
    }

    @Test
    fun `注释与空行不算地址`() {
        val uris = HlsPlaylist.uris(mediaPlaylist, "https://a.com/x.m3u8")
        // 三个分片，且没有把 #EXT-X-MAP / #EXTINF 混进来
        assertEquals(3, uris.size)
        assertTrue(uris.none { it.contains("#") })
        assertTrue(uris.none { it.isBlank() })
    }

    @Test
    fun `主清单取出变体清单地址`() {
        val uris = HlsPlaylist.uris(masterPlaylist, "https://cdn.example.com/vod/1/master.m3u8")
        assertEquals(
            listOf(
                "https://cdn.example.com/vod/1/360p/index.m3u8",
                "https://cdn.example.com/720p/index.m3u8",
            ),
            uris,
        )
    }

    @Test
    fun `limit 限制条数且保持顺序`() {
        assertEquals(
            listOf("https://a.com/seg-1.ts"),
            HlsPlaylist.uris(mediaPlaylist, "https://a.com/x.m3u8", limit = 1),
        )
        assertTrue(HlsPlaylist.uris(mediaPlaylist, "https://a.com/x.m3u8", limit = 0).isEmpty())
        assertTrue(HlsPlaylist.uris(mediaPlaylist, "https://a.com/x.m3u8", limit = -3).isEmpty())
    }

    @Test
    fun `初始化分片按 URI 属性取且能补全`() {
        assertEquals(
            "https://cdn.example.com/vod/1/init.mp4",
            HlsPlaylist.initSegment(mediaPlaylist, "https://cdn.example.com/vod/1/index.m3u8"),
        )
        // 属性顺序不固定时也要能取到
        val reversed = "#EXTM3U\n#EXT-X-MAP:BYTERANGE=\"720@0\",URI=\"init.mp4\"\nseg.ts"
        assertEquals(
            "https://cdn.example.com/init.mp4",
            HlsPlaylist.initSegment(reversed, "https://cdn.example.com/v.m3u8"),
        )
        assertNull(HlsPlaylist.initSegment("#EXTM3U\nseg.ts", "https://cdn.example.com/v.m3u8"))
    }

    @Test
    fun `地址不合法时宁可不返回`() {
        // baseUrl 不是合法 URI：相对地址无法补全，返回 null 而不是拼出个假地址
        assertNull(HlsPlaylist.initSegment("#EXT-X-MAP:URI=\"init.mp4\"", "不是地址"))
        assertTrue(HlsPlaylist.uris("seg.ts", "不是地址").isEmpty())
        // 绝对地址不需要 baseUrl
        assertEquals(listOf("https://a.com/seg.ts"), HlsPlaylist.uris("https://a.com/seg.ts", ""))
    }

    @Test
    fun `判断是否 HLS 地址`() {
        assertTrue(NextEpisodePrefetcher.looksLikeHls("https://a.com/vod/index.m3u8"))
        assertTrue(NextEpisodePrefetcher.looksLikeHls("https://a.com/vod/index.M3U8?token=1"))
        assertFalse(NextEpisodePrefetcher.looksLikeHls("https://a.com/vod/movie.mp4"))
        // 不带后缀的采集地址按直链处理
        assertFalse(NextEpisodePrefetcher.looksLikeHls("https://a.com/play?id=1"))
    }
}
