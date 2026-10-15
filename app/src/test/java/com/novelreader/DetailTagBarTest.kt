package com.novelreader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.novelreader.ui.components.DetailTagBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 详情页「标签小栏」UI 测试（v1.6.5.0）。
 *
 * 验证折叠 / 展开行为（演示数据只有 1~2 个标签，模拟器上看不到 ▾ 箭头，
 * 用 Robolectric 在本机确定性验证）：
 * - ≤3 个标签：全部展示、无箭头；
 * - >3 个标签：默认折叠（前 3 个 + 「更多 ▾」），点箭头展开全部 + 「收起 ▴」；
 * - 点标签触发回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetailTagBarTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `不超过三个标签时全部展示且无箭头`() {
        rule.setContent {
            DetailTagBar(tags = listOf("玄幻", "奇幻"), onTagClick = {})
        }
        rule.onNodeWithText("玄幻").assertIsDisplayed()
        rule.onNodeWithText("奇幻").assertIsDisplayed()
        // 没有「更多」入口（hasMore = false）
        rule.onNodeWithText("更多").assertDoesNotExist()
    }

    @Test
    fun `超过三个标签默认折叠显示更多箭头`() {
        rule.setContent {
            DetailTagBar(
                tags = listOf("玄幻", "奇幻", "爽文", "东方玄幻", "热血"),
                onTagClick = {},
            )
        }
        // 前 3 个可见
        rule.onNodeWithText("玄幻").assertIsDisplayed()
        rule.onNodeWithText("奇幻").assertIsDisplayed()
        rule.onNodeWithText("爽文").assertIsDisplayed()
        // 第 4 个起折叠
        rule.onNodeWithText("东方玄幻").assertDoesNotExist()
        rule.onNodeWithText("热血").assertDoesNotExist()
        // 「更多」箭头在
        rule.onNodeWithText("更多").assertIsDisplayed()
    }

    @Test
    fun `点更多箭头展开全部再点收起`() {
        rule.setContent {
            DetailTagBar(
                tags = listOf("玄幻", "奇幻", "爽文", "东方玄幻", "热血"),
                onTagClick = {},
            )
        }
        // 展开
        rule.onNodeWithText("更多").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("东方玄幻").assertIsDisplayed()
        rule.onNodeWithText("热血").assertIsDisplayed()
        rule.onNodeWithText("收起").assertIsDisplayed()
        rule.onNodeWithText("更多").assertDoesNotExist()

        // 收起
        rule.onNodeWithText("收起").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("东方玄幻").assertDoesNotExist()
        rule.onNodeWithText("更多").assertIsDisplayed()
    }

    @Test
    fun `点标签触发回调`() {
        var clicked: String? = null
        rule.setContent {
            DetailTagBar(tags = listOf("玄幻", "奇幻"), onTagClick = { clicked = it })
        }
        rule.onNodeWithText("奇幻").performClick()
        rule.waitForIdle()
        assertEquals("奇幻", clicked)
    }
}
