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
        itemType: String,
        itemTitle: String,
    ) {
        progressDao.insert(
            ProgressEntity(
                sourceId = sourceId,
                itemId = itemId,
                itemType = itemType,
                itemTitle = itemTitle,
                chapterUrl = chapterUrl,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                textPosition = textPosition,
                playPositionMs = playPositionMs,
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
        )
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
                type = SourceType.fromString(row.type ?: "") ?: SourceType.NOVEL,
                detailUrl = row.detailUrl ?: row.itemId,
                chapterTitle = row.chapterTitle,
                chapterIndex = row.chapterIndex,
                updatedAt = row.updatedAt,
            )
        }
    }
}
