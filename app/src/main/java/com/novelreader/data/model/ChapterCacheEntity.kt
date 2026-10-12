package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 章节缓存实体（Room）。
 *
 * 单章缓存：把已解析的正文持久化，离线或源失效时仍可阅读（#30）。
 * 用 sourceId + itemId + chapterIndex 唯一标识。
 */
@Entity(
    tableName = "chapter_cache",
    indices = [Index(value = ["sourceId", "itemId", "chapterIndex"], unique = true)],
)
data class ChapterCacheEntity(
    @PrimaryKey(autoGenerate = true)
    val cacheId: Long = 0,
    /** 源 ID */
    val sourceId: String,
    /** 源内条目 ID（书名唯一标识） */
    val itemId: String,
    /** 条目标题，用于缓存管理展示 */
    val itemTitle: String = "",
    /** 条目类型（novel/comic/video） */
    val itemType: String = "",
    /** 章节索引 */
    val chapterIndex: Int,
    /** 章节标题 */
    val chapterTitle: String = "",
    /** 章节 URL（源内定位） */
    val chapterUrl: String = "",
    /** 已解析的正文内容（小说=文本；漫画=图片地址换行拼接；视频=播放地址） */
    val content: String = "",
    /** 内容字节大小（估算，用于缓存占用统计） */
    val size: Long = 0,
    /** 缓存时间戳 */
    val cachedAt: Long = System.currentTimeMillis(),
)
