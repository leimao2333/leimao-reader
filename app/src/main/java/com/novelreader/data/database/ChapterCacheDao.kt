package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.ChapterCacheEntity

/**
 * 章节缓存 DAO（#30）。
 */
@Dao
interface ChapterCacheDao {

    /** 查某本书某章的缓存 */
    @Query(
        """
        SELECT * FROM chapter_cache
        WHERE sourceId = :sourceId AND itemId = :itemId AND chapterIndex = :chapterIndex
        LIMIT 1
        """
    )
    suspend fun get(sourceId: String, itemId: String, chapterIndex: Int): ChapterCacheEntity?

    /** 查某本书全部已缓存章节的索引（用于目录标记「已缓存」） */
    @Query(
        """
        SELECT chapterIndex FROM chapter_cache
        WHERE sourceId = :sourceId AND itemId = :itemId
        """
    )
    suspend fun cachedIndices(sourceId: String, itemId: String): List<Int>

    /** 某本书已缓存章节数 */
    @Query(
        """
        SELECT COUNT(*) FROM chapter_cache
        WHERE sourceId = :sourceId AND itemId = :itemId
        """
    )
    suspend fun countOf(sourceId: String, itemId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChapterCacheEntity)

    /** 删除某本书某章缓存 */
    @Query(
        """
        DELETE FROM chapter_cache
        WHERE sourceId = :sourceId AND itemId = :itemId AND chapterIndex = :chapterIndex
        """
    )
    suspend fun deleteChapter(sourceId: String, itemId: String, chapterIndex: Int)

    /** 删除某本书全部缓存 */
    @Query("DELETE FROM chapter_cache WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun deleteBook(sourceId: String, itemId: String)

    /** 清空全部缓存 */
    @Query("DELETE FROM chapter_cache")
    suspend fun clearAll()

    /** 缓存总占用（字节） */
    @Query("SELECT IFNULL(SUM(size), 0) FROM chapter_cache")
    suspend fun totalSize(): Long

    /** 缓存章节总数 */
    @Query("SELECT COUNT(*) FROM chapter_cache")
    suspend fun totalCount(): Int

    /** 缓存管理列表：按书聚合，返回每本书已缓存章节数、占用、最近缓存时间 */
    @Query(
        """
        SELECT sourceId, itemId, itemTitle, itemType,
               COUNT(*) AS chapterCount, IFNULL(SUM(size), 0) AS totalSize,
               MAX(cachedAt) AS lastCachedAt
        FROM chapter_cache
        GROUP BY sourceId, itemId
        ORDER BY lastCachedAt DESC
        """
    )
    suspend fun cacheBooks(): List<BookCacheRow>
}

/**
 * 缓存管理列表投影：按书聚合。
 */
data class BookCacheRow(
    val sourceId: String,
    val itemId: String,
    val itemTitle: String?,
    val itemType: String?,
    val chapterCount: Int,
    val totalSize: Long,
    val lastCachedAt: Long,
)
