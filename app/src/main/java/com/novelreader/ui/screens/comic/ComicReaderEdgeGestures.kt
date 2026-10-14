package com.novelreader.ui.screens.comic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** 竖滑亮度 / 音量指示 */
private data class ComicVerticalIndicator(val brightness: Boolean, val value: Float)

/**
 * 漫画阅读器的「边缘竖滑调亮度 / 音量」手势层（C2）。
 *
 * 交互约定（对齐主流漫画 App，同时避开与翻页 / 滚动 / 缩放手势冲突）：
 * - **屏幕左边缘竖滑**（起手点在左侧 1/6 宽度内）→ 调亮度；
 * - **屏幕右边缘竖滑**（起手点在右侧 1/6 宽度内）→ 调音量；
 * - 中间 2/3 区域不响应，保留给翻页 / 滚动 / 双击缩放。
 *
 * 实现要点：
 * - 亮度直接改当前 Activity 的 `Window.screenBrightness`（0.02 ~ 1.0），
 *   退出阅读器时恢复原值，不写入全局设置；
 * - 音量走 `AudioManager.STREAM_MUSIC`（系统媒体流），实时生效；
 * - 竖滑过程中在对应边缘显示指示条，抬手后约 600ms 自动消失。
 */
@Composable
fun ComicReaderEdgeGestures(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var indicator by remember { mutableStateOf<ComicVerticalIndicator?>(null) }

    // 进入阅读器时记录原始亮度，退出时恢复（避免改完亮度离开漫画后全局变暗 / 变亮）
    DisposableEffect(activity) {
        val original = activity?.window?.attributes?.screenBrightness
        onDispose {
            if (activity != null && original != null) {
                val attrs = activity.window.attributes
                attrs.screenBrightness = original
                activity.window.attributes = attrs
            }
        }
    }

    // 指示条抬手后自动消失
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(600)
            indicator = null
        }
    }

    // 读取当前亮度（window 未覆盖时按 1f 计）
    fun currentBrightness(): Float {
        val b = activity?.window?.attributes?.screenBrightness ?: -1f
        return if (b < 0f) 1f else b
    }

    fun applyBrightness(value: Float) {
        val window = activity?.window ?: return
        val attrs = window.attributes
        attrs.screenBrightness = value.coerceIn(0.02f, 1f)
        window.attributes = attrs
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ===== 手势层：左右边缘竖滑调亮度 / 音量 =====
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var startX = 0f
                    var startBrightness = 1f
                    var startVolume = 0
                    var engaged = false // 是否已锁定为「边缘竖滑」

                    detectDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            engaged = false
                            startBrightness = currentBrightness()
                            startVolume =
                                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        },
                        onDrag = { change, dragAmount ->
                            // 起手点不在边缘 → 不响应，也不消费（让翻页 / 滚动正常处理）
                            val edgeLeft = startX < size.width / EDGE_RATIO
                            val edgeRight = startX > size.width * (EDGE_RATIO - 1) / EDGE_RATIO
                            if (!edgeLeft && !edgeRight) return@detectDragGestures

                            // 判定为竖滑：纵向位移显著大于横向（避免左右翻页模式被误判）
                            if (!engaged) {
                                val slop = viewConfiguration.touchSlop.toFloat()
                                if (abs(dragAmount.y) > abs(dragAmount.x) &&
                                    abs(dragAmount.y) > slop
                                ) {
                                    engaged = true
                                } else {
                                    return@detectDragGestures
                                }
                            }

                            change.consume()
                            val fraction = -dragAmount.y / size.height.coerceAtLeast(1).toFloat()
                            if (edgeLeft) {
                                val v = (startBrightness + fraction).coerceIn(0.02f, 1f)
                                applyBrightness(v)
                                indicator = ComicVerticalIndicator(true, v)
                            } else {
                                val max = audioManager
                                    .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    .coerceAtLeast(1)
                                val v = (startVolume + fraction * max).roundToInt()
                                    .coerceIn(0, max)
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC, v, 0
                                )
                                indicator = ComicVerticalIndicator(false, v / max.toFloat())
                            }
                        },
                    )
                },
        )

        // ===== 亮度 / 音量指示条 =====
        indicator?.let { ind ->
            Box(
                modifier = Modifier
                    .align(
                        if (ind.brightness) Alignment.CenterStart else Alignment.CenterEnd
                    )
                    .padding(horizontal = 20.dp)
                    .width(52.dp)
                    .height(170.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (ind.brightness) Icons.Filled.Brightness6
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(90.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.3f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(ind.value.coerceIn(0f, 1f))
                                .background(Color.White),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${(ind.value * 100).toInt().coerceIn(0, 100)}%",
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** 从 Context 逐层解包出 Activity（兼容 ContextWrapper） */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 边缘判定比例：起手点 x < 宽度/EDGE_RATIO 为左边缘，> 宽度*(EDGE_RATIO-1)/EDGE_RATIO 为右边缘 */
private const val EDGE_RATIO = 6f
