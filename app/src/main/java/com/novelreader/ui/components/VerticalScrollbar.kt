package com.novelreader.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 侧边垂直滚动条（v1.8.2.0）。
 *
 * Compose 的 Android 侧没有内置滚动条（`VerticalScrollbar` 只在 desktop 版有），
 * 这里按「列表状态 → 拇指位置/长度」自己画一个：
 * - 拇指长度按「可见高度 / 估算内容高度」算，内容不满一屏时**不显示**；
 * - 位置按首个可见项的比例算，拖动时用相反比例换算成滚动距离；
 * - 手指落在右侧的触控带内即可拖（静止时拇指 7dp、触控带 30dp；**按住即变粗**，
 *   拇指涨到 13dp、触控带涨到 52dp 并向内扩，见下）；
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
 * 一个消费协程，由它串行执行。
 *
 * ## v1.8.7.5：大位移改成「按索引直跳」（合并位移还不够，ANR 仍在）
 *
 * 真机 ANR 记录（`data_app_anr`，v1.8.7.6）的主线程栈证明合并之后**依旧会卡死**，
 * 而且卡在同一个地方：
 *
 * ```
 * VerticalScrollbar → LazyGridState.scroll → LazyGridState.onScroll
 *   → forceRemeasure → LazyGridMeasure → Text.measure → LineBreaker.nComputeLineBreaks
 * ```
 *
 * 症结在于 `scrollBy` 是**同步**的、而且它会**逐项测量滚动路径上经过的每一个格子**。
 * 海报纸宽高比 2:3、三列布局下每行约 400px，而拇指拖 1px ≈ 内容滚 400px 以上
 * （3000 项的列表里这个比例能到 1:475）—— 快速下拉 800px 就是「一次 scrollBy 跳 1200 项」，
 * 上千个标题的换行测量挤在同一个帧里，5 秒都跑不完。
 *
 * 所以现在按位移大小分流：
 * - **小位移**（< 约一屏）：走 `scrollBy`，逐像素平滑，适合精细定位；
 * - **大位移**：换算成目标索引走 `scrollToItem` —— 懒加载布局只需组合**可见的那几项**，
 *   跳过中间上千项不做测量，代价从 O(跳过项数) 降到 O(可见项数)。
 *
 * 两条路都包在 `runCatching` 里：拖动过程中列表被新数据替换时，框架层偶发的
 * 回收时序异常只会让这一帧少滚一点，不会再把进程带崩。
 *
 * ## v1.8.7.5：按住变大 + 让开系统手势区
 *
 * 拇指一直是 6dp 的话，全面屏手势机型上很难按中：右侧边缘 20dp 左右是系统的返回手势带，
 * 手指一碰就被系统接管，而拇指本身又细，表现就是「按半天按不到，还老触发返回」。
 *
 * 现在两件事一起做：
 * 1. **按住就变大**：手指一落下（还没滑，也就没有触摸阈值的延迟）拇指 7dp → 13dp，
 *    并伴随一次轻震动。放大的方向是**向内**扩，不去抢屏幕最外侧那一条 —— 越靠中间
 *    越不容易被系统手势截走。
 * 2. **触控带一直是宽的**（v1.8.7.6 起固定 52dp）：一开始想让触控带也跟着按下变宽，
 *    结果按下瞬间节点重排，Compose 会取消正在进行的手势 —— 表现为「快速一甩完全不动，
 *    只有慢慢拖才生效」，正是用户抱怨的那种「按半天按不到」。现在触控带固定宽、
 *    只有拇指的绘制宽度做动画，手势节点不再重排。拇指本身仍是 7dp，视觉不吵。
 * 3. **不跟系统手势硬抢**：一度加过 `systemGestureExclusion()`，实测没用又添乱
 *    （系统对单边可排除的高度有上限，超出部分照样被裁），已移除；现在的做法是
 *    把可拖区域整体向内加宽，让手指落在手势带之外，这是能稳定按中的那条路。
 *
 * 按下与拖动合在同一个 `pointerInput` 里（`awaitEachGesture` + 自己跟事件流）：拆成两个
 * 实测会在边缘互相干扰，按下根本没反应。拖动的手势检测一旦被重组重启，正在进行的
 * 拖动会当场断掉，所以滚动比例、首项索引都用 `rememberUpdatedState` 读最新值。
 */
@Composable
fun VerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 7.dp,
    /** 触控带宽度（v1.8.7.6 起固定生效） */
    touchWidth: Dp = 30.dp,
    /** 按住 / 拖动时的拇指宽度（v1.8.7.5）：比静止态粗近一倍，手指按上去才看得见、抓得住 */
    pressedThumbWidth: Dp = 13.dp,
    /** 触控带宽度（向内扩，避开屏幕最外侧的系统返回手势带）；实际取它与 [touchWidth] 的较大者 */
    pressedTouchWidth: Dp = 52.dp,
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
        onScrollToIndex = { index -> listState.scrollToItem(index) },
        modifier = modifier,
        thumbWidth = thumbWidth,
        touchWidth = touchWidth,
        pressedThumbWidth = pressedThumbWidth,
        pressedTouchWidth = pressedTouchWidth,
        minThumbHeight = minThumbHeight,
        color = color,
    )
}

/** 网格版滚动条（标签搜索的分类网格在用） */
@Composable
fun VerticalScrollbar(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 7.dp,
    /** 触控带宽度（v1.8.7.6 起固定生效） */
    touchWidth: Dp = 30.dp,
    /** 按住 / 拖动时的拇指宽度（v1.8.7.5）：比静止态粗近一倍，手指按上去才看得见、抓得住 */
    pressedThumbWidth: Dp = 13.dp,
    /** 触控带宽度（向内扩，避开屏幕最外侧的系统返回手势带）；实际取它与 [touchWidth] 的较大者 */
    pressedTouchWidth: Dp = 52.dp,
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
        onScrollToIndex = { index -> gridState.scrollToItem(index) },
        modifier = modifier,
        thumbWidth = thumbWidth,
        touchWidth = touchWidth,
        pressedThumbWidth = pressedThumbWidth,
        pressedTouchWidth = pressedTouchWidth,
        minThumbHeight = minThumbHeight,
        color = color,
    )
}

/**
 * 滚动条本体：两个重载都把「列表长什么样」抽成这几个标量后走这里。
 *
 * @param onScrollBy 精细滚动（小位移）
 * @param onScrollToIndex 直接跳到某个索引（大位移，避免逐项测量）
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
    onScrollToIndex: suspend (Int) -> Unit,
    modifier: Modifier,
    thumbWidth: Dp,
    touchWidth: Dp,
    pressedThumbWidth: Dp,
    pressedTouchWidth: Dp,
    minThumbHeight: Dp,
    color: Color,
) {
    // 空列表 / 还没布局出来：整个滚动条不占位也不响应
    if (totalItems <= 0 || visibleSizes.isEmpty()) return

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    // 手指一落下就置位（不等触摸阈值），拇指随之变粗 —— 「按到了」这件事要立刻有反馈
    var pressed by remember { mutableStateOf(false) }
    val expanded = pressed || dragging

    val curThumbWidth by animateDpAsState(
        targetValue = if (expanded) pressedThumbWidth else thumbWidth,
        animationSpec = tween(durationMillis = 120),
        label = "scrollbarThumbWidth",
    )
    // 触控带宽度**固定**取放大后的值，不跟着按下动画（v1.8.7.6）：
    // 一旦让这个宽度参与动画，按下瞬间节点就要重排，而重排会让 Compose 取消
    // 正在进行的手势 —— 表现是「快速一甩（<120ms）完全不动，只有慢慢拖才生效」。
    // 宁可一直用宽的那条：拇指仍然只有 7dp，视觉不吵，按中的容错还更大。
    val fixedTouchWidth = pressedTouchWidth.coerceAtLeast(touchWidth)

    // 拖动位移先攒这里，由下面唯一一个协程串行取走；conflated 信号保证「一帧只唤醒一次」
    val pendingDelta = remember { AtomicReference(0f) }
    val scrollSignal = remember { Channel<Unit>(Channel.CONFLATED) }
    val currentOnScrollBy = rememberUpdatedState(onScrollBy)
    val currentOnScrollToIndex = rememberUpdatedState(onScrollToIndex)
    // 消费协程不会因为索引变化而重启，所以要读**最新**的首项索引（每次跳转后都会更新）
    val latestFirstIndex = rememberUpdatedState(firstIndex)

    BoxWithConstraints(
        // 宽度固定（见 fixedTouchWidth 的说明）：放大只改拇指的绘制宽度，
        // 右侧边缘位置不变，手势节点不重排，快速拖动才不会被打断
        modifier = modifier
            .fillMaxHeight()
            .width(fixedTouchWidth),
    ) {
        val viewportPx = constraints.maxHeight.toFloat()
        if (viewportPx <= 0f) return@BoxWithConstraints

        val visiblePx = visibleSizes.sum().toFloat().coerceAtLeast(1f)
        val avgItemPx = visiblePx / visibleSizes.size
        // 内容总高度：可见项平均高 × 总项数。项数与可见数相同说明只有一屏，不需要滚动条
        val contentPx = max(viewportPx, avgItemPx * totalItems)
        if (contentPx <= viewportPx * 1.02f) return@BoxWithConstraints

        // 超过这个位移就改用「按索引直跳」：一屏以内的位移走 scrollBy 才既平滑又便宜
        val jumpThresholdPx = viewportPx * 0.9f
        // 每滚 1px 大致前进多少项（网格里一行多项，但比例关系一致）
        val itemsPerPx = totalItems / contentPx

        // 键**只能放 scrollSignal**（v1.8.7.6 修）：这两个换算值随 totalItems 变，
        // 而结果是一边翻页一边往列表里加的 —— 键一变，下面这个消费协程就被重启，
        // 攒着的位移跟着一起丢，表现是「数据还在涨的时候怎么甩都甩不动」。
        // 要读最新值一律走 rememberUpdatedState，不要拿它们当 LaunchedEffect 的键。
        val latestJumpThreshold = rememberUpdatedState(jumpThresholdPx)
        val latestItemsPerPx = rememberUpdatedState(itemsPerPx)
        val latestTotalItems = rememberUpdatedState(totalItems)

        LaunchedEffect(scrollSignal) {
            for (signal in scrollSignal) {
                val pending = pendingDelta.getAndSet(0f)
                if (pending == 0f) continue
                if (abs(pending) >= latestJumpThreshold.value) {
                    // 大位移：直接落到估算索引。LazyGrid 只组合可见行，
                    // 不会像 scrollBy 那样把中间经过的每一项都测一遍。
                    val target = (latestFirstIndex.value + pending * latestItemsPerPx.value)
                        .roundToInt()
                        .coerceIn(0, (latestTotalItems.value - 1).coerceAtLeast(0))
                    runCatching { currentOnScrollToIndex.value(target) }
                        .onFailure {
                            android.util.Log.w("Scrollbar", "跳转失败，丢弃本次位移", it)
                        }
                } else {
                    runCatching { currentOnScrollBy.value(pending) }
                        .onFailure {
                            android.util.Log.w("Scrollbar", "scrollBy 失败，丢弃本次位移", it)
                        }
                }
            }
        }

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

        // 三档：静止最淡、列表自己滚动时略深、按住拖动时最深（这时它是「正在用的控件」）
        val alpha by animateFloatAsState(
            targetValue = when {
                expanded -> 0.85f
                scrolling -> 0.55f
                else -> 0.30f
            },
            label = "scrollbarAlpha",
        )

        // 拇指位置 / 高度要在手势里读，用 rememberUpdatedState 避免数值变化重启 pointerInput
        val thumbTopState = rememberUpdatedState(thumbTop)
        val thumbHeightState = rememberUpdatedState(thumbPx)

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(fixedTouchWidth)
                // 一个手势处理器管两件事：按下 → 立刻放大 + 震动；同时开始跟手。
                // 以前拆成两个 pointerInput（一个观察按下、一个检测拖动），实测在边缘
                // 会互相干扰，按下根本没反应 —— 合成一个最稳。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // v1.8.8.4：**只有按在拇指附近才算数**。
                        // 触控带为了好按做得很宽（52dp），但整条都吃事件的话，列表右侧
                        // 那一竖条的点击/翻页全被它吞掉 —— 用户点右边没反应，体验很差。
                        // 现在按在拇指上下各 32dp 之外就直接放行，事件照常传给下层。
                        val hitSlop = 32.dp.toPx()
                        val top = thumbTopState.value
                        val bottom = top + thumbHeightState.value
                        if (down.position.y < top - hitSlop || down.position.y > bottom + hitSlop) {
                            return@awaitEachGesture
                        }
                        // 不等触摸阈值：手指落下就放大，这样「按到了」是立刻可见的
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        try {
                            // 不用 drag()，自己跟事件流（v1.8.7.6 真机定位）：
                            // drag() 只对 Move 事件吐位移、会忽略 UP——而快速一甩时
                            // （事件间隔小于一帧）管线可能一个 Move 都没派出来，
                            // 最后那 900px 全压在 UP 上，drag() 直接返回 0，表现就是
                            // 「甩得越快越不动」。自己跟流则 MOVE 与 UP 的位移都收。
                            val pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: break
                                // 顺序不能反（v1.8.7.6 修）：positionChange() 在事件已被消费时
                                // **恒返回 0**，先 consume() 再读位移，等于永远读到 0 ——
                                // 表现就是「滚动条把触摸吃掉了，但列表一格不动」。
                                val dy = change.positionChange().y
                                change.consume()
                                if (dy != 0f) {
                                    dragging = true
                                    // 只累加、不启协程：真正滚动由上面的消费协程合并执行
                                    pendingDelta.updateAndGet { it + dy * ratioState.value }
                                    scrollSignal.trySend(Unit)
                                }
                                if (!change.pressed) break
                            }
                        } finally {
                            // 松手后**不丢弃**最后一段位移：那是用户拖到的位置，
                            // 丢掉的表现就是「拖到底松手，结果停在半路」。
                            pressed = false
                            dragging = false
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    // 右端对齐 + 固定内边距：触控带变宽时拇指原地长粗，不会横向跳动
                    .align(Alignment.TopEnd)
                    .padding(end = (touchWidth - thumbWidth) / 2)
                    .offset { IntOffset(0, thumbTop.roundToInt()) }
                    .width(curThumbWidth)
                    .height(with(density) { thumbPx.toDp() })
                    .background(color.copy(alpha = alpha), RoundedCornerShape(50)),
            )
        }
    }
}
