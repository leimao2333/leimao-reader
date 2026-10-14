package com.novelreader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 轻量下拉刷新容器（v1.5.0.0）。
 *
 * 为什么不用 Material3 的 PullToRefreshBox：项目 BOM 2024.09.03 上它是实验 API，
 * 在「Lazy 列表 + 嵌套滚动」场景行为不完全可控。这里用 Compose 标准模式自实现：
 * - 子内容（Lazy 列表）滚到顶部后继续下拉时，drag 经 nestedScroll 交给本容器消费，
 *   内容随手指下移（阻尼 0.5，最大 120dp）；
 * - 下拉超过 80dp 进入「松手刷新」armed 状态，松手触发 [onRefresh]；
 * - 刷新期间顶部常驻指示圈 + 「正在刷新…」，[refreshing] 由调用方维护；
 * - 若调用方忘记维护状态，2s 后兜底回弹。
 *
 * @param refreshing 是否正在刷新
 * @param onRefresh 松手触发刷新
 * @param content 内容（下拉时随之下移）
 */
@Composable
fun PullToRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val triggerPx = remember(density) { with(density) { 80.dp.toPx() } }
    val maxPullPx = remember(density) { with(density) { 120.dp.toPx() } }
    val indicatorSizePx = remember(density) { with(density) { 36.dp.toPx() }.roundToInt() }
    val hintGapPx = remember(density) { with(density) { 2.dp.toPx() }.roundToInt() }

    var pullPx by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    var hasPulled by remember { mutableStateOf(false) }
    var reboundJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 闭包捕获最新值（remember(Unit) 的闭包不会因参数变化而重建）
    val currentRefreshing by rememberUpdatedState(refreshing)
    val currentArmed by rememberUpdatedState(armed)
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    // 下拉位移 → 平滑动画
    val offsetPx by animateFloatAsState(
        targetValue = if (refreshing) triggerPx * 0.6f else pullPx,
        label = "pullOffset",
    )

    // nestedScroll：内容滚到顶后，向下的 drag 由本容器消费做下拉
    val connection = remember(triggerPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (currentRefreshing) return Offset.Zero
                if (available.y > 0f) {
                    // 新拖动取消待执行的回弹（连续微拖动可累积触发）
                    reboundJob?.cancel()
                    reboundJob = null
                    val new = (pullPx + available.y * 0.5f).coerceAtMost(maxPullPx)
                    pullPx = new
                    armed = new >= triggerPx
                    hasPulled = true
                    return Offset(0f, available.y)
                }
                if (available.y < 0f && pullPx > 0f) {
                    val shrink = minOf(pullPx, -available.y)
                    pullPx -= shrink
                    armed = pullPx >= triggerPx
                    return Offset(0f, -shrink)
                }
                return Offset.Zero
            }
        }
    }

    // 松手检测：只关心抬起（Final pass），不做拖动判断
    val releaseDetector = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val up = event.changes.firstOrNull { it.changedToUp() }
                if (up != null && hasPulled) {
                    if (currentArmed && !currentRefreshing) {
                        scope.launch {
                            currentOnRefresh()
                            // 兜底：调用方若忘记维护 refreshing 状态，2s 后自行回弹
                            delay(2000)
                            pullPx = 0f
                            armed = false
                        }
                    } else {
                        // 未达阈值：延迟 200ms 回弹。期间若用户继续拖动（新 DOWN→MOVE），
                        // onPreScroll 会取消回弹，使连续微拖动也能累积触发刷新
                        reboundJob?.cancel()
                        reboundJob = scope.launch {
                            delay(200)
                            if (!currentRefreshing) {
                                pullPx = 0f
                                armed = false
                            }
                        }
                    }
                    hasPulled = false
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().nestedScroll(connection).then(releaseDetector)) {
        // ===== 顶部指示器（跟随下拉位移） =====
        val showIndicator = refreshing || pullPx > 0.5f
        if (showIndicator) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .offset {
                        IntOffset(0, (offsetPx - indicatorSizePx).roundToInt().coerceAtLeast(0))
                    }
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (armed) "↑" else "↓",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val hint = when {
                refreshing -> "正在刷新…"
                armed -> "松手刷新"
                else -> null
            }
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, (offsetPx + hintGapPx).roundToInt()) }
                        .fillMaxWidth(),
                )
            }
        }

        // ===== 内容（随下拉下移） =====
        Box(modifier = Modifier.offset { IntOffset(0, offsetPx.roundToInt()) }) {
            content()
        }
    }
}
