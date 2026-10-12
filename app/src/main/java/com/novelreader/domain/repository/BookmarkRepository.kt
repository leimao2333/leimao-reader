package com.novelreader.domain.repository

import com.novelreader.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * 书签（Domain 层模型）。
 */
data class Bookmark(
    val id: Long,
    val sourceId: String,
    val itemId: String,
    val itemTitle: String,
    val chapterIndex: Int,
    val chapterUrl: String,
    val chapterTitle: String,
    /** 章节内位置（滚动=比例 0~10000；翻页=页码） */
    val textPosition: Int,
    /** 摘录片段 */
    val snippet: String,
    val createdAt: Long,
)

/**
 * 书签仓库接口（v1.1.0）。
 */
interface BookmarkRepository {

    /** 观察某本书的书签列表 */
    fun observeForBook(sourceId: String, itemId: String): Flow<List<Bookmark>>

    /** 某本书的书签列表 */
    suspend fun listForBook(sourceId: String, itemId: String): List<Bookmark>

    /** 某章的书签列表 */
    suspend fun listForChapter(sourceId: String, itemId: String, chapterUrl: String): List<Bookmark>

    /** 新增书签；返回该书签 id */
    suspend fun add(
        item: MediaItem,
        chapterIndex: Int,
        chapterUrl: String,
        chapterTitle: String,
        textPosition: Int,
        snippet: String,
    ): Long

    /** 按 id 删除 */
    suspend fun removeById(id: Long)

    /** 删除某章某位置的书签（再次点击即取消） */
    suspend fun removeAt(sourceId: String, itemId: String, chapterUrl: String, textPosition: Int)

    /** 是否已存在该书签 */
    suspend fun exists(sourceId: String, itemId: String, chapterUrl: String, textPosition: Int): Boolean

    /** 某本书书签数量 */
    suspend fun count(sourceId: String, itemId: String): Int
}
