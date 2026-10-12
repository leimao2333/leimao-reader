package com.novelreader.ui.screens.comic

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
 * 漫画阅读 ViewModel。
 *
 * 管理图片列表、章节切换、翻页方式（上下滚动 / 左右翻页）。
 * 翻页方式通过 PreferencesManager 持久化。
 */
class ComicReaderViewModel(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
    private val preferences: PreferencesManager,
) : ViewModel() {

    /** 图片地址列表 */
    private val _images = MutableStateFlow<List<String>>(emptyList())
    val images: StateFlow<List<String>> = _images.asStateFlow()

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

    /** 翻页方式：0=上下滚动，1=左右翻页 */
    val pageMode: StateFlow<Int> = preferences.comicPageModeFlow
        .let { flow ->
            val state = MutableStateFlow(0)
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
        loadContent(chapter)
    }

    /** 当前已加载的章节，用于失败重试 */
    private var lastChapter: ChapterItem? = null

    fun loadContent(chapter: ChapterItem) {
        currentChapter = chapter
        lastChapter = chapter
        _chapterTitle.value = chapter.title
        _currentPage.value = 0
        _images.value = emptyList()
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { contentRepository.content(chapter) }
                .onSuccess { result ->
                    val imgs = result.comicImages?.filter { it.isNotBlank() } ?: emptyList()
                    _images.value = imgs
                    // 图片列表为空时不进入渲染分支，避免纯黑屏无提示
                    if (imgs.isEmpty()) {
                        _error.value = "本章暂无可用图片，可能源已失效或章节待更新"
                    } else {
                        saveProgress()
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

    fun nextChapter() {
        val idx = currentIndex()
        val chapters = _chapters.value
        if (idx >= 0 && idx + 1 < chapters.size) {
            loadContent(chapters[idx + 1])
        }
    }

    fun prevChapter() {
        val idx = currentIndex()
        if (idx > 0) {
            loadContent(_chapters.value[idx - 1])
        }
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
}
