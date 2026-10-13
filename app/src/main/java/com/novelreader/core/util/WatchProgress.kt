package com.novelreader.core.util

/**
 * 「继续观看」的进度换算（v1.3.4.0）。
 *
 * 抽成纯函数是为了可测：这段逻辑有一串容易被忽略的边界
 * （老记录没有总时长、直播流时长为 0 / 未知、单集电影没有集数概念），
 * 出错的表现是进度条永远空着或永远满格 —— 静默错误，只有单测能提前兜住。
 */
object WatchProgress {

    /**
     * 观看进度比例（0..1）。
     *
     * 时间进度优先（`playPositionMs / durationMs`），拿不到总时长时退化为集数进度
     * （`(chapterIndex + 1) / totalChapter`）—— 老记录在 v1.3.4.0 之前没存总时长，
     * 直播流也永远没有时长，这两种情况都得有得看。
     *
     * @param playPositionMs 上次播放位置（毫秒）
     * @param durationMs 总时长（毫秒，0 = 未知）
     * @param chapterIndex 集号（0 起）
     * @param totalChapter 总集数（0 / 1 = 不适用）
     */
    fun fraction(
        playPositionMs: Long,
        durationMs: Long,
        chapterIndex: Int,
        totalChapter: Int,
    ): Float {
        val byTime = if (durationMs > 0L) playPositionMs.toFloat() / durationMs else 0f
        if (byTime > 0f) return byTime.coerceIn(0f, 1f)

        val byEpisode =
            if (totalChapter > 1) (chapterIndex + 1).toFloat() / totalChapter else 0f
        return byEpisode.coerceIn(0f, 1f)
    }

    /**
     * 集数角标文案，形如 `3/24`。
     *
     * 单片电影（`totalChapter <= 1`）返回空串 —— 卡片上不该出现 `1/1`。
     */
    fun episodeLabel(chapterIndex: Int, totalChapter: Int): String =
        if (totalChapter > 1) "${chapterIndex + 1}/$totalChapter" else ""
}
