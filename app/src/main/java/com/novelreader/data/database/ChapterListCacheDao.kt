package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.ChapterListCacheEntity

/**
 * 目录缓存 DAO（v1.8.8.5）。
 *
 * 一本书一行，整份目录以 JSON 存放；按 `sourceId + itemId` 定位。
 */
@Dao
interface ChapterListCacheDao {

    /** 取某本书的目录缓存 */
    @Query(
        """
        SELECT * FROM chapter_list_cache
        WHERE sourceId = :sourceId AND itemId = :itemId
        LIMIT 1
        """
    )
    suspend fun get(sourceId: String, itemId: String): ChapterListCacheEntity?

    /** 写入 / 覆盖目录缓存 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ChapterListCacheEntity)

    /** 清掉某本书的目录缓存 */
    @Query("DELETE FROM chapter_list_cache WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun delete(sourceId: String, itemId: String)

    /** 清空全部目录缓存 */
    @Query("DELETE FROM chapter_list_cache")
    suspend fun clearAll()
}
