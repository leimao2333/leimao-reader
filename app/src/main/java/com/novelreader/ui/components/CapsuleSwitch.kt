package com.novelreader.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 胶囊切换器（v1.3.4.0，对标 MoonTV 的 `CapsuleSwitch`）。
 *
 * 灰色药丸底 + 一枚会滑动的白色指示块，用来切换同一层级下的两三个视图
 * （影视首页的「首页 / 收藏」、片库的「选集 / 换源」等）。
 *
 * 实现上刻意用**等宽固定项**：指示块位移 = `项宽 × 下标`，
 * 不必去测量每个按钮的实时位置，省掉一轮 layout 回调，
 * 也避免首帧测量未完成时指示块闪一下。
 *
 * @param options 选项文案
 * @param selectedIndex 当前选中下标
 * @param onSelect 点击回调（传下标）
 * @param itemWidth 单项宽度；默认 72dp，三字以内文案够用
 */
@Composable
fun CapsuleSwitch(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 72.dp,
    height: Dp = 30.dp,
) {
    if (options.isEmpty()) return
    val offsetX by animateDpAsState(
        targetValue = itemWidth * selectedIndex.coerceIn(0, options.lastIndex),
        animationSpec = tween(durationMillis = 260),
        label = "capsuleOffset",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        // 滑动指示块
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .size(width = itemWidth, height = height)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surface),
        )
        Row {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(height)
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
