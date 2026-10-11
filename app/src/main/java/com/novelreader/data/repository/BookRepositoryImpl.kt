package com.novelreader.data.repository

import com.novelreader.data.database.BookDao
import com.novelreader.data.model.BookEntity
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 书架仓库实现（Room 持久化）。
 */
class BookRepositoryImpl(
    private val bookDao: BookDao,
) : BookRepository {

    override fun observeBookshelf(): Flow<List<MediaItem>> =
        bookDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeBookshelfByType(type: SourceType): Flow<List<MediaItem>> =
        bookDao.observeByType(type.name.lowercase()).map { list -> list.map { it.toDomain() } }

    override suspend fun isCollected(item: MediaItem): Boolean =
        bookDao.get(item.sourceId, item.id) != null

    override suspend fun addToBookshelf(item: MediaItem) {
        bookDao.insert(
            BookEntity(
                sourceId = item.sourceId,
                itemId = item.id,
                title = item.title,
                cover = item.cover,
                author = item.author,
                desc = item.desc,
                type = item.type.name.lowercase(),
                detailUrl = item.detailUrl,
            )
        )
    }

    override suspend fun removeFromBookshelf(item: MediaItem) {
        bookDao.delete(item.sourceId, item.id)
    }
}

/** BookEntity -> MediaItem */
fun BookEntity.toDomain(): MediaItem = MediaItem(
    id = itemId,
    title = title,
    cover = cover,
    author = author,
    desc = desc,
    type = SourceType.fromString(type) ?: SourceType.NOVEL,
    detailUrl = detailUrl,
    sourceId = sourceId,
)
