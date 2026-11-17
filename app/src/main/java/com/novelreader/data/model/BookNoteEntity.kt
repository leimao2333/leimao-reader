package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书籍备注实体（Room，C7）。
 *
 * 一本书一条备注，`bookId` 直接做主键（与 books.bookId 对应，不做外键级联：
 * 书架删除是批量 SQL，级联约束在有未提交事务时容易报错，清理交给 ShelfPurger 显式处理）。
 */
@Entity(tableName = "book_notes")
data class BookNoteEntity(
    /** 对应的书架条目 ID（books.bookId） */
    @PrimaryKey
    val bookId: Long,
    /** 备注正文 */
    val note: String,
    /** 最后修改时间戳 */
    val updatedAt: Long = System.currentTimeMillis(),
)
