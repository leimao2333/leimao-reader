package com.novelreader.ui.screens.comic

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * 支持「双指捏合缩放 + 双击放大/复位 + 拖动查看细节」的漫画图片。
 *
 * 设计要点：
 * - 缩放 / 平移手势自己实现（[detectComicGestures]），双击用 [detectTapGestures]；
 * - 缩放与平移都通过 [graphicsLayer] 的 scale / translation 实现，不改动布局尺寸，
 *   因此不会触发父容器（LazyColumn / HorizontalPager）的滚动与翻页；
 * - **未放大时的单指拖动一律不接管**（详见 [detectComicGestures] 的说明）；
 * - 双击在「1x ↔ 放大」之间切换；缩小到 1x 以下自动回弹到 1x 并复位位移；
 * - 放大后平移位移做边界约束，不拖出大片空白；
 * - 缩放状态逐页独立（keyed by [url]），翻页 / 切图后新页从 1x 开始。
 *
 * @param enabled 是否开启缩放交互（由阅读器根据翻页模式控制）
 * @param onZoomStateChanged 缩放态变化回调（true=进入放大态应锁住父容器翻页 / 滚动）
 */
@Composable
fun ZoomableComicImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    placeholderModifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    script: String? = null,
    enabled: Boolean = true,
    onZoomStateChanged: (Boolean) -> Unit = {},
    /** 图片加载失败回调：向上冒泡，由阅读器向源要备用镜像地址（v1.7.1.0） */
    onLoadFailed: (String) -> Unit = {},
) {
    // 逐页独立状态：翻页 / 切图后重置为 1x
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(url) { mutableStateOf(IntSize.Zero) }

    val isZoomed = scale > 1f + 0.01f

    // 手势闭包里要始终读到最新 isZoomed（panZoomLock 依赖它）
    val currentIsZoomed by rememberUpdatedState(isZoomed)

    // 缩放态变化时通知外部（锁住 / 解锁父容器翻页、滚动）
    LaunchedEffect(isZoomed) {
        onZoomStateChanged(isZoomed)
    }

    // 把位移约束在「图片放大后不拖出空白」的范围内
    fun constrainOffset() {
        if (scale <= 1f || viewportSize == IntSize.Zero) {
            offset = Offset.Zero
            return
        }
        // 放大后的可视内容尺寸超过 viewport 的部分才是可拖范围
        val maxX = (viewportSize.width * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (viewportSize.height * (scale - 1f) / 2f).coerceAtLeast(0f)
        offset = Offset(
            offset.x.coerceIn(-maxX, maxX),
            offset.y.coerceIn(-maxY, maxY),
        )
    }

    ComicImage(
        url = url,
        modifier = modifier
            .onSizeChanged { viewportSize = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            // 双击：1x ↔ 放大
            .pointerInput(url, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { pos ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = MAX_SCALE
                            // 以双击点为中心：把点击点偏移到 viewport 中心
                            val center = Offset(
                                viewportSize.width / 2f,
                                viewportSize.height / 2f,
                            )
                            offset = (center - pos) * (scale - 1f)
                            constrainOffset()
                        }
                    },
                )
            }
            // 捏合缩放 + 放大后平移（自己实现，见 detectComicGestures 的说明）
            .pointerInput(url, enabled) {
                if (!enabled) return@pointerInput
                detectComicGestures(
                    isZoomed = { currentIsZoomed },
                ) { pan, zoom ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                    scale = newScale
                    offset = if (newScale <= 1f) Offset.Zero else offset + pan
                    constrainOffset()
                }
            },
        contentScale = contentScale,
        placeholderModifier = placeholderModifier,
        headers = headers,
        script = script,
        onLoadFailed = onLoadFailed,
    )
}

/**
 * 漫画图片的「捏合缩放 + 放大后平移」手势（v1.7.2.0 自己实现）。
 *
 * **为什么不用现成的 `detectTransformGestures`** —— 这是「漫画划不动」的病根：
 * 它只要位移超过 touch slop 就无条件 `consume()` 事件，**不管当前是一根手指还是
 * 两根**。单指拖动被图片层吃掉之后，父容器（翻页 Pager / 滚动 LazyColumn）再也
 * 收不到事件，于是页面完全不跟手；而 `panZoomLock` 也拦不住这件事 ——
 * 那个参数只决定「要不要忽略旋转分量」，与「几根手指才接管」无关。
 * 表现就是：手指在漫画上左右滑，图片自己什么都没做（1x 时 offset 被归零），
 * 页面也翻不动。
 *
 * 因此这里按**手指数量 + 当前缩放态**自行判定：
 * - **未放大 + 单指** → 完全不接管，事件原样留给父容器翻页 / 滚动；
 * - **双指** → 捏合缩放（任何时候都能从 1x 放大），并接管事件；
 * - **已放大 + 单指** → 平移查看细节，并接管事件避免误翻页。
 *
 * @param isZoomed 读取「当前是否处于放大态」（用 getter 而非布尔值，保证手势协程
 *   永远读到最新状态，不必因状态变化重启手势循环）
 * @param onTransform 手势生效时回调：累计平移 [pan] 与本次缩放倍率 [zoom]
 */
private suspend fun PointerInputScope.detectComicGestures(
    isZoomed: () -> Boolean,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var lastCentroid: Offset? = null
        var lastSpan = 0f
        var pastTouchSlop = false
        var motionAcc = 0f

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            // 未放大 + 单指：**彻底不接管**，把事件完整留给父容器
            if (pressed.size < 2 && !isZoomed()) {
                lastCentroid = null
                lastSpan = 0f
                pastTouchSlop = false
                motionAcc = 0f
                continue
            }

            var sum = Offset.Zero
            pressed.forEach { sum += it.position }
            val centroid = sum / pressed.size.toFloat()
            // 双指间距：捏合缩放的依据（单指时取 0，表示没有缩放分量）
            val span = if (pressed.size >= 2) {
                (pressed[0].position - pressed[1].position).getDistance()
            } else {
                0f
            }

            val prevCentroid = lastCentroid
            val prevSpan = lastSpan
            lastCentroid = centroid
            lastSpan = span
            if (prevCentroid == null) continue

            val pan = centroid - prevCentroid
            val zoom = if (pressed.size >= 2 && prevSpan > 1f && span > 1f) {
                span / prevSpan
            } else {
                1f
            }

            if (!pastTouchSlop) {
                // 把「平移」与「缩放时手指的位移」都换算成像素，再和 touch slop 比：
                // 两指间距从 S 变到 S' 时，每根手指相对中心移动 |S - S'| / 2
                motionAcc += pan.getDistance() + abs(1f - zoom) * span / 2f
                if (motionAcc > touchSlop) pastTouchSlop = true
            }
            if (!pastTouchSlop) continue

            if (zoom != 1f || pan != Offset.Zero) onTransform(pan, zoom)
            // 接管之后必须吃掉事件，否则父容器会同时翻页 / 滚动（放大后误翻页）
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    }
}

/** 最大缩放倍数 */
private const val MAX_SCALE = 4f
