package com.novelreader.core.source

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 源操作失败记录（v1.7.1.0）。
 *
 * 数据层的 `runCatching { adapter.xxx() }.getOrDefault(emptyList())` 会把
 * 「超时 / 被墙 / 解析失败 / 源语法不兼容」与「该源**确实**没有目录或图片」
 * 折叠成同一个空结果，UI 只能一律显示「源已失效」——既误导用户，也让排查无从下手。
 *
 * 这里把每个源最近一次的真实异常记下来（只保留最新一条），供 UI 区分提示：
 * 有记录 → 「拉取失败（超时 / 解析错误…）」；无记录 → 「该源确实没有内容」。
 *
 * 记录的字符串**不含完整地址**（见 `core/util/LogSanitizer` 的一贯约定），
 * 只有操作名 + 异常类名 + 截断后的 message。
 *
 * v1.7.x（B12）新增结构化诊断：在原有「最新一条错误」之外，额外按源聚合
 * 累计次数、按操作分类计数、最近发生时间，供日志页 / 反馈包做「哪个源、
 * 哪类操作最容易挂」的离线诊断。仍然只存在内存里，不落库、不改 UI。
 */
object SourceErrorLog {

    /** 上限：只是提示用，不追求精确，超限时让出一半避免无限增长 */
    private const val MAX_ENTRIES = 64

    /** 最近一次失败的可读描述：sourceId -> "操作：ExceptionClass message" */
    private val errors = ConcurrentHashMap<String, String>()

    /**
     * 结构化聚合桶（B12）。
     *
     * 仅内存、进程内累计；[clear] 会一并清空（见下方说明）。
     */
    private class Bucket {
        /** 累计失败次数（自上次 clear 起） */
        val total = AtomicInteger(0)

        /** sourceId -> 操作名 -> 次数（如「目录」→ 3） */
        val byKind = ConcurrentHashMap<String, AtomicInteger>()

        /** 最近一次失败时间（epoch millis），0 表示尚未记录 */
        @Volatile
        var lastAt: Long = 0L

        /** 最近一次失败的描述，与 [errors] 中的值一致 */
        @Volatile
        var lastMessage: String = ""

        /** 源显示名（可选，只有调用方显式传入时才有） */
        @Volatile
        var name: String? = null
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * 单源诊断摘要（B12）。
     *
     * @param sourceId 源 id
     * @param sourceName 源显示名；调用方未提供时回退为 [sourceId]
     * @param totalErrors 累计失败次数
     * @param lastErrorAt 最近一次失败时间（epoch millis）
     * @param byKind 按操作名分类的失败次数
     * @param lastMessage 最近一次失败的可读描述
     */
    data class SourceDiagnostic(
        val sourceId: String,
        val sourceName: String,
        val totalErrors: Int,
        val lastErrorAt: Long,
        val byKind: Map<String, Int>,
        val lastMessage: String,
    )

    /**
     * 记录一次失败；[op] 为口语化操作名（如「目录」「图片」「详情」）。
     *
     * [sourceName] 可选，仅用于 [summarize] 输出更可读的诊断（老调用点不传，
     * 不影响原有行为）。
     */
    fun record(sourceId: String, op: String, e: Throwable, sourceName: String? = null) {
        if (sourceId.isBlank()) return
        if (errors.size >= MAX_ENTRIES) {
            errors.keys.take(errors.size / 2).forEach { errors.remove(it) }
        }
        val detail = e.message?.take(120)?.takeIf { it.isNotBlank() }
        val message = buildString {
            append(op)
            append('：')
            append(e.javaClass.simpleName)
            if (detail != null) {
                append(' ')
                append(detail)
            }
        }
        errors[sourceId] = message
        accumulate(sourceId, op, message, sourceName)
    }

    /** 该源最近一次失败的可读描述；没有失败记录返回 null */
    fun describe(sourceId: String): String? =
        if (sourceId.isBlank()) null else errors[sourceId]

    // ------------------------------------------------------------------
    // B12：结构化诊断
    // ------------------------------------------------------------------

    /**
     * 按 sourceId 聚合当前内存中的失败记录。
     *
     * 局限：数据源就是内存里的 [errors] / [buckets]（最多 [MAX_ENTRIES] 个源，
     * 进程重启即丢失，且每个源只保留最新一条 message）。因此
     * [SourceDiagnostic.totalErrors] 是「自上次 clear / 启动以来」的累计次数，
     * 不是全历史。够用于「这个源最近是不是反复挂」的排查，不适合当统计报表。
     *
     * 按 [SourceDiagnostic.lastErrorAt] 倒序，最近出问题的源排最前。
     */
    fun summarize(): List<SourceDiagnostic> =
        buckets.entries
            .map { (id, bucket) ->
                SourceDiagnostic(
                    sourceId = id,
                    sourceName = bucket.name ?: id,
                    totalErrors = bucket.total.get(),
                    lastErrorAt = bucket.lastAt,
                    byKind = bucket.byKind.mapValues { it.value.get() },
                    lastMessage = bucket.lastMessage.ifBlank { errors[id].orEmpty() },
                )
            }
            .sortedByDescending { it.lastErrorAt }

    /**
     * 清理诊断记录。
     *
     * - `clear()`：清空全部（故障排查页「重置」用）
     * - `clear(sourceId)`：只清某个源，**同时清掉该源的累计统计**
     *   （语义与 [clear] 一致：该源刚成功，历史失败不再代表现状）
     */
    fun clear(sourceId: String? = null) {
        if (sourceId.isNullOrBlank()) {
            errors.clear()
            buckets.clear()
            return
        }
        errors.remove(sourceId)
        buckets.remove(sourceId)
    }

    /** 写入聚合桶；与 [record] 分开只是为了保持 record 的可读性 */
    private fun accumulate(sourceId: String, op: String, message: String, sourceName: String?) {
        val bucket = buckets[sourceId] ?: run {
            // 超限时按同样的「让出一半」策略收缩，避免诊断桶无限增长
            if (buckets.size >= MAX_ENTRIES) {
                buckets.keys.take(buckets.size / 2).forEach { buckets.remove(it) }
            }
            Bucket().also { buckets[sourceId] = it }
        }
        bucket.total.incrementAndGet()
        val counter = bucket.byKind[op] ?: AtomicInteger(0).also { bucket.byKind[op] = it }
        counter.incrementAndGet()
        bucket.lastAt = System.currentTimeMillis()
        bucket.lastMessage = message
        if (!sourceName.isNullOrBlank()) bucket.name = sourceName
    }
}
