package com.novelreader.core.util

/**
 * 智能伴生分组（C6）。
 *
 * 书架上的书按「在读 / 未读 / 已读完」自动归类，用户不用手动分组。
 * 这里只做纯逻辑：输入是每本书的阅读状态，输出是分组到书籍 ID 的映射，
 * 不依赖 Room / Android，方便单测与在 ViewModel 里直接组合。
 *
 * 数据来源见 `BookDao.observeShelfStates`：books 左连接 progress，
 * 有无进度 → `hasProgress`，是否读完 → `finished`（实体里没有 finished 字段，
 * 用「进度 ≥ 99%」近似，见 [BookshelfAutoGroup.approxFinished]）。
 */
enum class ShelfCompanionGroup {
    /** 在读：有进度且未读完 */
    READING,

    /** 未读：一次都没打开过 */
    UNREAD,

    /** 已读完：进度 ≥ 99% */
    FINISHED,
}

/**
 * 单本书的自动分组输入。
 *
 * @param bookId 书架行主键
 * @param hasProgress 是否存在阅读进度
 * @param finished 是否已读完
 */
data class AutoGroupInput(
    val bookId: Long,
    val hasProgress: Boolean,
    val finished: Boolean,
)

object BookshelfAutoGroup {

    /**
     * 进度的完成判定阈值（百分比）。
     *
     * 100 会让「最后一章读了 98%」的书永远留在在读里；99 已经足够说明读到尾声。
     */
    const val FINISHED_PERCENT = 99

    /**
     * 把书架条目按阅读状态归类。
     *
     * 规则：
     * - `finished = true` → [ShelfCompanionGroup.FINISHED]（无论是否有进度，读完优先）
     * - 有进度且未完成 → [ShelfCompanionGroup.READING]
     * - 无进度 → [ShelfCompanionGroup.UNREAD]
     *
     * 返回的 Map 三组恒存在（可能为空列表），UI 可直接 `groups[READING]` 取用；
     * 每组内部保持传入顺序（调用方按 addedAt / 最近阅读排好序，这里不重排）。
     */
    fun classify(inputs: List<AutoGroupInput>): Map<ShelfCompanionGroup, List<Long>> {
        val buckets = ShelfCompanionGroup.entries.associateWith { mutableListOf<Long>() }
        inputs.forEach { input ->
            val group = when {
                input.finished -> ShelfCompanionGroup.FINISHED
                input.hasProgress -> ShelfCompanionGroup.READING
                else -> ShelfCompanionGroup.UNREAD
            }
            buckets.getValue(group).add(input.bookId)
        }
        return buckets.mapValues { (_, ids) -> ids.toList() }
    }

    /**
     * 「读完」近似判定：`(chapterIndex + 1) * 100 >= totalChapter * 99`。
     *
     * 章节索引从 0 起，所以读到第 totalChapter 章时比例为 100%。
     * `totalChapter <= 0`（目录未缓存 / 未知）一律视为未读完，避免把新书误判为读完。
     *
     * 该表达式与 `BookDao.observeShelfStates` 里的 SQL 判定保持一致，改一处要同步改另一处。
     */
    fun approxFinished(chapterIndex: Int, totalChapter: Int): Boolean {
        if (totalChapter <= 0) return false
        return (chapterIndex + 1) * 100 >= totalChapter * FINISHED_PERCENT
    }
}
