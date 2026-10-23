package com.novelreader.core.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * 视频预缓存时长的档位换算（v1.8.4.0）。
 *
 * 纯计算，不碰播放器，可直接单测。档位与 [DefaultLoadControl] 四个参数的关系：
 *
 * - `minBufferMs`：低于这个量就继续加载；`maxBufferMs`：高于这个量就停止加载（= 向后缓冲多久）
 * - `bufferForPlaybackMs`：起播前至少攒多少（2s，太大会让每集开头都停顿）
 * - `bufferForPlaybackAfterRebufferMs`：卡顿后重新开播前至少攒多少（4s，防止「播 0.5s 又卡」）
 *
 * 关档（0）不是「完全缓冲」，而是收到 15s —— 完全不缓冲会让采集站的慢分片每几秒卡一次，
 * 省流用户要的是「少下一点」，不是「一直卡」。
 */
object VideoBufferOptions {

    /** 关闭档（仍有 15s 的最小缓冲，只是不再往后吃到最长档位） */
    const val OFF = 0

    /**
     * 可选档位（秒），与设置页顺序一致。
     *
     * v1.8.7.9（待办 #32）：原来最长 120s，网络好的源想看 4K 长片时回退 / 快进仍要重新拉流，
     * 因此往上补 180 / 300 / 600 三档。**加档位时三处必须一起改**：
     * ① 这里的 [CHOICES]；② [durationsMs] 的 `when` 分支（写死在 else 里会静默按 120s 处理）；
     * ③ `PreferencesManager.setVideoBufferSeconds` 的 `coerceIn` 上界（否则写不进去）。
     */
    val CHOICES = listOf(0, 15, 30, 60, 120, 180, 300, 600)

    /** 最长档位（秒），供偏好层做上界钳制 */
    val MAX_SECONDS: Int = CHOICES.max()

    /** 设置页 / 播放页展示用文案 */
    fun label(seconds: Int): String = when {
        seconds <= 0 -> "关闭"
        // 5 分钟以上用「分」表示，避免 600s 这类读起来要在脑子里换算
        seconds >= 300 && seconds % 60 == 0 -> "${seconds / 60}min"
        else -> "${seconds}s"
    }

    /** 把任意存量值对齐到最近的合法档位（脏数据兜底） */
    fun normalize(seconds: Int): Int = CHOICES.minByOrNull { kotlin.math.abs(it - seconds) } ?: MAX_SECONDS

    /**
     * 换算成 `DefaultLoadControl` 需要的四个毫秒值：
     * `[minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs]`。
     *
     * `minBufferMs` 随档位一起抬高：max 拉得很长而 min 仍停在 30s 时，加载器会在「刚过 min」
     * 就松劲，实际吃不到 max（长缓冲档形同虚设）。四个值仍需满足
     * `forPlaybackMs <= minBufferMs <= maxBufferMs`。
     */
    fun durationsMs(seconds: Int): IntArray {
        val (min, max) = when {
            seconds <= 0 -> 5_000 to 15_000
            seconds <= 15 -> 8_000 to 15_000
            seconds <= 30 -> 15_000 to 30_000
            seconds <= 60 -> 20_000 to 60_000
            seconds <= 120 -> 30_000 to 120_000
            seconds <= 180 -> 45_000 to 180_000
            seconds <= 300 -> 60_000 to 300_000
            else -> 90_000 to 600_000
        }
        return intArrayOf(min, max, 2_000, 4_000)
    }
}

/**
 * 可热切换缓冲策略的 [LoadControl]（v1.8.4.0）。
 *
 * 为什么需要这一层：ExoPlayer 只在 `Builder` 上接受 `LoadControl`，创建之后没有
 * `setLoadControl` 之类的公开入口，而设置项是异步从 DataStore 读回来的 ——
 * 播放器往往先建好、偏好后到。若只能建时决定，用户改完设置要等下次进播放页才生效。
 *
 * 做法：所有回调都转发给一个可替换的 [DefaultLoadControl] 委托，`update()` 时新建
 * 一个委托换上去，正在播的播放器下一批分片就按新策略加载。
 *
 * 两个细节：
 * 1. **共用同一个 [DefaultAllocator]** —— 加载器在创建时抓走了 allocator 实例，
 *    换委托后若 allocator 也换新的，新委托看到的是「已分配 0 字节」，
 *    体积阈值判断会失真（本项目开了 time-first，影响不大，但没必要留隐患）。
 * 2. **不重放 `onTracksSelected`** —— 体积阈值同上（`setPrioritizeTimeOverSizeThresholds(true)`
 *    让时间阈值优先），重放反而要缓存 6 个参数，不值得。
 */
@OptIn(UnstableApi::class)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class VideoBufferControl(seconds: Int) : LoadControl {

    private val allocator: DefaultAllocator =
        DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    @Volatile
    private var delegate: LoadControl = build(VideoBufferOptions.normalize(seconds))

    /** 当前生效的缓冲秒数（0 = 关闭档），便于日志 / 调试 */
    @Volatile
    var seconds: Int = VideoBufferOptions.normalize(seconds)
        private set

    /** 切换缓冲时长；与当前值相同则什么都不做（避免无谓地丢弃委托的加载状态） */
    fun update(newSeconds: Int) {
        val normalized = VideoBufferOptions.normalize(newSeconds)
        synchronized(this) {
            if (normalized == seconds) return
            seconds = normalized
            delegate = build(normalized)
        }
    }

    private fun build(seconds: Int): LoadControl {
        val d = VideoBufferOptions.durationsMs(seconds)
        return DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(d[0], d[1], d[2], d[3])
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    // ===== LoadControl 转发（新 → 旧的重载都转发，具体走哪个由播放器决定） =====

    override fun getAllocator(): Allocator = allocator

    override fun onPrepared() {
        delegate.onPrepared()
    }

    override fun onPrepared(playerId: PlayerId) {
        delegate.onPrepared(playerId)
    }

    override fun onTracksSelected(
        renderers: Array<Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>,
    ) {
        delegate.onTracksSelected(renderers, trackGroups, trackSelections)
    }

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>,
    ) {
        delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    }

    override fun onStopped() {
        delegate.onStopped()
    }

    override fun onStopped(playerId: PlayerId) {
        delegate.onStopped(playerId)
    }

    override fun onReleased() {
        delegate.onReleased()
    }

    override fun onReleased(playerId: PlayerId) {
        delegate.onReleased(playerId)
    }

    override fun getBackBufferDurationUs(): Long = delegate.backBufferDurationUs

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(): Boolean = delegate.retainBackBufferFromKeyframe()

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldStartPlayback(
        playbackPositionUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long,
    ): Boolean = delegate.shouldStartPlayback(
        playbackPositionUs, playbackSpeed, rebuffering, targetLiveOffsetUs,
    )
}
