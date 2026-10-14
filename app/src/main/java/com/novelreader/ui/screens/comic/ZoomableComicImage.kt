package com.novelreader.ui.screens.comic

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * 支持「双指捏合缩放 + 双击放大/复位 + 拖动查看细节」的漫画图片。
 *
 * 设计要点：
 * - 用 [detectTransformGestures] 处理双指缩放 / 平移，用 [detectTapGestures] 处理双击；
 * - 缩放与平移都通过 [graphicsLayer] 的 scale / translation 实现，不改动布局尺寸，
 *   因此不会触发父容器（LazyColumn / HorizontalPager）的滚动与翻页；
 * - 缩放态下「吞掉」单指拖动事件（transform 手势会消费），避免放大后误翻页；
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
            // 双指捏合缩放 + 平移。
            // 用 panZoomLock 区分两种状态（detectTransformGestures 的 panZoomLock=true 时，
            // 单指拖动不会触发 onGesture，只有双指捏合才会）：
            // - 1x（未放大）：panZoomLock=true → 单指上下滑透传给 LazyColumn / Pager 正常翻页，
            //   双指捏合才触发缩放（放大）；
            // - 放大态：panZoomLock=false → 单指拖动触发 onGesture 平移图片、双指继续缩放。
            .pointerInput(url, enabled) {
                if (!enabled) return@pointerInput
                detectTransformGestures(
                    panZoomLock = !currentIsZoomed,
                ) { _, pan, zoom, _ ->
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
    )
}

/** 最大缩放倍数 */
private const val MAX_SCALE = 4f
