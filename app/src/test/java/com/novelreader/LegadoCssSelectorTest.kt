package com.novelreader

import com.novelreader.source.legado.LegadoRuleEngine
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSS id 选择器（`tag#id`、`#id`、`tag#id.class`）的解析回归。
 *
 * 背景：引擎的 `looksLikeCss` 只认空格 / `>` / `[` / `:` / `,`，**不认 `#`**，
 * 于是 `div#content.showtxt@text` 会落到 legado 短式解析，被按 `.` 拆成
 * `div#content` + `showtxt`，最终 select 到非法选择器 → 匹配不到元素 →
 * 正文静默为空。线上表现：何以生肖（`div#content.showtxt@text`）、
 * 笔趣阁系（`div#content@html`）。legado 语法里 id 一律写 `id.xxx`，
 * 所以带 `#` 的规则按 CSS 解析是安全的。
 */
class LegadoCssSelectorTest {

    private val html = """
        <html><body>
          <div id="content" class="showtxt">　　“斗之力，三段！”<br/>　　“萧炎，斗之力，三段！”</div>
          <div class="other">干扰项</div>
        </body></html>
    """.trimIndent()

    /** 何以生肖：`div#content.showtxt@text`（tag + id + class 混写） */
    @Test
    fun `正文规则 - tag 加 id 加 class 混写按 CSS 解析`() {
        val text = LegadoRuleEngine.extractString(html, "div#content.showtxt@text")
        assertTrue("应取到正文，实际为 $text", text != null && text.contains("斗之力"))
        assertTrue("不应混入其他元素", text!!.indexOf("干扰项") < 0)
    }

    /** 笔趣阁：`div#content@html`（tag + id，无 class） */
    @Test
    fun `正文规则 - tag 加 id 按 CSS 解析`() {
        val text = LegadoRuleEngine.extractString(html, "div#content@html")
        assertTrue("应取到正文 HTML，实际为 $text", text != null && text.contains("斗之力"))
    }

    /** 纯 `#id`（原本走 legado Id 分支，加 `#` 判定后仍须一致） */
    @Test
    fun `正文规则 - 纯 id 选择器行为不变`() {
        val text = LegadoRuleEngine.extractString(html, "#content@text")
        assertTrue("应取到正文，实际为 $text", text != null && text.contains("斗之力"))
        assertTrue("不应混入其他元素", text!!.indexOf("干扰项") < 0)
    }

    /** legado 短式类名不受影响：`.showtxt` / `class.showtxt` 仍能取到 */
    @Test
    fun `短式类名与显式 class 前缀不受影响`() {
        assertTrue(LegadoRuleEngine.extractString(html, ".showtxt@text")!!.contains("斗之力"))
        assertTrue(LegadoRuleEngine.extractString(html, "class.showtxt@text")!!.contains("斗之力"))
    }

    /** 复合类名（空格分隔）仍按 legado 语法处理：`class.a b` → `.a.b` */
    @Test
    fun `复合类名仍按 legado 语法拼成 CSS`() {
        val doc = """<html><body><div class="info-zi mb15">简介正文</div></body></html>"""
        assertTrue(LegadoRuleEngine.extractString(doc, "class.info-zi mb15@html")!!.contains("简介正文"))
    }
}
