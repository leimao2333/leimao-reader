package com.novelreader.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 平板 / 大屏自适应（C3）：窄屏占满，宽屏把内容约束在一个舒适最大宽度内并居中。
 *
 * 不引入 WindowSizeClass 依赖：用 [LocalConfiguration] 的 screenWidthDp 判断，
 * 超过 600dp（Material 的 compact/medium 分界）视为大屏，内容限宽 [maxWidth] 并水平
 * 居中，避免列表在平板上拉伸到整屏导致文字行过长、卡片稀疏。手机行为完全不变。
 */
fun Modifier.adaptiveWidth(maxWidth: Dp = 900.dp): Modifier = composed {
    val widthDp = LocalConfiguration.current.screenWidthDp
    if (widthDp > 600) {
        this.fillMaxSize().wrapContentSize(Alignment.TopCenter).widthIn(max = maxWidth)
    } else {
        this
    }
}
