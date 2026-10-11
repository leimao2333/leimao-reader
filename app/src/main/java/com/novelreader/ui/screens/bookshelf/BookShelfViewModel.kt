package com.novelreader.ui.screens.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.domain.repository.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 书架 ViewModel。
 *
 * 展示收藏的小说 / 漫画 / 视频，并附带每个条目的阅读进度。
 * 支持「继续阅读」：定位到上次进度章节并跳转阅读页。
 */
class BookShelfViewModel(
    private val bookRepository: BookRepository,
    private val progressRepository: ProgressRepository,
    private val contentRepository: ContentRepository,
) : ViewModel() {

    /** 书架条目 */
    private val _books = MutableStateFlow<List<MediaItem>>(emptyList())
    val books: StateFlow<List<MediaItem>> = _books.asStateFlow()

    /** 进度缓存：itemKey -> 进度 */
    private val _progressMap = MutableStateFlow<Map<String, ReadingProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, ReadingProgress>> = _progressMap.asStateFlow()

    init {
        observeBooks()
    }

    private fun observeBooks() {
        viewModelScope.launch {
            bookRepository.observeBookshelf().collectLatest { list ->
                _books.value = list
                val map = mutableMapOf<String, ReadingProgress>()
                list.forEach { item ->
                    val p = progressRepository.getProgress(item.sourceId, item.id)
                    if (p != null) map[itemKey(item)] = p
                }
                _progressMap.value = map
            }
        }
    }

    /** 移除收藏 */
    fun remove(item: MediaItem) {
        viewModelScope.launch {
            bookRepository.removeFromBookshelf(item)
        }
    }

    /**
     * 继续阅读。
     *
     * 加载该内容的章节列表 + 进度，定位到上次章节后回调。
     *
     * @param item 收藏条目
     * @param onReady 回调，参数为 (章节列表, 目标章节)。
     *                若找不到进度章节，目标章节为第一章；章节列表为空则回调 null。
     */
    fun continueReading(
        item: MediaItem,
        onReady: (chapters: List<ChapterItem>, target: ChapterItem?) -> Unit,
    ) {
        viewModelScope.launch {
            val progress = progressRepository.getProgress(item.sourceId, item.id)
            val chapters = runCatching { contentRepository.chapterList(item) }
                .getOrDefault(emptyList())

            if (chapters.isEmpty()) {
                onReady(emptyList(), null)
                return@launch
            }

            // 定位进度章节
            val target = progress?.let { p ->
                chapters.firstOrNull { it.url == p.chapterUrl }
                    ?: chapters.firstOrNull { it.index == p.chapterIndex }
            } ?: chapters.first()

            onReady(chapters, target)
        }
    }

    /** 生成进度缓存 key */
    private fun itemKey(item: MediaItem): String = "${item.sourceId}:${item.id}"

    fun progressFor(item: MediaItem): ReadingProgress? =
        _progressMap.value[itemKey(item)]
}
