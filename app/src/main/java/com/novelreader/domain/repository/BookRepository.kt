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

    // ===== 分组管理（v1.3.0.0） =====

    /** 设置条目分组（空串 = 移出分组） */
    suspend fun setBookGroup(item: MediaItem, group: String)

    /** 所有已用分组名 */
    fun observeGroupNames(): Flow<List<String>>

    /** 更换书架条目封面（v1.2.3.0 长按书更换封面） */
    suspend fun updateBookCover(item: MediaItem, coverUrl: String)

    /** 重命名分组 */
    suspend fun renameGroup(oldName: String, newName: String)

    /** 删除分组（组内条目变为未分组） */
    suspend fun deleteGroup(name: String)
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
        /** 视频：总时长（毫秒，0 = 未知）——「继续观看」进度条分母（v1.3.4.0） */
        durationMs: Long = 0,
        itemType: String = "",
        itemTitle: String = "",
        itemCover: String? = null,
        itemAuthor: String? = null,
        pageIndex: Int = 0,
        totalChapter: Int = 0,
    )

    /** 读取进度 */
    suspend fun getProgress(sourceId: String, itemId: String): ReadingProgress?

    /** 删除某条阅读记录（最近阅读长按移除） */
    suspend fun removeProgress(sourceId: String, itemId: String)

    /** 清空全部阅读/播放历史（阅读历史页「清空全部」） */
    suspend fun clearAllProgress()

    /**
     * 最近阅读列表（按更新时间倒序）。
     *
     * @param type 类型过滤；null = 全部类型
     */
    suspend fun recentReadings(type: com.novelreader.domain.model.SourceType? = null):
        List<com.novelreader.domain.model.RecentReading>
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
    /** 视频：总时长（毫秒，0 = 未知） */
    val durationMs: Long = 0,
    /** 翻页模式：上次退出时的页码（0 起） */
    val pageIndex: Int = 0,
    /** 最后更新时间戳（书架按最近阅读排序用） */
    val updatedAt: Long = 0L,
    /** 章节总数（0 = 未知） */
    val totalChapter: Int = 0,
)
