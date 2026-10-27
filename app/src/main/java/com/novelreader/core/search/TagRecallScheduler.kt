package com.novelreader.core.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 召回调度器（v1.8.9.1）—— 方案里「调度层」的落地。
 *
 * 它只做四件事，且**都不碰标签判定**：
 * 1. **分批 + 滑动窗口**：近义词按优先级排序后每 [batchSize] 个一批，
 *    同时在飞的批次不超过 [windowSize]；一批回来立刻补下一批。
 * 2. **双重限流**：全局池（用户设置的总并发）+ 每源信号量（[perSourcePermits]），
 *    防止某个源被一次打几十个请求，也防止一个死源占满全部额度。
 * 3. **软 / 硬截止**：软截止保证「首包」——到点即使一条都没回来也先推一个空快照，
 *    界面能从转圈切到「已找到 0 条，继续搜索中」；硬截止防挂死，到点没发出去的
 *    任务直接标 [RecallOutcome.SKIPPED]。
 * 4. **收敛早停**：连续 [convergeBatches] 批新增都少于 [convergeMinNew] 条就停发长尾。
 *    这正是「几十个近义词 × 多源」不会真的打出几百个请求的原因。
 *
 * 关键设计：**超时 / 报错只改任务的终态，不影响它带回来的条数，也不会把源拉黑。**
 * 以前这里用 `deadSources` 把超时的源整轮跳过，一个源认不出英文写法就被判死，
 * 连带它其它写法的结果全部丢掉 —— 「结果越搜越少」就是这么来的。
 *
 * @param execute 真正发请求的动作；异常由调度器统一折算成终态
 * @param requestTimeoutMs 单请求超时（与适配器层的 callTimeout 配合，这里只是兜底）
 * @param slowSources 历史超时率偏高的源：**只影响排序**（同优先级下排到后一波），
 *   不会跳过 —— 跳过就是又一次「慢源被当成没结果」，结果会越搜越少
 */
class TagRecallScheduler(
    private val globalPool: Semaphore,
    private val perSourcePermits: Int = DEFAULT_PER_SOURCE_PERMITS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val windowSize: Int = DEFAULT_WINDOW,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val softDeadlineMs: Long = DEFAULT_SOFT_DEADLINE_MS,
    private val hardDeadlineMs: Long = DEFAULT_HARD_DEADLINE_MS,
    private val convergeBatches: Int = DEFAULT_CONVERGE_BATCHES,
    private val convergeMinNew: Int = DEFAULT_CONVERGE_MIN_NEW,
    private val slowSources: Set<String> = emptySet(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val execute: suspend (RecallTask) -> List<com.novelreader.domain.model.MediaItem>,
) {

    private val sourceLocks = ConcurrentHashMap<String, Semaphore>()

    /**
     * 按「写法优先级 → 源轮转」排任务（v1.8.9.2）。
     *
     * 保留「主词先搜」的语义（优先级是第一排序键），但在同一优先级内**逐源轮转**：
     * 先给每个源发第一条，再发每个源的第二条……这样批次里天然覆盖多个源，
     * 收敛早停砍掉的只会是「各源的长尾写法」，而不是「排在字母表末尾的整个源」。
     * 源之间的先后由「是否慢源 → 源 id」决定，慢源仍然靠后。
     */
    private fun interleaveBySource(tasks: List<RecallTask>): List<RecallTask> {
        if (tasks.size <= 1) return tasks
        val out = ArrayList<RecallTask>(tasks.size)
        tasks.groupBy { it.termPriority }.toSortedMap().forEach { (_, tier) ->
            val perSource = tier.groupBy { it.sourceId }
            val sources = perSource.keys.sortedWith(
                compareBy({ it in slowSources }, { it }),
            )
            val queues = sources.map { perSource.getValue(it).sortedBy { t -> t.url } }
            val cursors = IntArray(queues.size)
            var remaining = queues.sumOf { it.size }
            while (remaining > 0) {
                for (i in queues.indices) {
                    val cursor = cursors[i]
                    if (cursor < queues[i].size) {
                        out += queues[i][cursor]
                        cursors[i] = cursor + 1
                        remaining--
                    }
                }
            }
        }
        return out
    }

    private fun sourceLock(sourceId: String): Semaphore =
        sourceLocks.getOrPut(sourceId) { Semaphore(perSourcePermits) }

    /**
     * 跑完 [tasks]，每批回来就回调一次 [onBatch]（回调串行，不会并发）。
     *
     * @param onFirstPacketSoftDeadline 到软截止时若还一条都没推过，会先用空快照回调一次
     */
    suspend fun run(
        tasks: List<RecallTask>,
        onBatch: suspend (RecallBatch) -> Unit,
    ): RecallResult = coroutineScope {
        if (tasks.isEmpty()) {
            return@coroutineScope RecallResult(emptyList(), emptyMap())
        }

        // 主词优先；同一优先级里**慢源排到后一波** —— 让快源先把首包填满，
        // 慢源照样会被问到（只是排在后面），绝不像旧版那样直接跳过。
        //
        // v1.8.9.2：同优先级内改为**按源轮转**。以前是 (优先级, 慢源, 源 id, url) 直接排，
        // 同一个源的入口全挤在一起，源 id 靠后的（实测一整批 `venera_*` 都排在
        // `legado_*` 之后）只能等到最后 —— 而收敛早停早把尾部砍了，于是这些源
        // 一次都没被问到，日志里整批 `SKIPPED`。轮转保证前几批就把每个源都过一遍，
        // 谁快谁慢只影响它自己后续入口的先后。
        val ordered = interleaveBySource(tasks)
        val batches = ordered.chunked(batchSize)
        val total = ordered.size
        val startMs = clock()

        val allResponses = Collections.synchronizedList(mutableListOf<RecallResponse>())
        val status = ConcurrentHashMap<String, RecallOutcome>()
        val seenKeys = HashSet<String>()
        val pending = AtomicInteger(total)
        val emitted = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val emitLock = Mutex()
        // 同时在飞的批次数（滑动窗口）。批大小 × 窗口 ≈ 实际并发，与全局池配合使用：
        // 窗口限的是「批」，池限的是「请求」，两层各管一件事。
        val batchWindow = Semaphore(windowSize.coerceAtLeast(1))
        var convergeStreak = 0

        // 软截止：还没推过任何东西就先推一个空快照，让界面从「转圈」切到「搜索中」
        val softTimer = launch {
            kotlinx.coroutines.delay(softDeadlineMs)
            if (!emitted.get()) {
                emitLock.withLock {
                    if (!emitted.get()) {
                        emitted.set(true)
                        onBatch(
                            RecallBatch(
                                responses = emptyList(),
                                pending = pending.get(),
                                total = total,
                                sourceStatus = HashMap(status),
                            ),
                        )
                    }
                }
            }
        }

        val jobs = batches.map { batch ->
            async {
                // 先抢窗口再判早停：抢窗口期间可能已经被别的批次判成收敛了，
                // 判早了会白打一批请求（正是要省掉的那些）。
                batchWindow.withPermit {
                    val now = clock()
                    if (stopped.get() || now - startMs >= hardDeadlineMs) {
                        val skipped = batch.map { RecallResponse(it, emptyList(), RecallOutcome.SKIPPED) }
                        publish(skipped, emitLock, allResponses, status, seenKeys, pending, total, onBatch) {
                            emitted.set(true)
                        }
                        return@withPermit
                    }
                    val responses = batch.map { task -> async { runOne(task) } }.awaitAll()
                    publish(responses, emitLock, allResponses, status, seenKeys, pending, total, onBatch) {
                        emitted.set(true)
                    }
                    // 收敛判定：按「本批新增条数」累计，连续若干批都几乎没有新东西就停长尾
                    emitLock.withLock {
                        val newCount = responses.sumOf { it.items.size }
                        convergeStreak = if (newCount < convergeMinNew) convergeStreak + 1 else 0
                        if (convergeStreak >= convergeBatches) stopped.set(true)
                    }
                }
            }
        }
        jobs.awaitAll()
        softTimer.cancel()

        RecallResult(allResponses.toList(), HashMap(status))
    }

    /**
     * 把一批结果并入总账并回调。
     *
     * 合并、去重、计数都放在同一把锁里：几批可能同时回来，分开放会让「新增条数」
     * 这种依赖前值的统计出错（收敛早停正是靠它）。
     */
    private suspend fun publish(
        responses: List<RecallResponse>,
        emitLock: Mutex,
        allResponses: MutableList<RecallResponse>,
        status: ConcurrentHashMap<String, RecallOutcome>,
        seenKeys: HashSet<String>,
        pending: AtomicInteger,
        total: Int,
        onBatch: suspend (RecallBatch) -> Unit,
        markEmitted: () -> Unit,
    ) = emitLock.withLock {
        responses.forEach { response ->
            allResponses.add(response)
            pending.addAndGet(-1)
            // 源状态取「最坏」的那一个：同一源既有成功又有超时时，对用户来说仍是「可能不全」
            val previous = status[response.task.sourceId]
            status[response.task.sourceId] = worseOf(previous, response.outcome)
            response.items.forEach { seenKeys.add("${it.sourceId}:${it.id}") }
        }
        markEmitted()
        onBatch(
            RecallBatch(
                responses = responses,
                pending = pending.get().coerceAtLeast(0),
                total = total,
                sourceStatus = HashMap(status),
            ),
        )
    }

    private suspend fun runOne(task: RecallTask): RecallResponse {
        val started = clock()
        return try {
            sourceLock(task.sourceId).withPermit {
                globalPool.withPermit {
                    val items = withTimeout(requestTimeoutMs) { execute(task) }
                    RecallResponse(
                        task = task,
                        items = items,
                        outcome = if (items.isEmpty()) RecallOutcome.EMPTY else RecallOutcome.SUCCESS,
                        latencyMs = clock() - started,
                    )
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // 超时 = 未知，不是「没有」。这个区别是整套方案的地基。
            RecallResponse(task, emptyList(), RecallOutcome.TIMEOUT, clock() - started)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            RecallResponse(task, emptyList(), RecallOutcome.ERROR, clock() - started)
        }
    }

    private fun worseOf(a: RecallOutcome?, b: RecallOutcome): RecallOutcome {
        if (a == null) return b
        val rank = mapOf(
            RecallOutcome.SUCCESS to 0,
            RecallOutcome.EMPTY to 1,
            RecallOutcome.SKIPPED to 2,
            RecallOutcome.ERROR to 3,
            RecallOutcome.TIMEOUT to 4,
        )
        return if ((rank[b] ?: 0) >= (rank[a] ?: 0)) b else a
    }

    companion object {
        const val DEFAULT_PER_SOURCE_PERMITS = 2
        const val DEFAULT_BATCH_SIZE = 4
        const val DEFAULT_WINDOW = 2
        const val DEFAULT_REQUEST_TIMEOUT_MS = 8_000L
        const val DEFAULT_SOFT_DEADLINE_MS = 4_000L
        const val DEFAULT_HARD_DEADLINE_MS = 30_000L
        const val DEFAULT_CONVERGE_BATCHES = 3
        const val DEFAULT_CONVERGE_MIN_NEW = 2
    }
}
