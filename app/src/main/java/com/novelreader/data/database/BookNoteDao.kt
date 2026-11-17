package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.BookNoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书籍备注 DAO（C7）。
 *
 * 一本书只有一条备注，写入统一走 [upsert]（bookId 主键冲突即替换）。
 */
@Dao
interface BookNoteDao {

    /** 观察单本书的备注（无备注时发射空列表，仓库层取 firstOrNull） */
    @Query("SELECT * FROM book_notes WHERE bookId = :bookId LIMIT 1")
    fun observe(bookId: Long): Flow<List<BookNoteEntity>>

    /** 一次性读取单本书的备注 */
    @Query("SELECT * FROM book_notes WHERE bookId = :bookId LIMIT 1")
    suspend fun get(bookId: Long): BookNoteEntity?

    /** 写入 / 覆盖备注 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookNoteEntity)

    /** 删除某本书的备注 */
    @Query("DELETE FROM book_notes WHERE bookId = :bookId")
    suspend fun delete(bookId: Long)

    /** 批量删除备注（删书时一并清理用） */
    @Query("DELETE FROM book_notes WHERE bookId IN (:bookIds)")
    suspend fun deleteAll(bookIds: List<Long>)
}
