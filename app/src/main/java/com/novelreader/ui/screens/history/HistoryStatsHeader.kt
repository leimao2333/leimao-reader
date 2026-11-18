package com.novelreader.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.core.reader.GlobalStats

/**
 * 阅读统计头部（A14）。
 *
 * 展示全局概览：总时长 / 连续天数 / 本月时长 / 日均时长 / 已读书目数。
 *
 * 主题约定：
 * - 所有颜色一律取自 [MaterialTheme.colorScheme]，深浅色自动切换，不写死色值；
 * - 卡片底色用 `surfaceVariant`，数字用 `primary`（深色模式下 `primary` 是浅蓝，
 *   在深底上依旧有对比度），辅助文字用 `onSurfaceVariant`。
 *
 * @param stats 纯逻辑层聚合出的全局概览（空数据时各项为 0，UI 显示「暂无」）
 */
@Composable
fun HistoryStatsHeader(
    stats: GlobalStats,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 第一行：两个大指标（总时长 / 连续天数），是用户最关心的两个数
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatBlock(
                label = "累计时长",
                value = formatMinutes(stats.totalDurationSeconds),
                modifier = Modifier.weight(1f),
            )
            StatBlock(
                label = "连续阅读",
                value = if (stats.streakDays > 0) "${stats.streakDays} 天" else "暂无",
                modifier = Modifier.weight(1f),
            )
        }

        // 第二行：三个次要指标
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatBlock(
                label = "本月",
                value = formatMinutes(stats.thisMonthDurationSeconds),
                modifier = Modifier.weight(1f),
                emphasize = false,
            )
            StatBlock(
                label = "日均",
                value = formatMinutes(stats.dailyAverageSeconds),
                modifier = Modifier.weight(1f),
                emphasize = false,
            )
            StatBlock(
                label = "已读书目",
                value = "${stats.bookCount} 本",
                modifier = Modifier.weight(1f),
                emphasize = false,
            )
        }
    }
}

/**
 * 单个指标块：上面是小标题，下面是数值。
 *
 * @param emphasize 主指标（累计时长 / 连续天数）用更醒目的样式；
 *   次要指标用普通正文的样式，否则一屏全是放大号数字反而看不清重点。
 */
@Composable
private fun StatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = true,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = if (emphasize) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasize) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * 秒 → 「x 小时 y 分」/「y 分」。
 *
 * 放在 UI 层而不是纯逻辑层：纯逻辑层只做加总，单位怎么摆是展示层的事。
 */
internal fun formatMinutes(seconds: Long): String {
    if (seconds <= 0L) return "0 分"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h} 小时 ${m} 分"
        m > 0 -> "${m} 分"
        else -> "${seconds} 秒"
    }
}
