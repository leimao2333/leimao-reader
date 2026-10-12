package com.novelreader

import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.legado.LegadoBookSource
import com.novelreader.source.legado.LegadoRuleEngine
import com.novelreader.source.legado.LegadoSourceAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * legado 规则引擎 + 真实书源测试。
 *
 * - 离线 fixture：验证规则语义（任意属性 / 尾部 JS / @css:属性 / 区间）
 * - 联网用例（需设环境变量 LEIMAO_REAL_TESTS=1）：
 *   得奇小说网（目录翻页 + 正文翻页）、手机小说（GBK POST 搜索）
 */
class LegadoRealSourceTest {

    private fun adapter(json: String): LegadoSourceAdapter {
        val src = LegadoBookSource.parseOne(json)
        val info = SourceInfo(
            id = "test_${src.name.hashCode()}",
            name = src.name,
            type = SourceType.NOVEL,
            baseUrl = src.url,
            format = SourceFormat.LEGADO,
        )
        return LegadoSourceAdapter(info, src)
    }

    // ===== 离线规则语义 =====

    @Test
    fun `任意属性名 - 取 onclick 属性`() {
        val html = """<html><body><a href="javascript:void(0)" onclick="showBook('abc123','','')">书名A</a></body></html>"""
        val v = LegadoRuleEngine.extractString(html, "tag.a.0@onclick")
        assertEquals("showBook('abc123','','')", v)
    }

    @Test
    fun `尾部JS规则 - result 正则提取`() {
        // 阅友小说的结构：列表项容器带 onclick（含空格），bookUrl 规则 = @onclick@js:正则提取
        val html = """
            <html><body>
              <div class="v-list-item" onclick="showBook('abc123', '', '')"><div class="v-title">书名A</div></div>
              <div class="v-list-item" onclick="showBook('def456', '', '')"><div class="v-title">书名B</div></div>
            </body></html>
        """.trimIndent()
        val list = LegadoRuleEngine.extractList(
            html,
            ".v-list-item",
            mapOf(
                "name" to ".v-title@text",
                "bookUrl" to "@onclick@js:result.match(/\\('(.*?)', '', ''\\)/)[1]",
            ),
        )
        assertEquals(2, list.size)
        assertEquals("abc123", list[0]["bookUrl"])
        assertEquals("def456", list[1]["bookUrl"])
        assertEquals("书名A", list[0]["name"])
    }

    @Test
    fun `css规则带属性 - @css_img@src`() {
        val html = """<html><body><div class="cover"><img src="http://x/img.jpg"></div></body></html>"""
        val v = LegadoRuleEngine.extractString(html, "@css:img@src")
        assertEquals("http://x/img.jpg", v)
    }

    @Test
    fun `标签区间 - tag span 区间写法`() {
        val html = """<html><body><p><span>玄幻</span><span>连载</span><span>100万</span></p></body></html>"""
        // legado 语义：区间取前两个 span，文本类提取多元素拼接
        val v = LegadoRuleEngine.extractString(html, "tag.p.0@tag.span[0:1]@text")
        assertEquals("玄幻\n连载", v)
    }

    @Test
    fun `replaceRegex - ##前缀净化`() {
        val html = """<html><body><div class="con">正文内容（本章完）</div></body></html>"""
        val v = LegadoRuleEngine.extractString(html, "class.con.0@text##（本章完）")
        assertEquals("正文内容", v)
    }

    // ===== 联网真实源验证 =====

    private fun realTestsEnabled(): Boolean = System.getenv("LEIMAO_REAL_TESTS") == "1"

    private fun loadShuyuan(): List<Map<String, Any?>> {
        val file = java.io.File("../shuyuan.json")
        assumeTrue("找不到 shuyuan.json", file.exists())
        val text = file.readText()
        val arr = com.google.gson.JsonParser.parseString(text).asJsonArray
        return arr.map { el ->
            val obj = el.asJsonObject
            obj.entrySet().associate { (k, v) ->
                k to if (v.isJsonPrimitive) v.asString else v.toString()
            }
        }
    }

    private fun sourceJson(name: String): String? =
        loadShuyuan().firstOrNull { (it["bookSourceName"] as? String) == name }
            ?.let { m ->
                val obj = com.google.gson.JsonObject()
                m.forEach { (k, v) ->
                    if (v is String) obj.addProperty(k, v) else obj.add(k, com.google.gson.JsonParser.parseString(v.toString()))
                }
                obj.toString()
            }

    /** 得奇小说网：搜索 → 详情 → 完整目录（含翻页）→ 第一章正文（含翻页拼接） */
    @Test
    fun `得奇小说网 - 完整链路`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJson("得奇小说网") ?: throw AssertionError("shuyuan.json 中没有 得奇小说网")
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("得奇搜索无结果", results.isNotEmpty())
        val book = results.first()
        assertTrue("书名不应为空", book.title.isNotBlank())
        assertTrue("bookUrl 应为绝对地址", book.detailUrl.startsWith("http"))

        val detail = kotlinx.coroutines.runBlocking { ad.detail(book) }
        assertTrue("详情标题不应为空", detail.title.isNotBlank())

        val chapters = kotlinx.coroutines.runBlocking { ad.chapterList(detail) }
        assertTrue("目录翻页后章节数应远超单页(>50)，实际 ${chapters.size}", chapters.size > 50)

        val content = kotlinx.coroutines.runBlocking { ad.content(chapters[0]) }
        val text = content.novelText ?: ""
        assertTrue("第一章正文过短（正文翻页可能失效）：${text.length}", text.length > 1000)
    }

    /** 手机小说：GBK POST 搜索，关键词需按 GBK 编码，否则返回乱码/无结果 */
    @Test
    fun `手机小说 - GBK POST 搜索`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJson("手机小说") ?: throw AssertionError("shuyuan.json 中没有 手机小说")
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("GBK POST 搜索无结果", results.isNotEmpty())
        assertTrue("结果应包含关键词相关书名", results.any { it.title.contains("斗破") })
    }

    /** 阅友小说：CSS 列表 + onclick JS bookUrl */
    @Test
    fun `阅友小说 - 搜索与bookUrl提取`() {
        assumeTrue(realTestsEnabled())
        val json = sourceJson("阅友小说") ?: throw AssertionError("shuyuan.json 中没有 阅友小说")
        val ad = adapter(json)

        val results = kotlinx.coroutines.runBlocking { ad.search("斗破苍穹", 1) }
        assertTrue("阅友搜索无结果", results.isNotEmpty())
        // bookUrl 来自 onclick JS 提取，必须是有效绝对链接（而非书名兜底）
        assertTrue(
            "bookUrl 应为 http 绝对地址：${results.first().detailUrl}",
            results.first().detailUrl.startsWith("http"),
        )
    }
}
