package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 阅读 / 播放进度实体（Room）。
 *
 * 记录每个条目最后阅读的章节和位置。
 * 用 sourceId + itemId 唯一标识（联合唯一索引）。
 */
@Entity(
    tableName = "progress",
    indices = [Index(value = ["sourceId", "itemId"], unique = true)],
)
data class ProgressEntity(
    @PrimaryKey(autoGenerate = true)
    val progressId: Long = 0,
    /** 源 ID */
    val sourceId: String,
    /** 源内条目 ID */
    val itemId: String,
    /** 条目类型（novel/comic/video）——最近阅读按类型过滤用 */
    val itemType: String = "",
    /** 条目标题（书名/漫名/节目名）——书不在书架时最近阅读也能显示正确标题 */
    val itemTitle: String = "",
    /** 最后阅读的章节 URL */
    val chapterUrl: String,
    /** 最后阅读的章节标题 */
    val chapterTitle: String,
    /** 章节索引 */
    val chapterIndex: Int = 0,
    /** 小说：正文内滚动位置（字符偏移） */
    val textPosition: Int = 0,
    /** 视频：播放进度（毫秒） */
    val playPositionMs: Long = 0,
    /** 更新时间戳 */
    val updatedAt: Long = System.currentTimeMillis(),
)
