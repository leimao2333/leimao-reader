package com.novelreader

import com.novelreader.core.media.MediaHostPool
import com.novelreader.source.moontv.MacCmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 同源 CDN 域名池 + 播放域名收集（v1.9.5.5）。
 *
 * 背景：采集站的播放域名会到期 / 被停放（返回「域名出售」占位页），
 * 而同一路径往往还挂在同源其它 CDN 上。播放失败时要能拿出这些域名去试。
 */
class MediaHostPoolTest {

    @Before
    fun setUp() {
        MediaHostPool.clear()
    }

    @Test
    fun `records hosts in order and returns most recent first`() {
        MediaHostPool.record("src", listOf("cdn2.shayubf3.com", "3sybf.com"))
        MediaHostPool.record("src", listOf("vip8.3sybf.com"))
        assertEquals(
            listOf("vip8.3sybf.com", "3sybf.com", "cdn2.shayubf3.com"),
            MediaHostPool.hosts("src"),
        )
    }

    @Test
    fun `promote moves a host to the front`() {
        MediaHostPool.record("src", listOf("a.example.com", "b.example.com", "c.example.com"))
        MediaHostPool.promote("src", "a.example.com")
        assertEquals("a.example.com", MediaHostPool.hosts("src").first())
    }

    @Test
    fun `pool is per source and capped`() {
        repeat(30) { i -> MediaHostPool.record("src", listOf("cdn$i.example.com")) }
        assertTrue(MediaHostPool.hosts("src").size <= 16)
        assertTrue(MediaHostPool.hosts("other").isEmpty())
    }

    @Test
    fun `replaceHost keeps path and query`() {
        assertEquals(
            "https://3sybf.com/20240913/HCkz724H/index.m3u8?sign=1",
            MediaHostPool.replaceHost(
                "https://cdn2.shayubf3.com/20240913/HCkz724H/index.m3u8?sign=1",
                "3sybf.com",
            ),
        )
        assertNull(MediaHostPool.replaceHost("https://cdn.example.com", "3sybf.com"))
        assertNull(MediaHostPool.replaceHost("/local/path.m3u8", "3sybf.com"))
    }

    @Test
    fun `hostOf strips scheme port and query`() {
        assertEquals("cdn2.shayubf3.com", MediaHostPool.hostOf("https://cdn2.shayubf3.com/a/b.m3u8?x=1"))
        assertEquals("cdn.example.com:8443", MediaHostPool.hostOf("http://cdn.example.com:8443/a.m3u8"))
        assertNull(MediaHostPool.hostOf("not a url"))
    }

    @Test
    fun `collectPlayHosts only reads vod_play_url`() {
        val json = """
            {"code":1,"list":[
              {"vod_id":1,"vod_pic":"https://pic.example.net/a.jpg",
               "vod_play_url":"在线播放${'$'}https:\/\/cdn2.shayubf3.com\/20240913\/HCkz724H\/index.m3u8"},
              {"vod_id":2,"vod_pic":"https://pic2.example.net/b.jpg",
               "vod_play_url":"第01集${'$'}https://vip8.3sybf.com/20230516/Bmi8zPxM/index.m3u8#第02集${'$'}https://vip8.3sybf.com/a/2.m3u8"}
            ]}
        """.trimIndent()
        val hosts = MacCmsParser.collectPlayHosts(json)
        assertEquals(listOf("cdn2.shayubf3.com", "vip8.3sybf.com"), hosts)
        assertFalse(hosts.any { it.contains("pic") })
    }

    @Test
    fun `collectPlayHosts tolerates garbage`() {
        assertTrue(MacCmsParser.collectPlayHosts("not json").isEmpty())
        assertTrue(MacCmsParser.collectPlayHosts("""{"list":[]}""").isEmpty())
    }
}
