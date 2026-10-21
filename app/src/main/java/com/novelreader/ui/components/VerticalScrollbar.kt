package com.novelreader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 侧边垂直滚动条（v1.8.2.0）。
 *
 * Compose 的 Android 侧没有内置滚动条（`VerticalScrollbar` 只在 desktop 版有），
 * 这里按「列表状态 → 拇指位置/长度」自己画一个：
 * - 拇指长度按「可见高度 / 估算内容高度」算，内容不满一屏时**不显示**；
 * - 位置按首个可见项的比例算，拖动时用相反比例换算成列表滚动距离；
 * - 手指落在右侧 22dp 的触控带内即可拖（拇指 6dp，单手也抓得住）；
 * - 静止时半透明、滚动或拖动时加深，尽量不挡内容。
 *
 * 估算用的是可见项的平均高度，所以用 `items` 铺平列表时最准；分组卡片高低不一时
 * 拇指长度只是近似，但拖动方向和落点比例是对的，够用。
 *
 * ## v1.8.7.2：拖动改成「合并位移、单协程串行消费」（修 ANR）
 *
 * v1.8.7.1 在视频模块（3 列海报网格 + 长标题）拖滚动条会把主线程堵死 5s 以上，
 * bugreport 的 ANR 主线程栈是：
 *
 * ```
 * VerticalScrollbarKt$VerticalScrollbar$7$1.invokeSuspend(VerticalScrollbar.kt:96)
 *   → ScrollbarBox$2$1$4$1(VerticalScrollbar.kt:175)
 *   → AndroidComposeView.measureAndLayout → LazyGridMeasure
 *   → TextStringSimpleNode.measure → LineBreaker.nComputeLineBreaks
 * ```
 *
 * 原因是拖动回调里 `scope.launch { onScrollBy(...) }`：每个 MotionEvent 都新起一个
 * 协程，每个协程各自 `scrollBy` → 各自触发一次整张 LazyGrid 的重新测量。触摸采样
 * 率高于帧率时，一帧能塞进十几个 `scrollBy`，文字换行测量（最贵的一步）被反复重跑，
 * 主线程排不上输入事件，于是 Input dispatching timed out。
 *
 * 现在改成：拖动只往 `pendingDelta` 里累加位移，再用一个 conflated 信号唤醒**唯一**
 * 一个消费协程；该协程一次性取走累计位移、`scrollBy` 一次。`scrollBy` 本身会挂起到
 * 下一帧，所以天然做到「每帧最多重测一次」，多余位移被合并而不是排队。
 */
@Composable
fun VerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 6.dp,
    touchWidth: Dp = 22.dp,
    minThumbHeight: Dp = 36.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    ScrollbarBox(
        totalItems = listState.layoutInfo.totalItemsCount,
        visibleSizes = listState.layoutInfo.visibleItemsInfo.map { it.size },
        firstIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0,
        firstOffsetFraction = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { info ->
            if (info.size > 0) info.offset.toFloat() / info.size else 0f
        } ?: 0f,
        visibleCount = listState.layoutInfo.visibleItemsInfo.size,
        scrolling = listState.isScrollInProgress,
        onScrollBy = { delta -> listState.scrollBy(delta) },
        modifier = modifier,
        thumbWidth = thumbWidth,
        touchWidth = touchWidth,
        minThumbHeight = minThumbHeight,
        color = color,
    )
}

/** 网格版滚动条（标签搜索的分类网格在用） */
@Composable
fun VerticalScrollbar(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 6.dp,
    touchWidth: Dp = 22.dp,
    minThumbHeight: Dp = 36.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val first = gridState.layoutInfo.visibleItemsInfo.minByOrNull { it.offset.y }
    ScrollbarBox(
        totalItems = gridState.layoutInfo.totalItemsCount,
        visibleSizes = gridState.layoutInfo.visibleItemsInfo.map { it.size.height },
        firstIndex = first?.index ?: 0,
        firstOffsetFraction = first?.let { info ->
            if (info.size.height > 0) info.offset.y.toFloat() / info.size.height else 0f
        } ?: 0f,
        visibleCount = gridState.layoutInfo.visibleItemsInfo.size,
        scrolling = gridState.isScrollInProgress,
        onScrollBy = { delta -> gridState.scrollBy(delta) },
        modifier = modifier,
        thumbWidth = thumbWidth,
        touchWidth = touchWidth,
        minThumbHeight = minThumbHeight,
        color = color,
    )
}

/**
 * 滚动条本体：两个重载都把「列表长什么样」抽成这几个标量后走这里。
 */
@Composable
private fun ScrollbarBox(
    totalItems: Int,
    visibleSizes: List<Int>,
    firstIndex: Int,
    firstOffsetFraction: Float,
    visibleCount: Int,
    scrolling: Boolean,
    onScrollBy: suspend (Float) -> Unit,
    modifier: Modifier,
    thumbWidth: Dp,
    touchWidth: Dp,
    minThumbHeight: Dp,
    color: Color,
) {
    // 空列表 / 还没布局出来：整个滚动条不占位也不响应
    if (totalItems <= 0 || visibleSizes.isEmpty()) return

    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }

    // 拖动位移先攒这里，由下面唯一一个协程逐帧取走；conflated 信号保证「一帧只唤醒一次」
    val pendingDelta = remember { AtomicReference(0f) }
    val scrollSignal = remember { Channel<Unit>(Channel.CONFLATED) }
    val currentOnScrollBy = rememberUpdatedState(onScrollBy)
    LaunchedEffect(scrollSignal) {
        for (signal in scrollSignal) {
            val delta = pendingDelta.getAndSet(0f)
            if (delta != 0f) currentOnScrollBy.value(delta)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(touchWidth),
    ) {
        val viewportPx = constraints.maxHeight.toFloat()
        if (viewportPx <= 0f) return@BoxWithConstraints

        val visiblePx = visibleSizes.sum().toFloat().coerceAtLeast(1f)
        val avgItemPx = visiblePx / visibleSizes.size
        // 内容总高度：可见项平均高 × 总项数。项数与可见数相同说明只有一屏，不需要滚动条
        val contentPx = max(viewportPx, avgItemPx * totalItems)
        if (contentPx <= viewportPx * 1.02f) return@BoxWithConstraints

        val minThumbPx = with(density) { minThumbHeight.toPx() }
        val thumbPx = (viewportPx * viewportPx / contentPx)
            .coerceIn(minThumbPx.coerceAtMost(viewportPx), viewportPx)
        val maxThumbTop = (viewportPx - thumbPx).coerceAtLeast(0f)

        // 首项已滚出去的部分也算进度，避免整项跳动
        val scrollableItems = (totalItems - visibleCount).coerceAtLeast(1)
        val progress = ((firstIndex + firstOffsetFraction) / scrollableItems).coerceIn(0f, 1f)
        val thumbTop = progress * maxThumbTop

        // 拖 1px 拇指 → 内容滚多少 px。用 rememberUpdatedState 让手势闭包始终读最新值，
        // 又不会因为数值变化重启 pointerInput（重启会中断正在进行的拖动）
        val scrollRatio = if (maxThumbTop > 0f) (contentPx - viewportPx) / maxThumbTop else 1f
        val ratioState = rememberUpdatedState(scrollRatio)

        val alpha by animateFloatAsState(
            targetValue = if (dragging || scrolling) 0.62f else 0.26f,
            label = "scrollbarAlpha",
        )

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(touchWidth)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        // 只累加、不启协程：真正滚动由上面的消费协程合并执行
                        pendingDelta.updateAndGet { it + dragAmount * ratioState.value }
                        scrollSignal.trySend(Unit)
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, thumbTop.roundToInt()) }
                    .width(thumbWidth)
                    .height(with(density) { thumbPx.toDp() })
                    .background(color.copy(alpha = alpha), RoundedCornerShape(50)),
            )
        }
    }
}
