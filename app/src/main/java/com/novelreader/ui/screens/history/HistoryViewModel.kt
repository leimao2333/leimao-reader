package com.novelreader.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.reader.BookStats
import com.novelreader.core.reader.ChapterStat
import com.novelreader.core.reader.GlobalStats
import com.novelreader.core.reader.ReadingRecord
import com.novelreader.core.reader.ReadingStats
import com.novelreader.core.reader.TimelineBucket
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * 阅读历史 ViewModel（v1.4.3.0）。
 *
 * 独立「历史」页：按类型（全部 / 小说 / 漫画 / 视频）筛选，展示所有阅读/播放记录，
 * 支持多选批量清除与「清空全部」。
 *
 * A14：阅读记录时间轴 + 章节维度统计。
 *
 * 数据来源仍是既有的 `progress` 表（通过 [ProgressRepository]），
 * 只是把「最后一次进度」翻译成纯逻辑层 [ReadingStats] 认识的 [ReadingRecord]，
 * 再由纯逻辑层负责时间轴 / 章节维度 / 全局概览的聚合 ——
 * 聚合算法不写在这里，纯逻辑层改了就自动生效。
 */
class HistoryViewModel(
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    /** 当前类型筛选；null = 全部 */
    private val _selectedType = MutableStateFlow<SourceType?>(null)
    val selectedType: StateFlow<SourceType?> = _selectedType.asStateFlow()

    /** 历史记录（按更新时间倒序） */
    private val _items = MutableStateFlow<List<RecentReading>>(emptyList())
    val items: StateFlow<List<RecentReading>> = _items.asStateFlow()

    /** 是否正在加载 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 多选模式下的选中集合（key = sourceId|itemId） */
    private val _selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedKeys: StateFlow<Set<String>> = _selectedKeys.asStateFlow()

    /** 是否处于多选模式 */
    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()

    // ===== A14：阅读统计 =====

    /**
     * 时间轴粒度（按日 / 周 / 月）。
     *
     * 默认按日：用户最常问的是「我昨天读了多久」，按日粒度能直接回答。
     */
    private val _granularity = MutableStateFlow(ReadingStats.Granularity.DAY)
    val granularity: StateFlow<ReadingStats.Granularity> = _granularity.asStateFlow()

    /** 映射后的阅读记录（纯逻辑层的输入） */
    private val _records = MutableStateFlow<List<ReadingRecord>>(emptyList())

    /** 全局概览（总时长 / 连续天数 / 本月时长 / 日均时长 / 已读书目数 …） */
    private val _globalStats = MutableStateFlow(GlobalStats.empty())
    val globalStats: StateFlow<GlobalStats> = _globalStats.asStateFlow()

    /** 时间轴（按当前粒度分桶） */
    private val _timeline = MutableStateFlow<List<TimelineBucket>>(emptyList())
    val timeline: StateFlow<List<TimelineBucket>> = _timeline.asStateFlow()

    /** 每日趋势（近 [TREND_DAYS] 天的阅读时长，用于画柱状条） */
    private val _trend = MutableStateFlow<List<DailyTrendPoint>>(emptyList())
    val trend: StateFlow<List<DailyTrendPoint>> = _trend.asStateFlow()

    /** 当前展开章节统计的书（null = 未选中） */
    private var selectedBookId: String? = null

    /** 选中书的章节维度统计 */
    private val _selectedBookStats = MutableStateFlow<BookStats?>(null)
    val selectedBookStats: StateFlow<BookStats?> = _selectedBookStats.asStateFlow()

    /** 选中书的逐章明细 */
    private val _selectedChapters = MutableStateFlow<List<ChapterStat>>(emptyList())
    val selectedChapters: StateFlow<List<ChapterStat>> = _selectedChapters.asStateFlow()

    init {
        refresh()
    }

    /** 切换类型筛选并重新加载 */
    fun selectType(type: SourceType?) {
        if (_selectedType.value == type) return
        _selectedType.value = type
        refresh()
    }

    /** 重新加载历史（回前台 / 筛选切换时调用） */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val list = runCatching {
                progressRepository.recentReadings(_selectedType.value)
            }.getOrDefault(emptyList())
            _items.value = list
            // 清掉已不存在的选中项
            val validKeys = list.map { keyOf(it) }.toSet()
            _selectedKeys.value = _selectedKeys.value.intersect(validKeys)
            applyStats(list)
            _loading.value = false
        }
    }

    /** 切换时间轴粒度 */
    fun setGranularity(g: ReadingStats.Granularity) {
        if (_granularity.value == g) return
        _granularity.value = g
        recomputeTimeline()
    }

    // ===== 多选 / 删除 =====

    /** 进入 / 退出多选模式 */
    fun toggleSelecting() {
        _selecting.value = !_selecting.value
        if (!_selecting.value) _selectedKeys.value = emptySet()
    }

    fun exitSelecting() {
        _selecting.value = false
        _selectedKeys.value = emptySet()
    }

    /** 切换单条选中状态 */
    fun toggleSelect(key: String) {
        val cur = _selectedKeys.value
        _selectedKeys.value = if (key in cur) cur - key else cur + key
    }

    /** 全选 / 取消全选 */
    fun toggleSelectAll() {
        val all = _items.value.map { keyOf(it) }.toSet()
        _selectedKeys.value = if (_selectedKeys.value == all) emptySet() else all
    }

    /** 删除单条记录 */
    fun remove(item: RecentReading) {
        viewModelScope.launch {
            runCatching { progressRepository.removeProgress(item.sourceId, item.itemId) }
            refresh()
        }
    }

    /** 批量删除选中记录 */
    fun removeSelected() {
        val keys = _selectedKeys.value
        if (keys.isEmpty()) return
        viewModelScope.launch {
            _items.value.forEach { r ->
                if (keyOf(r) in keys) {
                    runCatching { progressRepository.removeProgress(r.sourceId, r.itemId) }
                }
            }
            exitSelecting()
            refresh()
        }
    }

    /** 清空全部历史 */
    fun clearAll() {
        viewModelScope.launch {
            runCatching { progressRepository.clearAllProgress() }
            exitSelecting()
            refresh()
        }
    }

    /** 点开某本书的章节维度统计 */
    fun selectBook(bookId: String) {
        selectedBookId = bookId
        refreshSelectedBook()
    }

    /** 关闭章节维度统计 */
    fun clearSelectedBook() {
        selectedBookId = null
        _selectedBookStats.value = null
        _selectedChapters.value = emptyList()
    }

    /**
     * 用最新一批进度重算全部统计数据。
     *
     * 统计与列表共用同一份数据（同一个类型筛选）：
     * 页面上「筛选小说」时，列表、统计头、时间轴应该讲同一个故事，
     * 否则用户会以为统计漏算了。
     */
    private fun applyStats(list: List<RecentReading>) {
        val records = ReadingRecordMapper.toRecords(list)
        _records.value = records
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        _globalStats.value = ReadingStats.globalStats(records, zone, today)
        _trend.value = buildTrend(records, zone, today)
        recomputeTimeline()
        refreshSelectedBook()
    }

    /** 时间轴只依赖「记录 + 粒度」，粒度切换时不必重算其它统计 */
    private fun recomputeTimeline() {
        val zone = ZoneId.systemDefault()
        _timeline.value = ReadingStats.timeline(_records.value, _granularity.value, zone)
    }

    /** 选中书变化时重算它的章节维度统计（记录刷新后也要跟着更新） */
    private fun refreshSelectedBook() {
        val id = selectedBookId ?: return
        _selectedBookStats.value = ReadingStats.bookStats(_records.value, id)
        _selectedChapters.value = ReadingStats.chapters(_records.value, id)
    }

    /**
     * 把纯逻辑层返回的「每日时长序列」补上日期。
     *
     * [ReadingStats.dailyTrend] 只给秒数，UI 要画「最近 N 天」的柱状条就得知道
     * 每一格是哪一天，这里按「最后一天 = 今天」往回推。
     */
    private fun buildTrend(
        records: List<ReadingRecord>,
        zone: ZoneId,
        today: LocalDate,
    ): List<DailyTrendPoint> {
        val seconds = ReadingStats.dailyTrend(records, zone, today, TREND_DAYS)
        return seconds.mapIndexed { index, value ->
            DailyTrendPoint(
                date = today.minusDays((TREND_DAYS - 1 - index).toLong()),
                seconds = value,
            )
        }
    }

    companion object {
        fun keyOf(r: RecentReading): String = "${r.sourceId}|${r.itemId}"

        /** 趋势条展示的天数（两周足够看出规律，又不至于挤成一条线） */
        const val TREND_DAYS = 14
    }
}

/**
 * 每日趋势的一格：某天的阅读时长。
 *
 * 日期与秒数一起带，是为了 UI 不用再自己算「第几天前」。
 */
data class DailyTrendPoint(
    val date: LocalDate,
    val seconds: Long,
)

/**
 * 阅读记录映射器：把 `progress` 表的行翻译成纯逻辑层 [ReadingRecord]。
 *
 * 为什么单独抽出来：
 * - 纯逻辑层（[ReadingStats]）刻意不认识 Room 实体，映射口径只在这里定义一次；
 * - 口径里有「估算」成分（阅读时长），必须能被单测钉住，所以不写在 Composable 里。
 *
 * ## 数据事实（决定了下面的口径）
 *
 * `progress` 表对 `(sourceId, itemId)` 建了**联合唯一索引**，且写入用的是 REPLACE，
 * 所以**每本书在表里只有一行**：记着「最后一次读到第几章」和「最后一次的更新时间」。
 * 换句话说，表里没有「逐次阅读流水」，也就没有「这次读了多久」。
 *
 * ## 各字段口径
 *
 * - [ReadingRecord.bookId] = `sourceId:itemId`：与表的联合唯一索引一致，
 *   保证同一本书永远是同一个 id（否则 [ReadingStats.bookStats] 按 bookId 过滤会漏）。
 * - [ReadingRecord.bookTitle] = 条目标题。
 * - [ReadingRecord.chapterTitle] = 最后阅读的章节标题。
 * - [ReadingRecord.chapterIndex] = 进度里的章节序号（0 起）。
 * - [ReadingRecord.totalChapters] = 进度里的总章节数（0 = 未知）。
 * - [ReadingRecord.timestampMillis] = 进度的 `updatedAt`（最后一次阅读时间）。
 * - [ReadingRecord.durationSeconds] = **估算值**，见 [estimateDurations]。
 * - [ReadingRecord.chapterWordCount] = 0（未知）：进度表不存章节字数，
 *   纯逻辑层约定 0 表示未知，因此「平均每章字数」会显示为未知而不是 0 字。
 */
internal object ReadingRecordMapper {

    /**
     * 相邻两次阅读的最大间隔（毫秒），用来给估算时长封顶。
     *
     * 隔夜的间隔（十几个小时）显然不是「一次阅读」的长度 —— 用户只是隔天再打开。
     * 不封顶的话，「三天没读书」会被算成三天的阅读时长，总时长直接离谱。
     */
    const val MAX_GAP_MILLIS = 60L * 60L * 1000L

    /**
     * 把最近阅读列表翻译成阅读记录。
     *
     * 同一本书在列表里只出现一次（DAO 已按 `sourceId+itemId` 去重），
     * 这里仍按 bookId 做一次归并，防御 DAO 语义变化。
     */
    fun toRecords(items: List<RecentReading>): List<ReadingRecord> {
        if (items.isEmpty()) return emptyList()

        // 同书归并：保留更新时间最新的一条
        val latest = items.groupBy { bookIdOf(it) }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.updatedAt } ?: rows.first() }
            .values
            // 按时间升序：估算时长要用相邻记录的时间差
            .sortedBy { it.updatedAt }

        val durations = estimateDurations(latest)
        val result = ArrayList<ReadingRecord>(latest.size)
        latest.forEachIndexed { index, item ->
            result += expandChapters(
                ReadingRecord(
                    bookId = bookIdOf(item),
                    bookTitle = item.title,
                    chapterTitle = item.chapterTitle,
                    chapterIndex = item.chapterIndex.coerceAtLeast(0),
                    totalChapters = item.totalChapter,
                    durationSeconds = durations[index],
                    timestampMillis = item.updatedAt,
                    chapterWordCount = 0,
                ),
            )
        }
        return result
    }

    /**
     * 阅读时长的估算口径。
     *
     * `progress` 表**没有**阅读时长字段，能用的只有「最后一次更新时间」。
     * 这里用**相邻两条记录的时间差**近似：那段时间里用户大致在读（可能正在换书），
     * 把这个间隔平分给相邻的两本书。
     *
     * 为什么平分而不是全给后一条：
     * - 全给后一条的话，**最早**的那本书时长恒为 0，它所在的那天会被
     *   [ReadingStats.globalStats] 当成「没读」的日子，连续天数会少算一天；
     * - 平分之后每本书的时长都 > 0（只要有邻居），活跃日不会漏。
     *
     * 为什么封顶（[MAX_GAP_MILLIS]）：隔夜 / 隔天的间隔不是「一次阅读」的长度。
     *
     * 因此这个值是**估算**，UI 上按「约」字提示，不当成精确数据展示。
     * 只读过一本书时没有邻居可比，时长为 0 —— 这是数据本身的限制，不是 bug。
     */
    fun estimateDurations(items: List<RecentReading>): List<Long> {
        if (items.isEmpty()) return emptyList()
        val seconds = ArrayList<Long>(items.size)
        for (index in items.indices) {
            val left = if (index > 0) gapMillis(items[index - 1], items[index]) else 0L
            val right = if (index < items.size - 1) gapMillis(items[index], items[index + 1]) else 0L
            seconds += (left + right) / 2 / 1000L
        }
        return seconds
    }

    /** 相邻两条记录的时间差（封顶） */
    private fun gapMillis(earlier: RecentReading, later: RecentReading): Long =
        (later.updatedAt - earlier.updatedAt).coerceIn(0L, MAX_GAP_MILLIS)

    /**
     * 把「读到第 k 章」展开成逐章记录。
     *
     * 为什么要展开：`progress` 表每本书只有「最后读到第几章」这一条信息，
     * 若一条记录只对应一章，[ReadingStats.bookStats] 算出的「已读章节数」永远是 1，
     * 进度百分比也会失真。展开成 0..k 章后，已读章节数 = k+1，
     * 与书架页显示的进度口径（`chapterIndex + 1`）一致。
     *
     * 时长按已读章节**均摊**（余数落在最后一章）：表里没有逐章耗时，
     * 均摊只是为了让「总时长」与「平均每章时长」自洽，不代表真实逐章耗时。
     */
    fun expandChapters(base: ReadingRecord): List<ReadingRecord> {
        val readCount = base.chapterIndex + 1
        if (readCount <= 1) return listOf(base)

        val perChapter = base.durationSeconds / readCount
        val remainder = base.durationSeconds - perChapter * readCount
        return (0 until readCount).map { index ->
            base.copy(
                chapterIndex = index,
                // 只有最后一章有真实标题（表里只记了最后读到的那章）
                chapterTitle = if (index == readCount - 1) base.chapterTitle else "",
                durationSeconds = perChapter + if (index == readCount - 1) remainder else 0L,
            )
        }
    }

    /** 书籍唯一 id：与 `progress` 表的联合唯一索引口径一致 */
    fun bookIdOf(item: RecentReading): String = "${item.sourceId}:${item.itemId}"
}
