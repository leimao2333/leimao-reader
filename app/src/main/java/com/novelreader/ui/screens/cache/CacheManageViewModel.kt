package com.novelreader.ui.screens.cache

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.data.cache.VideoPrefetchCache
import com.novelreader.domain.repository.BookCacheSummary
import com.novelreader.domain.repository.ChapterCacheRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 缓存管理 ViewModel（#30）。
 *
 * 展示本地已缓存章节（按书聚合）+ 视频预加载（v1.6.0.0），支持清理单本 / 全部 / 视频预加载。
 */
class CacheManageViewModel(
    private val chapterCacheRepository: ChapterCacheRepository,
    private val videoPrefetchCache: VideoPrefetchCache,
    private val preferences: PreferencesManager,
    /**
     * 视频播放缓存（边播边存，v1.7.7.1）。
     *
     * 默认走真实的 media3 `SimpleCache`；单测注入假实现，避免依赖 Android Context。
     */
    private val videoPlayCache: com.novelreader.data.cache.VideoPlayCacheStore =
        com.novelreader.data.cache.VideoMediaCacheStore,
    /**
     * 磁盘缓存统计/清理跑在哪个调度器上。
     *
     * 默认 IO；单测注入 `UnconfinedTestDispatcher` —— 真实的 `Dispatchers.IO`
     * 不受 `runTest` 的虚拟时间控制，断言时任务可能还没跑完（v1.7.7.1 踩过）。
     */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        kotlinx.coroutines.Dispatchers.IO,
    /**
     * 漫画章节图片缓存（v1.8.2.0 漫画管理）。
     *
     * 漫画的离线缓存落在 `downloads/comic_cache/`（每章一个目录、里面是图片文件），
     * 和章节正文缓存是两套东西 —— 正文缓存表里没有图片字节，所以必须单独统计/清理。
     * 传 null 表示当前环境不管理漫画缓存（单测默认如此）。
     */
    private val downloadManager: com.novelreader.core.download.DownloadManager? = null,
) : ViewModel() {

    private val _books = MutableStateFlow<List<BookCacheSummary>>(emptyList())
    val books: StateFlow<List<BookCacheSummary>> = _books.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _totalSize = MutableStateFlow(0L)
    val totalSize: StateFlow<Long> = _totalSize.asStateFlow()

    /** 视频预加载总占用（字节，v1.6.0.0） */
    private val _videoPrefetchSize = MutableStateFlow(0L)
    val videoPrefetchSize: StateFlow<Long> = _videoPrefetchSize.asStateFlow()

    /** 视频预加载条目数（v1.6.0.0） */
    private val _videoPrefetchCount = MutableStateFlow(0)
    val videoPrefetchCount: StateFlow<Int> = _videoPrefetchCount.asStateFlow()

    /**
     * 视频播放缓存占用（边播边存的分片，v1.7.7.1）。
     *
     * 和上面的「视频预加载」是两码事：预加载存的是**播放地址/解析结果**（很小），
     * 这里存的是**真实的视频分片**（几百 MB 级别），也是唯一需要用户主动清理的。
     */
    private val _videoPlayCacheSize = MutableStateFlow(0L)
    val videoPlayCacheSize: StateFlow<Long> = _videoPlayCacheSize.asStateFlow()

    // ===== 漫画缓存（v1.8.2.0）=====

    /** 按漫画聚合的离线缓存清单（章节图片） */
    private val _comicCaches = MutableStateFlow<List<com.novelreader.core.download.ComicCacheSummary>>(emptyList())
    val comicCaches: StateFlow<List<com.novelreader.core.download.ComicCacheSummary>> = _comicCaches.asStateFlow()

    /** 漫画缓存总占用（字节） */
    private val _comicCacheSize = MutableStateFlow(0L)
    val comicCacheSize: StateFlow<Long> = _comicCacheSize.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    /** 刷新缓存统计 */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _books.value = runCatching { chapterCacheRepository.cacheBooks() }.getOrDefault(emptyList())
            _totalCount.value = runCatching { chapterCacheRepository.totalCount() }.getOrDefault(0)
            _totalSize.value = runCatching { chapterCacheRepository.totalSize() }.getOrDefault(0L)
            _videoPrefetchSize.value = runCatching { videoPrefetchCache.totalSize() }.getOrDefault(0L)
            _videoPrefetchCount.value = runCatching { videoPrefetchCache.count() }.getOrDefault(0)
            // 读磁盘缓存占用（SimpleCache.cacheSpace 是内存计数，但保险起见放 IO）
            _videoPlayCacheSize.value = kotlinx.coroutines.withContext(ioDispatcher) {
                runCatching { videoPlayCache.sizeBytes() }.getOrDefault(0L)
            }
            // 漫画缓存要遍历目录树（可能几万个图片文件），必须离开主线程
            val comic = kotlinx.coroutines.withContext(ioDispatcher) {
                val mgr = downloadManager
                if (mgr == null) {
                    null
                } else {
                    runCatching { mgr.comicCacheSummaries() to mgr.comicCacheTotalSize() }.getOrNull()
                }
            }
            _comicCaches.value = comic?.first ?: emptyList()
            _comicCacheSize.value = comic?.second ?: 0L
            _loading.value = false
        }
    }

    /** 删除某本书全部缓存 */
    fun removeBook(book: BookCacheSummary) {
        viewModelScope.launch {
            runCatching {
                chapterCacheRepository.removeBook(book.sourceId, book.itemId)
            }.onSuccess {
                _message.value = "已清理《${book.itemTitle}》缓存"
                refresh()
            }.onFailure {
                _message.value = "清理失败：${it.message}"
            }
        }
    }

    /** 清空全部章节缓存 */
    fun clearAll() {
        viewModelScope.launch {
            runCatching { chapterCacheRepository.clearAll() }
                .onSuccess {
                    _message.value = "已清空全部章节缓存"
                    refresh()
                }
                .onFailure {
                    _message.value = "清空失败：${it.message}"
                }
        }
    }

    /** 清空视频预加载（v1.6.0.0） */
    fun clearVideoPrefetch() {
        viewModelScope.launch {
            val size = _videoPrefetchSize.value
            runCatching { videoPrefetchCache.clear() }
                .onSuccess {
                    _message.value = "已清空视频预加载（${formatSize(size)}）"
                    refresh()
                }
                .onFailure {
                    _message.value = "清空失败：${it.message}"
                }
        }
    }

    /**
     * 清空视频播放缓存（边播边存的分片，v1.7.7.1）。
     *
     * 清完会立即重建缓存目录，后续播放照常边播边存 —— 不是「关掉缓存」。
     */
    fun clearVideoPlayCache() {
        viewModelScope.launch {
            val before = _videoPlayCacheSize.value
            val freed = kotlinx.coroutines.withContext(ioDispatcher) {
                runCatching { videoPlayCache.clear() }.getOrDefault(0L)
            }
            // freed 可能因为计数时机比 UI 显示的小而偏低，取两者较大值以免提示「清空 0 B」
            val shown = if (freed > 0L) freed else before
            _message.value = "已清空视频播放缓存（${formatSize(shown)}）"
            refresh()
        }
    }

    /** 一键清空（章节缓存 + 视频预加载 + 视频播放缓存，v1.6.0.0 / v1.7.7.1） */
    fun clearAllIncludingPrefetch() {
        viewModelScope.launch {
            val size = _videoPrefetchSize.value
            val playSize = _videoPlayCacheSize.value
            val comicSize = _comicCacheSize.value
            runCatching {
                chapterCacheRepository.clearAll()
                videoPrefetchCache.clear()
            }.onSuccess {
                kotlinx.coroutines.withContext(ioDispatcher) {
                    runCatching { videoPlayCache.clear() }
                    runCatching { downloadManager?.clearAllComicCache() }
                }
                _message.value = "已清空全部缓存（含视频 ${formatSize(size + playSize)}、漫画 ${formatSize(comicSize)}）"
                refresh()
            }.onFailure {
                _message.value = "清空失败：${it.message}"
            }
        }
    }

    /** 清理单本漫画的离线缓存（v1.8.2.0） */
    fun removeComicCache(entry: com.novelreader.core.download.ComicCacheSummary) {
        viewModelScope.launch {
            val mgr = downloadManager ?: return@launch
            val freed = kotlinx.coroutines.withContext(ioDispatcher) {
                runCatching { mgr.clearComicCacheByDir(entry.dirName) }.getOrDefault(0L)
            }
            _message.value = "已清理《${entry.title}》漫画缓存（${formatSize(freed)}）"
            refresh()
        }
    }

    /** 清空全部漫画缓存（v1.8.2.0） */
    fun clearComicCache() {
        viewModelScope.launch {
            val mgr = downloadManager ?: return@launch
            val before = _comicCacheSize.value
            val freed = kotlinx.coroutines.withContext(ioDispatcher) {
                runCatching { mgr.clearAllComicCache() }.getOrDefault(0L)
            }
            val shown = if (freed > 0L) freed else before
            _message.value = "已清空漫画缓存（${formatSize(shown)}）"
            refresh()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 格式化字节数为可读字符串 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / 1024f / 1024f)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}
