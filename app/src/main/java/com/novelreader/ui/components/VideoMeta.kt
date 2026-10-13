package com.novelreader.ui.components

import com.novelreader.domain.model.MediaItem

/**
 * 影视元信息展示工具（v1.3.7.0）。
 *
 * 与 [BookMeta] 对称：书籍读 `status / wordCount / lastChapter / lastUpdate`，
 * 影视读 `score / typeName / year / area / remarks`（由 `MacCmsParser.toItem` 写入 extras）。
 *
 * 为什么不复用 [BookMeta]：两者的字段与顺序完全不同——影视没有「连载中 / 字数 / 最新章节」，
 * 硬套的结果是一行全空，聚合卡片会比原来的平铺卡片更空。
 */
object VideoMeta {

    /**
     * 组装一行元信息，形如：
     * `9.2 · 连续剧 · 2026 · 大陆 · 更新至24集`
     *
     * 各字段缺失时自动跳过；全部缺失返回空串（调用方据此隐藏整行）。
     *
     * @param showScore 是否展示评分（聚合卡片展示——评分是选源的重要依据）
     */
    fun summaryLine(item: MediaItem, showScore: Boolean = true): String {
        val parts = mutableListOf<String>()
        if (showScore) scoreText(item)?.let { parts += it }
        typeName(item)?.let { parts += it }
        year(item)?.let { parts += it }
        area(item)?.let { parts += it }
        remarks(item)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /**
     * 评分文本。采集站 `vod_score` 有 `0` / `0.0` / `""` 三种「无评分」写法，
     * 统一视为缺失——否则卡片上会冒出一排「0.0」。
     */
    fun scoreText(item: MediaItem): String? {
        val raw = item.extras["score"]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val v = raw.toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        // 保留一位小数，去掉多余的 .0（8 → 8，9.2 → 9.2）
        return if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.CHINA, "%.1f", v)
    }

    /** 分类名（电影 / 连续剧 / 综艺 / 动漫） */
    fun typeName(item: MediaItem): String? =
        item.extras["typeName"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.author?.trim()?.takeIf { it.isNotEmpty() }

    /** 年份。源可能返回 `2026` 或带空白的 ` 2026 ` */
    fun year(item: MediaItem): String? {
        val raw = item.extras["year"]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == 4) digits else null
    }

    /** 地区（大陆 / 美国 / 韩国 …） */
    fun area(item: MediaItem): String? =
        item.extras["area"]?.trim()?.takeIf { it.isNotEmpty() && it != "未知" }

    /** 备注（更新至24集 / 已完结 / 高清 …） */
    fun remarks(item: MediaItem): String? =
        item.extras["remarks"]?.trim()?.takeIf { it.isNotEmpty() }
}
