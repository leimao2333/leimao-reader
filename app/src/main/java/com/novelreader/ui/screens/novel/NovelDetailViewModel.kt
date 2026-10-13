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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    private val chapterCacheRepository: com.novelreader.domain.repository.ChapterCacheRepository? = null,
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

    /** 换源搜索进度：已搜源数 / 总源数 */
    private val _changeSourceProgress = MutableStateFlow<String?>(null)
    val changeSourceProgress: StateFlow<String?> = _changeSourceProgress.asStateFlow()

    fun load(item: MediaItem) {
        _detail.value = item
        viewModelScope.launch {
            _loading.value = true
            // 详情与章节的收藏/进度状态并行取，但**目录必须在详情之后**：
            // 详情规则可能写入 legado 变量（`@put:{cid:$.comic_id}` 等），
            // 目录地址里的 `@get:{cid}` 依赖它；并行会导致目录拿到空 ID → 目录空白。
            val metaDeferred = launch { loadCollected(item) }
            val progressDeferred = launch { loadProgressFlag(item) }
            val detailed = loadDetail(item)
            loadChapters(detailed)
            metaDeferred.join()
            progressDeferred.join()
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

    /** 取详情，返回带上详情阶段变量的条目（目录规则要用） */
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

    // ===== 批量离线缓存（v1.2.3.0） =====

    /** 批量缓存进度（running=false 且 message 非空时表示结束） */
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
     * 批量缓存章节：从列表位置 fromIndex（0 起，含）开始缓存 count 章；count < 0 表示缓存到末尾。
     * 4 路并发，已缓存的章节自动跳过；正文写入章节缓存后可离线阅读。
     */
    fun cacheChapters(fromIndex: Int, count: Int) {
        val item = _detail.value ?: return
        val repo = chapterCacheRepository ?: return
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
            val alreadyCached = runCatching { repo.cachedIndices(item.sourceId, item.id) }
                .getOrDefault(emptySet())
            val toFetch = targets.filter { it.index !in alreadyCached }
            _cacheProgress.value = CacheProgress(0, toFetch.size, 0, true)
            val semaphore = kotlinx.coroutines.sync.Semaphore(4)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val fetched = java.util.concurrent.atomic.AtomicInteger(0)
            kotlinx.coroutines.coroutineScope {
                toFetch.forEach { ch ->
                    launch {
                        semaphore.withPermit {
                            val result = runCatching { contentRepository.content(ch) }.getOrNull()
                            val text = result?.novelText
                            if (!text.isNullOrBlank()) {
                                runCatching { repo.saveCache(item, ch.index, ch.title, ch.url, text) }
                                fetched.incrementAndGet()
                            }
                        }
                        val d = done.incrementAndGet()
                        _cacheProgress.value = CacheProgress(d, toFetch.size, fetched.get(), true)
                    }
                }
            }
            _cacheProgress.value = CacheProgress(
                done = toFetch.size,
                total = toFetch.size,
                newCached = fetched.get(),
                running = false,
                message = "缓存完成：本次处理 ${targets.size} 章，新缓存 ${fetched.get()} 章" +
                    if (toFetch.size < targets.size) "（已缓存 ${targets.size - toFetch.size} 章跳过）" else "",
            )
        }
    }

    /** 缓存全书 */
    fun cacheAll() = cacheChapters(0, -1)

    /** 缓存前 50 章 */
    fun cacheFirst50() = cacheChapters(0, 50)

    /** 从当前阅读进度的下一章起缓存 50 章（无进度则从头开始） */
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

    /**
     * 搜索同名书：按「书名 + 作者」匹配（与阅读页换源同一套判定），排除当前源。
     *
     * 用并发带进度的 [ContentRepository.searchWithProgress]（8 路并发 + 模糊匹配），
     * 旧实现是串行 [ContentRepository.search]——22 个源逐个抓，死源要等满超时，
     * 总耗时可达数分钟，面板看起来像「永远没有结果」。
     *
     * 候选**增量刷新**：每完成一个源就刷新一次候选，不等全部搜完；
     * 整体限时 60 秒——个别死源的挂起时间会超过单次读超时，不能陪它耗到底。
     */
    private fun searchSameTitle() {
        val current = _detail.value ?: return
        val title = current.title.trim()
        if (title.isBlank()) return
        viewModelScope.launch {
            _changeSourceLoading.value = true
            _changeSourceError.value = null
            _changeSourceProgress.value = null
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(60_000) {
                    contentRepository.searchWithProgress(
                        type = SourceType.NOVEL,
                        keyword = title,
                        page = 1,
                        concurrency = 8,
                        fuzzyEnabled = true,
                    ).collect { state ->
                        _changeSourceProgress.value =
                            if (state.isSearching) "正在搜索书源 ${state.searchedCount}/${state.totalCount}"
                            else null
                        _changeSourceCandidates.value = filterCandidates(state.results, current, title)
                    }
                }
            }.onFailure { e ->
                _changeSourceError.value = e.message ?: "换源搜索失败"
                _changeSourceCandidates.value = emptyList()
            }
            _changeSourceLoading.value = false
        }
    }

    /** 换源候选过滤：书名归一化互相包含 + 作者归一化一致（与阅读页同一套判定） */
    private fun filterCandidates(
        results: List<com.novelreader.domain.model.MediaItem>,
        current: com.novelreader.domain.model.MediaItem,
        title: String,
    ): List<com.novelreader.domain.model.MediaItem> {
        val curTitleNorm = com.novelreader.source.search.FuzzyMatch.normalize(title)
        val curAuthor = current.author?.trim()?.let {
            com.novelreader.source.search.FuzzyMatch.normalizeAuthor(it)
        }
        return results.filter { cand ->
            // 排除当前这本书（id 或详情 URL 一致都算同一本）
            if (cand.sourceId == current.sourceId &&
                (cand.id == current.id || (cand.id.isBlank() && cand.detailUrl == current.detailUrl))
            ) return@filter false
            val t = com.novelreader.source.search.FuzzyMatch.normalize(cand.title)
            // 同源 + 同名（归一化后）视为当前书换了个 id，同样排除
            if (cand.sourceId == current.sourceId && t == curTitleNorm) return@filter false
            val titleMatch = t == curTitleNorm || t.contains(curTitleNorm) || curTitleNorm.contains(t)
            if (!titleMatch) return@filter false
            // 作者一致（归一化去站点前缀；任一为空视为通过）
            val candAuthor = cand.author?.trim()?.let {
                com.novelreader.source.search.FuzzyMatch.normalizeAuthor(it)
            }
            if (curAuthor.isNullOrBlank() || candAuthor.isNullOrBlank()) true
            else candAuthor == curAuthor
        }
    }
}
