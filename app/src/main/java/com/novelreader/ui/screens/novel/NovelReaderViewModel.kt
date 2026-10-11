package com.novelreader.ui.screens.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
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
        loadContent(chapter)
    }

    /** 加载章节正文 */
    fun loadContent(chapter: ChapterItem) {
        currentChapter = chapter
        _chapterTitle.value = chapter.title
        _currentChapterIndex.value = chapter.index
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { contentRepository.content(chapter) }
                .onSuccess { result ->
                    _content.value = result.novelText ?: ""
                    saveProgress()
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

    // ===== 进度保存 =====
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
            )
        }
    }
}
