package com.novelreader.ui.screens.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.source.search.ChapterLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 小说阅读 ViewModel。
 *
 * 管理正文内容、章节切换，以及阅读设置（字体大小、背景色、日夜间）。
 * 设置通过 PreferencesManager 持久化，跨页面/重启保留。
 */
class NovelReaderViewModel(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
    private val preferences: PreferencesManager,
    private val chapterCacheRepository: com.novelreader.domain.repository.ChapterCacheRepository? = null,
    private val bookRepository: BookRepository? = null,
) : ViewModel() {

    // ===== 正文 =====
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

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

    /** 当前书是否有下一章（末章文案 / 是否显示「下一章」入口） */
    private val _hasNextChapter = MutableStateFlow(false)
    val hasNextChapter: StateFlow<Boolean> = _hasNextChapter.asStateFlow()

    /** 当前书是否有上一章 */
    private val _hasPrevChapter = MutableStateFlow(false)
    val hasPrevChapter: StateFlow<Boolean> = _hasPrevChapter.asStateFlow()

    /** 当前书籍信息（换源后会更新，供顶栏书名 / 来源实时刷新） */
    private val _currentItem = MutableStateFlow<MediaItem?>(null)
    val currentItemState: StateFlow<MediaItem?> = _currentItem.asStateFlow()

    /** 当前正文是否来自本地缓存（#30：UI 可提示「离线缓存」） */
    private val _fromCache = MutableStateFlow(false)
    val fromCache: StateFlow<Boolean> = _fromCache.asStateFlow()

    /** 已缓存章节索引（#30：目录标记「已缓存」） */
    private val _cachedIndices = MutableStateFlow<Set<Int>>(emptySet())
    val cachedIndices: StateFlow<Set<Int>> = _cachedIndices.asStateFlow()

    // ===== 章节信息 =====
    private var currentItem: MediaItem? = null
    private var currentChapter: ChapterItem? = null

    /** 章节列表（用于切换上一章/下一章） */
    private val _chapters = MutableStateFlow<List<ChapterItem>>(emptyList())
    val chapters: StateFlow<List<ChapterItem>> = _chapters.asStateFlow()

    /** 当前章节标题 */
    private val _chapterTitle = MutableStateFlow("")
    val chapterTitle: StateFlow<String> = _chapterTitle.asStateFlow()

    // ===== 阅读设置（从 DataStore 订阅） =====
    val fontSize: StateFlow<Float> = preferences.novelFontSizeFlow
        .let { flow ->
            val state = MutableStateFlow(PreferencesManager.DEFAULT_FONT_SIZE)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    val bgIndex: StateFlow<Int> = preferences.novelBgIndexFlow
        .let { flow ->
            val state = MutableStateFlow(0)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    val nightMode: StateFlow<Boolean> = preferences.novelNightFlow
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 翻页方式：0=上下滚动，1=左右翻页 */
    val pageMode: StateFlow<Int> = preferences.novelPageModeFlow
        .let { flow ->
            val state = MutableStateFlow(0)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 翻页动画：0=无动画，1=滑动，2=覆盖，3=仿真翻页 */
    val pageAnim: StateFlow<Int> = preferences.novelPageAnimFlow
        .let { flow ->
            val state = MutableStateFlow(1)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 隐藏栏目：顶部栏 */
    val hideTopBar: StateFlow<Boolean> = preferences.novelHideTopBarFlow
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 隐藏栏目：底部进度栏 */
    val hideBottomBar: StateFlow<Boolean> = preferences.novelHideBottomBarFlow
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 隐藏栏目：正文章节标题 */
    val hideChapterTitle: StateFlow<Boolean> = preferences.novelHideChapterTitleFlow
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 行距倍数 */
    val lineHeight: StateFlow<Float> = preferences.novelLineHeightFlow
        .let { flow ->
            val state = MutableStateFlow(PreferencesManager.DEFAULT_LINE_HEIGHT)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 段距（dp） */
    val paragraphSpacing: StateFlow<Float> = preferences.novelParagraphSpacingFlow
        .let { flow ->
            val state = MutableStateFlow(PreferencesManager.DEFAULT_PARAGRAPH_SPACING)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 左右页边距（dp） */
    val sidePadding: StateFlow<Float> = preferences.novelSidePaddingFlow
        .let { flow ->
            val state = MutableStateFlow(PreferencesManager.DEFAULT_SIDE_PADDING)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 自动翻页开关 */
    val autoPage: StateFlow<Boolean> = preferences.novelAutoPageFlow
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 自动翻页间隔（秒） */
    val autoPageInterval: StateFlow<Int> = preferences.novelAutoPageIntervalFlow
        .let { flow ->
            val state = MutableStateFlow(PreferencesManager.DEFAULT_AUTO_PAGE_INTERVAL)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 音量键翻页开关（#8） */
    val volumePage: StateFlow<Boolean> = preferences.novelVolumePageFlow
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collectLatest { state.value = it } }
            state
        }

    /** 是否显示菜单（顶栏 + 底部菜单） */
    private val _showMenu = MutableStateFlow(false)
    val showMenu: StateFlow<Boolean> = _showMenu.asStateFlow()

    /** 菜单当前页签：0=目录，1=界面，2=设置 */
    private val _menuTab = MutableStateFlow(0)
    val menuTab: StateFlow<Int> = _menuTab.asStateFlow()

    /** 当前章节索引（进度滑块用） */
    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    /**
     * 进入章节时用于恢复滚动的正文位置比例（0~10000）。
     *
     * 0 表示从顶部开始；非 0 表示该章上次退出时的位置，滚动页据此还原。
     */
    private val _initialTextPosition = MutableStateFlow(0)
    val initialTextPosition: StateFlow<Int> = _initialTextPosition.asStateFlow()

    /** 当前章节内的正文位置比例（0~10000），随滚动回写并持久化 */
    private var lastTextPosition: Int = 0

    /**
     * 进入本章时用于恢复的翻页模式页码（0 起）。
     *
     * 仅在翻页方式=左右翻页、且历史进度章节与本次进入章节一致时才有意义。
     */
    private val _initialPageIndex = MutableStateFlow(0)
    val initialPageIndex: StateFlow<Int> = _initialPageIndex.asStateFlow()

    /** 翻页模式当前页码（0 起），随翻页回写并持久化 */
    private var lastPageIndex: Int = 0

    // ===== 换源 =====
    /** 换源面板是否打开 */
    private val _changeSourceOpen = MutableStateFlow(false)
    val changeSourceOpen: StateFlow<Boolean> = _changeSourceOpen.asStateFlow()

    /** 换源候选列表 */
    private val _changeSourceCandidates = MutableStateFlow<List<MediaItem>>(emptyList())
    val changeSourceCandidates: StateFlow<List<MediaItem>> = _changeSourceCandidates.asStateFlow()

    /** 换源搜索中 */
    private val _changeSourceLoading = MutableStateFlow(false)
    val changeSourceLoading: StateFlow<Boolean> = _changeSourceLoading.asStateFlow()

    /** 换源筛选：仅同作者（默认开） */
    private val _sameAuthorOnly = MutableStateFlow(true)
    val sameAuthorOnly: StateFlow<Boolean> = _sameAuthorOnly.asStateFlow()

    /** 换源错误信息 */
    private val _changeSourceError = MutableStateFlow<String?>(null)
    val changeSourceError: StateFlow<String?> = _changeSourceError.asStateFlow()

    /** 换源候选是否还有下一页（分页搜索） */
    private val _changeSourceHasMore = MutableStateFlow(false)
    val changeSourceHasMore: StateFlow<Boolean> = _changeSourceHasMore.asStateFlow()

    /** 正在加载换源候选下一页 */
    private val _changeSourceLoadingMore = MutableStateFlow(false)
    val changeSourceLoadingMore: StateFlow<Boolean> = _changeSourceLoadingMore.asStateFlow()

    /** 已搜索到的换源页码（从 1 开始） */
    private var changeSourcePage: Int = 1

    /** 当前阅读的书籍名（换源搜索用） */
    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    /** 当前阅读的书籍作者（换源同作者筛选用） */
    private val _currentAuthor = MutableStateFlow<String?>(null)
    val currentAuthor: StateFlow<String?> = _currentAuthor.asStateFlow()

    /**
     * 初始化阅读（进入阅读页时调用）。
     *
     * @param item 书籍信息
     * @param chapters 章节列表
     * @param chapter 当前章节
     */
    fun init(item: MediaItem, chapters: List<ChapterItem>, chapter: ChapterItem) {
        currentItem = item
        _currentItem.value = item
        _chapters.value = chapters
        currentChapter = chapter
        _chapterTitle.value = chapter.title
        _currentTitle.value = item.title
        _currentAuthor.value = item.author
        refreshNavFlags()
        viewModelScope.launch {
            // 收藏状态（顶栏收藏按钮）
            runCatching { bookRepository?.isCollected(item) == true }
                .onSuccess { _collected.value = it }
            // 读取历史进度：若进度所属章节正是本次进入的章节，则恢复其正文位置比例
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
            // 翻页模式页码恢复：同样要求历史进度属于本章，避免串章
            if (saved != null && saved.chapterUrl == chapter.url && saved.pageIndex > 0) {
                lastPageIndex = saved.pageIndex.coerceAtLeast(0)
                _initialPageIndex.value = lastPageIndex
            } else {
                lastPageIndex = 0
                _initialPageIndex.value = 0
            }
            refreshCachedIndices()
            loadChapter(chapter)
        }
    }

    /** 刷新已缓存章节索引（#30：目录标记 / 缓存状态展示） */
    fun refreshCachedIndices() {
        val item = currentItem ?: return
        val repo = chapterCacheRepository ?: return
        viewModelScope.launch {
            runCatching { repo.cachedIndices(item.sourceId, item.id) }
                .onSuccess { _cachedIndices.value = it }
        }
    }

    /** 手动缓存当前章节（#30） */
    fun cacheCurrentChapter() {
        val item = currentItem ?: return
        val chapter = currentChapter ?: return
        val repo = chapterCacheRepository ?: return
        val text = _content.value
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repo.saveCache(
                    item = item,
                    chapterIndex = chapter.index,
                    chapterTitle = chapter.title,
                    chapterUrl = chapter.url,
                    content = text,
                )
            }.onSuccess { refreshCachedIndices() }
        }
    }

    /** 刷新「是否有上一章 / 下一章」标记（末章文案、章节边界提示用） */
    private fun refreshNavFlags() {
        val idx = currentIndex()
        val size = _chapters.value.size
        _hasPrevChapter.value = idx > 0
        _hasNextChapter.value = idx >= 0 && idx < size - 1
    }

    /** 轻量提示（自动消失由 UI 控制） */
    fun showToast(message: String) {
        _toast.value = message
    }

    /** 关闭提示条 */
    fun dismissToast() {
        _toast.value = null
    }

    /** 收藏 / 取消收藏当前书（顶栏收藏按钮） */
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
        currentChapter?.let { loadChapter(it) }
    }

    /** 切换章节（会重置正文位置，回到该章顶部 / 第一页） */
    fun loadContent(chapter: ChapterItem) {
        lastTextPosition = 0
        _initialTextPosition.value = 0
        lastPageIndex = 0
        _initialPageIndex.value = 0
        loadChapter(chapter)
    }

    /** 加载章节正文（不改变正文位置比例） */
    private fun loadChapter(chapter: ChapterItem) {
        currentChapter = chapter
        _chapterTitle.value = chapter.title
        _currentChapterIndex.value = chapter.index
        refreshNavFlags()
        val item = currentItem
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            // #30 优先读本地缓存：源失效 / 离线时仍可阅读
            if (item != null && chapterCacheRepository != null) {
                val cached = runCatching {
                    chapterCacheRepository.getCached(item.sourceId, item.id, chapter.index)
                }.getOrNull()
                if (!cached.isNullOrBlank()) {
                    _content.value = cached
                    _fromCache.value = true
                    saveProgress()
                    _loading.value = false
                    return@launch
                }
            }

            _fromCache.value = false
            runCatching { contentRepository.content(chapter) }
                .onSuccess { result ->
                    val text = result.novelText?.trim().orEmpty()
                    if (text.isEmpty()) {
                        // 源返回空正文（多为源失效 / 规则不匹配）：给出提示而非白屏，便于用户换源
                        _content.value = ""
                        _error.value = "本章正文为空：该书源可能已失效，可在菜单中「切换书源」重试"
                    } else {
                        _content.value = result.novelText ?: ""
                        saveProgress()
                        // 缓存到本地（供离线阅读 / 缓存管理）
                        if (item != null && chapterCacheRepository != null) {
                            runCatching {
                                chapterCacheRepository.saveCache(
                                    item = item,
                                    chapterIndex = chapter.index,
                                    chapterTitle = chapter.title,
                                    chapterUrl = chapter.url,
                                    content = _content.value,
                                )
                            }
                        }
                    }
                }
                .onFailure { e ->
                    _error.value = e.message ?: "加载失败"
                }
            _loading.value = false
        }
    }

    /** 切换章节 */
    fun switchChapter(index: Int) {
        val chapters = _chapters.value
        if (index in chapters.indices) {
            loadContent(chapters[index])
        }
    }

    /**
     * 当前章节在列表中的位置。
     * 依次尝试：对象同一性 → (index,url) 双重匹配 → url 匹配 → index 匹配。
     * 防御个别源 url 重复或 index 字段漂移导致的翻页错位。
     */
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

    // ===== 设置调节 =====
    fun increaseFontSize() {
        val current = fontSize.value
        val steps = com.novelreader.ui.theme.ReaderBackgrounds.fontSizeSteps
        val next = steps.firstOrNull { it > current } ?: steps.last()
        viewModelScope.launch { preferences.setNovelFontSize(next) }
    }

    fun decreaseFontSize() {
        val current = fontSize.value
        val steps = com.novelreader.ui.theme.ReaderBackgrounds.fontSizeSteps
        val next = steps.lastOrNull { it < current } ?: steps.first()
        viewModelScope.launch { preferences.setNovelFontSize(next) }
    }

    fun setBackground(index: Int) {
        viewModelScope.launch { preferences.setNovelBgIndex(index) }
    }

    fun toggleNightMode() {
        viewModelScope.launch { preferences.setNovelNight(!nightMode.value) }
    }

    fun setPageMode(mode: Int) {
        viewModelScope.launch { preferences.setNovelPageMode(mode) }
    }

    fun setPageAnim(anim: Int) {
        viewModelScope.launch { preferences.setNovelPageAnim(anim) }
    }

    fun setHideTopBar(value: Boolean) {
        viewModelScope.launch { preferences.setNovelHideTopBar(value) }
    }

    fun setHideBottomBar(value: Boolean) {
        viewModelScope.launch { preferences.setNovelHideBottomBar(value) }
    }

    fun setHideChapterTitle(value: Boolean) {
        viewModelScope.launch { preferences.setNovelHideChapterTitle(value) }
    }

    fun setLineHeight(value: Float) {
        viewModelScope.launch { preferences.setNovelLineHeight(value) }
    }

    fun setParagraphSpacing(value: Float) {
        viewModelScope.launch { preferences.setNovelParagraphSpacing(value) }
    }

    fun setSidePadding(value: Float) {
        viewModelScope.launch { preferences.setNovelSidePadding(value) }
    }

    fun setAutoPage(value: Boolean) {
        viewModelScope.launch { preferences.setNovelAutoPage(value) }
    }

    fun setAutoPageInterval(value: Int) {
        viewModelScope.launch { preferences.setNovelAutoPageInterval(value) }
    }

    /** 音量键翻页开关 */
    fun setVolumePage(value: Boolean) {
        viewModelScope.launch { preferences.setNovelVolumePage(value) }
    }

    // ===== 音量键翻页事件（#8） =====
    /** 翻页请求：-1=上一页，1=下一页（0 无）。子组件（翻页/滚动）订阅后执行。 */
    private val _pageTurn = kotlinx.coroutines.flow.MutableSharedFlow<Int>(
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val pageTurn: kotlinx.coroutines.flow.SharedFlow<Int> = _pageTurn.asSharedFlow()

    /** 音量键上一页 */
    fun volumePrevPage() {
        _pageTurn.tryEmit(-1)
        showToast("↑ 上一页")
    }

    /** 音量键下一页 */
    fun volumeNextPage() {
        _pageTurn.tryEmit(1)
        showToast("↓ 下一页")
    }

    /** 单击屏幕中央：弹出/收起菜单 */
    fun toggleMenu() {
        // 连击去抖：单击已开关菜单、紧随其后的双击判定会再触发一次，
        // 200ms 内的第二次调用直接忽略，避免菜单「闪一下就不见」。
        val now = System.currentTimeMillis()
        if (now - lastMenuToggleAt < 200L) return
        lastMenuToggleAt = now
        _showMenu.value = !_showMenu.value
    }

    /** 上次切换菜单的时间戳（连击去抖用） */
    private var lastMenuToggleAt: Long = 0L

    /** 切换菜单页签（目录/界面/设置） */
    fun setMenuTab(tab: Int) {
        _menuTab.value = tab
    }

    // ===== 换源 =====
    /** 打开换源面板并搜索同名书 */
    fun openChangeSource() {
        _changeSourceOpen.value = true
        searchSameTitle()
    }

    /** 关闭换源面板 */
    fun closeChangeSource() {
        _changeSourceOpen.value = false
    }

    /** 切换「仅同作者」开关（切换后重新过滤，无需重新搜索） */
    fun setSameAuthorOnly(value: Boolean) {
        _sameAuthorOnly.value = value
        filterCandidates()
    }

    /** 搜索当前书名的候选源（第 1 页） */
    private fun searchSameTitle() {
        val title = _currentTitle.value
        if (title.isBlank()) return
        changeSourcePage = 1
        _changeSourceHasMore.value = false
        viewModelScope.launch {
            _changeSourceLoading.value = true
            _changeSourceError.value = null
            runCatching {
                contentRepository.search(SourceType.NOVEL, title, 1)
            }.onSuccess { results ->
                _allCandidates = results
                _changeSourceHasMore.value = results.isNotEmpty()
                filterCandidates()
            }.onFailure { e ->
                _changeSourceError.value = e.message ?: "换源搜索失败"
                _allCandidates = emptyList()
                _changeSourceCandidates.value = emptyList()
                _changeSourceHasMore.value = false
            }
            _changeSourceLoading.value = false
        }
    }

    /**
     * 加载换源候选的下一页。
     *
     * 旧实现只搜第 1 页，热门书在前几页之外的其他源永远搜不到（「换源形同虚设」之一）。
     * 这里支持翻页追加，并做去重，保证候选池够用。
     */
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
                        val existed = _allCandidates.map { it.sourceId + "|" + it.id }.toHashSet()
                        val merged = _allCandidates + more.filter { (it.sourceId + "|" + it.id) !in existed }
                        _allCandidates = merged
                        filterCandidates()
                    }
                }
                .onFailure { e ->
                    _changeSourceError.value = e.message ?: "加载更多失败"
                }
            _changeSourceLoadingMore.value = false
        }
    }

    /** 全量候选（未按同作者过滤） */
    private var _allCandidates: List<MediaItem> = emptyList()

    /**
     * 按书名 + 可选同作者过滤候选。
     * 排除当前源同一本书（sourceId+id 相同）。
     */
    private fun filterCandidates() {
        val title = _currentTitle.value.trim()
        val author = _currentAuthor.value
        val current = currentItem
        val sameAuthor = _sameAuthorOnly.value
        val filtered = _allCandidates.filter { cand ->
            // 排除当前正在读的这本书
            if (current != null && cand.sourceId == current.sourceId && cand.id == current.id) return@filter false
            // 书名匹配（完全相等或互相包含，忽略大小写）
            val titleMatch = cand.title.trim().equals(title, ignoreCase = true) ||
                cand.title.contains(title, ignoreCase = true) ||
                title.contains(cand.title.trim(), ignoreCase = true)
            if (!titleMatch) return@filter false
            // 同作者筛选：开启时要求作者一致（任一为空视为不匹配则太严，空作者视为匹配）
            if (sameAuthor) {
                val candAuthor = cand.author?.trim()
                val curAuthor = author?.trim()
                if (curAuthor.isNullOrBlank() || candAuthor.isNullOrBlank()) true
                else candAuthor.equals(curAuthor, ignoreCase = true)
            } else true
        }
        _changeSourceCandidates.value = filtered
    }

    /**
     * 切换到候选源。
     *
     * 定位策略（三级，避免「一律回退第一章」）：
     * 1. 章节标题完全相等
     * 2. 标题归一化后相等（忽略空格/标点/繁简差异）
     * 3. 标题互相包含
     * 4. 以上都失败 → 按当前阅读进度比例映射到目标目录（近似续读位置）
     *
     * 成功后：更新当前书信息、章节列表，关闭面板并给出提示；失败保留错误信息。
     */
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
                    val index = locateChapter(newChapters, curTitle, curPos, oldTotal)
                    // 切换：更新当前书、章节列表、书名作者，加载对应章节
                    currentItem = target
                    _currentItem.value = target
                    _chapters.value = newChapters
                    _currentTitle.value = target.title
                    _currentAuthor.value = target.author
                    // 重置换源面板状态
                    _changeSourceOpen.value = false
                    _allCandidates = emptyList()
                    _changeSourceCandidates.value = emptyList()
                    _changeSourceHasMore.value = false
                    changeSourcePage = 1
                    loadContent(newChapters[index])
                    // 换源成功反馈（旧实现切换后无任何提示，用户不知道是否生效）
                    val srcLabel = target.sourceName.ifBlank { target.title }
                    showToast("已切换到「$srcLabel」，定位到第 ${index + 1}/${newChapters.size} 章")
                }
                .onFailure { e ->
                    _changeSourceError.value = e.message ?: "切换源失败"
                }
            _changeSourceLoading.value = false
        }
    }

    /** 在目标目录中定位对应的阅读位置（见 [ChapterLocator.locate]） */
    private fun locateChapter(
        newChapters: List<ChapterItem>,
        curTitle: String,
        curPos: Int,
        oldTotal: Int,
    ): Int = ChapterLocator.locate(newChapters, curTitle, curPos, oldTotal)

    // ===== 进度保存 =====

    /**
     * 回写当前章节内的正文位置比例（0~1），由滚动阅读页在滚动停止后调用。
     * 位置变化时才落库，避免无意义的重复写入。
     *
     * @param chapterIndex 触发回写的章节索引；与当前章节不一致（切章瞬间）时忽略，防止串章
     */
    fun saveScrollRatio(chapterIndex: Int, ratio: Float) {
        if (currentChapter?.index != chapterIndex) return
        val pos = (ratio.coerceIn(0f, 1f) * 10000f).toInt()
        if (pos == lastTextPosition) return
        lastTextPosition = pos
        saveProgress()
    }

    /**
     * 回写翻页模式的当前页码，由左右翻页页在停稳后调用。
     * 与 [saveScrollRatio] 互不干扰：同一章两种模式各自记录位置。
     *
     * @param chapterIndex 触发回写的章节索引；与当前章节不一致（切章瞬间）时忽略，防止串章
     * @param page 当前页码（0 起）
     */
    fun savePageIndex(chapterIndex: Int, page: Int) {
        if (currentChapter?.index != chapterIndex) return
        val p = page.coerceAtLeast(0)
        if (p == lastPageIndex) return
        lastPageIndex = p
        saveProgress()
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
                itemType = com.novelreader.domain.model.SourceType.NOVEL.name,
                itemTitle = item.title,
                itemCover = item.cover,
                itemAuthor = item.author,
                pageIndex = lastPageIndex,
                totalChapter = _chapters.value.size,
            )
        }
    }
}
