package com.novelreader.source.search

/**
 * 视频片名归一化（v1.3.7.0）。
 *
 * 用途：聚合搜索时把**同一部片在不同采集站的名字差异**归一到同一个 key，
 * 从而折叠成一条「多个播放源」的结果。
 *
 * 为什么不能直接用 [FuzzyMatch.normalize]：
 * 采集站的片名常带「来源侧的后缀」——年份、画质、版本、状态，而小说书名不会。
 * 同一个 `流浪地球3` 在各站可能写成：
 * ```
 * 流浪地球3        → 流浪地球3
 * 流浪地球3(2026)  → 流浪地球3
 * 流浪地球3【HD】  → 流浪地球3
 * 流浪地球3[国语]  → 流浪地球3
 * 流浪地球3 高清完整版 → 流浪地球3
 * ```
 * 不做这层剥离，多源结果就永远折叠不起来，「聚合搜索」形同虚设。
 *
 * **安全边界**：只剥离**包裹型**后缀（成对括号内的内容）与词表中的状态词，
 * 绝不剥离「第N季 / 第N部 / N」这类**内容标识**——那是不同作品，合并会误导用户。
 * ```
 * 庆余年第二季   → 庆余年第二季   （不是尾巴括号，保留）
 * 唐人街探案3    → 唐人街探案3    （裸数字在中间/结尾都不是括号，保留）
 * 速度与激情10   → 速度与激情10   （同上）
 * ```
 */
object VideoTitleKey {

    /** 成对括号（全 / 半角） */
    private val BRACKETS = listOf(
        '(' to ')',
        '（' to '）',
        '[' to ']',
        '【' to '】',
        '{' to '}',
        '｛' to '｝',
    )

    /**
     * 版本 / 状态词缀（归一化后比较，即已去标点、转小写）。
     *
     * 只在**位于结尾**时剥离，避免「高清」出现在片名中间时被误裁
     * （如「高清时代」不该变成「时代」）。
     */
    private val TAIL_NOISE = listOf(
        "高清完整版", "高清版", "完整版", "蓝光版", "蓝光", "bd版", "bd",
        "hd版", "hd", "超清版", "超清", "4k版", "4k", "1080p", "720p",
        "国语版", "粤语版", "国语", "粤语", "中字版", "中字", "中文字幕",
        "无删减版", "未删减版", "未删减", "抢先版", "tc版", "ts版",
        "抢先", "预告片", "预告", "花絮", "抖音版", "解说版", "合集",
    )

    /**
     * 片名 → 分组 key。
     *
     * 步骤：
     * 1. [FuzzyMatch.normalize] 去空白 / 书名号 / 标点、全角转半角、转小写；
     * 2. 循环剥离**尾部**成对括号块（`(2026)` / `【hd】` / `[国语]`），
     *    以及尾部词表中的版本词缀；
     * 3. 若剥离后为空（片名本来就只有括号，如 `【预告】`），退回第 1 步结果，
     *    避免所有「纯括号片名」被归一成同一个空 key 而错误合并。
     *
     * 循环而非单次：`流浪地球3(2026)【hd】` 需要剥两轮。
     */
    fun of(title: String): String {
        val base = FuzzyMatch.normalize(title)
        if (base.isEmpty()) return base

        var s = base
        var changed = true
        while (changed) {
            changed = false

            // 尾部成对括号：找到最后一个开括号，若其后紧跟对应的闭括号（即在结尾）则整体剥离
            for ((open, close) in BRACKETS) {
                val o = s.lastIndexOf(open)
                if (o >= 0 && s.endsWith(close) && o < s.length - 1) {
                    s = s.substring(0, o)
                    changed = true
                    break
                }
            }
            if (changed) continue

            // 尾部版本词缀
            val hit = TAIL_NOISE.firstOrNull { s.endsWith(it) && s.length > it.length }
            if (hit != null) {
                s = s.substring(0, s.length - hit.length)
                changed = true
            }
        }

        return s.ifEmpty { base }
    }
}
