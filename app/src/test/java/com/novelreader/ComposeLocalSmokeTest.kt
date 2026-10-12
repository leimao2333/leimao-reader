package com.novelreader

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 探针：验证 Compose UI 能否在本机（Robolectric，无需模拟器 / 真机）渲染与断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeLocalSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `Compose 可在本机渲染并断言文本`() {
        rule.setContent { Text("本机 Compose 测试 OK") }
        rule.onNodeWithText("本机 Compose 测试 OK").assertIsDisplayed()
    }
}
