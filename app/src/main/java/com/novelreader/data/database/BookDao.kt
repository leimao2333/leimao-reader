package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书架（收藏）DAO。
 */
@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE type = :type ORDER BY addedAt DESC")
    fun observeByType(type: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE sourceId = :sourceId AND itemId = :itemId LIMIT 1")
    suspend fun get(sourceId: String, itemId: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Query("DELETE FROM books WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun delete(sourceId: String, itemId: String)

    @Delete
    suspend fun delete(book: BookEntity)
}
