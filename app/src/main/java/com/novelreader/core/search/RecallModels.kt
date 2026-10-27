package com.novelreader.core.search

import com.novelreader.domain.model.MediaItem

/**
 * 召回层的数据模型（v1.8.9.1）。
 *
 * 这一层只回答一个问题：**某个 (源 × 写法) 到底有没有结果**。
 * 「这条结果算不算命中标签」是结果层的事（见 `TagVerdict`），不在这里做 ——
 * 以前两者混在 `searchOneTag` 里，导致「超时」和「搜到了但不相关」共用同一个
 * `emptyList()` 表示，下游根本分不清，于是出现了「慢源被当成没结果 → 结果越搜越少」。
 */

/** 单个任务的终态。**超时是「未知」，不是「没有」。** */
enum class RecallOutcome {
    /** 正常返回且有内容 */
    SUCCESS,

    /** 正常返回但确实没有内容（源说「查无此项」） */
    EMPTY,

    /** 超时 / 截止到点被中断 —— 内容未知，结果层必须当 unknown 处理 */
    TIMEOUT,

    /** 请求报错（源不认这种写法、解析失败、域名失效…） */
    ERROR,

    /** 因收敛早停 / 硬截止 / 健康度降级而没有真正发出 */
    SKIPPED,
    ;

    /** 这个终态是否意味着「我们不知道源里有没有这部作品」 */
    val isUnknown: Boolean get() = this == TIMEOUT || this == ERROR

    /** 这个终态是否说明源本身不可用（用于慢源治理，不用于过滤结果） */
    val isFailure: Boolean get() = this == TIMEOUT || this == ERROR
}

/** 召回任务里入口的种类：真实分类页 vs 关键词搜索 */
enum class EntryKind { CATEGORY, KEYWORD }

/**
 * 一个召回任务 = 一次网络请求。
 *
 * [termPriority] 是该写法在近义词组里的优先级（0 = 主词）。分批时按它排序 ——
 * 主词、高区分度的写法先发，长尾后发，收敛早停砍掉的正是长尾。
 */
data class RecallTask(
    val tagIndex: Int,
    val sourceId: String,
    val url: String,
    val term: String,
    val termPriority: Int,
    val kind: EntryKind,
    val page: Int = 1,
) {
    /** 结果层用它判断「这条结果来自分类页还是关键词入口」 */
    val isCategory: Boolean get() = kind == EntryKind.CATEGORY
}

/** 一个任务跑完的结果 */
data class RecallResponse(
    val task: RecallTask,
    val items: List<MediaItem>,
    val outcome: RecallOutcome,
    /** 这次请求实际耗时，供源健康度统计（超时也记，等于把超时计入延迟） */
    val latencyMs: Long = 0L,
)

/**
 * 一批任务跑完后的快照，用于流式推给界面。
 *
 * [pending] 是**还没进终态**的任务数，归零即 done —— 这就是方案里
 * 「全部出现由任务终态判定，不由结果稳定判定」的落点。
 */
data class RecallBatch(
    val responses: List<RecallResponse>,
    val pending: Int,
    val total: Int,
    val sourceStatus: Map<String, RecallOutcome>,
)

/** 整轮召回的终态 */
data class RecallResult(
    val responses: List<RecallResponse>,
    val sourceStatus: Map<String, RecallOutcome>,
) {
    /** 有源超时/报错 → 结果是 partial（可能不全），界面要如实说明 */
    val partial: Boolean get() = sourceStatus.values.any { it.isUnknown }

    /** 超时的源（去重），供「重试慢源」用 */
    val timedOutSources: Set<String>
        get() = sourceStatus.filterValues { it == RecallOutcome.TIMEOUT }.keys

    /** 因为早停/截止而没问的源 */
    val skippedSources: Set<String>
        get() = sourceStatus.filterValues { it == RecallOutcome.SKIPPED }.keys
}
