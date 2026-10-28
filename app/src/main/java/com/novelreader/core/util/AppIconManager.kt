package com.novelreader.core.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标切换（v1.9.0.7，待办 #48 的「软件图标」）。
 *
 * Android 的主图标由清单里的 `activity-alias` 决定，**必须是编译期资源** —— 所以能做的
 * 只是「在几套预置图标之间切」，没法让用户自己塞一张图进去当桌面图标（系统不允许动态换）。
 *
 * 约束：
 * - 同一时刻**只能有一个** alias 处于启用状态，全禁用会导致桌面图标消失；
 * - `MainActivity` 本身不能带 LAUNCHER intent-filter，否则会多出一个入口；
 * - 切换后部分桌面不会立刻刷新，需要重启桌面或重新添加图标才看得到。
 */
object AppIconManager {

    /** 与 AndroidManifest 里的 activity-alias 一一对应，顺序即设置页的展示顺序 */
    private val ALIASES = listOf("IconChild", "IconTeen", "IconYoung", "IconMature")

    const val COUNT = 4

    /** 当前生效的图标下标；读不到时返回最后一个（成熟版，清单里默认启用的那个） */
    fun current(context: Context): Int {
        val pm = context.packageManager
        for (i in ALIASES.indices) {
            val state = pm.getComponentEnabledSetting(component(context, i))
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i
        }
        return ALIASES.lastIndex
    }

    /**
     * 切到第 [index] 套图标。
     *
     * **先启用目标再禁用其余**：反过来的话，中间会有一瞬间一个启用的都没有，
     * 部分桌面会把这当成「应用被卸载」，直接把图标从桌面删掉。
     */
    fun apply(context: Context, index: Int) {
        val target = index.coerceIn(0, ALIASES.lastIndex)
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, target),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        for (i in ALIASES.indices) {
            if (i == target) continue
            pm.setComponentEnabledSetting(
                component(context, i),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun component(context: Context, index: Int): ComponentName =
        ComponentName(context, "${context.packageName}.${ALIASES[index]}")
}
