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

/**
 * 漫画详情 ViewModel。
 */
class ComicDetailViewModel(
    private val contentRepository: ContentRepository,
    private val bookRepository: BookRepository,
    private val downloadManager: DownloadManager? = null,
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
            // （全免漫画等源在 ruleBookInfo.name 里 `@put:{cid:$.comic_id}`），
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
}
