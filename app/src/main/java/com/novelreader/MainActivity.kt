package com.novelreader

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.novelreader.core.pdf.IncomingPdf
import com.novelreader.core.pdf.PdfIntentImport
import com.novelreader.ui.navigation.AppNavHost
import com.novelreader.ui.theme.NovelReaderTheme
import com.novelreader.ui.theme.applySystemBarIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    /**
     * 外部应用推过来、待打开的 PDF（v1.8.7.2）。
     *
     * 实例级而不是 companion：系统「用其他软件打开」默认会给本 Activity 起一个新实例，
     * 两个实例共用一个 flow 的话，谁先把值取走就说不清了。
     */
    private val _incomingPdf = MutableStateFlow<IncomingPdf?>(null)
    private val incomingPdf: StateFlow<IncomingPdf?> = _incomingPdf

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
            // v1.9.0.2：详细日志开关（设置 → 开发者选项）同步到 DebugLog。
            // 打开后关键路径会写进内存环形缓冲，用户可在设置里一键导出。
            val debugLogging by app.container.preferencesManager.debugLoggingFlow
                .collectAsState(initial = false)
            LaunchedEffect(debugLogging) {
                com.novelreader.core.util.DebugLog.enabled = debugLogging
            }
            // v1.9.0.5：界面字体（设置 → 外观）
            val appFont by app.container.preferencesManager.appFontFlow
                .collectAsState(initial = 0)
            // v1.9.1.0：用户导入的字体文件（appFont == 4 时生效）
            val customFontPath by app.container.preferencesManager.customFontPathFlow
                .collectAsState(initial = "")
            // C1 动态取色：开关打开且取到过种子色（!= 0）时，用 ThemeEngine 按种子色生成整套配色；
            // 否则传 null，`NovelReaderTheme` 内部仍走原来的写死两套，观感完全不变。
            val themeDynamicColor by app.container.preferencesManager.themeDynamicColorFlow
                .collectAsState(initial = false)
            val themeSeedColor by app.container.preferencesManager.themeSeedColorFlow
                .collectAsState(initial = 0)
            val dynamicColorScheme = remember(themeDynamicColor, themeSeedColor, darkTheme) {
                if (themeDynamicColor && themeSeedColor != 0) {
                    com.novelreader.ui.theme.ThemeEngine.fromSeedColor(themeSeedColor, darkTheme)
                } else {
                    null
                }
            }
            // C2：主题风格（标准/暖色/冷色），动态取色关闭时生效
            val themeStyle by app.container.preferencesManager.themeStyleFlow
                .collectAsState(initial = 0)
            NovelReaderTheme(
                darkTheme = darkTheme,
                appFont = appFont,
                customFontPath = customFontPath,
                colorScheme = dynamicColorScheme,
                style = themeStyle,
            ) {
                // 夜间模式下页面不该露白（v1.9.6.3）。
                //
                // 窗口主题只有一份 `Theme.Material.Light.NoActionBar`（没有 values-night），
                // windowBackground 恒为白色；Compose 的夜间模式只换 MaterialTheme 里的配色，
                // 改不到窗口底色。于是夜间模式下凡是「页面没自己铺背景」的地方 —— 播放页
                // 下方选集区、页面转场、加载态 —— 全是白底（用户原话：夜间模式就该深色，
                // 和主题无关）。这里给整个 App 兜一层 Surface 取当前配色的 background，
                // 同时把窗口底色也一起换掉，转场时不会闪一下白。
                val appBackground = MaterialTheme.colorScheme.background
                val windowBackground = remember(appBackground) {
                    android.graphics.drawable.ColorDrawable(appBackground.toArgb())
                }
                SideEffect { window.setBackgroundDrawable(windowBackground) }

                Surface(modifier = Modifier.fillMaxSize(), color = appBackground) {
                    // 系统栏图标明暗跟随**应用**主题（v1.8.8.2）。
                    //
                    // enableEdgeToEdge() 在 onCreate 里按**系统**深色模式设过一次图标颜色，而本应用
                    // 的主题是用户设置项：系统深色 + 应用浅色时，白色图标就落在白色页面上 ——
                    // 整条状态栏看着像被顶栏盖住了（用户反馈原话）。这里在每次主题确定后覆盖一次，
                    // 后续各页若整屏是黑底（播放器全屏 / 漫画阅读）再由页面自己改成白色图标。
                    LaunchedEffect(darkTheme, themeMode) {
                        applySystemBarIcons(darkIcons = !darkTheme)
                        // 夜间模式优化：「跟随系统」模式下系统昼夜切换后，把折算值实时同步给
                        // 小说阅读页夜间（此前只在切换设置那一刻拍一次快照，系统切换后不跟随）。
                        // 只写 novelNight、不改 themeMode，保持「跟随系统」状态。
                        if (themeMode == com.novelreader.core.preferences.PreferencesManager.THEME_MODE_SYSTEM) {
                            app.container.preferencesManager.syncNovelNightForSystem(darkTheme)
                        }
                    }

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
                        incomingPdf = incomingPdf,
                        onIncomingPdfConsumed = { _incomingPdf.value = null },
                    )
                }
            }
        }

        handleIncomingPdf(intent)
    }

    /**
     * 本 Activity 已在栈里时，系统不会再走 onCreate —— 必须靠这里接住新 Intent。
     *
     * 注意没有改 `launchMode="singleTop"`：改了会让现有的
     * `novelreader://search` 深链在「应用已在前台」时走进 onNewIntent，
     * 而 setContent 里那段深链解析只在组合时读一次 intent，等于把深链弄坏。
     * 多开一个实例换来的代价只是返回键多退一层，可以接受。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPdf(intent)
    }

    /**
     * 「用其他软件打开」→ 复制进私有目录 → 交给导航层进阅读页。
     *
     * 复制放在 IO 线程：一份 20MB 的期刊 PDF 同步写会把首帧卡住；
     * 失败（不是 PDF / 读不出来）给一句 Toast，而不是跳进阅读页再报「打不开」。
     */
    private fun handleIncomingPdf(intent: Intent?) {
        if (!PdfIntentImport.isPdfIntent(intent)) return
        val uri = PdfIntentImport.extractUri(intent ?: return) ?: return
        lifecycleScope.launch {
            val pdf = PdfIntentImport.import(applicationContext, uri)
            if (pdf == null) {
                Toast.makeText(this@MainActivity, "这个文件不是有效的 PDF", Toast.LENGTH_LONG).show()
            } else {
                _incomingPdf.value = pdf
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
