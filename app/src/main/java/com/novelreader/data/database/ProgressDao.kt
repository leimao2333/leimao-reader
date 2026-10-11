package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.ProgressEntity

/**
 * 阅读/播放进度 DAO。
 */
@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress WHERE sourceId = :sourceId AND itemId = :itemId LIMIT 1")
    suspend fun get(sourceId: String, itemId: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun delete(sourceId: String, itemId: String)
}
