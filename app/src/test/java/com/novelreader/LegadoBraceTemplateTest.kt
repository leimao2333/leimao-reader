package com.novelreader

import com.novelreader.source.legado.LegadoRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `{{规则}}` 模板语法的回归测试（禁漫天堂正文空白）。
 *
 * 该源的正文规则是：
 * ```
 * {{@class.row thumb-overlay-albums@tag.img@data-original}}
 * @js:result.split("\n").map(x=>'<img src="'+x+'">').join("\n")
 * ```
 * 引擎此前不认 `{{}}`，前段取到空串 → JS 里只拆出一个空地址 → 0 张图。
 */
class LegadoBraceTemplateTest {

    private val html = """
        <html><body>
          <div class="row thumb-overlay-albums">
            <div class="tag"><img data-original="https://cdn.example.com/1.jpg"></div>
            <div class="tag"><img data-original="https://cdn.example.com/2.jpg"></div>
          </div>
          <div class="row thumb-overlay-albums">
            <div class="tag"><img data-original="https://cdn.example.com/3.jpg"></div>
          </div>
        </body></html>
    """.trimIndent()

    private val jmContentRule =
        "{{@class.row thumb-overlay-albums@tag.img@data-original}}" +
            "@js:result.split(\"\\n\").map(x=>'<img src=\"'+x+'\">').join(\"\\n\")"

    @Test
    fun `占位取到全部图片地址`() {
        val urls = LegadoRuleEngine.extractString(
            html,
            "{{@class.row thumb-overlay-albums@tag.img@data-original}}",
            "https://18comic.vip/photo/1/?shunt=1",
        )
        assertEquals(
            "https://cdn.example.com/1.jpg\nhttps://cdn.example.com/2.jpg\nhttps://cdn.example.com/3.jpg",
            urls,
        )
    }

    @Test
    fun `正文规则拼出img标签`() {
        val (value, _) = LegadoRuleEngine.evalContentRule(html, jmContentRule, "https://18comic.vip/photo/1/?shunt=1")
        val text = value.orEmpty()
        assertTrue("应包含第 1 张图，实际=$text", text.contains("https://cdn.example.com/1.jpg"))
        assertTrue("应包含第 3 张图，实际=$text", text.contains("https://cdn.example.com/3.jpg"))
        assertTrue("应渲染成 img 标签，实际=$text", text.contains("<img src="))
    }

    @Test
    fun `占位支持baseUrl与字面量拼接`() {
        val v = LegadoRuleEngine.extractString(
            html,
            "{{baseUrl}}|第{{@class.row thumb-overlay-albums@tag.img@data-original}}段",
            "https://site.example.com/p/9",
        )
        assertTrue("应以 baseUrl 开头，实际=$v", v!!.startsWith("https://site.example.com/p/9|第"))
        assertTrue("应保留字面量结尾，实际=$v", v.contains("段"))
    }

    @Test
    fun `无占位时不影响原有规则`() {
        val v = LegadoRuleEngine.extractString(html, "@class.row thumb-overlay-albums@tag.img@data-original", "https://x/")
        assertEquals(
            "https://cdn.example.com/1.jpg\nhttps://cdn.example.com/2.jpg\nhttps://cdn.example.com/3.jpg",
            v,
        )
    }
}
