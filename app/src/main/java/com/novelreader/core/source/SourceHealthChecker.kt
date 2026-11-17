package com.novelreader.core.source

import com.novelreader.domain.model.SourceAdapter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单次书源健康检测结果（B5）。
 *
 * 与 `core/util/SourceHealth`（同名但不同包，那个是「可否算可用」的布尔判定）
 * 的区别：这里带**耗时与原因**，供「书源体检」页面排序 / 分类展示用。
 *
 * @param sourceId 源 id（列表里定位用）
 * @param sourceName 源显示名（展示用，检测逻辑本身不读它）
 * @param ok 自检是否通过
 * @param latencyMs 自检耗时（毫秒）；负数表示没有测到（如外部取消）
 * @param checkedAt 检测时间（epoch millis）
 * @param reason 失败原因（异常类名等**不含站点地址**的短语）；成功为 null
 */
data class SourceHealth(
    val sourceId: String,
    val sourceName: String,
    val ok: Boolean,
    val latencyMs: Long,
    val checkedAt: Long,
    val reason: String? = null,
)

/** 由 [SourceHealth] 归纳出的健康档位（供 UI 用三种颜色 + 「未知」灰） */
enum class HealthVerdict {
    /** 自检通过且不慢 */
    HEALTHY,

    /** 自检通过但慢（网络能通，只是要等） */
    SLOW,

    /** 自检失败 / 超时 */
    DEAD,

    /** 无法判定（没测到耗时） */
    UNKNOWN,
}

/**
 * 书源健康检测（B5）。
 *
 * 只做「单源自检 + 归档」，不依赖 UI、不落库：调用方（源管理页 / 后台体检）
 * 自己决定并发度、进度上报与结果保留策略。把逻辑抽成纯类的目的与
 * [SourceErrorLog] 相同——阈值与异常处理不该埋进 ViewModel 的协程嵌套里。
 *
 * 文案红线：本类产出的 [SourceHealth.reason] **只放异常类名或固定短语**，
 * 绝不拼接 URL / 站点名（日志、反馈包会原样展示，见 `LogSanitizer` 的约定）。
 */
object SourceHealthChecker {

    /** 超过这个耗时就算「慢」而不是「健康」 */
    const val SLOW_THRESHOLD_MS = 3_000L

    /** 默认单源自检上限；与源管理页「检测」的 20s 口径接近但略保守 */
    const val DEFAULT_TIMEOUT_MS = 15_000L

    /** 自检返回 false（不抛异常）时的固定原因短语 */
    private const val REASON_TEST_FALSE = "自检未通过"

    /** 超时（withTimeoutOrNull 返回 null）时的固定原因短语 */
    private const val REASON_TIMEOUT = "超时"

    /**
     * 按耗时与结果归档。
     *
     * 顺序很重要：先看「有没有测到」（负数 → UNKNOWN），再看成败，
     * 最后才区分快慢。`ok == true` 但 latency 为负属于调用方传错，按 UNKNOWN 处理。
     */
    fun verdict(latencyMs: Long, ok: Boolean): HealthVerdict = when {
        latencyMs < 0L -> HealthVerdict.UNKNOWN
        !ok -> HealthVerdict.DEAD
        latencyMs >= SLOW_THRESHOLD_MS -> HealthVerdict.SLOW
        else -> HealthVerdict.HEALTHY
    }

    /**
     * 检测单个源。
     *
     * - 用 [withTimeoutOrNull] 兜住 [SourceAdapter.test]：网络请求可能不响应取消，
     *   没有超时上界时一个死源就能把整轮体检拖住。
     * - 任何异常都折算成 `ok = false`，原因只记**异常类名**（不含 message，
     *   因为 message 里常带完整 URL）。同时写入 [SourceErrorLog] 供排查页复用。
     * - 外部主动取消（协程被 cancel）会原样向上抛，不折算成「源已死」。
     */
    suspend fun check(
        adapter: SourceAdapter,
        sourceId: String,
        sourceName: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): SourceHealth {
        val startedAt = System.nanoTime()
        var reason: String? = null

        val ok: Boolean = try {
            val result = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { adapter.test() }
            if (result == null) {
                reason = REASON_TIMEOUT
                false
            } else {
                if (!result) reason = REASON_TEST_FALSE
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 只取类名：Throwable.message 常含请求地址，不能进展示层
            reason = e.javaClass.simpleName.ifBlank { "检测失败" }
            SourceErrorLog.record(sourceId, "自检", e, sourceName)
            false
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        return SourceHealth(
            sourceId = sourceId,
            sourceName = sourceName,
            ok = ok,
            latencyMs = elapsedMs,
            checkedAt = System.currentTimeMillis(),
            reason = reason,
        )
    }

    /**
     * 统计各档位数量。
     *
     * 四个档位**始终都有键**（没出现过的补 0），这样 UI 不用处理 `map[...] ?: 0`，
     * 空列表也能直接画「0 / 0 / 0 / 0」。
     */
    fun summarize(list: List<SourceHealth>): Map<HealthVerdict, Int> {
        val counts = HealthVerdict.entries.associateWith { 0 }.toMutableMap()
        list.forEach { health ->
            val key = verdict(health.latencyMs, health.ok)
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts
    }
}
