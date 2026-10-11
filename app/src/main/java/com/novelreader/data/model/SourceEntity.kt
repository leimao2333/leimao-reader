package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 源持久化实体（Room）。
 *
 * 存储已导入源的元信息和原始内容，App 重启后可恢复。
 */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    /** 内容类型：novel / comic / video */
    val type: String,
    val version: String,
    val author: String,
    val desc: String,
    val baseUrl: String,
    /** 原始格式：json / js */
    val format: String,
    /** 源原始内容（用于重建适配器） */
    val rawContent: String,
    val enabled: Boolean = true,
    /** 导入时间戳 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 智能分类 */
    val category: String = "",
)
