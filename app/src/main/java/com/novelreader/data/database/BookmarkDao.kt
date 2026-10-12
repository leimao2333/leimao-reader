package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书签 DAO（v1.1.0）。
 */
@Dao
interface BookmarkDao {

    /** 某本书的书签（按章节顺序） */
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE sourceId = :sourceId AND itemId = :itemId
        ORDER BY chapterIndex ASC, textPosition ASC
        """
    )
    fun observeForBook(sourceId: String, itemId: String): Flow<List<BookmarkEntity>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE sourceId = :sourceId AND itemId = :itemId
        ORDER BY chapterIndex ASC, textPosition ASC
        """
    )
    suspend fun listForBook(sourceId: String, itemId: String): List<BookmarkEntity>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE sourceId = :sourceId AND itemId = :itemId AND chapterUrl = :chapterUrl
        ORDER BY textPosition ASC
        """
    )
    suspend fun listForChapter(sourceId: String, itemId: String, chapterUrl: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks")
    suspend fun getAll(): List<com.novelreader.data.model.BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE bookmarkId = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        DELETE FROM bookmarks
        WHERE sourceId = :sourceId AND itemId = :itemId AND chapterUrl = :chapterUrl AND textPosition = :textPosition
        """
    )
    suspend fun deleteAt(sourceId: String, itemId: String, chapterUrl: String, textPosition: Int)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun countForBook(sourceId: String, itemId: String): Int
}
