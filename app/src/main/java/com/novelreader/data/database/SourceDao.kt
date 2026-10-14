package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.SourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 源 DAO。
 */
@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY createdAt DESC")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE type = :type ORDER BY createdAt DESC")
    suspend fun getByType(type: String): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Delete
    suspend fun delete(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 批量删除源（单条 SQL，只触发一次 invalidation）。
     *
     * v1.5.5.2 起用于「一键删除失效源」：循环 deleteById 会触发 N 次 Room invalidation，
     * 与 observeAll() 的 Flow 重新查询并发，导致 Cursor 读失效（Couldn't read row from
     * CursorWindow）闪退。改为单条 DELETE ... WHERE id IN (...) 只触发一次。
     */
    @Query("DELETE FROM sources WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
