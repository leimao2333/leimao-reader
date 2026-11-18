package com.novelreader

import com.novelreader.core.reader.ReadingRecord
import com.novelreader.core.reader.ReadingStats
import com.novelreader.core.reader.ReadingStats.Granularity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** A14 阅读记录时间轴 + 章节维度统计 单测。 */
class ReadingStatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 造一条记录：默认 2026-03-10 12:00（东八区） */
    private fun rec(
        bookId: String = "src1:book1",
        title: String = "书名一",
        chapter: String = "第一章",
        chapterIndex: Int = 0,
        totalChapters: Int = 10,
        duration: Long = 600L,
        date: LocalDate = LocalDate.of(2026, 3, 10),
        hour: Int = 12,
        wordCount: Int = 0,
    ) = ReadingRecord(
        bookId = bookId,
        bookTitle = title,
        chapterTitle = chapter,
        chapterIndex = chapterIndex,
        totalChapters = totalChapters,
        durationSeconds = duration,
        timestampMillis = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        chapterWordCount = wordCount,
    )

    // ---------------- 时间轴 ----------------

    @Test
    fun `空列表时间轴为空`() {
        assertTrue(ReadingStats.timeline(emptyList(), Granularity.DAY, zone).isEmpty())
    }

    @Test
    fun `单条记录生成一个桶`() {
        val buckets = ReadingStats.timeline(listOf(rec()), Granularity.DAY, zone)
        assertEquals(1, buckets.size)
        val bucket = buckets[0]
        assertEquals("2026-03-10", bucket.key)
        assertEquals(LocalDate.of(2026, 3, 10), bucket.startDate)
        assertEquals(600L, bucket.durationSeconds)
        assertEquals(1, bucket.chaptersRead)
        assertEquals(listOf("src1:book1"), bucket.bookIds)
        assertEquals("书名一", bucket.books[0].title)
    }

    @Test
    fun `同一天多条记录合并成一个桶`() {
        val buckets = ReadingStats.timeline(
            listOf(
                rec(chapterIndex = 0, duration = 600L, hour = 8),
                rec(chapterIndex = 1, duration = 300L, hour = 13),
                rec(chapterIndex = 2, duration = 100L, hour = 20),
            ),
            Granularity.DAY,
            zone,
        )
        assertEquals(1, buckets.size)
        assertEquals(1000L, buckets[0].durationSeconds)
        assertEquals(3, buckets[0].chaptersRead)
    }

    @Test
    fun `同一章多次阅读章节数只算一次`() {
        val buckets = ReadingStats.timeline(
            listOf(
                rec(chapterIndex = 0, duration = 300L, hour = 8),
                rec(chapterIndex = 0, duration = 400L, hour = 21),
            ),
            Granularity.DAY,
            zone,
        )
        assertEquals(1, buckets.size)
        assertEquals(700L, buckets[0].durationSeconds)
        assertEquals(1, buckets[0].chaptersRead)
    }

    @Test
    fun `跨天分成两个桶`() {
        val buckets = ReadingStats.timeline(
            listOf(
                rec(date = LocalDate.of(2026, 3, 10), duration = 100L),
                rec(date = LocalDate.of(2026, 3, 11), duration = 200L),
            ),
            Granularity.DAY,
            zone,
        )
        assertEquals(2, buckets.size)
        assertEquals("2026-03-10", buckets[0].key)
        assertEquals("2026-03-11", buckets[1].key)
        assertEquals(100L, buckets[0].durationSeconds)
        assertEquals(200L, buckets[1].durationSeconds)
    }

    @Test
    fun `周粒度同一周合并并按周一切分`() {
        // 2026-03-09 是周一，03-10 周二，03-15 周日 → 同一周
        val buckets = ReadingStats.timeline(
            listOf(
                rec(date = LocalDate.of(2026, 3, 9), duration = 100L),
                rec(date = LocalDate.of(2026, 3, 15), duration = 200L),
                rec(date = LocalDate.of(2026, 3, 16), duration = 400L),
            ),
            Granularity.WEEK,
            zone,
        )
        assertEquals(2, buckets.size)
        assertEquals(LocalDate.of(2026, 3, 9), buckets[0].startDate)
        assertEquals(300L, buckets[0].durationSeconds)
        assertEquals(LocalDate.of(2026, 3, 16), buckets[1].startDate)
        assertEquals(400L, buckets[1].durationSeconds)
    }

    @Test
    fun `月粒度同一月合并并按一号切分`() {
        val buckets = ReadingStats.timeline(
            listOf(
                rec(date = LocalDate.of(2026, 3, 1), duration = 100L),
                rec(date = LocalDate.of(2026, 3, 28), duration = 200L),
                rec(date = LocalDate.of(2026, 4, 2), duration = 300L),
            ),
            Granularity.MONTH,
            zone,
        )
        assertEquals(2, buckets.size)
        assertEquals(LocalDate.of(2026, 3, 1), buckets[0].startDate)
        assertEquals(300L, buckets[0].durationSeconds)
        assertEquals(LocalDate.of(2026, 4, 1), buckets[1].startDate)
        assertEquals(300L, buckets[1].durationSeconds)
    }

    @Test
    fun `跨月边界按日粒度分成两个桶`() {
        val buckets = ReadingStats.timeline(
            listOf(
                rec(date = LocalDate.of(2026, 1, 31), duration = 100L),
                rec(date = LocalDate.of(2026, 2, 1), duration = 200L),
            ),
            Granularity.DAY,
            zone,
        )
        assertEquals(2, buckets.size)
        assertEquals("2026-01-31", buckets[0].key)
        assertEquals("2026-02-01", buckets[1].key)
    }

    @Test
    fun `跨年边界按月粒度分成两个桶`() {
        val buckets = ReadingStats.timeline(
            listOf(
                rec(date = LocalDate.of(2025, 12, 31), duration = 100L),
                rec(date = LocalDate.of(2026, 1, 1), duration = 200L),
            ),
            Granularity.MONTH,
            zone,
        )
        assertEquals(2, buckets.size)
        assertEquals("2025-12", buckets[0].key)
        assertEquals("2026-01", buckets[1].key)
    }

    @Test
    fun `桶起点与终点构成本地自然日区间`() {
        val bucket = ReadingStats.timeline(listOf(rec()), Granularity.DAY, zone)[0]
        val start = LocalDate.of(2026, 3, 10).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 3, 11).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(start, bucket.startMillis)
        assertEquals(end, bucket.endMillis)
    }

    @Test
    fun `输入顺序不同结果一致`() {
        val a = rec(chapterIndex = 0, duration = 100L, hour = 8)
        val b = rec(bookId = "src1:book2", title = "书名二", chapterIndex = 0, duration = 200L, hour = 9)
        val c = rec(bookId = "src1:book3", title = "书名三", chapterIndex = 0, duration = 300L, hour = 10)

        val forward = ReadingStats.timeline(listOf(a, b, c), Granularity.DAY, zone)
        val shuffled = ReadingStats.timeline(listOf(c, a, b), Granularity.DAY, zone)
        val reversed = ReadingStats.timeline(listOf(b, c, a), Granularity.DAY, zone)

        assertEquals(forward, shuffled)
        assertEquals(forward, reversed)
        // 桶内书目按 bookId 升序，与输入顺序无关
        assertEquals(listOf("src1:book1", "src1:book2", "src1:book3"), forward[0].bookIds)
    }

    @Test
    fun `多本书同一桶的章节数按书分别累计`() {
        val bucket = ReadingStats.timeline(
            listOf(
                rec(bookId = "b1", chapterIndex = 0, duration = 100L),
                rec(bookId = "b1", chapterIndex = 1, duration = 100L),
                rec(bookId = "b2", chapterIndex = 0, duration = 100L),
            ),
            Granularity.DAY,
            zone,
        )[0]
        // 两本书各自的「第 0 章」互不混淆
        assertEquals(3, bucket.chaptersRead)
        assertEquals(2, bucket.books.size)
    }

    @Test
    fun `章节统计输入顺序不同结果一致`() {
        val a = rec(chapterIndex = 0, duration = 100L, hour = 8, title = "旧书名")
        val b = rec(chapterIndex = 1, duration = 200L, hour = 20, title = "新书名")

        val forward = ReadingStats.bookStats(listOf(a, b), "src1:book1")
        val shuffled = ReadingStats.bookStats(listOf(b, a), "src1:book1")

        assertEquals(forward, shuffled)
        // 书名取最近一次记录的书名
        assertEquals("新书名", forward.bookTitle)
    }

    // ---------------- 章节维度统计 ----------------

    @Test
    fun `章节统计已读章节总数与百分比`() {
        val stats = ReadingStats.bookStats(
            listOf(
                rec(chapterIndex = 0, duration = 100L),
                rec(chapterIndex = 1, duration = 200L),
                rec(chapterIndex = 2, duration = 300L),
            ),
            "src1:book1",
        )
        assertEquals(10, stats.totalChapters)
        assertEquals(3, stats.readChapters)
        assertEquals(30.0, stats.progressPercent, 0.001)
        assertEquals(600L, stats.totalDurationSeconds)
        assertEquals(200L, stats.averageChapterSeconds)
    }

    @Test
    fun `章节统计同一章多次阅读只算一章`() {
        val stats = ReadingStats.bookStats(
            listOf(
                rec(chapterIndex = 0, duration = 100L, hour = 8),
                rec(chapterIndex = 0, duration = 200L, hour = 21),
            ),
            "src1:book1",
        )
        assertEquals(1, stats.readChapters)
        assertEquals(10.0, stats.progressPercent, 0.001)
        assertEquals(300L, stats.totalDurationSeconds)
    }

    @Test
    fun `章节统计平均每章字数只统计已知字数`() {
        val stats = ReadingStats.bookStats(
            listOf(
                rec(chapterIndex = 0, wordCount = 1000),
                rec(chapterIndex = 1, wordCount = 3000),
                rec(chapterIndex = 2, wordCount = 0),
            ),
            "src1:book1",
        )
        assertEquals(3, stats.readChapters)
        assertEquals(4000, stats.totalWords)
        // 只按 2 个已知字数的章节取平均：(1000 + 3000) / 2
        assertEquals(2000, stats.averageChapterWords)
    }

    @Test
    fun `章节统计总章节数未知时百分比为零`() {
        val stats = ReadingStats.bookStats(
            listOf(rec(chapterIndex = 0, totalChapters = 0, duration = 100L)),
            "src1:book1",
        )
        assertEquals(0, stats.totalChapters)
        assertEquals(0.0, stats.progressPercent, 0.001)
        assertEquals(1, stats.readChapters)
    }

    @Test
    fun `无该书记录时返回全零默认值`() {
        val stats = ReadingStats.bookStats(listOf(rec()), "不存在的书")
        assertEquals("不存在的书", stats.bookId)
        assertEquals(0, stats.totalChapters)
        assertEquals(0, stats.readChapters)
        assertEquals(0.0, stats.progressPercent, 0.001)
        assertEquals(0L, stats.totalDurationSeconds)
        assertEquals(0L, stats.averageChapterSeconds)
        assertEquals(0, stats.averageChapterWords)
        assertEquals(0L, stats.firstReadMillis)
        assertEquals(0L, stats.lastReadMillis)
    }

    @Test
    fun `章节明细按时长累加且按序号排序`() {
        val list = ReadingStats.chapters(
            listOf(
                rec(chapterIndex = 1, chapter = "第二章", duration = 100L, hour = 8),
                rec(chapterIndex = 0, chapter = "第一章", duration = 200L, hour = 9),
                rec(chapterIndex = 1, chapter = "第二章", duration = 300L, hour = 10),
            ),
            "src1:book1",
        )
        assertEquals(2, list.size)
        assertEquals(0, list[0].chapterIndex)
        assertEquals(200L, list[0].durationSeconds)
        assertEquals(1, list[1].chapterIndex)
        assertEquals(400L, list[1].durationSeconds)
    }

    // ---------------- 全局概览 ----------------

    @Test
    fun `空列表全局概览全为零`() {
        val stats = ReadingStats.globalStats(emptyList(), zone, LocalDate.of(2026, 3, 10))
        assertEquals(0, stats.streakDays)
        assertEquals(0, stats.longestStreakDays)
        assertEquals(0L, stats.totalDurationSeconds)
        assertEquals(0L, stats.thisMonthDurationSeconds)
        assertEquals(0, stats.activeDays)
    }

    @Test
    fun `连续三天连续天数与最长连续天数都是三`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 3, 8), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 9), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 10), duration = 100L),
        )
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 10))
        assertEquals(3, stats.streakDays)
        assertEquals(3, stats.longestStreakDays)
        assertEquals(3, stats.activeDays)
    }

    @Test
    fun `中断后连续天数只算最近一段`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 3, 1), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 2), duration = 100L),
            // 03-03 缺读
            rec(date = LocalDate.of(2026, 3, 4), duration = 100L),
        )
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 4))
        assertEquals(1, stats.streakDays)
        assertEquals(2, stats.longestStreakDays)
    }

    @Test
    fun `单日阅读连续天数为一天`() {
        val stats = ReadingStats.globalStats(
            listOf(rec(date = LocalDate.of(2026, 3, 10), duration = 100L)),
            zone,
            LocalDate.of(2026, 3, 10),
        )
        assertEquals(1, stats.streakDays)
        assertEquals(1, stats.longestStreakDays)
    }

    @Test
    fun `今天还没读不算断签从昨天往前数`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 3, 9), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 10), duration = 100L),
        )
        // 今天 03-11 没有记录
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 11))
        assertEquals(2, stats.streakDays)
    }

    @Test
    fun `昨天也没读则连续天数归零`() {
        val records = listOf(rec(date = LocalDate.of(2026, 3, 1), duration = 100L))
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 10))
        assertEquals(0, stats.streakDays)
        // 历史最长仍是 1
        assertEquals(1, stats.longestStreakDays)
    }

    @Test
    fun `最长连续天数取历史上最长的一段`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 1, 1), duration = 100L),
            rec(date = LocalDate.of(2026, 1, 2), duration = 100L),
            rec(date = LocalDate.of(2026, 1, 3), duration = 100L),
            rec(date = LocalDate.of(2026, 1, 4), duration = 100L),
            rec(date = LocalDate.of(2026, 1, 5), duration = 100L),
            // 中间断一大段
            rec(date = LocalDate.of(2026, 3, 10), duration = 100L),
        )
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 10))
        assertEquals(5, stats.longestStreakDays)
        assertEquals(1, stats.streakDays)
    }

    @Test
    fun `时长为零的记录不计入活跃日与连续天数`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 3, 10), duration = 0L),
        )
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 10))
        assertEquals(0, stats.activeDays)
        assertEquals(0, stats.streakDays)
        assertEquals(0, stats.longestStreakDays)
        // 但总时长还是 0，书目计数仍算
        assertEquals(0L, stats.totalDurationSeconds)
        assertEquals(1, stats.bookCount)
    }

    @Test
    fun `时长为零的记录仍会出现在时间轴桶里`() {
        val buckets = ReadingStats.timeline(
            listOf(rec(chapterIndex = 0, duration = 0L)),
            Granularity.DAY,
            zone,
        )
        assertEquals(1, buckets.size)
        assertEquals(0L, buckets[0].durationSeconds)
        assertEquals(1, buckets[0].chaptersRead)
    }

    @Test
    fun `本月总时长只统计本月记录`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 2, 28), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 1), duration = 200L),
            rec(date = LocalDate.of(2026, 3, 10), duration = 300L),
        )
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 10))
        assertEquals(500L, stats.thisMonthDurationSeconds)
        assertEquals(600L, stats.totalDurationSeconds)
    }

    @Test
    fun `日均时长按统计区间自然天数摊分`() {
        // 03-08 ~ 03-10 共 3 天，总时长 600 秒 → 日均 200 秒
        val records = listOf(
            rec(date = LocalDate.of(2026, 3, 8), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 10), duration = 500L),
        )
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 10))
        assertEquals(200L, stats.dailyAverageSeconds)
        // 活跃日均：600 / 2 天 = 300
        assertEquals(300L, stats.activeDayAverageSeconds)
        assertEquals(2, stats.activeDays)
    }

    @Test
    fun `书目数与章节数跨书去重`() {
        val records = listOf(
            rec(bookId = "b1", chapterIndex = 0, duration = 100L),
            rec(bookId = "b1", chapterIndex = 0, duration = 100L),
            rec(bookId = "b2", chapterIndex = 0, duration = 100L),
        )
        val stats = ReadingStats.globalStats(records, zone, LocalDate.of(2026, 3, 10))
        assertEquals(2, stats.bookCount)
        assertEquals(2, stats.chapterCount)
    }

    // ---------------- 趋势 ----------------

    @Test
    fun `每日趋势缺失日期补零`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 3, 8), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 10), duration = 300L),
        )
        // 03-08 ~ 03-10 三天：100 / 0 / 300
        val trend = ReadingStats.dailyTrend(records, zone, LocalDate.of(2026, 3, 10), 3)
        assertEquals(listOf(100L, 0L, 300L), trend)
    }

    @Test
    fun `每日趋势末位对应结束日期`() {
        val records = listOf(rec(date = LocalDate.of(2026, 3, 10), duration = 300L))
        val trend = ReadingStats.dailyTrend(records, zone, LocalDate.of(2026, 3, 10), 7)
        assertEquals(7, trend.size)
        assertEquals(300L, trend.last())
        assertEquals(0L, trend[5])
    }

    @Test
    fun `每日趋势天数非法时返回空列表`() {
        assertTrue(ReadingStats.dailyTrend(listOf(rec()), zone, LocalDate.of(2026, 3, 10), 0).isEmpty())
        assertTrue(ReadingStats.dailyTrend(listOf(rec()), zone, LocalDate.of(2026, 3, 10), -3).isEmpty())
    }

    @Test
    fun `每日趋势跨月与跨年边界连续补零`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 2, 28), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 1), duration = 200L),
        )
        val trend = ReadingStats.dailyTrend(records, zone, LocalDate.of(2026, 3, 1), 2)
        assertEquals(listOf(100L, 200L), trend)
    }

    // ---------------- 时钟注入 ----------------

    @Test
    fun `用固定时钟注入今天`() {
        val records = listOf(
            rec(date = LocalDate.of(2026, 3, 9), duration = 100L),
            rec(date = LocalDate.of(2026, 3, 10), duration = 100L),
        )
        // 时钟固定在 2026-03-10 当天 → 连续 2 天
        val clock = Clock.fixed(
            LocalDate.of(2026, 3, 10).atTime(23, 0).atZone(zone).toInstant(),
            zone,
        )
        val stats = ReadingStats.globalStats(records, zone, clock)
        assertEquals(2, stats.streakDays)

        val trend = ReadingStats.dailyTrend(records, zone, clock, 2)
        assertEquals(listOf(100L, 100L), trend)
    }
}
