package com.novelreader.data.repository

import com.novelreader.data.database.ChapterCacheDao
import com.novelreader.data.model.ChapterCacheEntity
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.BookCacheSummary
import com.novelreader.domain.repository.ChapterCacheRepository

/**
 * 章节缓存仓库实现（Data 层）。
 */
class ChapterCacheRepositoryImpl(
    private val dao: ChapterCacheDao,
) : ChapterCacheRepository {

    override suspend fun getCached(
        sourceId: String,
        itemId: String,
        chapterIndex: Int,
    ): String? = dao.get(sourceId, itemId, chapterIndex)?.content?.takeIf { it.isNotBlank() }

    override suspend fun cachedIndices(sourceId: String, itemId: String): Set<Int> =
        dao.cachedIndices(sourceId, itemId).toSet()

    override suspend fun cachedCount(sourceId: String, itemId: String): Int =
        dao.countOf(sourceId, itemId)

    override suspend fun saveCache(
        item: MediaItem,
        chapterIndex: Int,
        chapterTitle: String,
        chapterUrl: String,
        content: String,
    ) {
        if (content.isBlank()) return
        dao.insert(
            ChapterCacheEntity(
                sourceId = item.sourceId,
                itemId = item.id,
                itemTitle = item.title,
                itemType = typeName(item.type),
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                chapterUrl = chapterUrl,
                content = content,
                size = content.toByteArray(Charsets.UTF_8).size.toLong(),
                cachedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun removeChapter(sourceId: String, itemId: String, chapterIndex: Int) =
        dao.deleteChapter(sourceId, itemId, chapterIndex)

    override suspend fun removeBook(sourceId: String, itemId: String) =
        dao.deleteBook(sourceId, itemId)

    override suspend fun removeBooks(items: List<MediaItem>) {
        if (items.isEmpty()) return
        items.map { "${it.sourceId}:${it.id}" }.chunked(500).forEach { keys ->
            if (keys.isNotEmpty()) dao.deleteBooks(keys)
        }
    }

    override suspend fun clearAll() = dao.clearAll()

    override suspend fun totalSize(): Long = dao.totalSize()

    override suspend fun totalCount(): Int = dao.totalCount()

    override suspend fun cacheBooks(): List<BookCacheSummary> =
        dao.cacheBooks().map {
            BookCacheSummary(
                sourceId = it.sourceId,
                itemId = it.itemId,
                itemTitle = it.itemTitle.orEmpty(),
                itemType = it.itemType.orEmpty(),
                chapterCount = it.chapterCount,
                totalSize = it.totalSize,
                lastCachedAt = it.lastCachedAt,
            )
        }

    private fun typeName(type: SourceType): String = when (type) {
        SourceType.NOVEL -> "novel"
        SourceType.COMIC -> "comic"
        SourceType.VIDEO -> "video"
    }
}
