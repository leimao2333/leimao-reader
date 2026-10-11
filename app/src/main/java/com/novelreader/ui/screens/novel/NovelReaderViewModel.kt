package com.novelreader.ui.screens.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    // ===== 正文 =====
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
        _chapters.value = chapters
        currentChapter = chapter
        _chapterTitle.value = chapter.title
        _currentTitle.value = item.title
        _currentAuthor.value = item.author
        viewModelScope.launch {
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
            loadChapter(chapter)
        }
    }

    /** 切换章节（会重置正文位置，回到该章顶部） */
    fun loadContent(chapter: ChapterItem) {
        lastTextPosition = 0
        _initialTextPosition.value = 0
        loadChapter(chapter)
    }

    /** 加载章节正文（不改变正文位置比例） */
    private fun loadChapter(chapter: ChapterItem) {
        currentChapter = chapter
        _chapterTitle.value = chapter.title
        _currentChapterIndex.value = chapter.index
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
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

    fun nextChapter() {
        val current = _chapters.value.indexOfFirst { it.url == currentChapter?.url }
        if (current >= 0 && current + 1 < _chapters.value.size) {
            loadContent(_chapters.value[current + 1])
        }
    }

    fun prevChapter() {
        val current = _chapters.value.indexOfFirst { it.url == currentChapter?.url }
        if (current > 0) {
            loadContent(_chapters.value[current - 1])
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

    /** 单击屏幕中央：弹出/收起菜单 */
    fun toggleMenu() {
        _showMenu.value = !_showMenu.value
    }

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

    /** 搜索当前书名的候选源 */
    private fun searchSameTitle() {
        val title = _currentTitle.value
        if (title.isBlank()) return
        viewModelScope.launch {
            _changeSourceLoading.value = true
            _changeSourceError.value = null
            runCatching {
                contentRepository.search(SourceType.NOVEL, title, 1)
            }.onSuccess { results ->
                _allCandidates = results
                filterCandidates()
            }.onFailure { e ->
                _changeSourceError.value = e.message ?: "换源搜索失败"
                _allCandidates = emptyList()
                _changeSourceCandidates.value = emptyList()
            }
            _changeSourceLoading.value = false
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
     * 拉取目标源目录，按当前章节标题匹配定位；匹配不到则从第一章开始。
     */
    fun switchSource(target: MediaItem) {
        val currentTitleText = currentChapter?.title ?: ""
        viewModelScope.launch {
            _changeSourceLoading.value = true
            _changeSourceError.value = null
            runCatching { contentRepository.chapterList(target) }
                .onSuccess { newChapters ->
                    if (newChapters.isEmpty()) {
                        _changeSourceError.value = "该源无章节"
                        _changeSourceLoading.value = false
                        return@launch
                    }
                    // 按章节标题匹配定位
                    var index = newChapters.indexOfFirst { it.title.trim() == currentTitleText.trim() }
                    if (index < 0) {
                        index = newChapters.indexOfFirst { it.title.contains(currentTitleText, ignoreCase = true) }
                    }
                    if (index < 0) index = 0
                    // 切换：更新当前书、章节列表、书名作者，加载对应章节
                    currentItem = target
                    _chapters.value = newChapters
                    _currentTitle.value = target.title
                    _currentAuthor.value = target.author
                    loadContent(newChapters[index])
                    _changeSourceOpen.value = false
                }
                .onFailure { e ->
                    _changeSourceError.value = e.message ?: "切换源失败"
                }
            _changeSourceLoading.value = false
        }
    }

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
            )
        }
    }
}
