package com.novelreader.ui.screens.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.download.DownloadManager
import com.novelreader.core.download.DownloadState
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
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
    private val progressRepository: com.novelreader.domain.repository.ProgressRepository? = null,
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

    /** 是否有阅读进度（决定按钮文案：开始阅读 / 继续阅读） */
    private val _hasProgress = MutableStateFlow(false)
    val hasProgress: StateFlow<Boolean> = _hasProgress.asStateFlow()

    /** 轻量提示（原地换源后展示） */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    /** 下载状态 */
    private val _download = MutableStateFlow<DownloadState?>(null)
    val download: StateFlow<DownloadState?> = _download.asStateFlow()

    // ===== 换源（详情页） =====
    /** 换源面板是否打开 */
    private val _changeSourceOpen = MutableStateFlow(false)
    val changeSourceOpen: StateFlow<Boolean> = _changeSourceOpen.asStateFlow()

    /** 同名同作者的候选源（排除当前源） */
    private val _changeSourceCandidates = MutableStateFlow<List<MediaItem>>(emptyList())
    val changeSourceCandidates: StateFlow<List<MediaItem>> = _changeSourceCandidates.asStateFlow()

    /** 换源搜索中 */
    private val _changeSourceLoading = MutableStateFlow(false)
    val changeSourceLoading: StateFlow<Boolean> = _changeSourceLoading.asStateFlow()

    /** 换源错误信息 */
    private val _changeSourceError = MutableStateFlow<String?>(null)
    val changeSourceError: StateFlow<String?> = _changeSourceError.asStateFlow()

    fun load(item: MediaItem) {
        _detail.value = item
        viewModelScope.launch {
            _loading.value = true
            // 并行加载详情和章节
            val detailDeferred = launch { loadDetail(item) }
            val chapterDeferred = launch { loadChapters(item) }
            loadCollected(item)
            loadProgressFlag(item)
            detailDeferred.join()
            chapterDeferred.join()
            _loading.value = false
        }
    }

    /** 是否有阅读进度（按钮文案用） */
    private suspend fun loadProgressFlag(item: MediaItem) {
        val repo = progressRepository ?: return
        _hasProgress.value = runCatching {
            repo.getProgress(item.sourceId, item.id)
        }.getOrNull()?.let { it.chapterUrl.isNotBlank() } == true
    }

    /**
     * 原地换源：不新开页面、不叠加返回栈，直接在当前详情页载入目标源的同名书。
     *
     * 旧实现是 navigate 到目标源的详情页（`Routes.novelDetail(candidate)`），
     * 用户连点几次换源后返回栈里堆一串详情页，返回要按很多次才能退出。
     */
    fun switchTo(target: MediaItem) {
        _changeSourceOpen.value = false
        _notice.value = "已切换到「${target.sourceName.ifBlank { target.title }}」"
        load(target)
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

    // ===== 换源（详情页） =====

    /** 打开换源面板并搜索同名书 */
    fun openChangeSource() {
        _changeSourceOpen.value = true
        searchSameTitle()
    }

    /** 关闭换源面板 */
    fun closeChangeSource() {
        _changeSourceOpen.value = false
    }

    /** 搜索同名书：按「书名 + 作者」匹配（与阅读页换源同一套判定），排除当前源 */
    private fun searchSameTitle() {
        val current = _detail.value ?: return
        val title = current.title.trim()
        if (title.isBlank()) return
        viewModelScope.launch {
            _changeSourceLoading.value = true
            _changeSourceError.value = null
            runCatching { contentRepository.search(SourceType.NOVEL, title, 1) }
                .onSuccess { results ->
                    val curAuthor = current.author?.trim()?.lowercase()
                    val candidates = results.filter { cand ->
                        // 排除当前这本书
                        if (cand.sourceId == current.sourceId && cand.id == current.id) return@filter false
                        // 书名匹配（归一化后互相包含或相等）
                        val t = com.novelreader.source.search.FuzzyMatch.normalize(cand.title)
                        val k = com.novelreader.source.search.FuzzyMatch.normalize(title)
                        val titleMatch = t == k || t.contains(k) || k.contains(t)
                        if (!titleMatch) return@filter false
                        // 作者一致（任一为空视为通过）
                        val candAuthor = cand.author?.trim()?.lowercase()
                        if (curAuthor.isNullOrBlank() || candAuthor.isNullOrBlank()) true
                        else candAuthor == curAuthor
                    }
                    _changeSourceCandidates.value = candidates
                }
                .onFailure { e ->
                    _changeSourceError.value = e.message ?: "换源搜索失败"
                    _changeSourceCandidates.value = emptyList()
                }
            _changeSourceLoading.value = false
        }
    }
}
