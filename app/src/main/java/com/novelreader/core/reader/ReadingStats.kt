package com.novelreader.core.reader

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 一条「阅读记录」的**输入视图**（A14：阅读记录时间轴 + 章节维度统计）。
 *
 * 为什么不直接用 Room 实体：
 * - 本文件属于纯逻辑层，不该知道数据库长什么样 —— 实体一改，统计逻辑跟着碎；
 * - 纯逻辑必须在 JVM 单测里能跑，Room 实体牵扯 Room 运行时，测起来很重；
 * - 后续批次若换存储（DataStore / 文件 / 服务端同步），聚合层完全不用动。
 * 所以这里定义自己的输入结构，由数据层负责把实体映射过来。
 *
 * 字段约定：
 * - [bookId] 书籍唯一标识。调用方自行组合（例如 `sourceId + ":" + itemId`，
 *   与 `ProgressEntity` 的联合唯一索引一致），聚合层只当不透明字符串用。
 * - [chapterIndex] 章节序号，**0 起**（与 `ProgressEntity.chapterIndex` 口径一致）；
 *   传负数表示「序号未知」，此时章节身份退化为按标题识别。
 * - [totalChapters] 该书总章节数，0 表示未知（此时进度百分比按 0 处理）。
 * - [durationSeconds] 本次阅读时长（秒）。负值按 0 处理（脏数据不参与统计）。
 * - [timestampMillis] 最后阅读时间戳（毫秒）。**所有「哪一天」的判定都以调用方
 *   传入的 `ZoneId` 换算**，不读系统默认时区，避免改时区 / 跨时区后统计漂移。
 * - [chapterWordCount] 该章节字数，0 表示未知（未知章节不参与「平均每章字数」）。
 */
data class ReadingRecord(
    val bookId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val chapterIndex: Int,
    val totalChapters: Int,
    val durationSeconds: Long,
    val timestampMillis: Long,
    val chapterWordCount: Int = 0,
)

/**
 * 时间轴上的一个书目条目（桶内涉及的书）。
 *
 * 单独成结构而不是只给 `List<String>`：时间轴 UI 要显示书名，
 * 让调用方再反查一次书名表属于重复劳动。
 */
data class TimelineBook(
    val bookId: String,
    val title: String,
)

/**
 * 时间轴的一个桶（按日 / 周 / 月聚合的结果）。
 *
 * - [startMillis] / [endMillis] 桶的左闭右开时间区间（毫秒），按传入时区换算。
 * - [key] 桶的可读标识：日粒度 `2026-01-05`，周粒度 `2026-W02`（ISO 周），
 *   月粒度 `2026-01`。UI 直接拿它当列表 key / 标签。
 * - [chaptersRead] 桶内读过的**不同章节**数（同一章读了多次只算一章）。
 * - [books] 桶内涉及的书籍，按 [bookId] 升序（结果与输入顺序无关，可预测）。
 */
data class TimelineBucket(
    val key: String,
    val startDate: LocalDate,
    val startMillis: Long,
    val endMillis: Long,
    val durationSeconds: Long,
    val chaptersRead: Int,
    val books: List<TimelineBook>,
) {
    /** 桶内涉及的书 id（与 [books] 一一对应，方便直接喂给只认 id 的 UI） */
    val bookIds: List<String> get() = books.map { it.bookId }
}

/**
 * 单个章节的统计（章节维度）。
 *
 * 同一章被分多次读时：时长累加、字数取最大值（字数是章节属性，重复累加会翻倍）、
 * [lastReadMillis] 取最后一次阅读时间。
 */
data class ChapterStat(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val durationSeconds: Long,
    val wordCount: Int,
    val lastReadMillis: Long,
)

/**
 * 单本书的聚合统计。
 *
 * [progressPercent] 为 0..100 的进度百分比；总章节数未知（0）时恒为 0。
 * [averageChapterSeconds] / [averageChapterWords] 是**整除**后的整数，
 * 展示层需要小数可自行换算，避免这里为了一点精度引入浮点。
 */
data class BookStats(
    val bookId: String,
    val bookTitle: String,
    val totalChapters: Int,
    val readChapters: Int,
    val progressPercent: Double,
    val totalDurationSeconds: Long,
    val averageChapterSeconds: Long,
    val averageChapterWords: Int,
    val totalWords: Int,
    val firstReadMillis: Long,
    val lastReadMillis: Long,
) {
    companion object {
        /** 该书没有任何记录时的默认值（全零，进度 0%） */
        fun empty(bookId: String) = BookStats(
            bookId = bookId,
            bookTitle = "",
            totalChapters = 0,
            readChapters = 0,
            progressPercent = 0.0,
            totalDurationSeconds = 0L,
            averageChapterSeconds = 0L,
            averageChapterWords = 0,
            totalWords = 0,
            firstReadMillis = 0L,
            lastReadMillis = 0L,
        )
    }
}

/**
 * 全局概览。
 *
 * - [streakDays] 当前连续阅读天数，规则见 `ReadingStats.globalStats`。
 * - [longestStreakDays] 历史最长连续天数。
 * - [thisMonthDurationSeconds] 本月累计时长（相对调用方传入的「今天」）。
 * - [dailyAverageSeconds] 日均时长 = 总时长 ÷ 统计区间天数（首次阅读日至今天，含两端）。
 * - [activeDayAverageSeconds] 活跃日均时长 = 总时长 ÷ 有阅读记录的天数。
 *   两个平均值分母不同、含义不同，都给出，UI 按需取用，别互相顶替。
 */
data class GlobalStats(
    val streakDays: Int,
    val longestStreakDays: Int,
    val totalDurationSeconds: Long,
    val thisMonthDurationSeconds: Long,
    val dailyAverageSeconds: Long,
    val activeDayAverageSeconds: Long,
    val activeDays: Int,
    val bookCount: Int,
    val chapterCount: Int,
) {
    companion object {
        /** 没有任何记录时的默认值（全零） */
        fun empty() = GlobalStats(
            streakDays = 0,
            longestStreakDays = 0,
            totalDurationSeconds = 0L,
            thisMonthDurationSeconds = 0L,
            dailyAverageSeconds = 0L,
            activeDayAverageSeconds = 0L,
            activeDays = 0,
            bookCount = 0,
            chapterCount = 0,
        )
    }
}

/**
 * 阅读记录聚合（A14）。
 *
 * 职责：把一堆零散的「阅读记录」聚合成 **时间轴**、**章节维度统计**、**全局概览**
 * 与 **每日趋势**。
 *
 * 设计约束：
 * 1. **纯逻辑**：不 import 任何 `android.*`，不碰 Room / Compose，可在 JVM 单测里直接跑。
 * 2. **时间可注入**：所有「今天是哪天」「这条记录属于哪一天」都由调用方传入的
 *    `ZoneId` 和日期决定，函数内部绝不调 `System.currentTimeMillis()`，
 *    单测因此完全确定。
 * 3. **结果可预测**：桶按时间升序、桶内书目按 id 升序；同一批数据无论输入顺序
 *    如何，输出都一致（内部先按时间排序再聚合）。
 *
 * 注意：`java.time` 在 Android 上需要 API 26 或 core library desugaring；
 * 本项目 `minSdk = 24` 且尚未开启 desugaring，接入真机前需要二选一：
 * 把 minSdk 提到 26，或在 `build.gradle.kts` 打开 `coreLibraryDesugaring`。
 * （单元测试跑在 JVM 上不受影响。）
 */
object ReadingStats {

    /** 时间桶粒度 */
    enum class Granularity {
        /** 自然日：本地 00:00 起算 */
        DAY,

        /** 自然周：ISO-8601，周一起算 */
        WEEK,

        /** 自然月：1 号 00:00 起算 */
        MONTH,
    }

    // ------------------------------------------------------------------
    // 1. 时间轴
    // ------------------------------------------------------------------

    /**
     * 按粒度把阅读记录聚合成时间轴。
     *
     * 分桶算法：
     * 1. 每条记录用传入的 `ZoneId` 换算成本地日期；
     * 2. 按粒度把日期**向过去取整**到桶首日（日=当天、周=本周一、月=1 号）；
     * 3. 桶首日的本地 00:00 即桶起点，桶起点到下一个桶起点即桶的右开区间；
     * 4. 桶内累加阅读时长、去重章节与书目。
     *
     * 只返回**有记录的桶**（不补空桶）；要画连续柱状图请用 [dailyTrend]。
     *
     * @param records 阅读记录（任意顺序，内部会先排序）
     * @param granularity 桶粒度
     * @param zoneId 用于把时间戳换算成「哪一天」的时区
     * @return 按桶起点升序排列的时间轴；输入为空时返回空列表
     */
    fun timeline(
        records: List<ReadingRecord>,
        granularity: Granularity,
        zoneId: ZoneId,
    ): List<TimelineBucket> {
        if (records.isEmpty()) return emptyList()

        // 先排序：保证「取最后出现的书名 / 章节标题」这类逻辑与输入顺序无关，
        // 同一批数据无论喂进来什么顺序，输出都一致。
        val sorted = records.sortedWith(
            compareBy({ it.timestampMillis }, { it.bookId }, { it.chapterIndex }, { it.chapterTitle }),
        )

        val buckets = LinkedHashMap<LocalDate, MutableBucket>()
        sorted.forEach { record ->
            val date = record.localDate(zoneId)
            val start = bucketStartDate(date, granularity)
            buckets.getOrPut(start) { MutableBucket(start) }.add(record)
        }

        return buckets.values
            .sortedBy { it.startDate }
            .map { it.toBucket(granularity, zoneId) }
    }

    // ------------------------------------------------------------------
    // 2. 章节维度统计
    // ------------------------------------------------------------------

    /**
     * 单本书的章节维度明细（按章节序号升序）。
     *
     * 章节身份 = `bookId + 章节序号`；章节序号未知（负数）时退化为按标题识别。
     * 因此同一本书里第 3 章读三次 → 一条记录；两本不同的书都有「第 3 章」→ 两条记录。
     */
    fun chapters(records: List<ReadingRecord>, bookId: String): List<ChapterStat> {
        val mine = records.filter { it.bookId == bookId }
        if (mine.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableChapter>()
        mine.sortedWith(
            compareBy({ it.timestampMillis }, { it.chapterIndex }, { it.chapterTitle }),
        ).forEach { record ->
            val key = chapterKey(record)
            val chapter = grouped.getOrPut(key) {
                MutableChapter(record.chapterIndex, record.chapterTitle)
            }
            chapter.durationSeconds += record.durationSeconds.coerceAtLeast(0L)
            // 字数是章节属性，重复记录同一章时取最大值而不是累加，否则平均字数会被放大
            if (record.chapterWordCount > chapter.wordCount) {
                chapter.wordCount = record.chapterWordCount
            }
            if (record.timestampMillis > chapter.lastReadMillis) {
                chapter.lastReadMillis = record.timestampMillis
            }
            if (chapter.title.isBlank() && record.chapterTitle.isNotBlank()) {
                chapter.title = record.chapterTitle
            }
        }

        return grouped.values
            .sortedWith(compareBy({ it.chapterIndex }, { it.title }))
            .map {
                ChapterStat(
                    bookId = bookId,
                    chapterIndex = it.chapterIndex,
                    chapterTitle = it.title,
                    durationSeconds = it.durationSeconds,
                    wordCount = it.wordCount,
                    lastReadMillis = it.lastReadMillis,
                )
            }
    }

    /**
     * 单本书的聚合统计。
     *
     * - [BookStats.totalChapters] 取所有记录里 `totalChapters` 的最大值
     *   （目录可能随抓取更新而增长，取最大值比取最后一条更不容易倒退）。
     * - [BookStats.readChapters] 读过的**不同章节**数。
     * - [BookStats.progressPercent] = 已读章节数 ÷ 总章节数 × 100，裁剪到 0..100；
     *   总章节数未知（0）时返回 0。
     * - [BookStats.averageChapterSeconds] = 累计时长 ÷ 已读章节数（整除）。
     * - [BookStats.averageChapterWords] = 已知字数章节的总字数 ÷ 已知字数章节数（整除）；
     *   全部未知时返回 0（不拿 0 去污染平均值）。
     *
     * 没有该书任何记录时，返回一份**全零默认值**（[BookStats.bookId] 照填，其余为 0/0.0），
     * 调用方无需先判空就能安全展示。
     */
    fun bookStats(records: List<ReadingRecord>, bookId: String): BookStats {
        val mine = records.filter { it.bookId == bookId }
        if (mine.isEmpty()) return BookStats.empty(bookId)

        val chapterList = chapters(mine, bookId)
        val readChapters = chapterList.size
        val totalDuration = mine.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        val totalChapters = mine.maxOf { it.totalChapters.coerceAtLeast(0) }

        val knownWordChapters = chapterList.filter { it.wordCount > 0 }
        val totalWords = knownWordChapters.sumOf { it.wordCount }
        val averageWords = if (knownWordChapters.isEmpty()) 0 else totalWords / knownWordChapters.size

        val progress = if (totalChapters > 0) {
            (readChapters * 100.0 / totalChapters).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        return BookStats(
            bookId = bookId,
            // 书名取「最近一次记录」的非空书名：与时间轴桶内的取名口径一致，
            // 且不依赖输入顺序（先按时间排序再取最后一条非空的）。
            bookTitle = mine.sortedBy { it.timestampMillis }
                .lastOrNull { it.bookTitle.isNotBlank() }
                ?.bookTitle
                ?: "",
            totalChapters = totalChapters,
            readChapters = readChapters,
            progressPercent = progress,
            totalDurationSeconds = totalDuration,
            averageChapterSeconds = if (readChapters == 0) 0L else totalDuration / readChapters,
            averageChapterWords = averageWords,
            totalWords = totalWords,
            firstReadMillis = mine.minOf { it.timestampMillis },
            lastReadMillis = mine.maxOf { it.timestampMillis },
        )
    }

    // ------------------------------------------------------------------
    // 3. 全局概览
    // ------------------------------------------------------------------

    /**
     * 全局概览（连续天数 / 本月时长 / 日均时长 / 最长连续天数）。
     *
     * **连续阅读天数（streak）的判定规则**：
     * - 先算出所有「有阅读时长（> 0 秒）」的本地日期集合；
     * - 若今天在集合里，从今天往前数连续天数；
     * - 若今天还没读（集合里没有今天），从昨天往前数 —— 今天刚开始还没读不算断签，
     *   这是主流阅读类 App 的口径；
     * - 昨天也没有 → 连续天数 0。
     *
     * 记录时长为 0 的那几天**不算活跃日**：用户可能只是点开目录没读进去，
     * 把它们算进连续天数会让 streak 虚高。
     *
     * **最长连续天数** 取历史上最长的一段连续活跃日，与当前连续天数无关。
     *
     * @param today 「今天」，由调用方注入（便于单测）；本月的边界也按它算
     */
    fun globalStats(
        records: List<ReadingRecord>,
        zoneId: ZoneId,
        today: LocalDate,
    ): GlobalStats {
        if (records.isEmpty()) return GlobalStats.empty()

        val totalDuration = records.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        val activeDates = records
            .filter { it.durationSeconds > 0L }
            .map { it.localDate(zoneId) }
            .toSortedSet()

        val month = YearMonth.of(today.year, today.monthValue)
        val monthDuration = records
            .filter {
                val d = it.localDate(zoneId)
                YearMonth.of(d.year, d.monthValue) == month
            }
            .sumOf { it.durationSeconds.coerceAtLeast(0L) }

        // 日均时长的分母：首次阅读日到「今天」之间的自然天数（含两端）。
        // 用自然天数而不是活跃天数，才能反映「坚持了多久」的体感。
        val spanDays = if (activeDates.isEmpty()) {
            1L
        } else {
            (ChronoUnit.DAYS.between(activeDates.first(), today) + 1).coerceAtLeast(1L)
        }

        return GlobalStats(
            streakDays = currentStreak(activeDates, today),
            longestStreakDays = longestStreak(activeDates),
            totalDurationSeconds = totalDuration,
            thisMonthDurationSeconds = monthDuration,
            dailyAverageSeconds = totalDuration / spanDays,
            activeDayAverageSeconds = if (activeDates.isEmpty()) 0L else totalDuration / activeDates.size,
            activeDays = activeDates.size,
            bookCount = records.map { it.bookId }.distinct().size,
            chapterCount = records.map { chapterKey(it) }.distinct().size,
        )
    }

    /**
     * 全局概览（用 [Clock] 注入「现在」）。
     *
     * [Clock] 会被换算到 `zoneId` 后取当天，等价于 [globalStats] 传入
     * `LocalDate.now(clock.withZone(zoneId))`。
     */
    fun globalStats(
        records: List<ReadingRecord>,
        zoneId: ZoneId,
        clock: Clock,
    ): GlobalStats = globalStats(records, zoneId, LocalDate.now(clock.withZone(zoneId)))

    // ------------------------------------------------------------------
    // 4. 趋势
    // ------------------------------------------------------------------

    /**
     * 最近 [days] 天的每日阅读时长序列（秒），用于画柱状图。
     *
     * - 区间为 `[endDate - days + 1, endDate]`，**升序**返回：最后一格就是 [endDate]；
     * - 中间没有记录的日期补 0（柱状图不能缺格子）；
     * - [days] <= 0 时返回空列表。
     */
    fun dailyTrend(
        records: List<ReadingRecord>,
        zoneId: ZoneId,
        endDate: LocalDate,
        days: Int,
    ): List<Long> {
        if (days <= 0) return emptyList()

        val perDay = HashMap<LocalDate, Long>()
        records.forEach { record ->
            val date = record.localDate(zoneId)
            perDay[date] = (perDay[date] ?: 0L) + record.durationSeconds.coerceAtLeast(0L)
        }

        val first = endDate.minusDays((days - 1).toLong())
        return (0 until days).map { offset ->
            perDay[first.plusDays(offset.toLong())] ?: 0L
        }
    }

    /**
     * 最近 [days] 天的每日阅读时长序列（用 [Clock] 注入「现在」）。
     */
    fun dailyTrend(
        records: List<ReadingRecord>,
        zoneId: ZoneId,
        clock: Clock,
        days: Int,
    ): List<Long> = dailyTrend(records, zoneId, LocalDate.now(clock.withZone(zoneId)), days)

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 桶的累加器 */
    private class MutableBucket(val startDate: LocalDate) {
        var durationSeconds: Long = 0L
        val chapters = LinkedHashSet<String>()
        val books = LinkedHashMap<String, String>()

        fun add(record: ReadingRecord) {
            durationSeconds += record.durationSeconds.coerceAtLeast(0L)
            chapters += chapterKey(record)
            // 记录已按时间升序遍历，最后一次出现的非空书名即为「最新书名」
            if (record.bookTitle.isNotBlank() || !books.containsKey(record.bookId)) {
                books[record.bookId] = record.bookTitle
            }
        }

        fun toBucket(granularity: Granularity, zoneId: ZoneId): TimelineBucket {
            val start = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = nextBucketStartDate(startDate, granularity)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            return TimelineBucket(
                key = bucketKey(startDate, granularity),
                startDate = startDate,
                startMillis = start,
                endMillis = end,
                durationSeconds = durationSeconds,
                chaptersRead = chapters.size,
                books = books.entries
                    .sortedBy { it.key }
                    .map { TimelineBook(it.key, it.value) },
            )
        }
    }

    /** 章节的累加器 */
    private class MutableChapter(val chapterIndex: Int, var title: String) {
        var durationSeconds: Long = 0L
        var wordCount: Int = 0
        var lastReadMillis: Long = 0L
    }

    /** 当前连续天数：从今天（今天没读则从昨天）往前数连续活跃日 */
    private fun currentStreak(activeDates: Set<LocalDate>, today: LocalDate): Int {
        if (activeDates.isEmpty()) return 0
        var cursor = if (today in activeDates) today else today.minusDays(1)
        var streak = 0
        while (cursor in activeDates) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 历史最长连续天数 */
    private fun longestStreak(activeDates: Set<LocalDate>): Int {
        if (activeDates.isEmpty()) return 0
        var best = 1
        var current = 1
        var previous: LocalDate? = null
        activeDates.forEach { date ->
            current = if (previous != null && previous.plusDays(1) == date) current + 1 else 1
            if (current > best) best = current
            previous = date
        }
        return best
    }
}

// ---------------------------------------------------------------------------
// 文件级私有辅助（放在 object 外，避免嵌套类访问外层实例成员时的歧义）
// ---------------------------------------------------------------------------

/** 记录 → 本地日期（按传入时区换算） */
private fun ReadingRecord.localDate(zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()

/**
 * 章节身份键：优先用章节序号，序号未知（负数）时退化为按标题。
 * 带上 bookId 是为了让不同书的「第 3 章」互不混淆。
 */
private fun chapterKey(record: ReadingRecord): String =
    if (record.chapterIndex >= 0) {
        "${record.bookId}#i${record.chapterIndex}"
    } else {
        "${record.bookId}#t:${record.chapterTitle.trim()}"
    }

/** 日期向过去取整到桶首日 */
private fun bucketStartDate(date: LocalDate, granularity: ReadingStats.Granularity): LocalDate =
    when (granularity) {
        ReadingStats.Granularity.DAY -> date
        ReadingStats.Granularity.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        ReadingStats.Granularity.MONTH -> date.withDayOfMonth(1)
    }

/** 下一个桶的首日（右开区间的上界） */
private fun nextBucketStartDate(start: LocalDate, granularity: ReadingStats.Granularity): LocalDate =
    when (granularity) {
        ReadingStats.Granularity.DAY -> start.plusDays(1)
        ReadingStats.Granularity.WEEK -> start.plusWeeks(1)
        ReadingStats.Granularity.MONTH -> start.plusMonths(1)
    }

/** 桶的可读标识 */
private fun bucketKey(start: LocalDate, granularity: ReadingStats.Granularity): String =
    when (granularity) {
        ReadingStats.Granularity.DAY -> start.toString()
        ReadingStats.Granularity.WEEK -> String.format(
            Locale.ROOT,
            "%04d-W%02d",
            start.get(IsoFields.WEEK_BASED_YEAR),
            start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        ReadingStats.Granularity.MONTH -> String.format(Locale.ROOT, "%04d-%02d", start.year, start.monthValue)
    }
