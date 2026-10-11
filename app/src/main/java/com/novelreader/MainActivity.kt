package com.novelreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.novelreader.ui.navigation.AppNavHost
import com.novelreader.ui.theme.NovelReaderTheme

/**
 * 主 Activity，承载 Compose UI。
 *
 * 主题的日夜间模式由 DataStore 中的全局设置驱动。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NovelReaderApp
        setContent {
            // 深色模式：关闭 / 开启 / 跟随系统（#36）
            val themeMode by app.container.preferencesManager.darkThemeModeFlow
                .collectAsState(initial = com.novelreader.core.preferences.PreferencesManager.THEME_MODE_LIGHT)
            val darkTheme = when (themeMode) {
                com.novelreader.core.preferences.PreferencesManager.THEME_MODE_DARK -> true
                com.novelreader.core.preferences.PreferencesManager.THEME_MODE_SYSTEM ->
                    androidx.compose.foundation.isSystemInDarkTheme()
                else -> false
            }
            NovelReaderTheme(darkTheme = darkTheme) {
                AppNavHost(container = app.container)
            }
        }
    }
}
