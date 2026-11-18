package com.novelreader.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.core.reader.BookStats
import com.novelreader.core.reader.ChapterStat
import com.novelreader.core.reader.ReadingStats
import com.novelreader.core.reader.TimelineBucket
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 阅读时间轴（A14）。
 *
 * 按日 / 周 / 月分组展示阅读记录，每组给出：时间范围、该组时长、读过章节数、涉及书目。
 * 点书目会展开该书的章节维度统计（总章节 / 已读章节 / 进度 / 平均每章时长与字数 / 章节列表）。
 *
 * 为什么不是独立路由：本项目的导航集中在 `AppNavHost`，A14 明确不允许改它，
 * 所以时间轴做成「阅读历史」页里的第二个视图，由 [HistoryScreen] 切换。
 *
 * 主题约定：一律取 [MaterialTheme.colorScheme]，深浅色自动适配，不写死色值。
 *
 * @param buckets 时间轴桶（已按粒度分好组）
 * @param trend 每日时长序列（画趋势条）
 * @param granularity 当前粒度
 * @param onGranularityChange 切换粒度
 * @param onBookClick 点书目（打开章节维度统计）
 */
@Composable
fun ReaderTimelineScreen(
    buckets: List<TimelineBucket>,
    trend: List<DailyTrendPoint>,
    granularity: ReadingStats.Granularity,
    onGranularityChange: (ReadingStats.Granularity) -> Unit,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GranularityChips(
            selected = granularity,
            onSelect = onGranularityChange,
        )

        if (buckets.isEmpty()) {
            TimelineEmptyState(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    DailyTrendBars(
                        trend = trend,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(buckets, key = { it.key }) { bucket ->
                    TimelineBucketRow(
                        bucket = bucket,
                        granularity = granularity,
                        onBookClick = onBookClick,
                    )
                }
            }
        }
    }
}

/**
 * 粒度切换 chips（日 / 周 / 月）。
 *
 * 用文字 chip 而不是图标：粒度是「按什么时间单位看」的概念，文字比图标直观，
 * 样式与历史页的类型筛选 chip 保持一致。
 */
@Composable
private fun GranularityChips(
    selected: ReadingStats.Granularity,
    onSelect: (ReadingStats.Granularity) -> Unit,
) {
    val entries = listOf(
        ReadingStats.Granularity.DAY to "按日",
        ReadingStats.Granularity.WEEK to "按周",
        ReadingStats.Granularity.MONTH to "按月",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (granularity, label) ->
            val isSelected = granularity == selected
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { onSelect(granularity) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 每日趋势条：把 [com.novelreader.core.reader.ReadingStats.dailyTrend] 的结果画成柱状条。
 *
 * 不引图表库：柱子就是 Box + 高度比例，一行代码能搞定的事不值得加依赖。
 * 没读的日子画一条极矮的底线，让「那天没读」也看得出来。
 */
@Composable
private fun DailyTrendBars(
    trend: List<DailyTrendPoint>,
    modifier: Modifier = Modifier,
) {
    if (trend.isEmpty()) return
    val maxSeconds = trend.maxOf { it.seconds }
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "近 ${trend.size} 天阅读时长",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            trend.forEach { point ->
                val ratio = if (maxSeconds > 0L) {
                    point.seconds.toFloat() / maxSeconds.toFloat()
                } else {
                    0f
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 至少留 2dp，否则「读了但时长很短」的日子完全看不见
                            .height((BAR_MAX_HEIGHT * ratio).dp.coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (point.seconds > 0L) barColor else trackColor),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${point.date.dayOfMonth}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 一个时间桶（一天 / 一周 / 一月）的展示行。
 *
 * 展示：时间范围、该组时长、读过章节数、涉及书目（可点，点了看章节维度统计）。
 */
@Composable
private fun TimelineBucketRow(
    bucket: TimelineBucket,
    granularity: ReadingStats.Granularity,
    onBookClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatBucketRange(bucket, granularity),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatMinutes(bucket.durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "读过 ${bucket.chaptersRead} 章 · ${bucket.books.size} 本",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        bucket.books.forEach { book ->
            Text(
                text = "· ${book.title.ifBlank { book.bookId }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable { onBookClick(book.bookId) }
                    .padding(top = 2.dp),
            )
        }
    }
}

/**
 * 时间桶 → 可读的时间标签。
 *
 * 按粒度给不同的说法：日粒度说「3月10日 周二」，周粒度说「3月9日 - 3月15日」，
 * 月粒度说「2026年3月」。
 */
internal fun formatBucketRange(
    bucket: TimelineBucket,
    granularity: ReadingStats.Granularity,
): String = when (granularity) {
    ReadingStats.Granularity.DAY -> bucket.startDate.format(DAY_FORMAT)
    ReadingStats.Granularity.WEEK -> {
        val end = bucket.startDate.plusDays(6)
        "${bucket.startDate.monthValue}月${bucket.startDate.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日"
    }
    ReadingStats.Granularity.MONTH -> "${bucket.startDate.year}年${bucket.startDate.monthValue}月"
}

/** 一天的时间标签：「3月10日 周二」 */
private val DAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA)

private const val BAR_MAX_HEIGHT = 48

/**
 * 时间轴空态：没有阅读记录时的引导文案。
 */
@Composable
private fun TimelineEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "还没有阅读记录\n去搜索并阅读喜欢的内容吧",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 章节维度统计底部面板：点书目后展示这本书的章节统计。
 *
 * 面板里章节列表可能很长（几百章），所以用 LazyColumn + 高度上限，
 * 避免整个面板被撑出屏幕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookChapterStatsSheet(
    stats: BookStats,
    chapters: List<ChapterStat>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stats.bookTitle.ifBlank { stats.bookId },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已读 ${stats.readChapters} / ${stats.totalChapters} 章" +
                    if (stats.totalChapters > 0) {
                        "（${stats.progressPercent.toInt()}%）"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats.totalChapters > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (stats.progressPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SheetMetric("总时长", formatMinutes(stats.totalDurationSeconds))
                SheetMetric("平均每章", formatMinutes(stats.averageChapterSeconds))
                // 字数未知时显示「未知」而不是 0 字：0 字会被误读成「这本书没字」
                SheetMetric(
                    label = "平均每章字数",
                    value = if (stats.averageChapterWords > 0) "${stats.averageChapterWords} 字" else "未知",
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "章节列表",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(chapters, key = { "${it.bookId}#${it.chapterIndex}" }) { chapter ->
                    ChapterRow(chapter)
                }
            }
        }
    }
}

/** 底部面板里的一行小指标 */
@Composable
private fun SheetMetric(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 章节列表的一行：序号 + 标题 + 时长 */
@Composable
private fun ChapterRow(chapter: ChapterStat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第 ${chapter.chapterIndex + 1} 章",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = chapter.chapterTitle.ifBlank { "（无标题）" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatMinutes(chapter.durationSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
