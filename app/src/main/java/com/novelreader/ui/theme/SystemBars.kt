package com.novelreader.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 系统状态栏 / 导航栏图标的明暗控制（v1.8.8.2）。
 *
 * 为什么需要单独一层：应用开了 `enableEdgeToEdge()`，状态栏是透明的、内容直接铺到屏幕顶端，
 * 所以「状态栏看不看得清」完全取决于**它背后那块背景**与**图标颜色**是否反差。
 * 而 `enableEdgeToEdge()` 默认是按**系统**深色模式决定图标颜色的 —— 本应用的主题却是
 * 用户设置项（默认浅色），两者不一致时就会出现「白色图标压白色页面」，整条状态栏消失。
 *
 * 因此这里的口径是：**按背景色的感知明度**决定图标，而不是按主题或系统设置。
 *
 * @param darkIcons `true` = 深色图标（浅底上用）；`false` = 白色图标（深底上用）
 */
fun Activity?.applySystemBarIcons(darkIcons: Boolean) {
    val window = this?.window ?: return
    val controller = WindowCompat.getInsetsController(window, window.decorView) ?: return
    controller.isAppearanceLightStatusBars = darkIcons
    controller.isAppearanceLightNavigationBars = darkIcons
}

/**
 * 这个背景色上应该用深色图标吗。
 *
 * 用感知明度（`luminance()`，已做 sRGB 伽马校正）而不是简单取平均：
 * 阅读页的「藏蓝」纸张色在数值上不算黑，但肉眼是深底，必须配白字 —— 明度判断与它一致。
 */
fun Color.prefersDarkSystemBarIcons(): Boolean = luminance() > 0.5f

/**
 * 让系统栏图标跟着「状态栏背后那块背景色」走。
 *
 * 放在页面根部、把该页面铺到状态栏下面的那个背景色传进来即可。用 [LaunchedEffect]
 * 而不是 `SideEffect`：只在明暗真的变了才写一次系统属性，避免每次重组都去动窗口。
 */
@Composable
fun SystemBarIconsOn(background: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val darkIcons = background.prefersDarkSystemBarIcons()
    LaunchedEffect(darkIcons) {
        view.context.findActivity()?.applySystemBarIcons(darkIcons)
    }
}

/** 从 Context 向上找到宿主 Activity */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
