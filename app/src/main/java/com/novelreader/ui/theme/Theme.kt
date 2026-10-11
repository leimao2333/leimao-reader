package com.novelreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题配置。
 *
 * 设计原则：
 * - 支持「白天 / 夜间」双模式（跟随系统，同时允许 App 内手动切换，见 [NovelReaderTheme]）。
 * - 阅读页背景色独立于全局主题（阅读页有专属的纸张色 / 夜间色）。
 */

// ===== 白天配色 =====
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1E3A5F),
    onPrimary = Color.White,
    secondary = Color(0xFF4A6FA5),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
)

// ===== 夜间配色 =====
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7FA8D9),
    onPrimary = Color(0xFF0A1A2F),
    secondary = Color(0xFF9BB8DF),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
)

/**
 * 阅读页纸张背景色（白天模式下可选）。
 * 预留多种配色，供小说阅读页切换。
 */
object ReaderBackgrounds {
    /** 白天模式可选纸张色 */
    val dayColors = listOf(
        Color(0xFFFFFFFF) to "纯白",
        Color(0xFFF5F0E1) to "米黄",
        Color(0xFFE8F0E3) to "护眼绿",
        Color(0xFFF6E8E8) to "浅粉",
    )

    /** 夜间模式可选背景色 */
    val nightColors = listOf(
        Color(0xFF121212) to "纯黑",
        Color(0xFF1A1A2E) to "深蓝黑",
        Color(0xFF1E1E1E) to "深灰",
    )

    /** 默认字体大小档位 */
    val fontSizeSteps = listOf(14f, 16f, 18f, 20f, 22f, 24f, 26f)
}

@Composable
fun NovelReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
