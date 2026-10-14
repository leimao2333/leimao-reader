package com.novelreader

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.novelreader.ui.navigation.AppNavHost
import com.novelreader.ui.theme.NovelReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 主 Activity，承载 Compose UI。
 *
 * 主题的日夜间模式由 DataStore 中的全局设置驱动。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * 全局画中画（PIP）状态（v1.4.6.0）。
         *
         * 视频播放页订阅它，在进入/退出小窗时隐藏/恢复控制层。
         * 用 companion StateFlow 而非 Compose 局部状态，是因为
         * onPictureInPictureModeChanged 回调只发生在 Activity 层，
         * 需要把它「广播」给当前任意正在播放页里的 Composable。
         */
        private val _inPip = MutableStateFlow(false)
        val inPip: StateFlow<Boolean> = _inPip
    }

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
                // 深链支持：novelreader://search?keyword=xxx&type=novel|comic|video
                // 直达对应类型搜索（自动化测试/外部调用；缺省 type=novel）
                val deepLink = remember(intent?.data) {
                    val data = intent?.data
                    if (data?.scheme == "novelreader" && data.host == "search") {
                        (data.getQueryParameter("keyword") ?: "") to (data.getQueryParameter("type") ?: "novel")
                    } else null
                }
                AppNavHost(
                    container = app.container,
                    initialNovelSearchKeyword = deepLink?.first?.takeIf { it.isNotBlank() },
                    initialSearchType = deepLink?.second ?: "novel",
                )
            }
        }
    }

    /** 进入/退出画中画时同步全局状态（v1.4.6.0） */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _inPip.value = isInPictureInPictureMode
    }
}
