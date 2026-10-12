package com.novelreader.data.repository

import com.novelreader.data.database.BookmarkDao
import com.novelreader.data.model.BookmarkEntity
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.Bookmark
import com.novelreader.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 书签仓库实现（Data 层）。
 */
class BookmarkRepositoryImpl(
    private val dao: BookmarkDao,
) : BookmarkRepository {

    override fun observeForBook(sourceId: String, itemId: String): Flow<List<Bookmark>> =
        dao.observeForBook(sourceId, itemId).map { list -> list.map { it.toDomain() } }

    override suspend fun listForBook(sourceId: String, itemId: String): List<Bookmark> =
        dao.listForBook(sourceId, itemId).map { it.toDomain() }

    override suspend fun listForChapter(
        sourceId: String,
        itemId: String,
        chapterUrl: String,
    ): List<Bookmark> = dao.listForChapter(sourceId, itemId, chapterUrl).map { it.toDomain() }

    override suspend fun add(
        item: MediaItem,
        chapterIndex: Int,
        chapterUrl: String,
        chapterTitle: String,
        textPosition: Int,
        snippet: String,
    ): Long = dao.insert(
        BookmarkEntity(
            sourceId = item.sourceId,
            itemId = item.id,
            itemTitle = item.title,
            itemType = typeName(item.type),
            chapterIndex = chapterIndex,
            chapterUrl = chapterUrl,
            chapterTitle = chapterTitle,
            textPosition = textPosition,
            snippet = snippet.take(120),
            createdAt = System.currentTimeMillis(),
        )
    )

    override suspend fun removeById(id: Long) = dao.deleteById(id)

    override suspend fun removeAt(
        sourceId: String,
        itemId: String,
        chapterUrl: String,
        textPosition: Int,
    ) = dao.deleteAt(sourceId, itemId, chapterUrl, textPosition)

    override suspend fun exists(
        sourceId: String,
        itemId: String,
        chapterUrl: String,
        textPosition: Int,
    ): Boolean = dao.listForChapter(sourceId, itemId, chapterUrl)
        .any { it.textPosition == textPosition }

    override suspend fun count(sourceId: String, itemId: String): Int =
        dao.countForBook(sourceId, itemId)

    private fun BookmarkEntity.toDomain() = Bookmark(
        id = bookmarkId,
        sourceId = sourceId,
        itemId = itemId,
        itemTitle = itemTitle,
        chapterIndex = chapterIndex,
        chapterUrl = chapterUrl,
        chapterTitle = chapterTitle,
        textPosition = textPosition,
        snippet = snippet,
        createdAt = createdAt,
    )

    private fun typeName(type: SourceType): String = when (type) {
        SourceType.NOVEL -> "novel"
        SourceType.COMIC -> "comic"
        SourceType.VIDEO -> "video"
    }
}
