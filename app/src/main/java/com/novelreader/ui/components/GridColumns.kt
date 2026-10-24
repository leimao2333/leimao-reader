package com.novelreader.ui.components

import com.novelreader.domain.model.SourceType

/**
 * 搜索结果宫格的自适应列数（v1.8.8.3 平板适配）。
 *
 * 手机（窄屏）维持原口径：视频 3 列、小说 / 漫画 2 列（这两类封面更方，
 * 三列会把书名挤成一两个字）。平板 / 横屏大宽度下按屏幕宽度阶梯加列，
 * 否则同一套卡片在 10 寸屏上会被拉成三四个超大格子，浪费横向空间。
 *
 * @param screenWidthDp [androidx.compose.ui.platform.LocalConfiguration] 的屏幕宽度（dp）
 * @param type 内容类型（决定基准列数）
 */
fun adaptiveGridColumns(screenWidthDp: Int, type: SourceType): Int {
    val base = if (type == SourceType.VIDEO) 3 else 2
    // 每 180dp 一档加一列；手机竖屏（~360dp）维持基准，平板（≥600dp）逐步加
    return when {
        screenWidthDp >= 1200 -> base + 5
        screenWidthDp >= 1000 -> base + 4
        screenWidthDp >= 800 -> base + 3
        screenWidthDp >= 600 -> base + 2
        screenWidthDp >= 480 -> base + 1
        else -> base
    }.coerceAtMost(8)
}
