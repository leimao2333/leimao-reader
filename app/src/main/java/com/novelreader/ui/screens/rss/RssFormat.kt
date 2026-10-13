package com.novelreader.ui.screens.rss

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 订阅模块的时间展示（v1.4.0）。
 */
internal object RssFormat {

    /** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 具体日期 */
    fun relativeTime(millis: Long): String {
        if (millis <= 0) return ""
        val diff = System.currentTimeMillis() - millis
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff < 2 * 86_400_000L -> "昨天"
            diff < 30 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
        }
    }

    /** 绝对时间（阅读页用） */
    fun absoluteTime(millis: Long): String {
        if (millis <= 0) return "时间未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    /** 最近抓取时间描述 */
    fun fetchTime(millis: Long): String =
        if (millis <= 0) "尚未抓取" else "更新于 ${relativeTime(millis)}"
}
