package com.novelreader.domain.repository

import com.novelreader.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * 书架 / 收藏仓库接口。
 */
interface BookRepository {

    /** 观察整个书架 */
    fun observeBookshelf(): Flow<List<MediaItem>>

    /** 观察某类型书架 */
    fun observeBookshelfByType(type: com.novelreader.domain.model.SourceType): Flow<List<MediaItem>>

    /** 是否已收藏 */
    suspend fun isCollected(item: MediaItem): Boolean

    /** 添加到书架 */
    suspend fun addToBookshelf(item: MediaItem)

    /** 从书架移除 */
    suspend fun removeFromBookshelf(item: MediaItem)
}

/**
 * 阅读 / 播放进度仓库接口。
 */
interface ProgressRepository {

    /** 保存进度 */
    suspend fun saveProgress(
        sourceId: String,
        itemId: String,
        chapterUrl: String,
        chapterTitle: String,
        chapterIndex: Int = 0,
        textPosition: Int = 0,
        playPositionMs: Long = 0,
    )

    /** 读取进度 */
    suspend fun getProgress(sourceId: String, itemId: String): ReadingProgress?
}

/**
 * 阅读进度（Domain 层）。
 */
data class ReadingProgress(
    val chapterUrl: String,
    val chapterTitle: String,
    val chapterIndex: Int = 0,
    val textPosition: Int = 0,
    val playPositionMs: Long = 0,
)
