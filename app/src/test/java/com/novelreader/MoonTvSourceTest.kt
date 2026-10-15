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
    fun `拼接接口地址`() {        assertEquals(
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
    fun `反代站的参数编码进 url 值而非拼在外层`() {
        // LunaTV-config 里的反代站：参数拼外层会命中反代 System Error（已实测），
        // 必须把采集参数编码后追加进 url 参数值
        val url = MacCmsParser.buildUrl(
            "https://pz.v88.qzz.io/?url=https://api.apibdzy.com/api.php/provide/vod",
            listOf("ac" to "videolist", "pg" to "1"),
        )
        assertEquals(
            "https://pz.v88.qzz.io/?url=" +
                "https%3A%2F%2Fapi.apibdzy.com%2Fapi.php%2Fprovide%2Fvod%3Fac%3Dvideolist%26pg%3D1",
            url,
        )
        // 目标地址自带 query 时用 & 追加
        val url2 = MacCmsParser.buildUrl(
            "https://pz.v88.qzz.io/?url=https://a.test/vod?ac=list",
            listOf("pg" to "2"),
        )
        assertEquals(
            "https://pz.v88.qzz.io/?url=" +
                "https%3A%2F%2Fa.test%2Fvod%3Fac%3Dlist%26pg%3D2",
            url2,
        )
    }

    @Test
    fun `播放地址协议校验与协议相对地址补全`() = kotlinx.coroutines.runBlocking {
        val site = com.novelreader.source.moontv.MoonTvSite(
            key = "test.local",
            name = "测试",
            api = "https://test.local/api.php/provide/vod",
            detail = "https://test.local",
        )
        val info = com.novelreader.domain.model.SourceInfo(
            id = "moontv_test.local",
            name = "测试",
            type = SourceType.VIDEO,
            format = SourceFormat.MOONTV,
        )
        val adapter = MoonTvSourceAdapter(info, site)

        fun ep(url: String) = com.novelreader.domain.model.ChapterItem(
            id = "0", title = "第1集", url = url, index = 0, sourceId = info.id,
        )

        // 正常 http(s) 地址原样通过
        assertEquals(
            "https://cdn.test/v/index.m3u8",
            adapter.content(ep("https://cdn.test/v/index.m3u8")).videoUrl,
        )
        assertEquals(
            "http://cdn.test/v/index.m3u8",
            adapter.content(ep("http://cdn.test/v/index.m3u8")).videoUrl,
        )
        // 协议相对地址（//host/xxx）按 detail 的协议补全
        assertEquals(
            "https://cdn.test/v/index.m3u8",
            adapter.content(ep("//cdn.test/v/index.m3u8")).videoUrl,
        )
        // 空白与首尾空格容错
        assertEquals(
            "https://cdn.test/v/index.m3u8",
            adapter.content(ep("  https://cdn.test/v/index.m3u8 ")).videoUrl,
        )
        // 非 http 地址（解析异常产物，如 HTML 片段 / 相对路径）拒绝并报错
        for (bad in listOf("/videos/2026/index.m3u8", "ftp://x/y", "<html>bad", "")) {
            val err = runCatching { adapter.content(ep(bad)) }.exceptionOrNull()
            assertTrue("非法地址应报错：$bad", err != null)
        }
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

    // ===== 多线路（v1.3.6.0 换源）=====

    @Test
    fun `解析全部线路并按集数降序`() {
        val lines = MacCmsParser.parseEpisodeLines(
            "线路A" + "$$$" + "线路B" + "$$$" + "短线路",
            "第01集${D}u1#第02集${D}u2" + "$$$" +
                "第01集${D}v1#第02集${D}v2#第03集${D}v3" + "$$$" +
                "预告${D}w1",
        )
        assertEquals(3, lines.size)
        // 集数最多（3 集）的线路排第一 —— 与 chapterList 的默认选路一致
        assertEquals("线路B", lines[0].name)
        assertEquals(3, lines[0].episodes.size)
        assertEquals("线路A", lines[1].name)
        assertEquals(2, lines[1].episodes.size)
        assertEquals("短线路", lines[2].name)
    }

    @Test
    fun `集数相同时保持响应里的原始顺序`() {
        val lines = MacCmsParser.parseEpisodeLines(
            "甲" + "$$$" + "乙",
            "e${D}a" + "$$$" + "e${D}b",
        )
        assertEquals(listOf("甲", "乙"), lines.map { it.name })
    }

    @Test
    fun `每条线路的剧集各自从 1 开始补集名`() {
        val lines = MacCmsParser.parseEpisodeLines(
            "甲" + "$$$" + "乙",
            "${D}x1#${D}x2" + "$$$" + "${D}y1",
        )
        // 甲乙各 2 集和 1 集：甲在前（集数多）
        assertEquals(listOf("第1集", "第2集"), lines[0].episodes.map { it.title })
        assertEquals(listOf("第1集"), lines[1].episodes.map { it.title })
    }

    @Test
    fun `单线路与空响应都不会被当成多线路`() {
        val one = MacCmsParser.parseEpisodeLines("独播", "第01集${D}u1")
        assertEquals(1, one.size)
        assertEquals("独播", one[0].name)
        // 线路名为空时留空，交由上层兜底成「默认线路」
        assertEquals("", MacCmsParser.parseEpisodeLines(null, "a${D}u1")[0].name)

        assertTrue(MacCmsParser.parseEpisodeLines(null, null).isEmpty())
        assertTrue(MacCmsParser.parseEpisodeLines("甲" + "$$$" + "乙", "").isEmpty())
        // 全是空线路（如 `$$$`）时不该产出空线路项
        assertTrue(MacCmsParser.parseEpisodeLines("甲" + "$$$" + "乙", "$$$").isEmpty())
    }

    @Test
    fun `取线路的 parseEpisodes 与新接口结果一致`() {
        val from = "甲" + "$$$" + "乙"
        val url = "第01集${D}a1#第02集${D}a2" + "$$$" + "第01集${D}b1"
        val best = MacCmsParser.parseEpisodes(from, url)
        val lines = MacCmsParser.parseEpisodeLines(from, url)
        // 兼容旧接口：parseEpisodes 就是「集数最多的那条线路」
        assertEquals(lines[0].episodes, best)
    }

    @Test
    fun `从详情响应里取出全部线路`() {
        val lines = MacCmsParser.episodeLinesOf(SAMPLE_LIST_JSON, "1001")
        assertEquals(1, lines.size)
        // 样例响应里 vod_play_from 是「线路A」
        assertEquals("线路A", lines[0].name)
        assertEquals(2, lines[0].episodes.size)

        // 未知 id 时按「取第一条」兜底，不返回空
        assertEquals(1, MacCmsParser.episodeLinesOf(SAMPLE_LIST_JSON, "9999").size)
        assertTrue(MacCmsParser.episodeLinesOf("{}", "1001").isEmpty())
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

    // ===== 分类树（v1.6.7.0 视频「类型 → 分类」二级下拉）=====

    @Test
    fun `分类树解析出一级与二级并还原父级名字`() {
        val tree = MacCmsParser.parseCategoryTree(SAMPLE_LIST_JSON)
        assertEquals(2, tree.size)
        val movie = tree.first { it.title == "电影" }
        assertEquals("1", movie.url)
        assertEquals("一级分类父级为空", "", movie.parent)

        val action = tree.first { it.title == "动作片" }
        assertEquals("10", action.url)
        // 存的是父级**名字**而不是 pid —— 名字才跨源稳定
        assertEquals("电影", action.parent)
    }

    @Test
    fun `缺少 type_pid 视为一级分类`() {
        val json = """{"class":[{"type_id":1,"type_name":"电影"},{"type_id":2,"type_name":"电视剧"}]}"""
        val tree = MacCmsParser.parseCategoryTree(json)
        assertEquals(listOf("电影", "电视剧"), tree.map { it.title })
        assertTrue("没有 pid 应算一级", tree.all { it.parent.isBlank() })
    }

    @Test
    fun `父级 id 查不到时该条按一级处理`() {
        // 只有孤立的二级，找不到父级 —— 不能因为解析不出父名就丢掉它
        val json = """{"class":[{"type_id":9,"type_pid":2,"type_name":"动作片"}]}"""
        val tree = MacCmsParser.parseCategoryTree(json)
        assertEquals(1, tree.size)
        assertEquals("动作片", tree.first().title)
        assertEquals("", tree.first().parent)
    }

    @Test
    fun `分类树对坏 JSON 与缺字段容错`() {
        assertTrue(MacCmsParser.parseCategoryTree("<html>502</html>").isEmpty())
        assertTrue(MacCmsParser.parseCategoryTree("""{"code":1}""").isEmpty())
        assertEquals(
            listOf("电影"),
            MacCmsParser.parseCategoryTree("""{"class":[{"type_id":1,"type_name":"电影"},{"type_name":"没有id"}]}""")
                .map { it.title },
        )
    }

    @Test
    fun `成功判定`() {
        assertTrue(MacCmsParser.isOk(SAMPLE_LIST_JSON))
        assertFalse(MacCmsParser.isOk("""{"code":0,"msg":"参数错误"}"""))
        assertFalse(MacCmsParser.isOk("<html>502</html>"))
    }

    // ===== 详情元信息（v1.3.4.0 详情页补全）=====

    @Test
    fun `详情响应带出年份评分地区与主创`() {
        val item = MacCmsParser.findItem(SAMPLE_DETAIL_JSON, "2002", "src", "演示源")!!
        assertEquals("2002", item.id)
        assertEquals("2025", item.extras["year"])
        assertEquals("8.5", item.extras["score"])
        assertEquals("大陆", item.extras["area"])
        assertEquals("国语", item.extras["lang"])
        assertEquals("连续剧", item.extras["typeName"])
        assertEquals("张三,李四", item.extras["actor"])
        assertEquals("王五", item.extras["director"])
        assertEquals("更新至第12集", item.extras["remarks"])
    }

    @Test
    fun `完整简介只在详情响应里带出`() {
        // 详情响应带 vod_play_url → 带出未截断的完整简介（已去 HTML）
        val detail = MacCmsParser.findItem(SAMPLE_DETAIL_JSON, "2002", "src", "源")!!
        val content = detail.extras["content"]
        assertNotNull("详情响应应带出完整简介", content)
        assertTrue(content!!.contains("完整简介正文"))
        assertFalse("简介不应残留 HTML 标签", content.contains("<p>"))

        // 列表/搜索响应不带 vod_play_url → 不带 content，避免导航参数被 2KB 简介撑大
        val list = MacCmsParser.parseList(SAMPLE_LIST_JSON, "src", "源").first()
        assertNull("列表响应不应带出完整简介", list.extras["content"])
        // desc 仍然截断到 200 字，列表卡片够用
        assertTrue(list.desc!!.length <= 200)
    }

    @Test
    fun `缺少元信息时不产生空键`() {
        val item = MacCmsParser.parseList(SAMPLE_LIST_JSON, "src", "源").first()
        // 样例列表无这些字段：要么不存在该键，要么为空串，取值方按空处理
        assertTrue(item.extras["actor"].orEmpty().isEmpty())
        assertTrue(item.extras["director"].orEmpty().isEmpty())
        assertTrue(item.extras["year"].orEmpty().isEmpty())
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

        /** 一份典型的苹果 CMS「详情」响应（含完整元信息与播放地址） */
        private const val SAMPLE_DETAIL_JSON = """{
  "code": 1,
  "list": [
    {
      "vod_id": 2002,
      "vod_name": "示例连续剧",
      "vod_pic": "https://img.test/b.jpg",
      "vod_remarks": "更新至第12集",
      "type_name": "连续剧",
      "vod_year": "2025",
      "vod_score": "8.5",
      "vod_area": "大陆",
      "vod_lang": "国语",
      "vod_actor": "张三,李四",
      "vod_director": "王五",
      "vod_blurb": "这是列表用的短简介",
      "vod_content": "<p>这是完整简介正文，长度超过短简介，用于验证详情页取的是 content 而不是被截断的 blurb。</p>",
      "vod_play_from": "线路A",
      "vod_play_url": "第01集${'$'}https://play.test/a1.m3u8#第02集${'$'}https://play.test/a2.m3u8"
    }
  ]
}"""
    }
}
