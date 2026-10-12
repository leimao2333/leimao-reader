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
        // 已收藏的条目再次加入时保留原 bookId 与分组，避免 REPLACE 覆盖丢失
        val existing = bookDao.get(item.sourceId, item.id)
        bookDao.insert(
            BookEntity(
                bookId = existing?.bookId ?: 0,
                sourceId = item.sourceId,
                itemId = item.id,
                title = item.title,
                cover = item.cover,
                author = item.author,
                desc = item.desc,
                type = item.type.name.lowercase(),
                detailUrl = item.detailUrl,
                groupName = item.groupName.ifBlank { existing?.groupName.orEmpty() },
            )
        )
    }

    override suspend fun removeFromBookshelf(item: MediaItem) {
        bookDao.delete(item.sourceId, item.id)
    }

    // ===== 分组管理（v1.3.0） =====

    override suspend fun setBookGroup(item: MediaItem, group: String) {
        bookDao.setGroup(item.sourceId, item.id, group.trim())
    }

    override fun observeGroupNames(): Flow<List<String>> = bookDao.observeGroupNames()

    override suspend fun renameGroup(oldName: String, newName: String) {
        bookDao.renameGroup(oldName.trim(), newName.trim())
    }

    override suspend fun deleteGroup(name: String) {
        bookDao.clearGroup(name.trim())
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
    addedAt = addedAt,
    groupName = groupName,
)
