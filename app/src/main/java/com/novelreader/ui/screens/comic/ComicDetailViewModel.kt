package com.novelreader.ui.screens.comic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.download.DownloadManager
import com.novelreader.core.download.DownloadState
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 漫画详情 ViewModel。
 */
class ComicDetailViewModel(
    private val contentRepository: ContentRepository,
    private val bookRepository: BookRepository,
    private val downloadManager: DownloadManager? = null,
    private val progressRepository: com.novelreader.domain.repository.ProgressRepository? = null,
) : ViewModel() {

    private val _detail = MutableStateFlow<MediaItem?>(null)
    val detail: StateFlow<MediaItem?> = _detail.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterItem>>(emptyList())
    val chapters: StateFlow<List<ChapterItem>> = _chapters.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _collected = MutableStateFlow(false)
    val collected: StateFlow<Boolean> = _collected.asStateFlow()

    private val _download = MutableStateFlow<DownloadState?>(null)
    val download: StateFlow<DownloadState?> = _download.asStateFlow()

    /**
     * 目录是否倒序展示（新话在前）。
     *
     * 只影响**展示顺序**：内部章节列表始终保持源顺序，
     * 「下一话 / 上一话」与阅读进度都按源顺序计算，避免倒序后翻页错乱。
     */
    private val _chapterDesc = MutableStateFlow(false)
    val chapterDesc: StateFlow<Boolean> = _chapterDesc.asStateFlow()

    fun toggleChapterOrder() {
        _chapterDesc.value = !_chapterDesc.value
    }

    fun load(item: MediaItem) {
        _detail.value = item
        viewModelScope.launch {
            _loading.value = true
            // 目录必须在详情之后取：详情规则可能写入 legado 变量
            // （部分源在 ruleBookInfo.name 里 `@put:{cid:$.comic_id}`），
            // 章节地址里的 `@get:{cid}` 依赖它；并行会导致目录拿到空 ID → 目录空白。
            val collectedDeferred = launch { loadCollected(item) }
            val detailed = loadDetail(item)
            loadChapters(detailed)
            collectedDeferred.join()
            _loading.value = false
        }
    }

    private suspend fun loadDetail(item: MediaItem): MediaItem =
        runCatching { contentRepository.detail(item) }
            .onSuccess { _detail.value = it }
            .getOrDefault(item)

    private suspend fun loadChapters(item: MediaItem) {
        runCatching { contentRepository.chapterList(item) }
            .onSuccess { _chapters.value = it }
    }

    private suspend fun loadCollected(item: MediaItem) {
        _collected.value = bookRepository.isCollected(item)
    }

    fun toggleCollect() {
        val item = _detail.value ?: return
        viewModelScope.launch {
            if (_collected.value) {
                bookRepository.removeFromBookshelf(item)
                _collected.value = false
            } else {
                bookRepository.addToBookshelf(item)
                _collected.value = true
            }
        }
    }

    /** 下载整部漫画（遍历所有章节收集图片） */
    fun downloadComic() {
        val item = _detail.value ?: return
        val manager = downloadManager ?: return
        val chapters = _chapters.value
        viewModelScope.launch {
            _download.value = DownloadState(isDownloading = true, progress = 0f)
            runCatching {
                val allImages = mutableListOf<String>()
                chapters.forEachIndexed { index, chapter ->
                    contentRepository.content(chapter).comicImages?.let { allImages.addAll(it) }
                    _download.value = DownloadState(
                        isDownloading = true,
                        progress = if (chapters.isNotEmpty()) (index + 1f) / chapters.size else 1f,
                    )
                }
                allImages
            }.onSuccess { images ->
                manager.download(item, ContentResult.comic(images)).collect { _download.value = it }
            }.onFailure { e ->
                _download.value = DownloadState(isDownloading = false, success = false, message = "下载失败：${e.message}")
            }
        }
    }

    // ===== 批量离线缓存（v1.2.11，对标小说侧） =====

    /** 批量缓存进度 */
    data class CacheProgress(
        val done: Int,
        val total: Int,
        val newCached: Int,
        val running: Boolean,
        val message: String? = null,
    )

    private val _cacheProgress = MutableStateFlow<CacheProgress?>(null)
    val cacheProgress: StateFlow<CacheProgress?> = _cacheProgress.asStateFlow()

    private var cacheJob: kotlinx.coroutines.Job? = null

    /**
     * 批量缓存章节：从列表位置 fromIndex（0 起，含）开始缓存 count 章；
     * count < 0 表示缓存到末尾。4 路并发，已缓存章节自动跳过。
     * 图片逐张下载到 comic_cache/（含解密与乱序还原），离线可读。
     */
    fun cacheChapters(fromIndex: Int, count: Int) {
        val item = _detail.value ?: return
        val manager = downloadManager ?: return
        val chapters = _chapters.value
        if (chapters.isEmpty()) {
            _cacheProgress.value = CacheProgress(0, 0, 0, false, message = "目录为空，无法缓存")
            return
        }
        val start = fromIndex.coerceIn(0, chapters.size - 1)
        val end = if (count < 0) chapters.size else (start + count).coerceAtMost(chapters.size)
        val targets = chapters.subList(start, end)
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            val alreadyCached = runCatching { manager.cachedComicChapters(item) }
                .getOrDefault(emptySet())
            val toFetch = targets.filter { it.index !in alreadyCached }
            _cacheProgress.value = CacheProgress(0, toFetch.size, 0, true)
            val semaphore = kotlinx.coroutines.sync.Semaphore(2)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val fetched = java.util.concurrent.atomic.AtomicInteger(0)
            kotlinx.coroutines.coroutineScope {
                toFetch.forEach { ch ->
                    launch {
                        semaphore.withPermit {
                            runCatching {
                                val result = contentRepository.content(ch)
                                // downloadChapterToCache 内是阻塞 HTTP，必须离开主线程
                                withContext(Dispatchers.IO) {
                                    manager.downloadChapterToCache(item, ch, result)
                                }
                                fetched.incrementAndGet()
                            }.onFailure { e ->
                                android.util.Log.w("ComicCache", "ch=${ch.index} FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                            }
                        }
                        val dn = done.incrementAndGet()
                        _cacheProgress.value = CacheProgress(dn, toFetch.size, fetched.get(), true)
                    }
                }
            }
            _cacheProgress.value = CacheProgress(
                done = toFetch.size,
                total = toFetch.size,
                newCached = fetched.get(),
                running = false,
                message = "缓存完成：本次处理 ${targets.size} 话，新缓存 ${fetched.get()} 话" +
                    if (toFetch.size < targets.size) "（已缓存 ${targets.size - toFetch.size} 话跳过）" else "",
            )
        }
    }

    /** 缓存全书 */
    fun cacheAll() = cacheChapters(0, -1)

    /** 缓存前 50 话 */
    fun cacheFirst50() = cacheChapters(0, 50)

    /** 从当前阅读进度的下一话起缓存 50 话（无进度则从头开始） */
    fun cacheNext50() {
        val item = _detail.value ?: return
        val repo = progressRepository ?: run { cacheChapters(0, 50); return }
        viewModelScope.launch {
            val idx = runCatching { repo.getProgress(item.sourceId, item.id) }
                .getOrNull()?.chapterIndex ?: -1
            cacheChapters((idx + 1).coerceAtLeast(0), 50)
        }
    }

    /** 取消批量缓存 */
    fun cancelCache() {
        cacheJob?.cancel()
        cacheJob = null
        _cacheProgress.value = _cacheProgress.value?.copy(running = false, message = "已取消缓存")
    }

    /** 清除缓存进度（关闭完成弹窗） */
    fun clearCacheProgress() {
        _cacheProgress.value = null
    }
}
