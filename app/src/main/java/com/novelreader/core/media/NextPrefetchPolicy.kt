package com.novelreader.core.media

/**
 * 「这一集要不要尾段预缓冲下一集」的判定（v1.8.4.4）。
 *
 * 为什么单独抽出来：判定条件有六个（剩余时长 / 有没有下一集 / 这一集是否已触发 /
 * 预缓存档位 / 仅 WiFi 开关 / 当前网络），全散在 ViewModel 的 ticker 里就只能靠
 * 「看下一集有没有秒开」来验证 —— 一旦没生效，既不知道是没触发还是触发后被拦了。
 *
 * 这里返回**拦下来的原因**（[blockReason]），null 才是放行：
 * ViewModel 把原因打进日志，`adb logcat -s NextPrefetch` 就能直接看到
 * 「为什么这一集没预缓冲」。
 *
 * 纯逻辑、不碰 Android，可直接单测。
 */
object NextPrefetchPolicy {

    /**
     * 触发点：当前集剩余不足这么多毫秒就开始预缓冲下一集。
     *
     * 20s 是「够下完 6MB、又不至于太早白花流量」的折中 —— 按 2Mbps 算 6MB ≈ 24s
     * 正片，弱网下 20s 也基本能拉完；再早的话用户可能中途退出，白下。
     */
    const val TRIGGER_MS = 20_000L

    /**
     * 判定是否放行。
     *
     * @param remainingMs 当前集剩余毫秒（`duration - position`）
     * @param hasNext 是否存在下一集
     * @param alreadyTriggered 这一集是否已经触发过（同一集只下一次）
     * @param bufferSeconds 「预缓存时长」档位，0 = 关闭
     * @param wifiOnly 是否开启了「仅 WiFi 预缓冲」
     * @param onWifi 当前是否在 WiFi 上（仅在 [wifiOnly] 为 true 时才需要真实探测）
     */
    fun shouldPrefetch(
        remainingMs: Long,
        hasNext: Boolean,
        alreadyTriggered: Boolean,
        bufferSeconds: Int,
        wifiOnly: Boolean,
        onWifi: Boolean,
    ): Boolean = blockReason(
        remainingMs = remainingMs,
        hasNext = hasNext,
        alreadyTriggered = alreadyTriggered,
        bufferSeconds = bufferSeconds,
        wifiOnly = wifiOnly,
        onWifi = onWifi,
    ) == null

    /**
     * 拦下来的原因；返回 null 表示**可以预缓冲**。
     *
     * 顺序按「越靠前越省事」排：先排掉不用探测网络的情况，
     * 只有前面全过、且开了仅 WiFi，才去问 [onWifi]（探网络有系统调用开销）。
     */
    fun blockReason(
        remainingMs: Long,
        hasNext: Boolean,
        alreadyTriggered: Boolean,
        bufferSeconds: Int,
        wifiOnly: Boolean,
        onWifi: Boolean,
    ): String? = when {
        alreadyTriggered -> "这一集已经预缓冲过"
        !hasNext -> "没有下一集"
        bufferSeconds <= 0 -> "预缓存时长已关闭"
        remainingMs > TRIGGER_MS -> "离结束还早（剩 ${remainingMs / 1000}s）"
        remainingMs < 0L -> "时长未知"
        wifiOnly && !onWifi -> "仅 WiFi 预缓冲，当前是移动网络"
        else -> null
    }
}
