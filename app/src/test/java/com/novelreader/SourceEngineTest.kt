package com.novelreader

import com.novelreader.source.engine.HtmlSelectorEngine
import com.novelreader.source.engine.JsonRuleParser
import com.novelreader.source.engine.PlaceholderEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 源解析引擎单元测试。
 *
 * 验证核心逻辑：占位符替换、选择器提取、JSON 规则解析。
 */
class SourceEngineTest {

    @Test
    fun testPlaceholderRender() {
        val template = "https://example.com/search?keyword={{keyword}}&page={{page}}"
        val result = PlaceholderEngine.render(
            template,
            mapOf("keyword" to "测试", "page" to "2"),
        )
        assertEquals("https://example.com/search?keyword=测试&page=2", result)
    }

    @Test
    fun testParseExpression() {
        val expr = HtmlSelectorEngine.parseExpression("div.title@text")
        assertEquals("div.title", expr.selector)
        assertEquals("text", expr.attr)

        val expr2 = HtmlSelectorEngine.parseExpression("a@href")
        assertEquals("a", expr2.selector)
        assertEquals("href", expr2.attr)
    }

    @Test
    fun testExtractText() {
        val html = "<html><body><div class=\"title\">书名</div></body></html>"
        val result = HtmlSelectorEngine.extract(html, "div.title@text")
        assertEquals("书名", result)
    }

    @Test
    fun testExtractAttr() {
        val html = "<html><body><a href=\"/book/123\">链接</a></body></html>"
        val result = HtmlSelectorEngine.extract(html, "a@href")
        assertEquals("/book/123", result)
    }

    @Test
    fun testExtractList() {
        val html = """
            <html><body>
              <div class="book-item">
                <a href="/book/1"><span class="title">书一</span></a>
                <span class="author">作者A</span>
              </div>
              <div class="book-item">
                <a href="/book/2"><span class="title">书二</span></a>
                <span class="author">作者B</span>
              </div>
            </body></html>
        """.trimIndent()

        val result = HtmlSelectorEngine.extractList(
            html,
            "div.book-item",
            mapOf("id" to "a@href", "title" to ".title@text", "author" to ".author@text"),
        )
        assertEquals(2, result.size)
        assertEquals("书一", result[0]["title"])
        assertEquals("/book/2", result[1]["id"])
        assertEquals("作者B", result[1]["author"])
    }

    @Test
    fun testJsonRuleParser() {
        val json = """
            {
              "name": "测试源",
              "type": "novel",
              "version": "1.0.0",
              "baseUrl": "https://example.com",
              "search": {
                "url": "https://example.com/search?keyword={{keyword}}",
                "list": "div.book-item",
                "fields": { "id": "a@href", "title": ".title@text" }
              },
              "content": { "url": "{{chapterUrl}}", "text": "#content@html" }
            }
        """.trimIndent()

        val ruleSet = JsonRuleParser.parse(json)
        assertEquals("测试源", ruleSet.name)
        assertEquals("novel", ruleSet.type)
        assertNotNull(ruleSet.search)
        assertEquals("div.book-item", ruleSet.search?.list)
        assertNotNull(ruleSet.content)
        assertEquals("#content@html", ruleSet.content?.text)
    }
}
