package com.novelreader

import com.novelreader.source.parser.LegadoRuleBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** legado 规则语法桥接（B2）纯逻辑测试：解析 / 替换 / 模板 / 语法探测。 */
class LegadoRuleBridgeTest {

    // ===== 语法特征探测 =====

    @Test
    fun `识别高级语法与简单规则`() {
        assertTrue(LegadoRuleBridge.analyze("a@href").hasAdvanced.not())
        assertTrue(LegadoRuleBridge.analyze("a@href||div@text").hasFallback)
        assertTrue(LegadoRuleBridge.analyze("div@text##\\s+##").hasReplace)
        assertTrue(LegadoRuleBridge.analyze("{{baseUrl}}/book").hasBrace)
        assertTrue(LegadoRuleBridge.analyze("a@img@src").hasChain)
        assertTrue(LegadoRuleBridge.analyze("@js:java.getString('x')").hasJsBridge)
        assertTrue(LegadoRuleBridge.analyze("@get:{bid}").hasGetPut)
    }

    @Test
    fun `hasAdvancedSyntax 对规则集合生效`() {
        assertFalse(LegadoRuleBridge.hasAdvancedSyntax(listOf("a@href", "div.title@text", null)))
        assertTrue(LegadoRuleBridge.hasAdvancedSyntax(listOf("a@href", "div@text##删我")))
        // 多级 @ 链只有单 @ 的旧引擎解释不了，必须触发桥接（hasAdvanced 曾漏掉 hasChain）
        assertTrue(LegadoRuleBridge.hasAdvancedSyntax(listOf("a@href", "a@img@src")))
    }

    // ===== || 多选一回退 =====

    @Test
    fun `splitFallback 按竖线拆分并去空`() {
        assertEquals(
            listOf("a@href", "div@text"),
            LegadoRuleBridge.splitFallback(" a@href ||  || div@text "),
        )
    }

    // ===== ## / %% 替换 =====

    @Test
    fun `parseReplace 拆出模式与模板`() {
        val (main, spec) = LegadoRuleBridge.parseReplace("div@text##\\s+##_")
        assertEquals("div@text", main)
        assertEquals("\\s+", spec!!.pattern)
        assertEquals("_", spec.template)
    }

    @Test
    fun `parseReplace 无替换时规格为空`() {
        val (main, spec) = LegadoRuleBridge.parseReplace("div@text")
        assertEquals("div@text", main)
        assertNull(spec)
    }

    @Test
    fun `百分号百分号正则规范化为井号井号`() {
        assertEquals("div@text##\\d+##X", LegadoRuleBridge.normalize("div@text%%\\d+%%X"))
        assertEquals("a@href", LegadoRuleBridge.normalize("a@href"))
    }

    @Test
    fun `applyReplace 支持分组引用`() {
        val spec = LegadoRuleBridge.ReplaceSpec("第(\\d+)章", "CH$1")
        assertEquals("CH123 内容", LegadoRuleBridge.applyReplace("第123章 内容", spec))
    }

    @Test
    fun `applyReplace 模板为空时删除匹配`() {
        val spec = LegadoRuleBridge.ReplaceSpec("<[^>]+>", null)
        assertEquals("正文", LegadoRuleBridge.applyReplace("<p>正文</p>".replace("<p>", "").replace("</p>", ""), spec))
    }

    @Test
    fun `applyReplace 跳过串尾伪空匹配不重复替换`() {
        // legado 里 (.*) 极常见：不能让 "/photo/1" 变成 ".../shunt=1/shunt=1"
        val spec = LegadoRuleBridge.ReplaceSpec("(.*)", "$1/?shunt=1")
        assertEquals("/photo/1/?shunt=1", LegadoRuleBridge.applyReplace("/photo/1", spec))
    }

    // ===== {{}} 模板 =====

    @Test
    fun `renderTemplate 求值并保留未知占位`() {
        val out = LegadoRuleBridge.renderTemplate("{{baseUrl}}/s?q={{kw}}") { key ->
            when (key) {
                "baseUrl" -> "https://x.com"
                "kw" -> "三体"
                else -> null
            }
        }
        assertEquals("https://x.com/s?q=三体", out)
    }

    @Test
    fun `renderTemplate 无占位原样返回`() {
        assertEquals("plain", LegadoRuleBridge.renderTemplate("plain") { null })
    }

    // ===== 多级 @ 链 =====

    @Test
    fun `splitChain 拆分多级选择器`() {
        assertEquals(listOf("div.list", "img", "src"), LegadoRuleBridge.splitChain("div.list@img@src"))
    }

    // ===== 委托求值（用真实 HTML 验证 legado 语法生效）=====

    @Test
    fun `extract 支持多级 @ 链与井号替换`() {
        val html = """<div class="list"><a href="/b/1"><img data-src="/c/1.jpg"></a></div>"""
        val v = LegadoRuleBridge.extract(html, ".list@img@data-src", "https://x.com")
        // data-src 是相对地址，legado 引擎会补 base
        assertTrue(v!!.contains("c/1.jpg"))
    }
}
