package com.novelreader

import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceType
import com.novelreader.source.loader.SourceValidator
import com.novelreader.source.moontv.Base58
import com.novelreader.source.moontv.MacCmsParser
import com.novelreader.source.moontv.MoonTvConfig
import com.novelreader.source.moontv.MoonTvSourceAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MoonTV / LunaTV 采集源导入与解析单测（v1.3.3.0）。
 *
 * 覆盖：Base58 解码 → 配置解析 → 苹果 CMS 接口解析（列表 / 选集 / 分类）→ 导入校验。
 */
class MoonTvSourceTest {

    /** 播放地址里的分隔符，避免与 Kotlin 字符串模板冲突 */
    private val D = "$"

    // ===== Base58 =====

    @Test
    fun `base58 解码已知向量`() {
        assertEquals(
            listOf<Byte>(0, 1, 2),
            Base58.decode("15T").map { it },
        )
        assertEquals(
            "MoonTV",
            Base58.decode("fZWaAhAM").toString(Charsets.UTF_8),
        )
        // 前导 '1' → 前导 0x00 字节
        assertEquals(
            listOf<Byte>(0, 0, 1, 2, 3),
            Base58.decode("11Ldp").map { it },
        )
    }

    @Test
    fun `base58 特征识别`() {
        assertTrue(Base58.looksLikeBase58(SAMPLE_CONFIG_B58))
        // JSON / URL / 书源都不应被当成 Base58
        assertFalse(Base58.looksLikeBase58("""{"api_site":{}}"""))
        assertFalse(Base58.looksLikeBase58("https://pz.v88.qzz.io?format=2&source=jingjian"))
        assertFalse(Base58.looksLikeBase58("abc"))
    }

    @Test
    fun `非法字符解码返回 null 而非抛异常`() {
        assertNull(Base58.decodeOrNull("0OIl"))
    }

    // ===== 配置解析 =====

    @Test
    fun `base58 订阅内容被识别为 MoonTV 配置`() {
        assertTrue(MoonTvConfig.isConfig(SAMPLE_CONFIG_B58))
        val text = MoonTvConfig.unwrap(SAMPLE_CONFIG_B58)
        assertTrue(text.trim().startsWith("{"))
        assertTrue(text.contains("api_site"))
    }

    @Test
    fun `配置解析出站点并跳过缺少 api 的条目`() {
        val sites = MoonTvConfig.parseConfig(SAMPLE_CONFIG_B58)
        assertEquals(1, sites.size)
        val site = sites.first()
        assertEquals("demo.test", site.key)
        assertEquals("演示源", site.name)
        assertEquals("https://demo.test/api.php/provide/vod", site.api)
        assertEquals("https://demo.test", site.detail)
        assertEquals("moontv_demo.test", site.sourceId)
    }

    @Test
    fun `站点原始 JSON 往返一致`() {
        val site = MoonTvConfig.parseConfig(SAMPLE_CONFIG_B58).first()
        val raw = MoonTvConfig.siteToRaw(site)
        assertTrue(MoonTvConfig.looksLikeSite(raw))
        assertEquals(site, MoonTvConfig.siteFromRaw(raw))
    }

    @Test
    fun `支持把接口地址直接写成字符串值`() {
        val json = """{"api_site":{"a.test":"https://a.test/api.php/provide/vod"}}"""
        val sites = MoonTvConfig.parseConfig(json)
        assertEquals(1, sites.size)
        assertEquals("https://a.test/api.php/provide/vod", sites.first().api)
    }

    @Test
    fun `自研 JSON 源不会被误判为单站点`() {
        val own = """{"name":"某小说源","type":"novel","search":{"url":"https://x"}}"""
        assertFalse(MoonTvConfig.looksLikeSite(own))
        assertFalse(MoonTvConfig.isConfig(own))
    }

    // ===== 校验器接入 =====

    @Test
    fun `导入校验识别 Base58 订阅链接`() {
        val result = SourceValidator.validate(SAMPLE_CONFIG_B58, "jingjian.txt")
        assertTrue(result.valid)
        assertEquals(SourceFormat.MOONTV, result.format)
        assertTrue(result.isMoonTvConfig)
        assertTrue(result.message.contains("1 个视频源"))
    }

    @Test
    fun `导入校验识别单站点 JSON`() {
        val raw = MoonTvConfig.siteToRaw(MoonTvConfig.parseConfig(SAMPLE_CONFIG_B58).first())
        val result = SourceValidator.validate(raw, "moontv_demo.test.moontv")
        assertTrue(result.valid)
        assertEquals(SourceFormat.MOONTV, result.format)
    }

    @Test
    fun `SourceManager 导入 Base58 订阅得到视频源且可重启恢复`() {
        val manager = com.novelreader.source.manager.SourceManager()
        val imported = manager.importSource(SAMPLE_CONFIG_B58, "jingjian.txt")
        assertEquals(1, imported.size)

        val info = imported.first().info
        assertEquals(SourceType.VIDEO, info.type)
        assertEquals(SourceFormat.MOONTV, info.format)
        assertEquals("moontv_demo.test", info.id)
        assertEquals("https://demo.test/api.php/provide/vod", info.baseUrl)
        assertNotNull(manager.getAdapter(info.id) as? MoonTvSourceAdapter)

        // 冷启动恢复：用落库的单源 JSON 重新导入，id 必须保持一致（否则源会重复累积）
        val restored = com.novelreader.source.manager.SourceManager()
        val again = restored.importSource(imported.first().rawContent, "${info.id}.moontv")
        assertEquals(info.id, again.first().info.id)
        assertEquals("演示源", again.first().info.name)
        assertNotNull(restored.getAdapter(info.id) as? MoonTvSourceAdapter)
    }

    @Test
    fun `无可用采集接口时导入报错`() {
        val broken = """{"api_site":{"only-name.test":{"name":"没有接口"}}}"""
        assertTrue(MoonTvConfig.isConfig(broken))
        assertTrue(MoonTvConfig.parseConfig(broken).isEmpty())
        val result = SourceValidator.validate(broken, "broken.txt")
        assertFalse(result.valid)
    }

    // ===== 苹果 CMS 接口解析 =====

    @Test
    fun `拼接接口地址`() {
        assertEquals(
            "https://a.test/api.php/provide/vod?ac=detail&wd=%E5%85%B3%E9%94%AE%E8%AF%8D&pg=2",
            MacCmsParser.buildUrl(
                "https://a.test/api.php/provide/vod",
                listOf("ac" to "detail", "wd" to "关键词", "pg" to "2"),
            ),
        )
        // 已有查询串时用 &
        assertEquals(
            "https://a.test/vod?token=1&ac=list&pg=1",
            MacCmsParser.buildUrl("https://a.test/vod?token=1", listOf("ac" to "list", "pg" to "1")),
        )
        // 空值参数被丢弃
        assertEquals(
            "https://a.test/vod?ac=detail",
            MacCmsParser.buildUrl("https://a.test/vod", listOf("ac" to "detail", "t" to "")),
        )
    }

    @Test
    fun `解析影片列表`() {
        val items = MacCmsParser.parseList(SAMPLE_LIST_JSON, "moontv_demo.test", "演示源")
        val item = items.first()
        assertEquals("1001", item.id)
        assertEquals("示例电影", item.title)
        assertEquals("https://img.test/a.jpg", item.cover)
        assertEquals(SourceType.VIDEO, item.type)
        assertEquals("moontv_demo.test", item.sourceId)
        assertEquals("演示源", item.sourceName)
        assertEquals("全12集", item.extras["remarks"])
        // 简介去 HTML 与实体
        assertTrue(item.desc!!.contains("简介"))
        assertFalse(item.desc!!.contains("<p>"))
    }

    @Test
    fun `解析多线路选集时取集数最多的线路`() {
        val episodes = MacCmsParser.parseEpisodes(
            "线路A" + "$$$" + "线路B",
            "第01集${D}u1#第02集${D}u2" + "$$$" +
                "第01集${D}v1#第02集${D}v2#第03集${D}v3",
        )
        assertEquals(3, episodes.size)
        assertEquals("线路B", episodes.first().line)
        assertEquals("第01集", episodes.first().title)
        assertEquals("v1", episodes.first().url)
        assertEquals("第03集", episodes.last().title)
    }

    @Test
    fun `选集兼容无集名与多地址写法`() {
        val noName = MacCmsParser.parseEpisodes(null, "https://x/1.m3u8#https://x/2.m3u8")
        assertEquals(2, noName.size)
        assertEquals("第1集", noName[0].title)
        assertEquals("https://x/2.m3u8", noName[1].url)

        val multi = MacCmsParser.parseEpisodes(null, "正片${D}https://x/a.m3u8${D}https://x/backup.m3u8")
        assertEquals(1, multi.size)
        assertEquals("正片", multi[0].title)
        assertEquals("https://x/a.m3u8", multi[0].url)

        assertTrue(MacCmsParser.parseEpisodes(null, null).isEmpty())
        assertTrue(MacCmsParser.parseEpisodes("线路A", "").isEmpty())
    }

    @Test
    fun `从详情响应里按 id 取剧集`() {
        val episodes = MacCmsParser.episodesOf(SAMPLE_LIST_JSON, "1001")
        assertEquals(2, episodes.size)
        assertEquals("第01集", episodes.first().title)

        val found = MacCmsParser.findItem(SAMPLE_LIST_JSON, "1001", "src", "源")
        assertNotNull(found)
        assertEquals("示例电影", found!!.title)
    }

    @Test
    fun `只取一级分类`() {
        val categories = MacCmsParser.parseCategories(SAMPLE_LIST_JSON)
        assertEquals(1, categories.size)
        assertEquals("电影", categories.first().title)
        assertEquals("1", categories.first().url)
    }

    @Test
    fun `成功判定`() {
        assertTrue(MacCmsParser.isOk(SAMPLE_LIST_JSON))
        assertFalse(MacCmsParser.isOk("""{"code":0,"msg":"参数错误"}"""))
        assertFalse(MacCmsParser.isOk("<html>502</html>"))
    }

    companion object {
        /** Base58 编码的 MoonTV 配置（含一个缺 api 的无效条目） */
        private const val SAMPLE_CONFIG_B58 =
            "aBGvRQp189WNspcqieeFegCAkGCwfVg5MynWnWF77LF3VrDf1pzdddRFZhPbTVKo7CNEq3Lno1kZSYAXXWuUvgQBr" +
                "GKuzEABUfZpX6AaP3TgmxLuxhUUCEnzZmmY5rYiNQnJRf4UEBaLBLafECcSTdJQfNaR7w8z9D3s346PKGZQrTiKg" +
                "NHnu5MzT9H85GWp3WofB4Y9gqwMkV9hsgLW8"

        /** 一份典型的苹果 CMS 列表响应 */
        private const val SAMPLE_LIST_JSON = """{
  "code": 1,
  "msg": "数据列表",
  "page": 1,
  "pagecount": 10,
  "total": 200,
  "list": [
    {
      "vod_id": 1001,
      "vod_name": "示例电影",
      "vod_pic": "https://img.test/a.jpg",
      "vod_remarks": "全12集",
      "type_name": "连续剧",
      "vod_blurb": "<p>简介 &amp; 说明</p>",
      "vod_play_from": "线路A",
      "vod_play_url": "第01集${'$'}https://play.test/1.m3u8#第02集${'$'}https://play.test/2.m3u8"
    }
  ],
  "class": [
    { "type_id": 1, "type_pid": 0, "type_name": "电影" },
    { "type_id": 10, "type_pid": 1, "type_name": "动作片" }
  ]
}"""
    }
}
