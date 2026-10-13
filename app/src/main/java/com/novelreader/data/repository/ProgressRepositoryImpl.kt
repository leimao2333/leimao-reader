package com.novelreader.data.repository

import com.novelreader.data.database.ProgressDao
import com.novelreader.data.model.ProgressEntity
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.domain.repository.ReadingProgress

/**
 * 阅读/播放进度仓库实现（Room 持久化）。
 */
class ProgressRepositoryImpl(
    private val progressDao: ProgressDao,
) : ProgressRepository {

    override suspend fun saveProgress(
        sourceId: String,
        itemId: String,
        chapterUrl: String,
        chapterTitle: String,
        chapterIndex: Int,
        textPosition: Int,
        playPositionMs: Long,
        durationMs: Long,
        itemType: String,
        itemTitle: String,
        itemCover: String?,
        itemAuthor: String?,
        pageIndex: Int,
        totalChapter: Int,
    ) {
        progressDao.insert(
            ProgressEntity(
                sourceId = sourceId,
                itemId = itemId,
                // 统一存小写：与 books.type、历史迁移里的数据保持一致。
                // 调用方传的是 SourceType.name（大写），此前直接落库导致
                // 「按类型查最近阅读」因大小写不匹配而恒为空。
                itemType = itemType.lowercase(),
                itemTitle = itemTitle,
                itemCover = itemCover,
                itemAuthor = itemAuthor,
                chapterUrl = chapterUrl,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                totalChapter = totalChapter,
                textPosition = textPosition,
                pageIndex = pageIndex,
                playPositionMs = playPositionMs,
                durationMs = durationMs,
            )
        )
    }

    override suspend fun getProgress(sourceId: String, itemId: String): ReadingProgress? {
        val entity = progressDao.get(sourceId, itemId) ?: return null
        return ReadingProgress(
            chapterUrl = entity.chapterUrl,
            chapterTitle = entity.chapterTitle,
            chapterIndex = entity.chapterIndex,
            textPosition = entity.textPosition,
            playPositionMs = entity.playPositionMs,
            durationMs = entity.durationMs,
            pageIndex = entity.pageIndex,
            updatedAt = entity.updatedAt,
            totalChapter = entity.totalChapter,
        )
    }

    override suspend fun removeProgress(sourceId: String, itemId: String) {
        progressDao.delete(sourceId, itemId)
    }

    override suspend fun recentReadings(type: SourceType?): List<RecentReading> {
        val typeKey = when (type) {
            SourceType.NOVEL -> "novel"
            SourceType.COMIC -> "comic"
            SourceType.VIDEO -> "video"
            null -> ""
        }
        return progressDao.recentReadings(typeKey).map { row ->
            RecentReading(
                sourceId = row.sourceId,
                itemId = row.itemId,
                title = row.title ?: row.chapterTitle,
                cover = row.cover,
                author = row.author,
                type = SourceType.fromString(row.type ?: "") ?: SourceType.NOVEL,
                detailUrl = row.detailUrl ?: row.itemId,
                chapterTitle = row.chapterTitle,
                chapterUrl = row.chapterUrl,
                chapterIndex = row.chapterIndex,
                totalChapter = row.totalChapter,
                playPositionMs = row.playPositionMs,
                durationMs = row.durationMs,
                updatedAt = row.updatedAt,
            )
        }
    }
}
