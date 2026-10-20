package com.novelreader.data.repository

import com.novelreader.data.database.BookDao
import com.novelreader.data.model.BookEntity
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.TagBehaviorRecorder
import com.novelreader.domain.repository.TagSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 书架仓库实现（Room 持久化）。
 *
 * v1.8.1.3：收藏成功后把行为回流到本地标签层（[TagBehaviorRecorder]），
 * 行为写库失败**不影响收藏本身**，所以整段用 runCatching 兜住。
 */
class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val behaviorRecorder: TagBehaviorRecorder? = null,
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
                requestOptions = item.requestOptions,
                groupName = item.groupName.ifBlank { existing?.groupName.orEmpty() },
            )
        )
        // 强信号：用户主动收藏，给这部作品已有标签的置信度加权
        runCatching { behaviorRecorder?.onBehavior(item, TagSignal.COLLECT) }
    }

    override suspend fun removeFromBookshelf(item: MediaItem) {
        bookDao.delete(item.sourceId, item.id)
    }

    override suspend fun removeFromBookshelfBatch(items: List<MediaItem>) {
        if (items.isEmpty()) return
        // Room 的 IN 列表有变量上限（SQLite 999），按 500 分块更稳
        items.map { "${it.sourceId}:${it.id}" }.chunked(500).forEach { keys ->
            if (keys.isNotEmpty()) bookDao.deleteByKeys(keys)
        }
    }

    override suspend fun updateBookCover(item: MediaItem, coverUrl: String) {
        bookDao.updateCover(item.sourceId, item.id, coverUrl)
    }

    // ===== 分组管理（v1.3.0.0） =====

    override suspend fun setBookGroup(item: MediaItem, group: String) {
        bookDao.setGroup(item.sourceId, item.id, group.trim())
    }

    override suspend fun setBookGroupBatch(items: List<MediaItem>, group: String) {
        if (items.isEmpty()) return
        val g = group.trim()
        items.map { "${it.sourceId}:${it.id}" }.chunked(500).forEach { keys ->
            if (keys.isNotEmpty()) bookDao.setGroupByKeys(keys, g)
        }
    }

    override fun observeGroupNames(type: SourceType): Flow<List<String>> =
        bookDao.observeGroupNames(type.name.lowercase())

    override suspend fun renameGroup(type: SourceType, oldName: String, newName: String) {
        bookDao.renameGroup(type.name.lowercase(), oldName.trim(), newName.trim())
    }

    override suspend fun deleteGroup(type: SourceType, name: String) {
        bookDao.clearGroup(type.name.lowercase(), name.trim())
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
    requestOptions = requestOptions,
    sourceId = sourceId,
    addedAt = addedAt,
    groupName = groupName,
)
