package com.novelreader

import androidx.test.core.app.ApplicationProvider
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.core.text.ContentPipelineConfigFactory
import com.novelreader.core.text.ReplaceRule
import com.novelreader.core.text.TextReplaceRules
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 偏好 → 流水线配置 的**真实**接线单测（B8）。
 *
 * 用 Robolectric 起一个真实的 [PreferencesManager]（真 DataStore），
 * 验证工厂读的是**真实存在的偏好键**：
 * - `KEY_NOVEL_REPLACE_RULES`（正文净化 / 替换规则）；
 * - `KEY_NOVEL_INDENT`（首行缩进开关）。
 *
 * 与纯函数那组测试（[ContentPipelineConfigFactoryTest]）互补：
 * 这里验「键接对了」，那里验「映射算对了」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentPipelinePreferencesTest {

    private fun preferences(): PreferencesManager =
        PreferencesManager(ApplicationProvider.getApplicationContext())

    @Test
    fun `替换规则偏好被解码进配置`() = runTest {
        val prefs = preferences()
        val rule = ReplaceRule("广告内容", "")
        prefs.setNovelReplaceRules(TextReplaceRules.encode(listOf(rule)))

        val mapped = ContentPipelineConfigFactory.readPreferences(prefs)

        assertEquals(listOf(rule), mapped.replaceRules)
        assertEquals(listOf(rule), ContentPipelineConfigFactory.toConfig(mapped).replaceRules)
    }

    @Test
    fun `首行缩进偏好被映射进配置`() = runTest {
        val prefs = preferences()
        prefs.setNovelIndent(true)

        val mapped = ContentPipelineConfigFactory.readPreferences(prefs)

        assertTrue(mapped.indentEnabled)
        assertTrue(ContentPipelineConfigFactory.toConfig(mapped).indentEnabled)
    }

    @Test
    fun `没设置过偏好时给默认值`() = runTest {
        val prefs = preferences()
        prefs.setNovelReplaceRules("")
        prefs.setNovelIndent(false)

        val mapped = ContentPipelineConfigFactory.readPreferences(prefs)

        assertTrue(mapped.replaceRules.isEmpty())
        assertEquals(false, mapped.indentEnabled)
        assertEquals(
            ContentPipelineConfigFactory.DEFAULT_MAX_BLANK_LINES,
            mapped.maxBlankLines,
        )
    }
}
