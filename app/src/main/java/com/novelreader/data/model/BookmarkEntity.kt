package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书签实体（Room，v1.1.0 新增）。
 *
 * 记录阅读过程中用户手动标记的位置（章节 + 章节内位置 + 摘录），
 * 支持书签列表查看与一键跳回。
 */
@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["sourceId", "itemId"]),
        Index(value = ["sourceId", "itemId", "chapterUrl", "textPosition"], unique = true),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val bookmarkId: Long = 0,
    val sourceId: String,
    val itemId: String,
    val itemTitle: String = "",
    val itemType: String = "",
    val chapterIndex: Int = 0,
    val chapterUrl: String = "",
    val chapterTitle: String = "",
    /** 章节内位置（滚动模式=比例 0~10000，翻页模式=页码） */
    val textPosition: Int = 0,
    /** 摘录片段（便于列表里辨认） */
    val snippet: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
