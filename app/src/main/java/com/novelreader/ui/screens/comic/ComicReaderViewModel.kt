package com.novelreader.ui.screens.comic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import java.io.File
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.domain.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 漫画阅读 ViewModel。
 *
 * 管理图片列表、章节切换、翻页方式（上下滚动 / 左右翻页）。
 * 翻页方式通过 PreferencesManager 持久化。
 */
class ComicReaderViewModel(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
    private val preferences: PreferencesManager,
    private val downloadManager: com.novelreader.core.download.DownloadManager? = null,
    private val bookRepository: BookRepository? = null,
) : ViewModel() {

    /** 当前漫画是否已在书架（初始 false，须等 [shelfStateReady] 为 true 才可信） */
    private val _collected = MutableStateFlow(false)
    val collected: StateFlow<Boolean> = _collected.asStateFlow()

    /** 收藏状态是否已加载完成（防止初始 false 误弹「加入书架」） */
    private val _shelfStateReady = MutableStateFlow(false)
    val shelfStateReady: StateFlow<Boolean> = _shelfStateReady.asStateFlow()

    /** 「未加入书架」提示开关（设置页可关，默认开） */
    private val _shelfPromptEnabled = MutableStateFlow(true)
    val shelfPromptEnabled: StateFlow<Boolean> = _shelfPromptEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.comicShelfPromptFlow.collectLatest { _shelfPromptEnabled.value = it }
        }
    }

    /** 图片地址列表 */
    private val _images = MutableStateFlow<List<String>>(emptyList())
    val images: StateFlow<List<String>> = _images.asStateFlow()

    /** 图片加载所需的请求头（防盗链 Referer 等，来自源规则） */
    private val _imageHeaders = MutableStateFlow<Map<String, String>>(emptyMap())
    val imageHeaders: StateFlow<Map<String, String>> = _imageHeaders.asStateFlow()

    /**
     * 逐图后处理脚本（按图片地址索引）。
     *
     * 部分站点把每页按 N 段纵向打乱后下发，源的 `modifyImage` 脚本
     * 描述了还原方式；这里把它带给阅读页，在解码阶段重排（见 ComicImageScramble）。
     */
    private val _imageScripts = MutableStateFlow<Map<String, String>>(emptyMap())
    val imageScripts: StateFlow<Map<String, String>> = _imageScripts.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 当前章节标题 */
    private val _chapterTitle = MutableStateFlow("")
    val chapterTitle: StateFlow<String> = _chapterTitle.asStateFlow()

    private var currentItem: MediaItem? = null
    private var currentChapter: ChapterItem? = null

    private val _chapters = MutableStateFlow<List<ChapterItem>>(emptyList())
    val chapters: StateFlow<List<ChapterItem>> = _chapters.asStateFlow()

    /** 当前话在目录中的下标（0 基） */
    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex.asStateFlow()

    /** 目录总话数 */
    private val _chapterCount = MutableStateFlow(0)
    val chapterCount: StateFlow<Int> = _chapterCount.asStateFlow()

    /** 是否存在上一话 / 下一话（底部导航条与章末入口据此显示可用态） */
    private val _hasPrevChapter = MutableStateFlow(false)
    val hasPrevChapter: StateFlow<Boolean> = _hasPrevChapter.asStateFlow()

    private val _hasNextChapter = MutableStateFlow(false)
    val hasNextChapter: StateFlow<Boolean> = _hasNextChapter.asStateFlow()

    /** 翻页方式：0=上下滚动，1=左右翻页 */
    val pageMode: StateFlow<Int> = preferences.comicPageModeFlow
        .let { flow ->
            val state = MutableStateFlow(0)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 预加载张数（1~8，默认 4；C5） */
    val preloadCount: StateFlow<Int> = preferences.comicPreloadCountFlow
        .let { flow ->
            val state = MutableStateFlow(com.novelreader.core.preferences.PreferencesManager.DEFAULT_COMIC_PRELOAD_COUNT)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 仅 WiFi 下预加载（C5） */
    val preloadWifiOnly: StateFlow<Boolean> = preferences.comicPreloadWifiOnlyFlow
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /**
     * 预加载话数（向后预取接下来 N 话的完整图片列表，0~5，默认 3；v1.6.0.0）。
     * 与章内预加载张数（[preloadCount]）正交：一个是章内图片提前几张，一个是阅读完
     * 当前话后自动预取后续 N 话的整章图片（落到 Coil 缓存，缓存管理页面可清理）。
     */
    val preloadChapters: StateFlow<Int> = preferences.comicPreloadChaptersFlow
        .let { flow ->
            val state = MutableStateFlow(com.novelreader.core.preferences.PreferencesManager.DEFAULT_COMIC_PRELOAD_CHAPTERS)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    fun setPreloadChapters(value: Int) {
        viewModelScope.launch { preferences.setComicPreloadChapters(value) }
    }

    /** 漫画预加载任务（切话时取消重启） */
    private var preloadJob: kotlinx.coroutines.Job? = null

    /** 横屏双页（左右翻页模式下横屏并排两页；C3） */
    val landscapeDoublePage: StateFlow<Boolean> = preferences.comicLandscapeDoublePageFlow
        .let { flow ->
            val state = MutableStateFlow(true)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 横屏双页开关 */
    fun setLandscapeDoublePage(value: Boolean) {
        viewModelScope.launch { preferences.setComicLandscapeDoublePage(value) }
    }

    /**
     * 边缘竖滑调亮度 / 音量手势（v1.7.6.0，默认关闭）。
     *
     * 关闭时不挂载边缘窄条 —— 左右边缘的竖划会原样交给内容的滚动 / 翻页处理，
     * 不会出现「翻页翻到屏幕最左 / 最右却被当成调亮度」的情况。
     */
    val edgeGesture: StateFlow<Boolean> = preferences.comicEdgeGestureFlow
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 当前页索引（左右翻页模式用） */
    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    fun init(item: MediaItem, chapters: List<ChapterItem>, chapter: ChapterItem) {
        currentItem = item
        _chapters.value = chapters
        currentChapter = chapter
        _chapterTitle.value = chapter.title
        syncPosition()
        loadContent(chapter)
        viewModelScope.launch {
            runCatching { bookRepository?.isCollected(item) == true }
                .onSuccess { _collected.value = it; _shelfStateReady.value = true }
                .onFailure { _shelfStateReady.value = true }
        }
    }

    /** 收藏 / 取消收藏当前漫画 */
    fun toggleCollect() {
        val repo = bookRepository ?: return
        val item = currentItem ?: return
        viewModelScope.launch {
            if (_collected.value) {
                runCatching { repo.removeFromBookshelf(item) }
                    .onSuccess { _collected.value = false }
            } else {
                runCatching { repo.addToBookshelf(item) }
                    .onSuccess { _collected.value = true }
            }
        }
    }

    /** 当前已加载的章节，用于失败重试 */
    private var lastChapter: ChapterItem? = null

    /** 已尝试过备用镜像的地址（每地址只回退一次，避免失败循环） */
    private val fallbackTried = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 整章重解析（v1.9.2.4）：自上次重解析以来的失败张数 */
    private val failedSinceRefresh = java.util.concurrent.atomic.AtomicInteger(0)

    /** 本章已重解析次数（每章上限，防死循环） */
    private var refreshCount = 0

    /** 上次重解析时刻（elapsedRealtime），用于限频 */
    private var lastRefreshAt = 0L

    /**
     * 图片加载失败 → 向源要可用地址并替换该图（v1.7.1.0，v1.9.2.3 扩展）。
     *
     * 覆盖两种情形（见 [com.novelreader.domain.model.ImageFallback]）：
     * 1. 源声明了备用镜像（禁漫天堂等有 5 条线路），第一个域名被墙不再等于整章看不了；
     * 2. 图片是惰性占位地址（`venera-image:`）—— 图库类源一章几百页时只预解析前若干张，
     *    其余在这里按需解析。等于「滚动到哪解析到哪」，与官方行为一致。
     */
    fun onImageFailed(url: String) {
        val item = currentItem ?: return
        if (url.isBlank()) return
        if (!fallbackTried.add(url)) return
        viewModelScope.launch {
            val fb = runCatching { contentRepository.fallbackImage(item.sourceId, url) }
                .getOrNull()
            val next = fb?.url
            if (next.isNullOrBlank() || next == url) {
                // 惰性占位解析不出来是最要命的一种：那张图会永远停在加载占位，
                // 用户看到的就是「后半章一直转圈」。这条日志用来区分
                // 「源没给新地址」与「地址拿到了但加载仍失败」。
                if (com.novelreader.core.image.LazyImage.isLazy(url)) {
                    android.util.Log.w(
                        "ComicReader",
                        "惰性占位解析失败（源未返回可用地址），该图会一直显示加载中",
                    )
                    // 整章重解析对惰性占位没用：重算出来的 token 与原来逐字节相同，
                    // 只会白白多打一次 content。这里直接返回。
                    return@launch
                }
                // 单张救不回来 → 可能是**整批地址一起失效**（预签名 / 一次性令牌），
                // 这时换一张没用，得整章重新解析（见 [maybeRefreshChapter]）
                maybeRefreshChapter()
                return@launch
            }
            val extraHeaders = fb.headers
            val list = _images.value
            val idx = list.indexOf(url)
            if (idx < 0) return@launch
            // 乱序还原脚本按地址查，换址要一并搬过去，否则新地址的图会显示成乱序
            val script = _imageScripts.value[url] ?: fb.script
            _images.value = list.toMutableList().also { it[idx] = next }
            if (!script.isNullOrBlank()) {
                _imageScripts.value = (_imageScripts.value - url) + (next to script)
            }
            if (extraHeaders.isNotEmpty()) {
                _imageHeaders.value = _imageHeaders.value + extraHeaders
            }
        }
    }

    /**
     * 整章重新解析图片地址（v1.9.2.4）。
     *
     * 有些源的图片地址是**带时效的一次性签名**（S3 预签名、CDN 防盗链令牌），
     * 一次 `loadEp` 给出的几十个地址会在同一时刻集体过期 —— 此后单张「换备用镜像」
     * 拿到的仍是同一批已失效地址，读者看到的就是「进章节一直转圈 / 图全裂」。
     *
     * 这里在「同一章连续 [REFRESH_THRESHOLD] 张失败」时，重跑一次 `content`
     * 拿一批**新签名**，并按**下标**回填：已经能显示的图保持不动（不闪、不乱序），
     * 只替换掉失败的与新地址。每章最多重解析 [MAX_REFRESH_PER_CHAPTER] 次，
     * 且两次之间至少间隔 [REFRESH_MIN_INTERVAL_MS]，避免源彻底坏掉时打成死循环。
     */
    private suspend fun maybeRefreshChapter() {
        val chapter = currentChapter ?: return
        if (refreshCount >= MAX_REFRESH_PER_CHAPTER) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRefreshAt < REFRESH_MIN_INTERVAL_MS) return
        if (failedSinceRefresh.incrementAndGet() < REFRESH_THRESHOLD) return
        // 先占位，防止并发失败回调同时触发多次重解析
        refreshCount++
        lastRefreshAt = now
        failedSinceRefresh.set(0)
        val result = runCatching { contentRepository.content(chapter) }.getOrNull() ?: return
        val fresh = result.comicImages?.filter { it.isNotBlank() } ?: return
        if (fresh.isEmpty()) return
        val old = _images.value
        if (old.isEmpty()) {
            _images.value = fresh
            _imageHeaders.value = result.comicHeaders
            _imageScripts.value = result.comicImageScripts
            return
        }
        // 按下标回填：换掉失败的那几张，其余保持原样
        val merged = old.mapIndexed { i, cur ->
            if (cur in fallbackTried) fresh.getOrElse(i) { cur } else cur
        }
        if (merged == old) return
        _images.value = merged
        _imageHeaders.value = _imageHeaders.value + result.comicHeaders
        _imageScripts.value = _imageScripts.value + result.comicImageScripts
        // 新地址重新获得一次回退机会
        fallbackTried.clear()
        android.util.Log.i(
            "ComicReader",
            "整章重解析图片地址：第 ${refreshCount} 次，共 ${fresh.size} 张",
        )
    }

    fun loadContent(chapter: ChapterItem) {
        currentChapter = chapter
        lastChapter = chapter
        _chapterTitle.value = chapter.title
        _currentPage.value = 0
        _images.value = emptyList()
        _imageScripts.value = emptyMap()
        // 换章后重置图片回退记录，避免同一地址在别的话里被误判为「已回退过」
        fallbackTried.clear()
        failedSinceRefresh.set(0)
        refreshCount = 0
        lastRefreshAt = 0L
        syncPosition()
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            // 章节缓存命中（v1.2.11.0 离线阅读）：本地图片文件直读，不走网络
            val cachedFiles = currentItem?.let { item ->
                runCatching {
                    downloadManager?.comicChapterCacheDir(item, chapter.index)
                        ?.listFiles()?.filter(File::isFile)
                        ?.sorted()?.map { android.net.Uri.fromFile(it).toString() }
                }.getOrNull().takeUnless { it.isNullOrEmpty() }
            }
            if (cachedFiles != null) {
                _images.value = cachedFiles
                _imageHeaders.value = emptyMap()
                _imageScripts.value = emptyMap()
                saveProgress()
                preloadNextChapters(chapter.index)
            } else runCatching { contentRepository.content(chapter) }
                .onSuccess { result ->
                    val imgs = result.comicImages?.filter { it.isNotBlank() } ?: emptyList()
                    _images.value = imgs
                    _imageHeaders.value = result.comicHeaders
                    _imageScripts.value = result.comicImageScripts
                    // 图片列表为空时不进入渲染分支，避免纯黑屏无提示
                    if (imgs.isEmpty()) {
                        // v1.7.1.0：区分「源报错」与「确实没有图片」
                        val why = com.novelreader.core.source.SourceErrorLog
                            .describe(chapter.sourceId)
                        _error.value = if (why != null) {
                            "本章图片拉取失败（$why）\n可尝试换源，或检查网络 / 代理后重试"
                        } else {
                            "本章暂无可用图片，可能源已失效或章节待更新"
                        }
                    } else {
                        saveProgress()
                        preloadNextChapters(chapter.index)
                    }
                }
                .onFailure { e ->
                    _images.value = emptyList()
                    _error.value = e.message?.takeIf { it.isNotBlank() }
                        ?: "加载失败，请检查网络或切换书源"
                }
            _loading.value = false
        }
    }

    /**
     * 预取当前章之后的 [preloadChapters] 话（v1.6.0.0）。
     *
     * 串行预取（每话正常会下载 10~50 张图，并发会把图片服务器打挂）：
     * 1. 先读本地章节缓存目录（[DownloadManager.comicChapterCacheDir]），
     *    已缓存的话直接跳过；
     * 2. 走源去拿图片地址列表（[contentRepository.content] 拿到的是元数据 / 图片 URL，
     *    不会真正下载图片；图片会在 Coil 首次展示时按需加载并落到磁盘缓存）；
     * 3. 不再额外保存图片——Coil 的磁盘缓存（25% 应用堆 + 512MB 磁盘）负责实际落地，
     *    「缓存管理」页的视频预加载分区主要给视频用，漫画的预加载间接由 Coil 缓存负责。
     */
    private fun preloadNextChapters(currentIndex: Int) {
        preloadJob?.cancel()
        val count = preloadChapters.value.coerceIn(0, 5)
        if (count == 0) return
        val chapters = _chapters.value
        val targets = (1..count).mapNotNull { chapters.getOrNull(currentIndex + it) }
        if (targets.isEmpty()) return
        preloadJob = viewModelScope.launch {
            for (ch in targets) {
                if (ch.index == currentChapter?.index) continue
                // 本地章节目录已有文件就跳过
                val cached = currentItem?.let { item ->
                    runCatching {
                        downloadManager?.comicChapterCacheDir(item, ch.index)
                            ?.listFiles()?.any(File::isFile) == true
                    }.getOrDefault(false)
                }
                if (cached == true) continue
                // 拉图片地址列表（不真正下载图片，由 Coil 触发实际下载并写缓存）
                runCatching { contentRepository.content(ch) }
            }
        }
    }

    /** 失败重试：重新加载当前章节 */
    fun retry() {
        lastChapter?.let { loadContent(it) }
    }

    /** 切换翻页方式 */
    fun setPageMode(mode: Int) {
        viewModelScope.launch { preferences.setComicPageMode(mode) }
    }

    /** 左右翻页：下一页 */
    fun nextPage() {
        val max = _images.value.size - 1
        if (_currentPage.value < max) {
            _currentPage.value = _currentPage.value + 1
        } else {
            nextChapter()
        }
    }

    /** 左右翻页：上一页 */
    fun prevPage() {
        if (_currentPage.value > 0) {
            _currentPage.value = _currentPage.value - 1
        } else {
            prevChapter()
        }
    }

    /** 跳转到指定页 */
    fun goToPage(index: Int) {
        if (index in _images.value.indices) {
            _currentPage.value = index
        }
    }

    /** 当前章节在列表中的位置（防御式：同一性 → 双重匹配 → url → index） */
    private fun currentIndex(): Int {
        val chapters = _chapters.value
        val ch = currentChapter ?: return -1
        chapters.forEachIndexed { i, c -> if (c === ch) return i }
        chapters.indexOfFirst { it.index == ch.index && it.url == ch.url }.let { if (it >= 0) return it }
        chapters.indexOfFirst { it.url == ch.url }.let { if (it >= 0) return it }
        return chapters.indexOfFirst { it.index == ch.index }
    }

    /** 同步「当前位置 / 总话数 / 是否有上下话」，供底部导航条与章末入口使用 */
    private fun syncPosition() {
        val idx = currentIndex()
        _chapterIndex.value = if (idx >= 0) idx else 0
        _chapterCount.value = _chapters.value.size
        _hasPrevChapter.value = idx > 0
        _hasNextChapter.value = idx >= 0 && idx + 1 < _chapters.value.size
    }

    /** 切到下一话；已到最后一话返回 false（调用方据此提示） */
    fun nextChapter(): Boolean {
        val idx = currentIndex()
        val chapters = _chapters.value
        if (idx >= 0 && idx + 1 < chapters.size) {
            loadContent(chapters[idx + 1])
            return true
        }
        return false
    }

    /** 切到上一话；已在第一话返回 false */
    fun prevChapter(): Boolean {
        val idx = currentIndex()
        if (idx > 0) {
            loadContent(_chapters.value[idx - 1])
            return true
        }
        return false
    }

    private fun saveProgress() {
        val item = currentItem ?: return
        val chapter = currentChapter ?: return
        viewModelScope.launch {
            progressRepository.saveProgress(
                sourceId = item.sourceId,
                itemId = item.id,
                chapterUrl = chapter.url,
                chapterTitle = chapter.title,
                chapterIndex = chapter.index,
                itemType = com.novelreader.domain.model.SourceType.COMIC.name,
                itemTitle = item.title,
                itemCover = item.cover,
                itemAuthor = item.author,
                totalChapter = _chapters.value.size,
            )
        }
    }

    private companion object {
        /** 同章连续失败多少张才认定「整批地址失效」 */
        const val REFRESH_THRESHOLD = 3

        /** 每章最多重解析次数 */
        const val MAX_REFRESH_PER_CHAPTER = 3

        /** 两次重解析的最小间隔 */
        const val REFRESH_MIN_INTERVAL_MS = 5_000L
    }
}
