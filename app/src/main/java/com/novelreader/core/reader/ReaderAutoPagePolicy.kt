package com.novelreader.core.reader

/**
 * 自动翻页策略与速度档位（对标开源阅读的 `ReaderAutoPagePolicy` / `ReaderPageTurnSpeed`）。
 *
 * 阅读页「自动翻页」需要三类计算：
 * - **速度档位**：UI 上给用户几档倍速，取值要吸附到档位以避免出现 1.37x 这种脏值；
 * - **停留时长**：固定节奏翻页（按 baseMs 与倍速算间隔），或按这一页还剩多少字估算；
 * - **到点判断**：定时器帧回调里判断「该翻页了吗」。
 *
 * 全部为纯函数，**不依赖 Android / Compose**，可在主线程或后台任意调用，
 * 便于单元测试与 Compose 之外的场景（例如 TTS 停顿、后台定时）复用。
 */
object ReaderAutoPagePolicy {

    /** 速度档位（倍速）。1.0x 为基准，档位越高翻页越慢 */
    val SPEED_CHOICES: List<Float> = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f)

    /** 单页停留下限：再快也不低于 300ms，避免闪屏 / 用户来不及看 */
    const val MIN_INTERVAL_MS: Long = 300L

    /** 未配置阅读速度时的默认字数/分钟（中文有声朗读约 300 字/分） */
    const val DEFAULT_CHARS_PER_MINUTE: Int = 300

    /**
     * 固定节奏下的翻页间隔。
     *
     * @param baseMs 1.0 倍速下的基础间隔（毫秒）
     * @param speed 倍速，非法值（<= 0 / NaN）按 1.0x 处理
     * @return `baseMs / speed`，并夹取到不低于 [MIN_INTERVAL_MS]
     */
    fun intervalMs(baseMs: Long, speed: Float): Long {
        val safeSpeed = if (speed.isNaN() || speed <= 0f) 1.0f else speed
        val raw = (baseMs.coerceAtLeast(0L) / safeSpeed).toLong()
        return raw.coerceAtLeast(MIN_INTERVAL_MS)
    }

    /**
     * 按剩余字数估算这一页还要停留多久。
     *
     * 公式：`remainingChars / (charsPerMinute * speed)` 换算成毫秒，
     * 即字数越多停得越久、倍速越高停得越短。
     *
     * @param remainingChars 当前页剩余未读字数，<= 0 时返回 0（无需等待，可立即翻页）
     * @param charsPerMinute 阅读速度（字/分钟），<= 0 时取 [DEFAULT_CHARS_PER_MINUTE]
     * @param speed 倍速，非法值按 1.0x 处理
     * @return 估算停留毫秒数，非 0 时不低于 [MIN_INTERVAL_MS]；返回 0 表示可直接翻页
     */
    fun nextDelayMs(remainingChars: Int, charsPerMinute: Int, speed: Float): Long {
        if (remainingChars <= 0) return 0L
        val cpm = if (charsPerMinute <= 0) DEFAULT_CHARS_PER_MINUTE else charsPerMinute
        val safeSpeed = if (speed.isNaN() || speed <= 0f) 1.0f else speed
        // 毫秒 = 字数 * 60000 / 每分钟字数 / 倍速
        val raw = (remainingChars.toLong() * 60_000L / cpm / safeSpeed).toLong()
        return raw.coerceAtLeast(MIN_INTERVAL_MS)
    }

    /**
     * 定时器回调里判断是否该自动翻页。
     *
     * @param nowMs 当前时间戳（毫秒），与 [lastTurnMs] 同一时钟源（推荐 elapsedRealtime）
     * @param lastTurnMs 上一次翻页的时间戳
     * @param intervalMs 期望间隔，<= 0 视为「立即翻页」
     */
    fun shouldAutoTurn(nowMs: Long, lastTurnMs: Long, intervalMs: Long): Boolean =
        intervalMs <= 0L || nowMs - lastTurnMs >= intervalMs

    /**
     * 把任意倍速吸附到最接近的合法档位（[SPEED_CHOICES]）。
     *
     * 用于恢复历史配置 / 滑杆取值后归一化：非法值（NaN、<= 0、无穷大）回落到 1.0x。
     */
    fun normalizeSpeed(v: Float): Float {
        if (v.isNaN() || v <= 0f) return SPEED_CHOICES.first()
        var best = SPEED_CHOICES.first()
        var bestDiff = kotlin.math.abs(v - best)
        for (choice in SPEED_CHOICES) {
            val diff = kotlin.math.abs(v - choice)
            if (diff < bestDiff) {
                best = choice
                bestDiff = diff
            }
        }
        return best
    }
}
