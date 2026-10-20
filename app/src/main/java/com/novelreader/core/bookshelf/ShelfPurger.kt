package com.novelreader.core.bookshelf

import com.novelreader.core.download.DownloadManager
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ChapterCacheRepository
import com.novelreader.domain.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架条目清除器（v1.8.3.0 待办 #4 / #5）。
 *
 * 「删除书籍」不只是删 `books` 表那一行：还要一并清掉
 * 章节缓存（`chapter_cache`）、阅读/播放进度（`progress`）与漫画图片缓存目录，
 * 否则重新收藏后会读回旧进度、旧缓存，磁盘也不会释放。
 *
 * 批量删除走仓储的批量 SQL（[BookRepository.removeFromBookshelfBatch] /
 * [ChapterCacheRepository.removeBooks]），不循环单条。
 */
class ShelfPurger(
    private val bookRepository: BookRepository,
    private val chapterCacheRepository: ChapterCacheRepository,
    private val progressRepository: ProgressRepository,
    private val downloadManager: DownloadManager? = null,
) {

    /**
     * 删除书架条目并清理其全部本地数据。
     *
     * @param items 待删除条目（空列表直接返回）
     */
    suspend fun purge(items: List<MediaItem>) {
        if (items.isEmpty()) return

        // 1) 书架行 —— 批量 SQL
        runCatching { bookRepository.removeFromBookshelfBatch(items) }

        // 2) 章节缓存（DB）—— 批量 SQL
        runCatching { chapterCacheRepository.removeBooks(items) }

        // 3) 阅读 / 播放进度
        items.forEach { item ->
            runCatching { progressRepository.removeProgress(item.sourceId, item.id) }
        }

        // 4) 漫画图片缓存目录（磁盘）—— 涉及文件 IO，放到 IO 线程
        val dm = downloadManager ?: return
        withContext(Dispatchers.IO) {
            items.filter { it.type == com.novelreader.domain.model.SourceType.COMIC }
                .forEach { item -> runCatching { dm.clearComicCache(item) } }
        }
    }

    /** 只清缓存与进度，保留书架条目（批量「清除已缓存章节」） */
    suspend fun purgeCacheOnly(items: List<MediaItem>) {
        if (items.isEmpty()) return
        runCatching { chapterCacheRepository.removeBooks(items) }
        val dm = downloadManager ?: return
        withContext(Dispatchers.IO) {
            items.filter { it.type == com.novelreader.domain.model.SourceType.COMIC }
                .forEach { item -> runCatching { dm.clearComicCache(item) } }
        }
    }
}
