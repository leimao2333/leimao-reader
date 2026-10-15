package com.novelreader.core.source

import java.util.concurrent.ConcurrentHashMap

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
 */
object SourceErrorLog {

    /** 上限：只是提示用，不追求精确，超限时让出一半避免无限增长 */
    private const val MAX_ENTRIES = 64

    private val errors = ConcurrentHashMap<String, String>()

    /** 记录一次失败；[op] 为口语化操作名（如「目录」「图片」「详情」） */
    fun record(sourceId: String, op: String, e: Throwable) {
        if (sourceId.isBlank()) return
        if (errors.size >= MAX_ENTRIES) {
            errors.keys.take(errors.size / 2).forEach { errors.remove(it) }
        }
        val detail = e.message?.take(120)?.takeIf { it.isNotBlank() }
        errors[sourceId] = buildString {
            append(op)
            append('：')
            append(e.javaClass.simpleName)
            if (detail != null) {
                append(' ')
                append(detail)
            }
        }
    }

    /** 该源刚成功过一次 → 清掉旧记录，避免拿历史失败误导本次提示 */
    fun clear(sourceId: String) {
        if (sourceId.isNotBlank()) errors.remove(sourceId)
    }

    /** 该源最近一次失败的可读描述；没有失败记录返回 null */
    fun describe(sourceId: String): String? =
        if (sourceId.isBlank()) null else errors[sourceId]
}
