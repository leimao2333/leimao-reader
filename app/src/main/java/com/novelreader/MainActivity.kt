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
            val darkTheme by app.container.preferencesManager.darkThemeFlow
                .collectAsState(initial = false)
            NovelReaderTheme(darkTheme = darkTheme) {
                AppNavHost(container = app.container)
            }
        }
    }
}
