package com.novelreader

import com.novelreader.core.search.EntryKind
import com.novelreader.core.search.RecallOutcome
import com.novelreader.core.search.RecallTask
import com.novelreader.core.search.TagRecallScheduler
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 召回调度层测试（v1.8.9.1）。
 *
 * 锁的是方案里的四条铁律，改这条链路之前先看这些用例：
 * 1. **超时 = 未知，不是没结果**；
 * 2. **慢 ≠ 不匹配**（超时的源不会被拉黑，也不影响别人）；
 * 3. **首包与全量解耦**（软截止先推一版，哪怕一条都没有）；
 * 4. **「全部出现」由任务终态判定**（pending 归零，不看结果稳不稳定）。
 */
class TagRecallSchedulerTest {

    private fun item(id: String, source: String) =
        MediaItem(id = id, title = "作品$id", type = SourceType.COMIC, sourceId = source)

    private fun task(source: String, term: String = "全彩", priority: Int = 0) = RecallTask(
        tagIndex = 0,
        sourceId = source,
        url = "search::$term",
        term = term,
        termPriority = priority,
        kind = EntryKind.KEYWORD,
    )

    @Test
    fun `超时记为未知而不是空结果`() = runBlocking {
        val scheduler = TagRecallScheduler(
            globalPool = Semaphore(4),
            requestTimeoutMs = 50,
            execute = { delay(5_000); emptyList() },
        )
        val result = scheduler.run(listOf(task("slow"))) {}
        assertEquals(RecallOutcome.TIMEOUT, result.sourceStatus["slow"])
        assertTrue(result.partial)
        assertTrue(result.timedOutSources.contains("slow"))
    }

    @Test
    fun `一个源超时不影响其它源的结果`() = runBlocking {
        val scheduler = TagRecallScheduler(
            globalPool = Semaphore(4),
            requestTimeoutMs = 100,
            execute = { t ->
                if (t.sourceId == "slow") {
                    delay(5_000)
                    emptyList()
                } else {
                    listOf(item("a", t.sourceId))
                }
            },
        )
        val result = scheduler.run(listOf(task("slow"), task("fast"))) {}
        val items = result.responses.flatMap { it.items }
        assertEquals(listOf("fast"), items.map { it.sourceId })
        // 慢源只被记成「不知道」，它的任务照样发出去过 —— 不是「跳过」
        assertEquals(RecallOutcome.TIMEOUT, result.sourceStatus["slow"])
        assertEquals(RecallOutcome.SUCCESS, result.sourceStatus["fast"])
    }

    @Test
    fun `源返回空列表记为空而不是未知`() = runBlocking {
        val scheduler = TagRecallScheduler(
            globalPool = Semaphore(4),
            execute = { emptyList() },
        )
        val result = scheduler.run(listOf(task("quiet"))) {}
        assertEquals(RecallOutcome.EMPTY, result.sourceStatus["quiet"])
        assertFalse(result.partial)
    }

    @Test
    fun `pending 归零即全部进终态`() = runBlocking {
        val tasks = (1..9).map { task("s$it", term = "词$it", priority = it) }
        val scheduler = TagRecallScheduler(
            globalPool = Semaphore(4),
            batchSize = 3,
            execute = { listOf(item(it.sourceId, it.sourceId)) },
        )
        var lastPending = Int.MAX_VALUE
        val result = scheduler.run(tasks) { batch -> lastPending = batch.pending }
        assertEquals(0, lastPending)
        assertEquals(tasks.size, result.responses.size)
    }

    @Test
    fun `软截止会先推一版空快照`() = runBlocking {
        val scheduler = TagRecallScheduler(
            globalPool = Semaphore(4),
            softDeadlineMs = 30,
            requestTimeoutMs = 5_000,
            execute = { delay(400); listOf(item("a", it.sourceId)) },
        )
        val batches = mutableListOf<Int>()
        scheduler.run(listOf(task("s1"))) { batch -> batches += batch.responses.size }
        // 第一版是「还没东西，但已经开始了」——界面据此从转圈切到「已找到 0 条」
        assertEquals(0, batches.first())
        assertTrue(batches.size >= 2)
    }

    @Test
    fun `收敛早停砍掉长尾`() = runBlocking {
        val calls = AtomicInteger(0)
        // 12 个写法，只有前两个有内容，后面全是空 —— 典型的「长尾没产出」
        val tasks = (0 until 12).map { task("s1", term = "词$it", priority = it) }
        val scheduler = TagRecallScheduler(
            globalPool = Semaphore(4),
            batchSize = 2,
            windowSize = 1,
            convergeBatches = 2,
            convergeMinNew = 1,
            execute = { t ->
                calls.incrementAndGet()
                if (t.termPriority < 2) listOf(item("i${t.termPriority}", t.sourceId)) else emptyList()
            },
        )
        scheduler.run(tasks) {}
        // 12 个任务不会全打出去
        assertTrue("实际请求 ${calls.get()} 次，应当明显少于 12", calls.get() < 12)
    }

    @Test
    fun `每源并发不超过额度`() = runBlocking {
        val inFlight = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val peak = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val scheduler = TagRecallScheduler(
            globalPool = Semaphore(16),
            perSourcePermits = 2,
            batchSize = 4,
            windowSize = 4,
            execute = { t ->
                val n = inFlight.getOrPut(t.sourceId) { AtomicInteger(0) }.incrementAndGet()
                peak.merge(t.sourceId, n) { a, b -> maxOf(a, b) }
                delay(20)
                inFlight.getValue(t.sourceId).decrementAndGet()
                listOf(item("i${t.termPriority}", t.sourceId))
            },
        )
        scheduler.run((0 until 8).map { task("s1", term = "词$it", priority = it) }) {}
        assertTrue("同源峰值 ${peak["s1"]} 超过了 2", (peak["s1"] ?: 0) <= 2)
    }

    @Test
    fun `空任务表直接返回`() = runBlocking {
        val scheduler = TagRecallScheduler(globalPool = Semaphore(2), execute = { emptyList() })
        val result = scheduler.run(emptyList()) {}
        assertTrue(result.responses.isEmpty())
        assertFalse(result.partial)
    }
}
