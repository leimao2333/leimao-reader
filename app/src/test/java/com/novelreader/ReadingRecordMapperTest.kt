package com.novelreader

import com.novelreader.core.reader.ReadingStats
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.screens.history.ReadingRecordMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * A14「progress 表 → 纯逻辑层 ReadingRecord」映射口径的回归测试。
 *
 * 背景：`progress` 表每本书只有一行（`(sourceId, itemId)` 联合唯一索引 + REPLACE 写入），
 * 没有「这次读了多久」的字段。时长只能靠**相邻记录的时间差**估算，
 * 这条口径直接影响「累计时长 / 连续天数 / 日均时长」等所有统计数字，
 * 必须钉死，不能随实现漂移。
 */
class ReadingRecordMapperTest {

    /** 造一条最近阅读记录 */
    private fun item(
        sourceId: String = "s1",
        itemId: String = "b1",
        title: String = "书名一",
        chapterTitle: String = "第一章",
        chapterIndex: Int = 0,
        totalChapter: Int = 10,
        updatedAt: Long = 0L,
    ) = RecentReading(
        sourceId = sourceId,
        itemId = itemId,
        title = title,
        type = SourceType.NOVEL,
        chapterTitle = chapterTitle,
        chapterIndex = chapterIndex,
        totalChapter = totalChapter,
        updatedAt = updatedAt,
    )

    private val minute = 60_000L
    private val hour = 60 * minute
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // ---------------- bookId ----------------

    @Test
    fun `bookId 由 sourceId 与 itemId 组合`() {
        val records = ReadingRecordMapper.toRecords(listOf(item(sourceId = "s1", itemId = "b1")))
        assertEquals("s1:b1", records.first().bookId)
    }

    @Test
    fun `同一本书的多条记录只保留更新时间最新的一条`() {
        val records = ReadingRecordMapper.toRecords(
            listOf(
                item(chapterIndex = 3, updatedAt = 1000L),
                item(chapterIndex = 7, updatedAt = 9000L),
                item(chapterIndex = 5, updatedAt = 5000L),
            ),
        )
        // 只剩一本书（展开成逐章记录），且最新进度是第 7 章 → 展开出 0..7 共 8 条
        assertEquals(1, records.map { it.bookId }.distinct().size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), records.map { it.chapterIndex })
    }

    // ---------------- 章节展开 ----------------

    @Test
    fun `读到第 k 章展开成 k+1 条章节记录`() {
        val records = ReadingRecordMapper.toRecords(listOf(item(chapterIndex = 2)))
        assertEquals(3, records.size)
        assertEquals(listOf(0, 1, 2), records.map { it.chapterIndex })
    }

    @Test
    fun `章节展开后已读章节数与书架页进度口径一致`() {
        // 书架页用 chapterIndex + 1 算已读章节，这里必须一致
        val records = ReadingRecordMapper.toRecords(listOf(item(chapterIndex = 49, totalChapter = 100)))
        assertEquals(50, records.size)
    }

    @Test
    fun `只有最后一章带真实标题`() {
        val records = ReadingRecordMapper.toRecords(
            listOf(item(chapterIndex = 1, chapterTitle = "第二章")),
        )
        assertEquals("", records[0].chapterTitle)
        assertEquals("第二章", records[1].chapterTitle)
    }

    @Test
    fun `章节序号为负时按未知处理并至少留一条记录`() {
        val records = ReadingRecordMapper.toRecords(listOf(item(chapterIndex = -1)))
        assertEquals(1, records.size)
        assertEquals(0, records.first().chapterIndex)
    }

    @Test
    fun `空列表映射出空结果`() {
        assertTrue(ReadingRecordMapper.toRecords(emptyList()).isEmpty())
    }

    // ---------------- 时长估算 ----------------

    @Test
    fun `只有一本书时时长为零（没有邻居可比）`() {
        val records = ReadingRecordMapper.toRecords(listOf(item(updatedAt = 0L)))
        assertEquals(0L, records.sumOf { it.durationSeconds })
    }

    @Test
    fun `相邻两本书的间隔平分给两本`() {
        // 相隔 1 小时 → 各得 30 分钟
        val records = ReadingRecordMapper.toRecords(
            listOf(
                item(itemId = "b1", updatedAt = 0L),
                item(itemId = "b2", updatedAt = hour),
            ),
        )
        assertEquals(30 * 60L, records[0].durationSeconds)
        assertEquals(30 * 60L, records[1].durationSeconds)
    }

    @Test
    fun `间隔超过一小时会被封顶`() {
        // 相隔 10 小时 → 每本最多 30 分钟（封顶 1 小时后平分）
        val records = ReadingRecordMapper.toRecords(
            listOf(
                item(itemId = "b1", updatedAt = 0L),
                item(itemId = "b2", updatedAt = 10 * hour),
            ),
        )
        assertEquals(30 * 60L, records[0].durationSeconds)
        assertEquals(30 * 60L, records[1].durationSeconds)
    }

    @Test
    fun `三本书时中间那本拿到两侧间隔各半`() {
        val records = ReadingRecordMapper.toRecords(
            listOf(
                item(itemId = "b1", updatedAt = 0L),
                item(itemId = "b2", updatedAt = hour),
                item(itemId = "b3", updatedAt = 3 * hour),
            ),
        )
        // 首本只有右侧间隔 → 一半；中间本两侧都有 → 各一半；末本同理
        assertEquals(30 * 60L, records[0].durationSeconds)
        assertEquals(60 * 60L, records[1].durationSeconds)
        assertEquals(30 * 60L, records[2].durationSeconds)
    }

    @Test
    fun `时间戳顺序颠倒时仍按时间排序后估算`() {
        val records = ReadingRecordMapper.toRecords(
            listOf(
                item(itemId = "b2", updatedAt = hour),
                item(itemId = "b1", updatedAt = 0L),
            ),
        )
        assertEquals("b1", records[0].bookId.removePrefix("s1:"))
        assertEquals(30 * 60L, records[0].durationSeconds)
        assertEquals(30 * 60L, records[1].durationSeconds)
    }

    @Test
    fun `总时长不因章节展开而被放大`() {
        // 一本书 10 章、估算 30 分钟 → 展开后各章均摊，总和仍是 30 分钟
        val records = ReadingRecordMapper.toRecords(
            listOf(
                item(itemId = "b1", updatedAt = 0L, chapterIndex = 9),
                item(itemId = "b2", updatedAt = hour, chapterIndex = 9),
            ),
        )
        // 两本各 30 分钟 → 合计 1 小时（单位是秒）
        assertEquals(60 * 60L, records.sumOf { it.durationSeconds })
    }

    // ---------------- 其它字段 ----------------

    @Test
    fun `总章节数与标题原样透传`() {
        val records = ReadingRecordMapper.toRecords(
            listOf(item(title = "书名一", totalChapter = 320, chapterIndex = 1)),
        )
        assertEquals(320, records.first().totalChapters)
        assertEquals("书名一", records.first().bookTitle)
    }

    @Test
    fun `章节字数未知时写 0`() {
        // 0 = 未知：纯逻辑层据此把该章排除在「平均每章字数」之外
        val records = ReadingRecordMapper.toRecords(listOf(item()))
        assertEquals(0, records.first().chapterWordCount)
    }

    @Test
    fun `时间戳用进度表的更新时间`() {
        val records = ReadingRecordMapper.toRecords(listOf(item(updatedAt = 123456L)))
        assertEquals(123456L, records.first().timestampMillis)
    }

    // ---------------- 与纯逻辑层的衔接 ----------------

    @Test
    fun `映射结果能被纯逻辑层正确聚合成单书统计`() {
        val records = ReadingRecordMapper.toRecords(
            listOf(item(itemId = "b1", title = "书名一", chapterIndex = 9, totalChapter = 20)),
        )
        val stats = ReadingStats.bookStats(records, "s1:b1")
        // 读到第 10 章 → 已读 10 章，进度 50%
        assertEquals(10, stats.readChapters)
        assertEquals(20, stats.totalChapters)
        assertEquals(50.0, stats.progressPercent, 0.001)
        assertEquals("书名一", stats.bookTitle)
    }

    @Test
    fun `映射结果能被纯逻辑层正确聚合成章节明细`() {
        val records = ReadingRecordMapper.toRecords(
            listOf(item(itemId = "b1", chapterTitle = "第十章", chapterIndex = 9)),
        )
        val chapters = ReadingStats.chapters(records, "s1:b1")
        assertEquals(10, chapters.size)
        // 章节序号升序
        assertEquals((0..9).toList(), chapters.map { it.chapterIndex })
        // 只有最后一章有真实标题
        assertEquals("第十章", chapters.last().chapterTitle)
    }

    @Test
    fun `多本书的记录能被纯逻辑层分到不同的时间桶`() {
        val records = ReadingRecordMapper.toRecords(
            listOf(
                item(itemId = "b1", updatedAt = 0L),
                item(itemId = "b2", updatedAt = 2 * 24 * hour),
            ),
        )
        val buckets = ReadingStats.timeline(records, ReadingStats.Granularity.DAY, zone)
        assertEquals(2, buckets.size)
        assertEquals(listOf("s1:b1"), buckets[0].bookIds)
        assertEquals(listOf("s1:b2"), buckets[1].bookIds)
    }
}
