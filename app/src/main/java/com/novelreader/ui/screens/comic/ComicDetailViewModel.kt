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
/**
 * 样张总数上限。
 *
 * 「加载更多」一次只加一章；「加载全部」可能跨越上百话目录，这里留一个大上限兜底，
 * 避免把整本书几百话的地址全塞进一个 List（每张只是一条 URL，但列表本身的渲染成本
 * 与 Coil 队列压力会明显上升）。
 */
private const val MAX_PREVIEWS = 600

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

    /**
     * 目录为空时的**具体原因**（v1.7.1.0）。
     *
     * 以前无论「请求超时 / 被墙 / 解析失败」还是「该源确实没有目录」，UI 都只显示
     * 「可能源已失效」。现在把数据层记下的真实异常透出来，用户至少知道是网络问题
     * 还是源本身的问题。
     */
    private val _chapterError = MutableStateFlow<String?>(null)
    val chapterError: StateFlow<String?> = _chapterError.asStateFlow()

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
            // D21：首批样张不挡 loading —— 它要再解析一次正文，放进首屏关键路径
            // 会让「点进来转圈」多等一次网络往返；页面先可交互，预览随后补上
            loadMorePreviews()
        }
    }

    private suspend fun loadDetail(item: MediaItem): MediaItem =
        runCatching { contentRepository.detail(item) }
            .onSuccess { _detail.value = it }
            .getOrDefault(item)

    private suspend fun loadChapters(item: MediaItem) {
        val list = runCatching { contentRepository.chapterList(item) }.getOrDefault(emptyList())
        _chapters.value = list
        _chapterError.value =
            if (list.isEmpty()) com.novelreader.core.source.SourceErrorLog.describe(item.sourceId)
            else null
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

    // ===== 批量离线缓存（v1.2.11.0，对标小说侧） =====

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

    // ===== 样张预览（D21 / D23 / D24） =====

    /** 详情页样张画廊的图片（按章节顺序逐章追加） */
    private val _previews = MutableStateFlow<List<String>>(emptyList())
    val previews: StateFlow<List<String>> = _previews.asStateFlow()

    private val _previewLoading = MutableStateFlow(false)
    val previewLoading: StateFlow<Boolean> = _previewLoading.asStateFlow()

    /** 「加载全部」是否在进行中 */
    private val _previewLoadingAll = MutableStateFlow(false)
    val previewLoadingAll: StateFlow<Boolean> = _previewLoadingAll.asStateFlow()

    /** 「加载全部」进度：已处理章节数 to 总章节数 */
    private val _previewLoadAllProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val previewLoadAllProgress: StateFlow<Pair<Int, Int>?> =
        _previewLoadAllProgress.asStateFlow()

    /**
     * 样张的请求头（防盗链 Referer / Cookie）。
     *
     * 不带它的话图床直接 403 —— 预览区会全是灰块（封面能出是因为封面链路另有一套兜底）。
     */
    private val _previewHeaders = MutableStateFlow<Map<String, String>>(emptyMap())
    val previewHeaders: StateFlow<Map<String, String>> = _previewHeaders.asStateFlow()

    /** 下一章样张的游标（指向 _chapters 下标） */
    private var previewCursor = 0

    /** 单章预览结果（D23 先看几张 / D24 长按章节预览卡） */
    data class ChapterPreview(
        val title: String,
        val images: List<String>,
        val loading: Boolean,
    )

    private val _chapterPreview = MutableStateFlow<ChapterPreview?>(null)
    val chapterPreview: StateFlow<ChapterPreview?> = _chapterPreview.asStateFlow()

    /**
     * 加载下一章样张（D21 分页加载）。
     *
     * 复刻 venera 的 `loadNext`：每次只解析下一章的开头几张图并追加，滚到末尾再点
     * 「加载更多」继续 —— 不做「一上来解析全书」这种会把源打挂的行为。
     */
    fun loadMorePreviews(count: Int = 6) {
        if (_previewLoading.value || _previewLoadingAll.value) return
        val chapter = _chapters.value.getOrNull(previewCursor) ?: return
        _previewLoading.value = true
        viewModelScope.launch {
            val (images, headers) = resolvePreview(chapter, count)
            previewCursor += 1
            if (headers.isNotEmpty()) {
                _previewHeaders.value = _previewHeaders.value + headers
            }
            if (images.isNotEmpty()) {
                _previews.value = (_previews.value + images).distinct().take(MAX_PREVIEWS)
            }
            _previewLoading.value = false
        }
    }

    /**
     * 一次把剩余所有章节的样张都拉进来（「加载全部」）。
     *
     * 逐章**串行**解析而不是并发：一百多话的目录并发打过去，源站点基本会限流甚至拉黑，
     * 而且预览本来就是「先看看」的功能，慢一点没关系。进度通过
     * [previewLoadAllProgress] 回显成 `已处理/总数`。
     */
    fun loadAllPreviews(count: Int = 6) {
        if (_previewLoading.value || _previewLoadingAll.value) return
        val chapters = _chapters.value
        if (chapters.isEmpty()) return
        _previewLoadingAll.value = true
        viewModelScope.launch {
            var index = previewCursor
            while (index < chapters.size) {
                val (images, headers) = resolvePreview(chapters[index], count)
                if (headers.isNotEmpty()) {
                    _previewHeaders.value = _previewHeaders.value + headers
                }
                if (images.isNotEmpty()) {
                    _previews.value = (_previews.value + images).distinct().take(MAX_PREVIEWS)
                }
                index += 1
                previewCursor = index
                _previewLoadAllProgress.value = index to chapters.size
                // 已经攒够上限就别再打源了
                if (_previews.value.size >= MAX_PREVIEWS) break
            }
            _previewLoadingAll.value = false
            _previewLoadAllProgress.value = null
        }
    }

    /** 章节预览（D23/D24）：取该章开头几张图，点章节标题旁「预览」或长按触发 */
    fun previewChapter(chapter: ChapterItem) {
        _chapterPreview.value = ChapterPreview(chapter.title, emptyList(), loading = true)
        viewModelScope.launch {
            val (images, headers) = resolvePreview(chapter, 9)
            if (headers.isNotEmpty()) {
                _previewHeaders.value = _previewHeaders.value + headers
            }
            _chapterPreview.value = ChapterPreview(chapter.title, images, loading = false)
        }
    }

    /**
     * 解析某章开头几张图，**连同请求头一起**返回。
     *
     * 走 `content()` 而不是 `previewImages()`：漫画正文解析结果本身就带 `comicHeaders`，
     * 一次请求就能同时拿到图片地址与防盗链头；只取地址的话预览图会被图床挡成灰块。
     */
    private suspend fun resolvePreview(
        chapter: ChapterItem,
        limit: Int,
    ): Pair<List<String>, Map<String, String>> {
        val result = runCatching { contentRepository.content(chapter) }.getOrNull()
            ?: return emptyList<String>() to emptyMap()
        var headers = result.comicHeaders
        val raw = result.comicImages.orEmpty().filter { it.isNotBlank() }

        // 图库类源一章几百页时只预解析前几张，其余是 `venera-image:` 惰性占位 ——
        // Coil 打不开这种 scheme（本地秒失败、不发请求），直接塞给 UI 就是一片灰块。
        // 先挑现成可用的地址，不够再按需解析占位。
        val ready = raw.filterNot { com.novelreader.core.image.LazyImage.isLazy(it) }.take(limit)
        if (ready.size >= limit) return ready to headers

        val out = ready.toMutableList()
        val used = ready.toSet()
        for (url in raw) {
            if (out.size >= limit) break
            if (url in used || !com.novelreader.core.image.LazyImage.isLazy(url)) continue
            val fb = runCatching { contentRepository.fallbackImage(chapter.sourceId, url) }
                .getOrNull()
            val next = fb?.url
            if (next.isNullOrBlank() || next == url) continue
            out.add(next)
            fb?.headers?.takeIf { it.isNotEmpty() }?.let { headers = headers + it }
        }
        return out to headers
    }

    /** 关闭章节预览卡 */
    fun dismissChapterPreview() {
        _chapterPreview.value = null
    }

    // ===== 评论预览（D33）=====

    private val _comments =
        MutableStateFlow<List<com.novelreader.domain.model.WorkComment>>(emptyList())
    val comments: StateFlow<List<com.novelreader.domain.model.WorkComment>> = _comments.asStateFlow()

    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading.asStateFlow()

    /** 评论所属章节标题（详情页没有「当前章」，用第一话） */
    private val _commentsTitle = MutableStateFlow("")
    val commentsTitle: StateFlow<String> = _commentsTitle.asStateFlow()

    /**
     * 拉第一话的评论做预览（D33）。
     *
     * 详情页本身没有章节上下文，而大多数源的评论挂在章节上；取第一话既能拿到
     * 「大家怎么看」的样本，也避免了额外一次作品级请求（多数源没有这个接口）。
     */
    fun loadCommentsPreview() {
        val chapter = _chapters.value.firstOrNull() ?: return
        if (_commentsLoading.value) return
        _commentsTitle.value = chapter.title
        _commentsLoading.value = true
        viewModelScope.launch {
            _comments.value = runCatching { contentRepository.comments(chapter) }
                .getOrDefault(emptyList())
            _commentsLoading.value = false
        }
    }
}
