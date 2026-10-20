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

    /**
     * 批量从书架移除（v1.8.3.0 待办 #5）。
     *
     * 走单条批量 SQL，不要循环调 [removeFromBookshelf]：删 100 本会开 100 次事务。
     */
    suspend fun removeFromBookshelfBatch(items: List<MediaItem>)

    // ===== 分组管理（v1.3.0.0） =====

    /** 设置条目分组（空串 = 移出分组） */
    suspend fun setBookGroup(item: MediaItem, group: String)

    /** 批量设置分组（空串 = 移出分组；v1.8.3.0 待办 #5） */
    suspend fun setBookGroupBatch(items: List<MediaItem>, group: String)

    /**
     * 某类型下所有已用分组名。
     *
     * v1.8.3.0 待办 #6：小说与漫画分组完全分开，按内容类型过滤。
     */
    fun observeGroupNames(type: com.novelreader.domain.model.SourceType): Flow<List<String>>

    /** 更换书架条目封面（v1.2.3.0 长按书更换封面） */
    suspend fun updateBookCover(item: MediaItem, coverUrl: String)

    /** 重命名某类型下的分组 */
    suspend fun renameGroup(type: com.novelreader.domain.model.SourceType, oldName: String, newName: String)

    /** 删除某类型下的分组（组内条目变为未分组） */
    suspend fun deleteGroup(type: com.novelreader.domain.model.SourceType, name: String)
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
     * 按内容类型清空进度（v1.8.1.2）。
     *
     * 视频首页「继续观看」的一键清空只用它：只清该类型，小说 / 漫画的阅读进度不受影响。
     */
    suspend fun clearProgressByType(type: String)

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
