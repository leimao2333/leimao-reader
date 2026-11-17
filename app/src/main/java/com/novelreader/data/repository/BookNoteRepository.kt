package com.novelreader.data.repository

import com.novelreader.data.database.BookNoteDao
import com.novelreader.data.model.BookNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 书籍备注仓库（C7）。
 *
 * 只暴露 domain 层的 [BookNote]，UI 不直接碰 Room 实体。
 */
interface BookNoteRepository {

    /** 观察某本书的备注（无备注发射 null） */
    fun observe(bookId: Long): Flow<BookNote?>

    /** 一次性读取某本书的备注 */
    suspend fun get(bookId: Long): BookNote?

    /**
     * 保存备注。
     *
     * 空白内容视为「清空备注」→ 直接删行，避免表里堆积空字符串记录。
     */
    suspend fun save(bookId: Long, note: String)

    /** 删除备注 */
    suspend fun delete(bookId: Long)
}

/** 备注（domain 投影） */
data class BookNote(
    val bookId: Long,
    val note: String,
    val updatedAt: Long,
)

/**
 * 书籍备注仓库实现（Room 持久化）。
 */
class BookNoteRepositoryImpl(
    private val dao: BookNoteDao,
) : BookNoteRepository {

    override fun observe(bookId: Long): Flow<BookNote?> =
        dao.observe(bookId).map { rows -> rows.firstOrNull()?.toDomain() }

    override suspend fun get(bookId: Long): BookNote? = dao.get(bookId)?.toDomain()

    override suspend fun save(bookId: Long, note: String) {
        if (note.isBlank()) {
            dao.delete(bookId)
            return
        }
        dao.upsert(
            BookNoteEntity(
                bookId = bookId,
                note = note,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun delete(bookId: Long) = dao.delete(bookId)

    private fun BookNoteEntity.toDomain() = BookNote(
        bookId = bookId,
        note = note,
        updatedAt = updatedAt,
    )
}
