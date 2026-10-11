package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书架 / 收藏实体（Room）。
 *
 * 存储用户收藏的小说/漫画/视频条目。
 * 用 sourceId + itemId 唯一标识一个条目。
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val bookId: Long = 0,
    /** 源 ID */
    val sourceId: String,
    /** 源内条目 ID */
    val itemId: String,
    /** 标题 */
    val title: String,
    /** 封面 */
    val cover: String?,
    /** 作者 */
    val author: String?,
    /** 简介 */
    val desc: String?,
    /** 内容类型：novel / comic / video */
    val type: String,
    /** 详情地址 */
    val detailUrl: String,
    /** 收藏时间戳 */
    val addedAt: Long = System.currentTimeMillis(),
)
