package com.novelreader

import com.novelreader.source.legado.LegadoRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字段规则 `||` 备选 + `##` 正则替换的回归测试。
 *
 * 背景（禁漫天堂目录空白）：
 * 1. `ruleToc.chapterName = "li@ownText||text"` 里的 `||` 只在文档级
 *    （[LegadoRuleEngine.extractString]）生效，字段级（目录/搜索项）走的是
 *    `extractFromElement`，整串被当成一个选择器 → 恒为空 → 章节名全空。
 * 2. `chapterUrl = "href##(.*)##$1/?shunt=1"`：Java `replaceAll` 会在串尾
 *    再命中一次长度为 0 的空串，导致替换执行两遍 →
 *    `/photo/1/?shunt=1/?shunt=1`。
 */
class LegadoFieldAltTest {

    private val html = """
        <html><body>
          <div class="list">
            <div class="item"><a class="reading" href="/photo/1"></a></div>
            <div class="item"><a class="reading" href="/photo/2"><span class="t">第2话</span></a></div>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `字段规则支持||备选`() {
        val items = LegadoRuleEngine.extractList(
            html,
            listRule = ".item",
            fieldRules = mapOf(
                "chapterName" to "li@ownText||span@text||text",
                "chapterUrl" to "a@href",
            ),
            baseUrl = "https://example.com/album/9/",
        )
        assertEquals(2, items.size)
        // 第一条：a 无文本、无 span → 备选全落空
        assertEquals("", items[0]["chapterName"])
        assertEquals("/photo/1", items[0]["chapterUrl"])
        // 第二条：span 文本命中
        assertEquals("第2话", items[1]["chapterName"])
    }

    @Test
    fun `字段规则||不吃空串`() {
        val v = LegadoRuleEngine.extractString(
            html,
            ".item@a@li@ownText||text",
            "https://example.com/",
        )
        // 文档级 `||`：`li@ownText` 取不到 → 退化到 text（此处首条为空 → 返回 null 或后续非空）
        assertEquals(true, v == null || v.isNotBlank())
    }

    @Test
    fun `##正则只替换一遍`() {
        val items = LegadoRuleEngine.extractList(
            html,
            listRule = ".item",
            fieldRules = mapOf("chapterUrl" to "a@href##(.*)##\$1/?shunt=1"),
            baseUrl = "https://example.com/album/9/",
        )
        assertEquals("/photo/1/?shunt=1", items[0]["chapterUrl"])
        assertEquals("/photo/2/?shunt=1", items[1]["chapterUrl"])
    }

    @Test
    fun `##正则删除仍按全局生效`() {
        val items = LegadoRuleEngine.extractList(
            """<div class="item"><a href="/photo/1/">x</a></div>""",
            listRule = ".item",
            fieldRules = mapOf("chapterUrl" to "a@href##/photo/##"),
            baseUrl = "https://example.com/",
        )
        assertEquals("1/", items[0]["chapterUrl"])
    }

    @Test
    fun `##正则分组替换保留普通语义`() {
        val items = LegadoRuleEngine.extractList(
            """<div class="item"><a href="/album/abc-123.html">x</a></div>""",
            listRule = ".item",
            fieldRules = mapOf("chapterUrl" to """a@href##.*-(\d+)\.html##${'$'}1"""),
            baseUrl = "https://example.com/",
        )
        assertEquals("123", items[0]["chapterUrl"])
    }
}
