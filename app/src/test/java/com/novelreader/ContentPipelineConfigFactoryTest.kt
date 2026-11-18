package com.novelreader

import com.novelreader.core.text.ContentPipeline
import com.novelreader.core.text.ContentPipelineConfig
import com.novelreader.core.text.ContentPipelineConfigFactory
import com.novelreader.core.text.ReplaceRule
import com.novelreader.core.text.TextCleaner
import com.novelreader.core.text.TextReplaceRules
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文处理配置工厂 单测（B8）。
 *
 * 覆盖：
 * - 偏好 → 配置的映射（替换规则 / 段首缩进 / 连续空行数）；
 * - 数据层配置与复评配置的开关口径；
 * - 流水线「净化 + 替换」的结果与既有 [TextCleaner] 旧逻辑等价。
 */
class ContentPipelineConfigFactoryTest {

    /** 一条用户规则（删除「广告内容」） */
    private val adRule = ReplaceRule("广告内容", "")

    // ===== 偏好 → 配置 =====

    @Test
    fun `替换规则串被正确解码成规则列表`() {
        val raw = TextReplaceRules.encode(listOf(adRule, ReplaceRule("第\\d+章", "【章节】", isRegex = true)))
        val prefs = ContentPipelineConfigFactory.preferencesOf(raw, indentEnabled = false)
        assertEquals(listOf(adRule, ReplaceRule("第\\d+章", "【章节】", isRegex = true)), prefs.replaceRules)
    }

    @Test
    fun `空规则串得到空规则列表`() {
        assertTrue(ContentPipelineConfigFactory.preferencesOf("", indentEnabled = false).replaceRules.isEmpty())
        assertTrue(ContentPipelineConfigFactory.preferencesOf(null, indentEnabled = false).replaceRules.isEmpty())
    }

    @Test
    fun `完整配置映射出替换规则与段首缩进`() {
        val prefs = ContentPipelineConfigFactory.preferencesOf(
            replaceRulesRaw = TextReplaceRules.encode(listOf(adRule)),
            indentEnabled = true,
            maxBlankLines = 2,
        )
        val config = ContentPipelineConfigFactory.toConfig(prefs)
        assertTrue("完整配置应开净化", config.sanitize)
        assertEquals(listOf(adRule), config.replaceRules)
        assertTrue("用户开了缩进就要带上", config.indentEnabled)
        assertEquals(2, config.maxBlankLines)
        assertTrue("完整配置含质量检查", config.qualityCheck)
    }

    @Test
    fun `默认偏好映射出不替换不缩进的完整配置`() {
        val config = ContentPipelineConfigFactory.toConfig(ContentPipelineConfigFactory.Preferences())
        assertTrue(config.replaceRules.isEmpty())
        assertFalse(config.indentEnabled)
        assertEquals(ContentPipelineConfigFactory.DEFAULT_MAX_BLANK_LINES, config.maxBlankLines)
    }

    // ===== 数据层配置 =====

    @Test
    fun `数据层配置只开净化`() {
        val config = ContentPipelineConfigFactory.forRepository()
        assertTrue("数据层要净化正文", config.sanitize)
        assertTrue("替换规则归阅读页唯一一处执行，数据层不重复应用", config.replaceRules.isEmpty())
        assertFalse("缩进归阅读页渲染层，数据层不插", config.indentEnabled)
        assertFalse("完整评分是全文扫描，快路径不开", config.qualityCheck)
        assertEquals(ContentPipelineConfigFactory.DEFAULT_MAX_BLANK_LINES, config.maxBlankLines)
    }

    @Test
    fun `数据层配置可覆盖连续空行上限`() {
        assertEquals(3, ContentPipelineConfigFactory.forRepository(maxBlankLines = 3).maxBlankLines)
        // 负数没有意义，钳到 0
        assertEquals(0, ContentPipelineConfigFactory.forRepository(maxBlankLines = -1).maxBlankLines)
    }

    @Test
    fun `复评配置只开质量检查`() {
        val config = ContentPipelineConfigFactory.forRescoring()
        assertTrue("复评路径要出完整评分", config.qualityCheck)
        assertFalse("正文已净化过，不必再净化", config.sanitize)
        assertTrue(config.replaceRules.isEmpty())
        assertFalse(config.indentEnabled)
    }

    @Test
    fun `没有注入偏好时给一份默认偏好`() = runTest {
        val prefs = ContentPipelineConfigFactory.readPreferences(null)
        assertTrue(prefs.replaceRules.isEmpty())
        assertFalse(prefs.indentEnabled)
        assertEquals(ContentPipelineConfigFactory.DEFAULT_MAX_BLANK_LINES, prefs.maxBlankLines)
    }

    // ===== 流水线与旧逻辑等价 =====

    @Test
    fun `流水线净化加替换的结果与旧逻辑等价`() {
        val raw = "第一段  \r\n\r\n\r\n第二段\r\n第三段"
        val rules = listOf(adRule)
        val old = TextCleaner.clean(raw, rules)
        val now = ContentPipeline.process(
            raw,
            ContentPipelineConfigFactory.forRepository().copy(replaceRules = rules),
        ).text
        assertEquals(old, now)
    }

    @Test
    fun `流水线替换后的正文与旧逻辑逐字一致`() {
        val raw = "正文里有广告内容，还有一段。\n\n广告内容再来一次。"
        val rules = listOf(adRule)
        val old = TextCleaner.clean(raw, rules)
        val now = ContentPipeline.process(
            raw,
            ContentPipelineConfigFactory.forRepository().copy(replaceRules = rules),
        ).text
        assertEquals(old, now)
        assertFalse("广告内容应被替换掉", now.contains("广告内容"))
    }

    @Test
    fun `流水线清掉控制字符是旧逻辑之外的净收益`() {
        val raw = "正文\u0007开始\uFEFF"
        val now = ContentPipeline.process(raw, ContentPipelineConfigFactory.forRepository()).text
        assertEquals("正文开始", now)
    }

    @Test
    fun `流水线处理两次结果相同`() {
        val config = ContentPipelineConfigFactory.forRepository().copy(replaceRules = listOf(adRule))
        val once = ContentPipeline.process("第一段\n\n\n\n第二段广告内容", config).text
        val twice = ContentPipeline.process(once, config).text
        assertEquals(once, twice)
    }
}
