package com.novelreader.ui.screens.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.download.DownloadManager
import com.novelreader.core.download.DownloadState
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 视频详情 ViewModel。
 *
 * 管理详情信息和视频目录（选集列表）。
 */
class VideoDetailViewModel(
    private val contentRepository: ContentRepository,
    private val bookRepository: BookRepository,
    private val downloadManager: DownloadManager? = null,
) : ViewModel() {

    private val _detail = MutableStateFlow<MediaItem?>(null)
    val detail: StateFlow<MediaItem?> = _detail.asStateFlow()

    /** 视频目录（选集列表） */
    private val _episodes = MutableStateFlow<List<ChapterItem>>(emptyList())
    val episodes: StateFlow<List<ChapterItem>> = _episodes.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _collected = MutableStateFlow(false)
    val collected: StateFlow<Boolean> = _collected.asStateFlow()

    private val _download = MutableStateFlow<DownloadState?>(null)
    val download: StateFlow<DownloadState?> = _download.asStateFlow()

    fun load(item: MediaItem) {
        _detail.value = item
        viewModelScope.launch {
            _loading.value = true
            val detailDeferred = launch { loadDetail(item) }
            val episodeDeferred = launch { loadEpisodes(item) }
            loadCollected(item)
            detailDeferred.join()
            episodeDeferred.join()
            _loading.value = false
        }
    }

    private suspend fun loadDetail(item: MediaItem) {
        runCatching { contentRepository.detail(item) }
            .onSuccess { _detail.value = it }
    }

    private suspend fun loadEpisodes(item: MediaItem) {
        runCatching { contentRepository.chapterList(item) }
            .onSuccess { _episodes.value = it }
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

    /** 下载视频（第一个可选集对应的播放地址） */
    fun downloadVideo() {
        val item = _detail.value ?: return
        val manager = downloadManager ?: return
        viewModelScope.launch {
            _download.value = DownloadState(isDownloading = true, progress = 0f)
            runCatching {
                // 优先取第一集，否则取详情自身
                val episode = _episodes.value.firstOrNull()
                val content = if (episode != null) contentRepository.content(episode)
                else contentRepository.content(
                    ChapterItem(id = item.id, title = item.title, url = item.detailUrl, sourceId = item.sourceId)
                )
                content
            }.onSuccess { content ->
                manager.download(item, content).collect { _download.value = it }
            }.onFailure { e ->
                _download.value = DownloadState(isDownloading = false, success = false, message = "下载失败：${e.message}")
            }
        }
    }
}
