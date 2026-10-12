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
    /**
     * 每个配色项：背景色 + 名称 + 对应的文字色。
     * 保证「黑底配白字、蓝底配白字、浅色底配黑字」，文字始终清晰可读。
     */
    data class ReaderColor(
        val background: Color,
        val name: String,
        val text: Color,
    )

    /** 白天模式可选纸张色（浅底配深字）——对标 legado 12+ 主题扩充（v1.2.0） */
    val dayColors = listOf(
        ReaderColor(Color(0xFFFFFFFF), "纯白", Color(0xFF1A1A1A)),
        ReaderColor(Color(0xFFF5F0E1), "米黄", Color(0xFF3A3228)),
        ReaderColor(Color(0xFFE8F0E3), "护眼绿", Color(0xFF22351F)),
        ReaderColor(Color(0xFFF6E8E8), "浅粉", Color(0xFF4A2E2E)),
        ReaderColor(Color(0xFF3555A0), "藏蓝", Color(0xFFFFFFFF)),
        ReaderColor(Color(0xFFF2EBDC), "羊皮纸", Color(0xFF4E4132)),
        ReaderColor(Color(0xFFE3EDEA), "浅青", Color(0xFF24382F)),
        ReaderColor(Color(0xFFECE9F5), "淡紫", Color(0xFF3A3050)),
        ReaderColor(Color(0xFFEFEFEF), "浅灰", Color(0xFF2B2B2B)),
        ReaderColor(Color(0xFFF7EED9), "杏黄", Color(0xFF4E3A1E)),
        ReaderColor(Color(0xFFE9F1F7), "天青", Color(0xFF24384A)),
        ReaderColor(Color(0xFFF0E6DA), "奶茶", Color(0xFF453528)),
    )

    /** 夜间模式可选背景色（深底配浅字） */
    val nightColors = listOf(
        ReaderColor(Color(0xFF121212), "纯黑", Color(0xFFB0B0B0)),
        ReaderColor(Color(0xFF1A1A2E), "深蓝黑", Color(0xFFB0B8C8)),
        ReaderColor(Color(0xFF1E1E1E), "深灰", Color(0xFFA8A8A8)),
        ReaderColor(Color(0xFF2A2A1A), "深褐", Color(0xFFC0B898)),
        ReaderColor(Color(0xFF12241C), "墨绿", Color(0xFFA8C0B0)),
        ReaderColor(Color(0xFF241A2E), "暗紫", Color(0xFFB8A8C8)),
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
