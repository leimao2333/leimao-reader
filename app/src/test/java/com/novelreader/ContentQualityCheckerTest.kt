package com.novelreader

import com.novelreader.core.text.ContentQualityChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B6 正文质量检查 单测。 */
class ContentQualityCheckerTest {

    private val normalText = buildString {
        repeat(12) {
            append("他沿着河岸慢慢走着，风把书页吹得哗哗响，远处传来几声狗叫。\n")
        }
    }

    @Test
    fun `正常正文不被判为空或广告`() {
        val q = ContentQualityChecker.check(normalText)
        assertTrue("正常正文应有足够字数", q.chars > 100)
        assertEquals("正常正文不应命中广告词", 0, q.adHits)
        assertFalse(ContentQualityChecker.isLikelyEmptyOrAd(normalText))
        assertTrue("正常正文分数应偏高", q.score >= 70)
    }

    @Test
    fun `极短正文判为无效`() {
        assertTrue(ContentQualityChecker.isLikelyEmptyOrAd("好的"))
        assertTrue(ContentQualityChecker.isLikelyEmptyOrAd(""))
    }

    @Test
    fun `乱码占比高的正文判为无效`() {
        val garbage = "\uFFFD\uFFFD" + "□□□□■■■■▯▮█▓▒░".repeat(6)
        val q = ContentQualityChecker.check(garbage)
        assertTrue("乱码占比应超过阈值", q.gibberishRatio > 0.3f)
        assertTrue(ContentQualityChecker.isLikelyEmptyOrAd(garbage))
    }

    @Test
    fun `末尾广告尾巴会被裁掉`() {
        val withAd = normalText + "\n请记住本站域名 www.example.com\n最新章节请加入书签"
        val stripped = ContentQualityChecker.stripAdTail(withAd)
        assertFalse("广告行应被删除", stripped.contains("请记住本站"))
        assertFalse("网址行应被删除", stripped.contains("www.example.com"))
        assertTrue("正文必须保留", stripped.contains("沿着河岸慢慢走着"))
    }

    @Test
    fun `正文中间的广告行不会被误删`() {
        val text = "第一段正常内容。\n请记住本站\n第二段继续正常内容，这里还有很长的一段话要写。\n" +
            buildString { repeat(6) { append("后面又是正常的叙述文字，继续读下去。\n") } }
        val stripped = ContentQualityChecker.stripAdTail(text)
        // 只处理末尾，中间那行广告必须保留（保守策略）
        assertTrue("中间行不应被删除", stripped.contains("请记住本站"))
    }

    @Test
    fun `无广告时原样返回`() {
        assertEquals(normalText, ContentQualityChecker.stripAdTail(normalText))
    }

    @Test
    fun `裁完剩余过短则原样返回（防误判）`() {
        // 正文本身很短，末尾是广告 → 删完不足 30 字，应整体原样返回
        val text = "短正文\n请记住本站域名\nwww.example.com"
        assertEquals(text, ContentQualityChecker.stripAdTail(text))
    }

    @Test
    fun `统计段落数与字数`() {
        val q = ContentQualityChecker.check("第一段\n\n第二段\n第三段")
        assertEquals("非空段落数", 3, q.paragraphs)
        assertEquals("去空白字数", 9, q.chars)
    }

    @Test
    fun `分数被夹在 0 到 100`() {
        val cases = listOf("", "短", normalText, "\uFFFD".repeat(500))
        for (c in cases) {
            val s = ContentQualityChecker.check(c).score
            assertTrue("score=$s 越界（text=${c.take(8)}）", s in 0..100)
        }
    }
}
