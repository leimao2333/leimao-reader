package com.novelreader.ui.screens.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.core.text.ReplaceRule
import com.novelreader.core.text.TextCleaner
import com.novelreader.core.text.TextReplaceRules
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.Bookmark
import com.novelreader.domain.repository.BookmarkRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.source.search.ChapterLocator
import com.novelreader.source.search.FuzzyMatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 小说阅读 ViewModel（v1.1.0.0 大修）。
 *
 * 对标开源阅读补齐的能力：
 * - 章节预加载（下一章 / 上一章）与内存缓存，翻页切章近乎瞬时；
 * - 书签（增删查 + 跳转）；
 * - 正文净化（替换规则）；
 * - 阅读时长统计；
 * - 统一翻页方式（覆盖 / 仿真 / 滑动 / 滚动 / 上下 / 无动画）与扩展阅读设置。
 */
class NovelReaderViewModel(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
    private val preferences: PreferencesManager,
    private val chapterCacheRepository: com.novelreader.domain.repository.ChapterCacheRepository? = null,
    private val bookRepository: BookRepository? = null,
    private val bookmarkRepository: BookmarkRepository? = null,
    /** 朗读控制器（v1.1.8.0 对标开源阅读听书）；为 null 时朗读功能不可用 */
    private val ttsController: com.novelreader.core.tts.TtsController? = null,
) : ViewModel() {

    // ===== 朗读（v1.1.8.0 对标开源阅读 ReadAloud） =====
    /** 是否正在朗读（由 TTS 引擎回调驱动） */
    val speaking: StateFlow<Boolean> = ttsController?.speaking
        ?: kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 朗读语速（0.5 ~ 2.0，1.0 = 正常） */
    private val _speechRate = MutableStateFlow(ttsController?.getSpeechRate() ?: 1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    /** 朗读当前章节正文 */
    fun speakCurrentChapter() {
        val controller = ttsController
        if (controller == null) {
            showToast("朗读功能不可用")
            return
        }
        val text = _content.value
        if (text.isBlank()) {
            showToast("正文为空，无法朗读")
            return
        }
        // 长文本分段：TTS 单次朗读有长度上限，按段落切分后逐段朗读
        controller.speak(text)
        showToast("开始朗读本章")
    }

    /** 停止朗读 */
    fun stopSpeaking() {
        ttsController?.stop()
    }

    /** 设置语速并即时生效 */
    fun setSpeechRate(rate: Float) {
        val r = rate.coerceIn(0.5f, 2.0f)
        _speechRate.value = r
        ttsController?.setSpeechRate(r)
    }

    /**
     * 离开阅读页时停止朗读（但不销毁 TTS 引擎——它由 AppContainer 单例持有、跨页面复用）。
     */
    override fun onCleared() {
        super.onCleared()
        ttsController?.stop()
    }

    // ===== 正文 =====
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    /** 当前章未净化原文（替换规则变更时据此重新净化） */
    private var currentRaw: String = ""

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 轻量提示条（换源成功 / 翻页反馈 / 章节边界提示），由 UI 展示后自动消失 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 是否已收藏（顶栏收藏按钮用） */
    private val _collected = MutableStateFlow(false)
    val collected: StateFlow<Boolean> = _collected.asStateFlow()

    private val _hasNextChapter = MutableStateFlow(false)
    val hasNextChapter: StateFlow<Boolean> = _hasNextChapter.asStateFlow()

    private val _hasPrevChapter = MutableStateFlow(false)
    val hasPrevChapter: StateFlow<Boolean> = _hasPrevChapter.asStateFlow()

    /** 当前书籍信息（换源后会更新，供顶栏书名 / 来源实时刷新） */
    private val _currentItem = MutableStateFlow<MediaItem?>(null)
    val currentItemState: StateFlow<MediaItem?> = _currentItem.asStateFlow()

    /** 当前正文是否来自本地缓存（离线缓存提示） */
    private val _fromCache = MutableStateFlow(false)
    val fromCache: StateFlow<Boolean> = _fromCache.asStateFlow()

    /** 已缓存章节索引（目录标记「已缓存」） */
    private val _cachedIndices = MutableStateFlow<Set<Int>>(emptySet())
    val cachedIndices: StateFlow<Set<Int>> = _cachedIndices.asStateFlow()

    /** 当前章字数（界面面板展示） */
    private val _charCount = MutableStateFlow(0)
    val charCount: StateFlow<Int> = _charCount.asStateFlow()

    // ===== 章节信息 =====
    private var currentItem: MediaItem? = null
    private var currentChapter: ChapterItem? = null

    private val _chapters = MutableStateFlow<List<ChapterItem>>(emptyList())
    val chapters: StateFlow<List<ChapterItem>> = _chapters.asStateFlow()

    private val _chapterTitle = MutableStateFlow("")
    val chapterTitle: StateFlow<String> = _chapterTitle.asStateFlow()

    // ===== 章节内存缓存（预加载） =====
    /** 章节正文原文内存缓存：chapterIndex -> raw */
    private val memoryCache = LinkedHashMap<Int, String>()

    private fun putMemory(index: Int, raw: String) {
        if (raw.isBlank()) return
        memoryCache[index] = raw
        while (memoryCache.size > 8) {
            val eldest = memoryCache.keys.firstOrNull() ?: break
            memoryCache.remove(eldest)
        }
    }

    private fun clearMemory() = memoryCache.clear()

    /** 预加载任务（切章时取消重启） */
    private var preloadJob: Job? = null

    // ===== 阅读设置（从 DataStore 订阅） =====
    private fun <T> stateOf(flow: kotlinx.coroutines.flow.Flow<T>, default: T): StateFlow<T> {
        val state = MutableStateFlow(default)
        viewModelScope.launch { flow.collectLatest { state.value = it } }
        return state
    }

    val fontSize: StateFlow<Float> = stateOf(preferences.novelFontSizeFlow, PreferencesManager.DEFAULT_FONT_SIZE)
    val bgIndex: StateFlow<Int> = stateOf(preferences.novelBgIndexFlow, 0)
    val nightMode: StateFlow<Boolean> = stateOf(preferences.novelNightFlow, false)

    /** 统一翻页方式：0=覆盖 1=仿真 2=滑动 3=滚动 4=上下 5=无动画 */
    val pageType: StateFlow<Int> = stateOf(preferences.novelPageTypeFlow, PreferencesManager.DEFAULT_PAGE_TYPE)

    val hideTopBar: StateFlow<Boolean> = stateOf(preferences.novelHideTopBarFlow, false)
    val hideBottomBar: StateFlow<Boolean> = stateOf(preferences.novelHideBottomBarFlow, false)
    val hideChapterTitle: StateFlow<Boolean> = stateOf(preferences.novelHideChapterTitleFlow, false)
    val lineHeight: StateFlow<Float> = stateOf(preferences.novelLineHeightFlow, PreferencesManager.DEFAULT_LINE_HEIGHT)
    val paragraphSpacing: StateFlow<Float> = stateOf(preferences.novelParagraphSpacingFlow, PreferencesManager.DEFAULT_PARAGRAPH_SPACING)
    val sidePadding: StateFlow<Float> = stateOf(preferences.novelSidePaddingFlow, PreferencesManager.DEFAULT_SIDE_PADDING)
    val autoPage: StateFlow<Boolean> = stateOf(preferences.novelAutoPageFlow, false)
    val autoPageInterval: StateFlow<Int> = stateOf(preferences.novelAutoPageIntervalFlow, PreferencesManager.DEFAULT_AUTO_PAGE_INTERVAL)
    val volumePage: StateFlow<Boolean> = stateOf(preferences.novelVolumePageFlow, false)

    // 大修新增设置
    val fontFamily: StateFlow<Int> = stateOf(preferences.novelFontFamilyFlow, PreferencesManager.FONT_FAMILY_DEFAULT)
    val bold: StateFlow<Boolean> = stateOf(preferences.novelBoldFlow, false)
    val indent: StateFlow<Boolean> = stateOf(preferences.novelIndentFlow, true)
    val letterSpacing: StateFlow<Float> = stateOf(preferences.novelLetterSpacingFlow, 0f)
    val brightness: StateFlow<Float> = stateOf(preferences.novelBrightnessFlow, PreferencesManager.DEFAULT_BRIGHTNESS)
    val fullScreen: StateFlow<Boolean> = stateOf(preferences.novelFullScreenFlow, false)
    val volumeUpPrev: StateFlow<Boolean> = stateOf(preferences.novelVolumeUpPrevFlow, true)
    val readSeconds: StateFlow<Long> = stateOf(preferences.novelReadSecondsFlow, 0L)
    val replaceRules: StateFlow<List<ReplaceRule>> =
        stateOf(preferences.novelReplaceRulesFlow, "").let { raw ->
            // raw 为字符串流，转成规则列表
            val state = MutableStateFlow<List<ReplaceRule>>(emptyList())
            viewModelScope.launch { raw.collectLatest { state.value = TextReplaceRules.decode(it) } }
            state
        }

    // ===== 简繁转换（v1.1.8.0 对标开源阅读） =====
    /** 0=不转换 1=简转繁 2=繁转简 */
    val convertMode: StateFlow<Int> =
        stateOf(preferences.novelConvertModeFlow, PreferencesManager.CONVERT_NONE)

    fun setConvertMode(mode: Int) {
        viewModelScope.launch { preferences.setNovelConvertMode(mode) }
    }

    // ===== 正文内搜索（v1.1.8.0 对标开源阅读 SearchMenu） =====
    private val _contentSearchOpen = MutableStateFlow(false)
    val contentSearchOpen: StateFlow<Boolean> = _contentSearchOpen.asStateFlow()

    /** 当前搜索关键词 */
    private val _contentQuery = MutableStateFlow("")
    val contentQuery: StateFlow<String> = _contentQuery.asStateFlow()

    /** 关键词在正文中出现的下标列表 */
    private val _contentMatches = MutableStateFlow<List<Int>>(emptyList())
    val contentMatches: StateFlow<List<Int>> = _contentMatches.asStateFlow()

    /** 当前高亮的匹配项下标（-1 = 无） */
    private val _currentMatchIndex = MutableStateFlow(-1)
    val currentMatchIndex: StateFlow<Int> = _currentMatchIndex.asStateFlow()

    fun openContentSearch() {
        _contentSearchOpen.value = true
    }

    fun closeContentSearch() {
        _contentSearchOpen.value = false
        _contentQuery.value = ""
        _contentMatches.value = emptyList()
        _currentMatchIndex.value = -1
        pendingMatchPosition = -1
    }

    /**
     * 更新搜索关键词并在当前正文中查找所有匹配。
     *
     * 空关键词清空结果；匹配为空时 currentMatchIndex 置 -1。
     */
    fun updateContentQuery(query: String) {
        _contentQuery.value = query
        recomputeMatches()
    }

    /** 跳到指定匹配项 */
    fun gotoMatch(index: Int) {
        val size = _contentMatches.value.size
        if (size == 0) return
        _currentMatchIndex.value = index.coerceIn(0, size - 1)
    }

    /** 下一个匹配（循环） */
    fun nextMatch() {
        val size = _contentMatches.value.size
        if (size == 0) return
        _currentMatchIndex.value = (_currentMatchIndex.value + 1) % size
    }

    /** 上一个匹配（循环） */
    fun prevMatch() {
        val size = _contentMatches.value.size
        if (size == 0) return
        _currentMatchIndex.value =
            if (_currentMatchIndex.value <= 0) size - 1 else _currentMatchIndex.value - 1
    }

    /** 按当前关键词重算匹配（正文加载/切换后调用） */
    private fun recomputeMatches() {
        val query = _contentQuery.value
        val text = _content.value
        if (query.isBlank() || text.isEmpty()) {
            _contentMatches.value = emptyList()
            _currentMatchIndex.value = -1
            return
        }
        val matches = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = text.indexOf(query, from)
            if (idx < 0) break
            matches.add(idx)
            from = idx + query.length
            if (from >= text.length) break
        }
        _contentMatches.value = matches
        _currentMatchIndex.value = if (matches.isEmpty()) -1 else 0
    }

    // ===== 全书搜索（v1.3.0.0 对标开源阅读「搜索全书」） =====

    /** 全书搜索结果项 */
    data class BookSearchResult(
        /** 章节在目录列表中的位置（switchChapter 用） */
        val listIndex: Int,
        /** 章节 index 字段 */
        val chapterIndex: Int,
        val chapterTitle: String,
        /** 匹配位置（净化后正文内下标） */
        val position: Int,
        /** 匹配处上下文摘录 */
        val excerpt: String,
        /** 该章正文总长（估算章内位置比例用） */
        val chapterLength: Int,
    )

    private val _bookSearchOpen = MutableStateFlow(false)
    val bookSearchOpen: StateFlow<Boolean> = _bookSearchOpen.asStateFlow()

    /** 全书搜索关键词 */
    private val _bookSearchQuery = MutableStateFlow("")
    val bookSearchQuery: StateFlow<String> = _bookSearchQuery.asStateFlow()

    private val _bookSearchResults = MutableStateFlow<List<BookSearchResult>>(emptyList())
    val bookSearchResults: StateFlow<List<BookSearchResult>> = _bookSearchResults.asStateFlow()

    /** 全书搜索进度提示（null = 无进行中任务） */
    private val _bookSearchProgress = MutableStateFlow<String?>(null)
    val bookSearchProgress: StateFlow<String?> = _bookSearchProgress.asStateFlow()

    private var bookSearchJob: Job? = null

    /** 待定位的章内匹配位置（跳转到全书搜索结果时用，正文加载后消费） */
    private var pendingMatchPosition: Int = -1

    fun openBookSearch() {
        _bookSearchOpen.value = true
        _bookSearchResults.value = emptyList()
        _bookSearchProgress.value = null
    }

    fun closeBookSearch() {
        bookSearchJob?.cancel()
        _bookSearchOpen.value = false
        _bookSearchProgress.value = null
    }

    /**
     * 全书搜索：并发抓取各章正文（内存 → 章节缓存 → 网络），逐章查找关键词。
     *
     * - 4 路并发 + 增量进度，避免千章大书卡死；
     * - 抓到的正文顺带写入章节缓存，搜索本身也是一次预热；
     * - 结果上限 500 条，防止 UI 卡顿。
     */
    fun searchWholeBook(query: String) {
        _bookSearchQuery.value = query
        bookSearchJob?.cancel()
        if (query.isBlank()) {
            _bookSearchResults.value = emptyList()
            _bookSearchProgress.value = null
            return
        }
        val item = currentItem ?: return
        val chapters = _chapters.value
        if (chapters.isEmpty()) return
        val repo = chapterCacheRepository
        val rules = replaceRules.value
        bookSearchJob = viewModelScope.launch {
            _bookSearchProgress.value = "正在搜索 0/${chapters.size}"
            val results = java.util.Collections.synchronizedList(mutableListOf<BookSearchResult>())
            val semaphore = Semaphore(4)
            var done = 0
            val doneLock = Any()
            coroutineScope {
                chapters.forEachIndexed { listIndex, ch ->
                    launch {
                        semaphore.withPermit {
                            val raw = run {
                                val mem = memoryCache[ch.index]
                                if (!mem.isNullOrBlank()) return@run mem
                                val cached = repo?.let {
                                    runCatching { it.getCached(item.sourceId, item.id, ch.index) }.getOrNull()
                                }
                                if (!cached.isNullOrBlank()) return@run cached
                                val fetched = runCatching { contentRepository.content(ch) }
                                    .getOrNull()?.novelText?.trim().orEmpty()
                                if (fetched.isNotBlank() && repo != null) {
                                    runCatching { repo.saveCache(item, ch.index, ch.title, ch.url, fetched) }
                                }
                                fetched
                            }
                            if (raw.isNotBlank()) {
                                val text = TextCleaner.clean(raw, rules)
                                var from = 0
                                while (results.size < 500) {
                                    val idx = text.indexOf(query, from)
                                    if (idx < 0) break
                                    val start = (idx - 20).coerceAtLeast(0)
                                    val end = (idx + query.length + 30).coerceAtMost(text.length)
                                    val excerpt = text.substring(start, end)
                                        .replace("\n", " ")
                                        .let { s ->
                                            (if (start > 0) "…" else "") + s + (if (end < text.length) "…" else "")
                                        }
                                    results.add(
                                        BookSearchResult(
                                            listIndex = listIndex,
                                            chapterIndex = ch.index,
                                            chapterTitle = ch.title,
                                            position = idx,
                                            excerpt = excerpt,
                                            chapterLength = text.length,
                                        )
                                    )
                                    from = idx + query.length
                                    if (from >= text.length) break
                                }
                            }
                            synchronized(doneLock) {
                                done++
                                if (done % 5 == 0 || done == chapters.size) {
                                    _bookSearchProgress.value = "正在搜索 $done/${chapters.size}"
                                }
                            }
                        }
                    }
                }
            }
            results.sortBy { it.listIndex }
            _bookSearchResults.value = results.toList()
            _bookSearchProgress.value =
                if (results.isEmpty()) "全书未找到「$query」" else null
        }
    }

    /** 跳转到全书搜索结果：切章 + 章内按位置定位匹配 */
    fun gotoBookResult(result: BookSearchResult) {
        val chapters = _chapters.value
        if (result.listIndex !in chapters.indices) {
            showToast("章节已不存在")
            return
        }
        bookSearchJob?.cancel()
        _bookSearchOpen.value = false
        _bookSearchProgress.value = null
        _contentSearchOpen.value = true
        _contentQuery.value = _bookSearchQuery.value
        pendingMatchPosition = result.position
        // 章内位置 → 比例（供滚动恢复），正文加载后 applyContent 会按 pendingMatchPosition 精确定位匹配
        val ratio = if (result.chapterLength > 0) {
            (result.position.toFloat() / result.chapterLength * 10000f).toInt().coerceIn(0, 10000)
        } else 0
        jumpTo(result.listIndex, ratio)
    }

    /** 菜单开关 */
    private val _showMenu = MutableStateFlow(false)
    val showMenu: StateFlow<Boolean> = _showMenu.asStateFlow()

    /** 菜单页签：0=目录，1=界面，2=设置 */
    private val _menuTab = MutableStateFlow(0)
    val menuTab: StateFlow<Int> = _menuTab.asStateFlow()

    /** 目录搜索关键词 */
    private val _chapterQuery = MutableStateFlow("")
    val chapterQuery: StateFlow<String> = _chapterQuery.asStateFlow()

    /** 目录倒序 */
    private val _chapterDesc = MutableStateFlow(false)
    val chapterDesc: StateFlow<Boolean> = _chapterDesc.asStateFlow()

    /** 正文净化规则配置面板开关 */
    private val _replaceOpen = MutableStateFlow(false)
    val replaceOpen: StateFlow<Boolean> = _replaceOpen.asStateFlow()

    fun openReplaceRules() {
        _replaceOpen.value = true
    }

    fun closeReplaceRules() {
        _replaceOpen.value = false
    }

    /** 当前章节索引 */
    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    /** 进入章节时用于恢复的正文位置比例（0~10000） */
    private val _initialTextPosition = MutableStateFlow(0)
    val initialTextPosition: StateFlow<Int> = _initialTextPosition.asStateFlow()

    private var lastTextPosition: Int = 0

    /** 进入本章时用于恢复的翻页页码（0 起） */
    private val _initialPageIndex = MutableStateFlow(0)
    val initialPageIndex: StateFlow<Int> = _initialPageIndex.asStateFlow()

    private var lastPageIndex: Int = 0

    // ===== 换源 =====
    private val _changeSourceOpen = MutableStateFlow(false)
    val changeSourceOpen: StateFlow<Boolean> = _changeSourceOpen.asStateFlow()

    private val _changeSourceCandidates = MutableStateFlow<List<MediaItem>>(emptyList())
    val changeSourceCandidates: StateFlow<List<MediaItem>> = _changeSourceCandidates.asStateFlow()

    private val _changeSourceLoading = MutableStateFlow(false)
    val changeSourceLoading: StateFlow<Boolean> = _changeSourceLoading.asStateFlow()

    /** 换源搜索进度：已搜源数 / 总源数 + 当前源名，用于「换源有进度」 */
    private val _changeSourceProgress = MutableStateFlow<String?>(null)
    val changeSourceProgress: StateFlow<String?> = _changeSourceProgress.asStateFlow()

    private val _sameAuthorOnly = MutableStateFlow(true)
    val sameAuthorOnly: StateFlow<Boolean> = _sameAuthorOnly.asStateFlow()

    private val _changeSourceError = MutableStateFlow<String?>(null)
    val changeSourceError: StateFlow<String?> = _changeSourceError.asStateFlow()

    private val _changeSourceHasMore = MutableStateFlow(false)
    val changeSourceHasMore: StateFlow<Boolean> = _changeSourceHasMore.asStateFlow()

    private val _changeSourceLoadingMore = MutableStateFlow(false)
    val changeSourceLoadingMore: StateFlow<Boolean> = _changeSourceLoadingMore.asStateFlow()

    private var changeSourcePage: Int = 1
    private var _allCandidates: List<MediaItem> = emptyList()
    /** 候选验证任务（0 章 / 无内容过滤），换源关闭或重新搜索时取消 */
    private var verifyJob: Job? = null
    /** 已验证「0 章」的候选 key 集合（在候选中排除） */
    private val emptyChapterKeys = HashSet<String>()

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _currentAuthor = MutableStateFlow<String?>(null)
    val currentAuthor: StateFlow<String?> = _currentAuthor.asStateFlow()

    // ===== 书签 =====
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    /** 初始化阅读（进入阅读页时调用） */
    fun init(item: MediaItem, chapters: List<ChapterItem>, chapter: ChapterItem) {
        val sameBook = currentItem?.sourceId == item.sourceId && currentItem?.id == item.id
        if (sameBook && _chapters.value == chapters && currentChapter?.url == chapter.url) {
            // 同一本书同一章重复进入：不重载，仅刷新外围状态
            refreshCachedIndices()
            return
        }
        clearMemory()
        currentItem = item
        _currentItem.value = item
        _chapters.value = cleanChapterTitles(chapters)
        currentChapter = chapter
        _chapterTitle.value = cleanChapterTitle(chapter.title)
        _currentTitle.value = item.title
        _currentAuthor.value = item.author
        refreshNavFlags()
        viewModelScope.launch {
            runCatching { bookRepository?.isCollected(item) == true }
                .onSuccess { _collected.value = it }
            val saved = runCatching {
                progressRepository.getProgress(item.sourceId, item.id)
            }.getOrNull()
            if (saved != null && saved.chapterUrl == chapter.url && saved.textPosition > 0) {
                lastTextPosition = saved.textPosition.coerceIn(0, 10000)
                _initialTextPosition.value = lastTextPosition
            } else {
                lastTextPosition = 0
                _initialTextPosition.value = 0
            }
            if (saved != null && saved.chapterUrl == chapter.url && saved.pageIndex > 0) {
                lastPageIndex = saved.pageIndex.coerceAtLeast(0)
                _initialPageIndex.value = lastPageIndex
            } else {
                lastPageIndex = 0
                _initialPageIndex.value = 0
            }
            refreshCachedIndices()
            refreshBookmarks()
            loadChapter(chapter)
        }
    }

    /** 刷新已缓存章节索引 */
    fun refreshCachedIndices() {
        val item = currentItem ?: return
        val repo = chapterCacheRepository ?: return
        viewModelScope.launch {
            runCatching { repo.cachedIndices(item.sourceId, item.id) }
                .onSuccess { _cachedIndices.value = it }
        }
    }

    /** 手动缓存当前章节 */
    fun cacheCurrentChapter() {
        val item = currentItem ?: return
        val chapter = currentChapter ?: return
        val repo = chapterCacheRepository ?: return
        val text = currentRaw
        if (text.isBlank()) {
            showToast("本章暂无可缓存内容")
            return
        }
        viewModelScope.launch {
            runCatching {
                repo.saveCache(item, chapter.index, chapter.title, chapter.url, text)
            }.onSuccess {
                refreshCachedIndices()
                showToast("已缓存本章")
            }
        }
    }

    /** 刷新「是否有上一章 / 下一章」标记 */
    private fun refreshNavFlags() {
        val idx = currentIndex()
        val size = _chapters.value.size
        _hasPrevChapter.value = idx > 0
        _hasNextChapter.value = idx >= 0 && idx < size - 1
    }

    /** 轻量提示 */
    fun showToast(message: String) {
        _toast.value = message
    }

    fun dismissToast() {
        _toast.value = null
    }

    /** 收藏 / 取消收藏当前书 */
    fun toggleCollect() {
        val repo = bookRepository ?: return
        val item = currentItem ?: return
        viewModelScope.launch {
            if (_collected.value) {
                runCatching { repo.removeFromBookshelf(item) }
                    .onSuccess { _collected.value = false; showToast("已移出书架") }
            } else {
                runCatching { repo.addToBookshelf(item) }
                    .onSuccess { _collected.value = true; showToast("已加入书架") }
            }
        }
    }

    /** 正文加载失败后重试本章 */
    fun retry() {
        currentChapter?.let { loadChapter(it, forceRefresh = true) }
    }

    /** 切换章节（重置位置，回到该章顶部 / 第一页） */
    fun loadContent(chapter: ChapterItem) {
        lastTextPosition = 0
        _initialTextPosition.value = 0
        lastPageIndex = 0
        _initialPageIndex.value = 0
        loadChapter(chapter)
    }

    /**
     * 加载章节正文。
     *
     * 取内容顺序：内存预加载缓存 → 本地章节缓存 → 网络。
     * 命中缓存时不显示 loading 遮罩，做到「切章秒开」。
     */
    private fun loadChapter(chapter: ChapterItem, forceRefresh: Boolean = false) {
        currentChapter = chapter
        _chapterTitle.value = cleanChapterTitle(chapter.title)
        _currentChapterIndex.value = chapter.index
        refreshNavFlags()
        val item = currentItem

        if (!forceRefresh) {
            val mem = memoryCache[chapter.index]
            if (!mem.isNullOrBlank()) {
                applyContent(mem, fromCache = false)
                _loading.value = false
                _error.value = null
                preloadAround(chapter.index)
                saveProgress()
                return
            }
        }

        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            if (!forceRefresh && item != null && chapterCacheRepository != null) {
                val cached = runCatching {
                    chapterCacheRepository.getCached(item.sourceId, item.id, chapter.index)
                }.getOrNull()
                if (!cached.isNullOrBlank()) {
                    putMemory(chapter.index, cached)
                    applyContent(cached, fromCache = true)
                    _loading.value = false
                    preloadAround(chapter.index)
                    saveProgress()
                    return@launch
                }
            }

            runCatching { contentRepository.content(chapter) }
                .onSuccess { result ->
                    val raw = result.novelText?.trim().orEmpty()
                    if (raw.isEmpty()) {
                        _content.value = ""
                        currentRaw = ""
                        _charCount.value = 0
                        _error.value = "本章正文为空：该书源可能已失效，可在菜单中「换源」重试"
                    } else {
                        putMemory(chapter.index, raw)
                        applyContent(raw, fromCache = false)
                        if (item != null && chapterCacheRepository != null) {
                            runCatching {
                                chapterCacheRepository.saveCache(
                                    item = item,
                                    chapterIndex = chapter.index,
                                    chapterTitle = chapter.title,
                                    chapterUrl = chapter.url,
                                    content = raw,
                                )
                            }.onSuccess { refreshCachedIndices() }
                        }
                        saveProgress()
                    }
                }
                .onFailure { e -> _error.value = e.message ?: "加载失败" }
            _loading.value = false
            preloadAround(chapter.index)
        }
    }

    /** 净化并落地正文 */
    private fun applyContent(raw: String, fromCache: Boolean) {
        currentRaw = raw
        val cleaned = TextCleaner.clean(raw, replaceRules.value)
        _content.value = cleaned
        _fromCache.value = fromCache
        _charCount.value = TextCleaner.charCount(cleaned)
        // 有进行中的章内查询时重算匹配（全书搜索跳章 / 切章后保持搜索态）
        if (_contentQuery.value.isNotBlank()) {
            recomputeMatches()
            // 消费全书搜索的定位请求：跳到目标位置之后（或最后一个）匹配项
            if (pendingMatchPosition >= 0) {
                val matches = _contentMatches.value
                val target = matches.indexOfFirst { it >= pendingMatchPosition }
                _currentMatchIndex.value = when {
                    target >= 0 -> target
                    matches.isNotEmpty() -> matches.lastIndex
                    else -> -1
                }
                pendingMatchPosition = -1
            }
        }
    }

    /**
     * 章节名净化（对标 legado TocRegex）：把替换规则同样应用到章节标题，
     * 去掉「123456第X章 xxx」「最新章节：」等站点噪音。单条规则出错自动跳过。
     */
    private fun cleanChapterTitle(title: String): String =
        TextReplaceRules.apply(title, replaceRules.value).trim()

    /** 批量清洗目录标题 */
    private fun cleanChapterTitles(chapters: List<ChapterItem>): List<ChapterItem> {
        val rules = replaceRules.value
        if (rules.isEmpty()) return chapters
        return chapters.map { ch -> ch.copy(title = TextReplaceRules.apply(ch.title, rules).trim()) }
    }

    /** 预加载相邻章节（下一章优先，其次上一章），提升翻章流畅度 */
    private fun preloadAround(index: Int) {
        preloadJob?.cancel()
        val chapters = _chapters.value
        val targets = buildList {
            chapters.getOrNull(index + 1)?.let { add(it) }
            chapters.getOrNull(index - 1)?.let { add(it) }
        }
        if (targets.isEmpty()) return
        preloadJob = viewModelScope.launch {
            for (ch in targets) {
                if (memoryCache[ch.index]?.isNotBlank() == true) continue
                if (ch.index == currentChapter?.index) continue
                val item = currentItem ?: break
                // 先本地缓存
                val cached = chapterCacheRepository?.let {
                    runCatching { it.getCached(item.sourceId, item.id, ch.index) }.getOrNull()
                }
                if (!cached.isNullOrBlank()) {
                    putMemory(ch.index, cached)
                    continue
                }
                // 再网络预取
                val raw = runCatching { contentRepository.content(ch) }
                    .getOrNull()?.novelText?.trim().orEmpty()
                if (raw.isNotBlank()) {
                    putMemory(ch.index, raw)
                    chapterCacheRepository?.let { repo ->
                        runCatching {
                            repo.saveCache(item, ch.index, ch.title, ch.url, raw)
                        }
                    }
                }
            }
            refreshCachedIndices()
        }
    }

    /** 切换章节（按列表下标） */
    fun switchChapter(index: Int) {
        val chapters = _chapters.value
        if (index in chapters.indices) loadContent(chapters[index])
    }

    /** 当前章节在列表中的位置 */
    private fun currentIndex(): Int {
        val chapters = _chapters.value
        val ch = currentChapter ?: return -1
        chapters.forEachIndexed { i, c -> if (c === ch) return i }
        chapters.indexOfFirst { it.index == ch.index && it.url == ch.url }.let { if (it >= 0) return it }
        chapters.indexOfFirst { it.url == ch.url }.let { if (it >= 0) return it }
        return chapters.indexOfFirst { it.index == ch.index }
    }

    fun nextChapter() {
        val idx = currentIndex()
        val chapters = _chapters.value
        if (idx >= 0 && idx + 1 < chapters.size) {
            loadContent(chapters[idx + 1])
        } else {
            showToast("已是最后一章")
        }
    }

    fun prevChapter() {
        val idx = currentIndex()
        if (idx > 0) {
            loadContent(_chapters.value[idx - 1])
        } else {
            showToast("已是第一章")
        }
    }

    /** 跳转到某章（不重置位置，用于书签恢复） */
    fun jumpTo(chapterIndex: Int, ratio: Int) {
        val chapters = _chapters.value
        val ch = chapters.getOrNull(chapterIndex)
        if (ch == null) {
            showToast("章节已不存在")
            return
        }
        lastTextPosition = ratio.coerceIn(0, 10000)
        _initialTextPosition.value = lastTextPosition
        lastPageIndex = 0
        _initialPageIndex.value = 0
        loadChapter(ch)
    }

    // ===== 设置调节 =====
    fun increaseFontSize() {
        val steps = com.novelreader.ui.theme.ReaderBackgrounds.fontSizeSteps
        val next = steps.firstOrNull { it > fontSize.value } ?: steps.last()
        viewModelScope.launch { preferences.setNovelFontSize(next) }
    }

    fun decreaseFontSize() {
        val steps = com.novelreader.ui.theme.ReaderBackgrounds.fontSizeSteps
        val next = steps.lastOrNull { it < fontSize.value } ?: steps.first()
        viewModelScope.launch { preferences.setNovelFontSize(next) }
    }

    fun setBackground(index: Int) = viewModelScope.launch { preferences.setNovelBgIndex(index) }
    fun toggleNightMode() = viewModelScope.launch { preferences.setNovelNight(!nightMode.value) }
    fun setPageType(type: Int) = viewModelScope.launch { preferences.setNovelPageType(type) }
    fun setHideTopBar(v: Boolean) = viewModelScope.launch { preferences.setNovelHideTopBar(v) }
    fun setHideBottomBar(v: Boolean) = viewModelScope.launch { preferences.setNovelHideBottomBar(v) }
    fun setHideChapterTitle(v: Boolean) = viewModelScope.launch { preferences.setNovelHideChapterTitle(v) }
    fun setLineHeight(v: Float) = viewModelScope.launch { preferences.setNovelLineHeight(v) }
    fun setParagraphSpacing(v: Float) = viewModelScope.launch { preferences.setNovelParagraphSpacing(v) }
    fun setSidePadding(v: Float) = viewModelScope.launch { preferences.setNovelSidePadding(v) }
    fun setAutoPage(v: Boolean) = viewModelScope.launch { preferences.setNovelAutoPage(v) }
    fun setAutoPageInterval(v: Int) = viewModelScope.launch { preferences.setNovelAutoPageInterval(v) }
    fun setVolumePage(v: Boolean) = viewModelScope.launch { preferences.setNovelVolumePage(v) }
    fun setVolumeUpPrev(v: Boolean) = viewModelScope.launch { preferences.setNovelVolumeUpPrev(v) }
    fun setFontFamily(v: Int) = viewModelScope.launch { preferences.setNovelFontFamily(v) }
    fun setBold(v: Boolean) = viewModelScope.launch { preferences.setNovelBold(v) }
    fun setIndent(v: Boolean) = viewModelScope.launch { preferences.setNovelIndent(v) }
    fun setLetterSpacing(v: Float) = viewModelScope.launch { preferences.setNovelLetterSpacing(v) }
    fun setFullScreen(v: Boolean) = viewModelScope.launch { preferences.setNovelFullScreen(v) }
    fun setBrightness(v: Float) = viewModelScope.launch { preferences.setNovelBrightness(v) }

    /** 保存替换规则并立即重新净化当前章 */
    fun setReplaceRules(rules: List<ReplaceRule>) {
        viewModelScope.launch {
            preferences.setNovelReplaceRules(TextReplaceRules.encode(rules))
            if (currentRaw.isNotBlank()) applyContent(currentRaw, _fromCache.value)
        }
    }

    // ===== 音量键翻页 =====
    private val _pageTurn = kotlinx.coroutines.flow.MutableSharedFlow<Int>(
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val pageTurn: kotlinx.coroutines.flow.SharedFlow<Int> = _pageTurn.asSharedFlow()

    fun volumePrevPage() {
        _pageTurn.tryEmit(-1)
        showToast("↑ 上一页")
    }

    fun volumeNextPage() {
        _pageTurn.tryEmit(1)
        showToast("↓ 下一页")
    }

    /** 单击屏幕中央：弹出/收起菜单（带连击去抖） */
    fun toggleMenu() {
        val now = System.currentTimeMillis()
        if (now - lastMenuToggleAt < 200L) return
        lastMenuToggleAt = now
        _showMenu.value = !_showMenu.value
    }

    /** 直接收起菜单（点选项目后调用） */
    fun closeMenu() {
        _showMenu.value = false
    }

    private var lastMenuToggleAt: Long = 0L

    fun setMenuTab(tab: Int) {
        _menuTab.value = tab
    }

    fun setChapterQuery(q: String) {
        _chapterQuery.value = q
    }

    fun toggleChapterOrder() {
        _chapterDesc.value = !_chapterDesc.value
    }

    // ===== 阅读时长 =====
    private var sessionStartMs: Long = 0L

    /** 阅读页可见时开始计时 */
    fun onReaderVisible() {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
    }

    /** 阅读页不可见时结算时长 */
    fun onReaderHidden() {
        flushReadTime()
    }

    private fun flushReadTime() {
        if (sessionStartMs == 0L) return
        val now = System.currentTimeMillis()
        val delta = (now - sessionStartMs) / 1000L
        sessionStartMs = now
        if (delta > 0) viewModelScope.launch { preferences.addNovelReadSeconds(delta) }
    }

    // ===== 书签 =====
    fun refreshBookmarks() {
        val repo = bookmarkRepository ?: return
        val item = currentItem ?: return
        viewModelScope.launch {
            runCatching { repo.listForBook(item.sourceId, item.id) }
                .onSuccess { _bookmarks.value = it }
        }
    }

    /**
     * 在当前阅读位置添加书签。
     *
     * @param ratio 当前章内位置比例（0~10000）
     */
    fun addBookmark(ratio: Int, snippet: String) {
        val repo = bookmarkRepository ?: return
        val item = currentItem ?: return
        val chapter = currentChapter ?: return
        viewModelScope.launch {
            runCatching {
                repo.add(
                    item = item,
                    chapterIndex = chapter.index,
                    chapterUrl = chapter.url,
                    chapterTitle = chapter.title,
                    textPosition = ratio.coerceIn(0, 10000),
                    snippet = snippet,
                )
            }.onSuccess {
                showToast("已添加书签")
                refreshBookmarks()
            }.onFailure { showToast("添加书签失败") }
        }
    }

    fun removeBookmark(id: Long) {
        val repo = bookmarkRepository ?: return
        viewModelScope.launch {
            runCatching { repo.removeById(id) }
                .onSuccess {
                    showToast("已删除书签")
                    refreshBookmarks()
                }
        }
    }

    /** 跳转到书签 */
    fun jumpToBookmark(bookmark: Bookmark) {
        val chapters = _chapters.value
        val pos = chapters.indexOfFirst { it.url == bookmark.chapterUrl && it.url.isNotBlank() }
            .let { if (it >= 0) it else chapters.indexOfFirst { it.index == bookmark.chapterIndex } }
        if (pos < 0) {
            showToast("书签所在章节已不存在")
            return
        }
        closeChangeSource()
        jumpTo(pos, bookmark.textPosition)
        showToast("已跳转到书签")
    }

    // ===== 换源 =====
    fun openChangeSource() {
        _changeSourceOpen.value = true
        emptyChapterKeys.clear()
        searchSameTitle()
    }

    fun closeChangeSource() {
        verifyJob?.cancel()
        _changeSourceOpen.value = false
    }

    fun setSameAuthorOnly(value: Boolean) {
        _sameAuthorOnly.value = value
        filterCandidates()
    }

    private fun searchSameTitle() {
        val title = _currentTitle.value
        if (title.isBlank()) return
        changeSourcePage = 1
        _changeSourceHasMore.value = false
        _changeSourceProgress.value = null
        viewModelScope.launch {
            _changeSourceLoading.value = true
            _changeSourceError.value = null
            // 换源搜索用「模糊模式」——书名归一化一致的结果排最前，其次近似匹配。
            // 精确模式要求书名完全等于关键词，不同源书名带书名号/副标题时全部漏掉，
            // 导致换源候选为空（换源「没用」的根因）。
            // 整体限时 60 秒：个别死源的挂起会超过单次读超时，不能陪它耗到底；
            // 候选增量刷新——每完成一个源就刷新一次，不等全部搜完。
            kotlinx.coroutines.withTimeoutOrNull(60_000) {
                contentRepository.searchWithProgress(
                    type = SourceType.NOVEL,
                    keyword = title,
                    page = 1,
                    concurrency = 8,
                    fuzzyEnabled = true,
                ).collectLatest { state ->
                    _changeSourceProgress.value =
                        if (state.isSearching) "正在搜索书源 ${state.searchedCount}/${state.totalCount}"
                        else null
                    _allCandidates = state.results
                    filterCandidates(verify = !state.isSearching)
                    if (!state.isSearching) {
                        _changeSourceHasMore.value = state.results.isNotEmpty()
                        _changeSourceLoading.value = false
                    }
                }
            } ?: run {
                // 超时：用已收集的结果收尾
                _changeSourceProgress.value = null
                _changeSourceHasMore.value = _allCandidates.isNotEmpty()
                filterCandidates(verify = true)
                _changeSourceLoading.value = false
            }
        }
    }

    fun loadMoreChangeSource() {
        val title = _currentTitle.value
        if (title.isBlank() || _changeSourceLoadingMore.value) return
        if (!_changeSourceHasMore.value) return
        val next = changeSourcePage + 1
        viewModelScope.launch {
            _changeSourceLoadingMore.value = true
            runCatching { contentRepository.search(SourceType.NOVEL, title, next) }
                .onSuccess { more ->
                    if (more.isEmpty()) {
                        _changeSourceHasMore.value = false
                    } else {
                        changeSourcePage = next
                        _allCandidates = mergeCandidates(_allCandidates, more)
                        filterCandidates()
                    }
                }
                .onFailure { e -> _changeSourceError.value = e.message ?: "加载更多失败" }
            _changeSourceLoadingMore.value = false
        }
    }

    /**
     * 合并换源候选：按「源 + 源内 ID」去重。
     * 换源语义是「同一本书在每个源各一条」——同名书来自不同源必须保留每一条，
     * 供用户挑选切换。因此不能按书名+作者合并（会把不同源误并成一条）。
     * 同一源内同 id 的重复条目才需要去重。
     */
    private fun mergeCandidates(base: List<MediaItem>, incoming: List<MediaItem>): List<MediaItem> {
        val merged = base.toMutableList()
        val seen = HashSet<String>(base.size)
        base.forEach { seen.add(candidateKey(it)) }
        for (item in incoming) {
            if (seen.add(candidateKey(item))) merged.add(item)
        }
        return merged
    }

    /** 候选去重键：源 + 源内 ID（同名书跨源不去重，换源需展示每个源） */
    private fun candidateKey(item: MediaItem): String {
        return "${item.sourceId}|${item.id.ifBlank { item.detailUrl }}"
    }

    private fun filterCandidates(verify: Boolean = true) {
        val title = _currentTitle.value.trim()
        val author = _currentAuthor.value
        val current = currentItem
        val sameAuthor = _sameAuthorOnly.value
        val titleNorm = FuzzyMatch.normalize(title)
        val filtered = _allCandidates.filter { cand ->
            // 排除当前正在用的源
            if (current != null && cand.sourceId == current.sourceId && cand.id == current.id) return@filter false
            // 书名归一化后与当前书名相等（同名），或以当前书名为前缀/包含（近似）
            val candTitleNorm = FuzzyMatch.normalize(cand.title)
            // 同源 + 同名（归一化后）视为当前书换了 id，同样排除
            if (current != null && cand.sourceId == current.sourceId && candTitleNorm == titleNorm) return@filter false
            val titleMatch = candTitleNorm == titleNorm ||
                candTitleNorm.startsWith(titleNorm) ||
                candTitleNorm.contains(titleNorm) ||
                titleNorm.contains(candTitleNorm)
            if (!titleMatch) return@filter false
            // 同作者过滤（作者归一化去前缀后比较）
            if (sameAuthor) {
                val candAuthor = cand.author?.trim()?.let { FuzzyMatch.normalizeAuthor(it) }
                val curAuthor = author?.trim()?.let { FuzzyMatch.normalizeAuthor(it) }
                if (curAuthor.isNullOrBlank() || candAuthor.isNullOrBlank()) true
                else candAuthor == curAuthor
            } else true
        }
        // 按「书名 + 作者」归一化合并去重，彻底消除同名书跨源重复
        val deduped = linkedMapOf<String, MediaItem>()
        for (cand in filtered) {
            val key = candidateKey(cand)
            // 排除已验证为「0 章」的候选
            if (key in emptyChapterKeys) continue
            deduped.putIfAbsent(key, cand)
        }
        _changeSourceCandidates.value = deduped.values.toList()
        // 后台验证候选章节数，0 章的候选从列表剔除（增量刷新阶段不验证，收尾统一验证）
        if (verify) verifyCandidates(deduped.values.toList())
    }

    /**
     * 后台逐个验证换源候选：剔除 0 章（目录为空）与「有章节但正文为空」的候选。
     * 并发小（3 路），避免阻塞；验证结果增量回写候选列表。
     */
    private fun verifyCandidates(candidates: List<MediaItem>) {
        verifyJob?.cancel()
        if (candidates.isEmpty()) return
        verifyJob = viewModelScope.launch {
            val semaphore = Semaphore(3)
            coroutineScope {
                candidates.map { cand ->
                    async {
                        semaphore.withPermit {
                            val key = candidateKey(cand)
                            if (key in emptyChapterKeys) return@withPermit
                            val chapters = runCatching { contentRepository.chapterList(cand) }
                                .getOrDefault(emptyList())
                            if (chapters.isEmpty()) {
                                emptyChapterKeys.add(key)
                                // 从候选列表剔除该 0 章候选
                                _changeSourceCandidates.value =
                                    _changeSourceCandidates.value.filter { candidateKey(it) != key }
                                return@withPermit
                            }
                            // 抽验第一章正文：有章节但抓不到正文 → 章节是空壳，同样剔除
                            val sample = runCatching { contentRepository.content(chapters.first()) }
                                .getOrNull()?.novelText?.trim().orEmpty()
                            if (sample.isEmpty()) {
                                emptyChapterKeys.add(key)
                                _changeSourceCandidates.value =
                                    _changeSourceCandidates.value.filter { candidateKey(it) != key }
                            }
                        }
                    }
                }.forEach { it.await() }
            }
        }
    }

    /** 切换到候选源（四级定位，避免一律回退第一章） */
    fun switchSource(target: MediaItem) {
        val curTitle = currentChapter?.title ?: ""
        val curPos = currentIndex()
        val oldTotal = _chapters.value.size
        viewModelScope.launch {
            _changeSourceLoading.value = true
            _changeSourceError.value = null
            runCatching { contentRepository.chapterList(target) }
                .onSuccess { newChapters ->
                    if (newChapters.isEmpty()) {
                        _changeSourceError.value = "该源目录为空，换源失败"
                        _changeSourceLoading.value = false
                        return@launch
                    }
                    val index = ChapterLocator.locate(newChapters, curTitle, curPos, oldTotal)
                    currentItem = target
                    _currentItem.value = target
                    _chapters.value = cleanChapterTitles(newChapters)
                    _currentTitle.value = target.title
                    _currentAuthor.value = target.author
                    clearMemory()
                    _changeSourceOpen.value = false
                    _allCandidates = emptyList()
                    _changeSourceCandidates.value = emptyList()
                    _changeSourceHasMore.value = false
                    changeSourcePage = 1
                    refreshBookmarks()
                    loadContent(newChapters[index])
                    val srcLabel = target.sourceName.ifBlank { target.title }
                    showToast("已切换到「$srcLabel」，定位到第 ${index + 1}/${newChapters.size} 章")
                }
                .onFailure { e -> _changeSourceError.value = e.message ?: "切换源失败" }
            _changeSourceLoading.value = false
        }
    }

    // ===== 进度保存 =====
    fun saveScrollRatio(chapterIndex: Int, ratio: Float) {
        if (currentChapter?.index != chapterIndex) return
        val pos = (ratio.coerceIn(0f, 1f) * 10000f).toInt()
        if (pos == lastTextPosition) return
        lastTextPosition = pos
        saveProgress()
    }

    fun savePageIndex(chapterIndex: Int, page: Int) {
        if (currentChapter?.index != chapterIndex) return
        val p = page.coerceAtLeast(0)
        if (p == lastPageIndex) return
        lastPageIndex = p
        saveProgress()
    }

    /** 翻页模式回写比例（书签 / 位置展示用，不落库位置字段） */
    fun updatePagerRatio(ratio: Int) {
        if (ratio in 0..10000) lastTextPosition = ratio
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
                textPosition = lastTextPosition,
                itemType = SourceType.NOVEL.name,
                itemTitle = item.title,
                itemCover = item.cover,
                itemAuthor = item.author,
                pageIndex = lastPageIndex,
                totalChapter = _chapters.value.size,
            )
        }
    }
}
