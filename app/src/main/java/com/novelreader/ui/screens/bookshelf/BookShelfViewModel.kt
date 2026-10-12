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
    private val preferencesManager: com.novelreader.core.preferences.PreferencesManager? = null,
    private val localBookStore: com.novelreader.core.local.LocalBookStore? = null,
) : ViewModel() {

    /** 书架条目 */
    private val _books = MutableStateFlow<List<MediaItem>>(emptyList())
    val books: StateFlow<List<MediaItem>> = _books.asStateFlow()

    /** 进度缓存：itemKey -> 进度 */
    private val _progressMap = MutableStateFlow<Map<String, ReadingProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, ReadingProgress>> = _progressMap.asStateFlow()

    /** 最新章节缓存：itemKey -> 最新章节标题（详情规则可能较慢，按需补齐） */
    private val _lastChapterMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastChapterMap: StateFlow<Map<String, String>> = _lastChapterMap.asStateFlow()

    /** 总章节数缓存：itemKey -> 章节总数（用于进度条百分比） */
    private val _chapterCountMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    val chapterCountMap: StateFlow<Map<String, Int>> = _chapterCountMap.asStateFlow()

    /** 书架展现形式（0=封面墙 1=紧凑网格 2=列表，持久化） */
    private val _viewMode = MutableStateFlow(0)
    val viewMode: StateFlow<Int> = _viewMode.asStateFlow()

    /** 封面墙 / 紧凑网格列数（2~6，持久化） */
    private val _columns = MutableStateFlow(3)
    val columns: StateFlow<Int> = _columns.asStateFlow()

    init {
        observeBooks()
        preferencesManager?.let { prefs ->
            viewModelScope.launch {
                prefs.bookshelfViewModeFlow.collect { _viewMode.value = it }
            }
            viewModelScope.launch {
                prefs.bookshelfColumnsFlow.collect { _columns.value = it }
            }
        }
    }

    /** 切换书架展现形式（持久化） */
    fun setViewMode(value: Int) {
        viewModelScope.launch { preferencesManager?.setBookshelfViewMode(value) }
    }

    /** 设置封面墙 / 紧凑网格列数（持久化） */
    fun setColumns(value: Int) {
        viewModelScope.launch { preferencesManager?.setBookshelfColumns(value) }
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

    /** 导入结果提示（UI 展示后自动消失） */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun dismissImportMessage() {
        _importMessage.value = null
    }

    /**
     * 导入本地 TXT 书（v1.2.0）：解析章节 → 存入本地书库 → 加入书架。
     */
    fun importLocalBook(uri: android.net.Uri, displayName: String?) {
        val store = localBookStore ?: return
        viewModelScope.launch {
            runCatching { store.import(uri, displayName) }
                .onSuccess { meta ->
                    val adapter = com.novelreader.source.local.LocalBookAdapter(meta, store)
                    runCatching { bookRepository.addToBookshelf(adapter.item()) }
                    _importMessage.value = "已导入「${meta.title}」（${meta.chapterCount} 章）"
                }
                .onFailure { e ->
                    _importMessage.value = "导入失败：${e.message ?: "未知错误"}"
                }
        }
    }

    /** 移除收藏 */
    fun remove(item: MediaItem) {
        viewModelScope.launch {
            bookRepository.removeFromBookshelf(item)
        }
    }

    /** 批量移除收藏（多选管理） */
    fun removeBatch(items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            items.forEach { bookRepository.removeFromBookshelf(it) }
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

    /**
     * 补齐书架条目的「最新章节」与章节总数（封面墙展示用）。
     *
     * 需要拉取目录，较耗时，因此在书架列表加载后按需（每个条目仅一次）在后台补齐，
     * 结果缓存避免重复请求。失败静默忽略。
     */
    fun enrichChapterInfo(items: List<MediaItem>) {
        viewModelScope.launch {
            items.forEach { item ->
                val key = itemKey(item)
                if (_lastChapterMap.value.containsKey(key)) return@forEach
                val chapters = runCatching { contentRepository.chapterList(item) }.getOrDefault(emptyList())
                if (chapters.isEmpty()) return@forEach
                _chapterCountMap.value = _chapterCountMap.value + (key to chapters.size)
                val last = chapters.lastOrNull()?.title
                if (!last.isNullOrBlank()) {
                    _lastChapterMap.value = _lastChapterMap.value + (key to last)
                }
            }
        }
    }
}
