package com.novelreader.core.util

/**
 * 进度条上的「缓存/缓冲」展示口径（v1.7.7.0）。
 *
 * 抽成纯逻辑是为了能离线断言：百分比取整、最小 1%、满 100% 换文案、
 * 时长未知时不显示 —— 这些边界在 UI 里肉眼很难验（要真等它缓冲到 99%）。
 */
object VideoCacheProgress {

    /**
     * 已缓冲百分比（0..100）。
     *
     * @param bufferedMs 播放器的 `bufferedPosition`（时间轴语义，不是磁盘字节）
     * @param durationMs 总时长；未知（0 / TIME_UNSET）时返回 0
     */
    fun percent(bufferedMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val b = bufferedMs.coerceIn(0L, durationMs)
        return ((b * 100) / durationMs).toInt().coerceIn(0, 100)
    }

    /**
     * 进度条上那行小字。
     *
     * - 时长未知 / 还没开始缓冲 → `null`（不占位，避免一行空文案忽隐忽现）
     * - 缓冲满 → 「已缓存」（点开下一集之前，这一集已经在内存里了）
     * - 否则 → 「缓存 37%」，且**最小显示 1%**：`bufferedPosition` 在刚起播时
     *   常常只有几十毫秒，算出来是 0%，但用户明明看到进度条动了一点，
     *   显示 0% 反而像坏了
     */
    fun label(bufferedMs: Long, durationMs: Long): String? {
        if (durationMs <= 0L) return null
        if (bufferedMs <= 0L) return null
        val p = percent(bufferedMs, durationMs)
        return when {
            p >= 100 -> "已缓存"
            else -> "缓存 ${p.coerceAtLeast(1)}%"
        }
    }

    /**
     * 进度条上「已缓冲」那一段的长度比例（0..1）。
     *
     * 拖动时以拖动位置为准（否则手指在红点上、白段却在别处，看着就是错位）。
     */
    fun bufferedFraction(bufferedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** 已播放比例（0..1）；拖动态由调用方传入的 drag 值覆盖 */
    fun playedFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}
