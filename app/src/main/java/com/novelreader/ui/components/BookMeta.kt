package com.novelreader.ui.components

import com.novelreader.domain.model.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书籍元信息展示工具（搜索结果 / 详情页共用）。
 *
 * 数据来源为 [MediaItem.extras]（legado 源搜索规则带出的 kind / lastChapter /
 * status / wordCount / lastUpdate / totalChapter），统一在此格式化，
 * 保证搜索结果卡片与详情页展示口径一致。
 */
object BookMeta {

    /**
     * 组装一行元信息，形如：
     * 「连载中 · 共 320 章 · 152.3万字 · 最新章节：第320章 大结局 · 3小时前」
     *
     * 各字段缺失时自动跳过，全部缺失返回空字符串（调用方据此隐藏整行）。
     *
     * @param showLastChapter 是否展示「最新章节」（搜索结果卡片展示，详情页另起一行）
     * @param showUpdateTime 是否展示「更新时间」
     */
    fun summaryLine(
        item: MediaItem,
        showLastChapter: Boolean = true,
        showUpdateTime: Boolean = true,
    ): String {
        val parts = mutableListOf<String>()
        statusText(item)?.let { parts += it }
        wordCountText(item)?.let { parts += it }
        if (showLastChapter) {
            lastChapter(item)?.let { parts += "最新章节：$it" }
        }
        if (showUpdateTime) {
            updateTimeText(item)?.let { parts += it }
        }
        return parts.joinToString(" · ")
    }

    /** 状态规范化：连载中 / 已完结。源可能返回 "连载"/"完结"/"1"/"0" 等。 */
    fun statusText(item: MediaItem): String? = normalizeStatus(item.extras["status"] ?: item.extras["kind"])

    /** 源状态字符串 → 统一中文状态；无法识别返回 null */
    fun normalizeStatus(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val lower = s.lowercase()
        return when {
            lower.contains("完结") || lower.contains("complete") || lower == "1" -> "已完结"
            lower.contains("连载") || lower.contains("serial") || lower.contains("ongoing") || lower == "0" -> "连载中"
            else -> null
        }
    }

    /** 最新章节标题 */
    fun lastChapter(item: MediaItem): String? =
        item.extras["lastChapter"]?.trim()?.takeIf { it.isNotEmpty() }

    /** 章节数文本，如「共 320 章」；来源 totalChapter 或 lastChapter 中的数字 */
    fun chapterCountText(item: MediaItem): String? {
        val raw = item.extras["totalChapter"]?.trim()
        if (!raw.isNullOrEmpty()) {
            val n = raw.filter { it.isDigit() }
            if (n.isNotEmpty()) return "共 $n 章"
        }
        return null
    }

    /** 字数文本，形如「152.3万字」/「8600字」；源返回 "1523000" 或 "152.3万字" 均兼容 */
    fun wordCountText(item: MediaItem): String? {
        val raw = item.extras["wordCount"]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // 已是带单位的文本（如 "152万字"）直接使用
        if (raw.any { it == '万' || it == '字' || it == 'k' || it == 'K' }) return raw
        val num = raw.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
        return when {
            num >= 10000 -> String.format(Locale.CHINA, "%.1f万字", num / 10000)
            num > 0 -> "${num.toLong()}字"
            else -> null
        }
    }

    /** 更新时间文本：优先相对时间（x分钟前/x小时前/昨天/x天前），超过 14 天显示日期 */
    fun updateTimeText(item: MediaItem): String? {
        val raw = item.extras["lastUpdate"]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val ts = parseTimestamp(raw)
        return if (ts != null) relativeTime(ts) else raw.take(24)
    }

    /**
     * 解析源返回的更新时间：支持毫秒/秒级时间戳、yyyy-MM-dd[ HH:mm[:ss]]、yyyy/MM/dd 等。
     * 解析失败返回 null（调用方按原始文本展示）。
     */
    fun parseTimestamp(raw: String): Long? {
        // 纯数字 → 时间戳（长度 >= 13 视为毫秒）
        if (raw.all { it.isDigit() }) {
            val n = raw.toLongOrNull() ?: return null
            return if (raw.length >= 13) n else n * 1000L
        }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM/dd",
            "MM-dd HH:mm", "MM-dd",
        )
        val now = System.currentTimeMillis()
        for (p in patterns) {
            runCatching {
                val fmt = SimpleDateFormat(p, Locale.CHINA).apply { isLenient = true }
                val d = fmt.parse(raw) ?: return@runCatching
                // 缺年份的短格式补当前年
                if (p.startsWith("MM")) {
                    val cal = java.util.Calendar.getInstance()
                    cal.time = d
                    cal.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
                    return cal.timeInMillis
                }
                return d.time
            }
        }
        return null
    }

    /** 相对时间：x分钟前 / x小时前 / 昨天 / x天前 / yyyy-MM-dd */
    fun relativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        if (diff < 0) return "刚刚"
        val minutes = diff / 60000
        val hours = diff / 3600000
        val days = diff / 86400000
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days == 1L -> "昨天"
            days < 14 -> "${days}天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp))
        }
    }

    /** 最近阅读时间描述（首页最近阅读用） */
    fun recentTime(timestamp: Long): String = relativeTime(timestamp)
}
