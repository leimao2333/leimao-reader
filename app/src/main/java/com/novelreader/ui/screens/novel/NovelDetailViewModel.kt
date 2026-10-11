package com.novelreader.ui.screens.novel

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
 * 小说详情 ViewModel。
 *
 * 管理详情信息、章节列表、收藏状态、下载。
 */
class NovelDetailViewModel(
    private val contentRepository: ContentRepository,
    private val bookRepository: BookRepository,
    private val downloadManager: DownloadManager? = null,
) : ViewModel() {

    /** 详情信息 */
    private val _detail = MutableStateFlow<MediaItem?>(null)
    val detail: StateFlow<MediaItem?> = _detail.asStateFlow()

    /** 章节列表 */
    private val _chapters = MutableStateFlow<List<ChapterItem>>(emptyList())
    val chapters: StateFlow<List<ChapterItem>> = _chapters.asStateFlow()

    /** 加载状态 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 是否已收藏 */
    private val _collected = MutableStateFlow(false)
    val collected: StateFlow<Boolean> = _collected.asStateFlow()

    /** 下载状态 */
    private val _download = MutableStateFlow<DownloadState?>(null)
    val download: StateFlow<DownloadState?> = _download.asStateFlow()

    fun load(item: MediaItem) {
        _detail.value = item
        viewModelScope.launch {
            _loading.value = true
            // 并行加载详情和章节
            val detailDeferred = launch { loadDetail(item) }
            val chapterDeferred = launch { loadChapters(item) }
            loadCollected(item)
            detailDeferred.join()
            chapterDeferred.join()
            _loading.value = false
        }
    }

    private suspend fun loadDetail(item: MediaItem) {
        runCatching { contentRepository.detail(item) }
            .onSuccess { _detail.value = it }
    }

    private suspend fun loadChapters(item: MediaItem) {
        runCatching { contentRepository.chapterList(item) }
            .onSuccess { _chapters.value = it }
    }

    private suspend fun loadCollected(item: MediaItem) {
        _collected.value = bookRepository.isCollected(item)
    }

    /** 切换收藏 */
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

    /** 下载整本小说（拼接所有章节正文） */
    fun downloadBook() {
        val item = _detail.value ?: return
        val manager = downloadManager ?: return
        val chapters = _chapters.value
        viewModelScope.launch {
            _download.value = DownloadState(isDownloading = true, progress = 0f)
            runCatching {
                val sb = StringBuilder()
                chapters.forEachIndexed { index, chapter ->
                    val content = contentRepository.content(chapter)
                    content.novelText?.let {
                        sb.append(chapter.title).append("\n\n").append(it).append("\n\n")
                    }
                    _download.value = DownloadState(
                        isDownloading = true,
                        progress = if (chapters.isNotEmpty()) (index + 1f) / chapters.size else 1f,
                    )
                }
                sb.toString()
            }.onSuccess { text ->
                manager.download(item, ContentResult.novel(text)).collect { _download.value = it }
            }.onFailure { e ->
                _download.value = DownloadState(isDownloading = false, success = false, message = "下载失败：${e.message}")
            }
        }
    }
}
