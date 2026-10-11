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
}
