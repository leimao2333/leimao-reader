package com.novelreader.domain.repository

import com.novelreader.domain.model.MediaItem

/**
 * 章节缓存仓库接口（单章缓存 + 缓存管理，#30）。
 *
 * 小说 / 漫画 / 视频通用：按 sourceId + itemId + chapterIndex 缓存已解析内容，
 * 支持离线读取、缓存进度标记与缓存清理。
 */
interface ChapterCacheRepository {

    /** 读取某章缓存；不存在返回 null */
    suspend fun getCached(sourceId: String, itemId: String, chapterIndex: Int): String?

    /** 某本书已缓存章节索引集合（目录标记「已缓存」） */
    suspend fun cachedIndices(sourceId: String, itemId: String): Set<Int>

    /** 某本书已缓存章节数 */
    suspend fun cachedCount(sourceId: String, itemId: String): Int

    /** 写入/更新某章缓存 */
    suspend fun saveCache(
        item: MediaItem,
        chapterIndex: Int,
        chapterTitle: String,
        chapterUrl: String,
        content: String,
    )

    /** 删除某本书某章缓存 */
    suspend fun removeChapter(sourceId: String, itemId: String, chapterIndex: Int)

    /** 删除某本书全部缓存 */
    suspend fun removeBook(sourceId: String, itemId: String)

    /** 清空全部缓存 */
    suspend fun clearAll()

    /** 缓存总占用（字节） */
    suspend fun totalSize(): Long

    /** 缓存章节总数 */
    suspend fun totalCount(): Int

    /** 缓存管理列表：按书聚合 */
    suspend fun cacheBooks(): List<BookCacheSummary>
}

/**
 * 缓存管理列表项（按书聚合）。
 */
data class BookCacheSummary(
    val sourceId: String,
    val itemId: String,
    val itemTitle: String,
    val itemType: String,
    val chapterCount: Int,
    val totalSize: Long,
    val lastCachedAt: Long,
)
