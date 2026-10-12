package com.novelreader.ui.screens.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
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
 * 首页 ViewModel（合并书架 + 最近阅读）。
 *
 * 首页内容区直接展示「书架」：收藏的小说/漫画/视频封面墙，
 * 按「最近阅读」时间倒序（默认排序），附带阅读进度。
 * 这样「最近阅读」与「书架」不再拆成两个功能——书架本身就是最近阅读的入口，
 * 未收藏但读过、或收藏未读的内容都在书架统一呈现。
 */
class HomeViewModel(
    private val bookRepository: BookRepository,
    private val progressRepository: ProgressRepository,
    private val contentRepository: ContentRepository,
    private val localBookStore: com.novelreader.core.local.LocalBookStore? = null,
) : ViewModel() {

    /** 本地 TXT 导入结果提示（首页 Toast 展示） */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun dismissImportMessage() {
        _importMessage.value = null
    }

    /** 导入本地 TXT 书：解析章节 → 存入本地书库 → 加入书架（v1.2.0） */
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

    /** 书架条目（收藏内容） */
    private val _books = MutableStateFlow<List<MediaItem>>(emptyList())
    val books: StateFlow<List<MediaItem>> = _books.asStateFlow()

    /** 进度缓存：itemKey -> 进度 */
    private val _progressMap = MutableStateFlow<Map<String, ReadingProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, ReadingProgress>> = _progressMap.asStateFlow()

    /** 最新章节缓存：itemKey -> 最新章节标题（封面墙展示用，按需补齐） */
    private val _lastChapterMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastChapterMap: StateFlow<Map<String, String>> = _lastChapterMap.asStateFlow()

    /** 总章节数缓存：itemKey -> 章节总数（进度条百分比用） */
    private val _chapterCountMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    val chapterCountMap: StateFlow<Map<String, Int>> = _chapterCountMap.asStateFlow()

    /** 当前展示的类型（tab 切换），null = 全部 */
    private var currentType: SourceType? = null

    init {
        observeBooks()
        observeGroups()
    }

    // ===== 书架分组（v1.3.0 对标 legado 书架分组） =====

    /** 所有已用分组名 */
    private val _groupNames = MutableStateFlow<List<String>>(emptyList())
    val groupNames: StateFlow<List<String>> = _groupNames.asStateFlow()

    /** 当前选中的分组筛选（null = 全部；"" = 未分组） */
    private val _selectedGroup = MutableStateFlow<String?>(null)
    val selectedGroup: StateFlow<String?> = _selectedGroup.asStateFlow()

    private fun observeGroups() {
        viewModelScope.launch {
            bookRepository.observeGroupNames().collectLatest { _groupNames.value = it }
        }
    }

    /** 切换分组筛选（null = 全部，"" = 未分组） */
    fun selectGroup(name: String?) {
        _selectedGroup.value = name
    }

    /** 设置条目分组（空串 = 移出分组） */
    fun setBookGroup(item: MediaItem, group: String) {
        viewModelScope.launch { bookRepository.setBookGroup(item, group) }
    }

    /** 重命名分组 */
    fun renameGroup(oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        viewModelScope.launch {
            bookRepository.renameGroup(oldName, newName.trim())
            if (_selectedGroup.value == oldName) _selectedGroup.value = newName.trim()
        }
    }

    /** 删除分组（组内条目变为未分组） */
    fun deleteGroup(name: String) {
        viewModelScope.launch {
            bookRepository.deleteGroup(name)
            if (_selectedGroup.value == name) _selectedGroup.value = null
        }
    }

    private fun observeBooks() {
        viewModelScope.launch {
            bookRepository.observeBookshelf().collectLatest { list ->
                _books.value = list
                refreshProgress()
            }
        }
    }

    /** 重新加载书架条目的阅读进度（回前台时调用，进度可能已变化） */
    fun refreshProgress() {
        viewModelScope.launch {
            val map = mutableMapOf<String, ReadingProgress>()
            _books.value.forEach { item ->
                val p = progressRepository.getProgress(item.sourceId, item.id)
                if (p != null) map[itemKey(item)] = p
            }
            _progressMap.value = map
        }
    }

    /** 切换内容类型（tab 切换），书架随 tab 过滤类型 */
    fun setType(type: SourceType?) {
        if (currentType == type) return
        currentType = type
    }

    /** 移除收藏 */
    fun remove(item: MediaItem) {
        viewModelScope.launch { bookRepository.removeFromBookshelf(item) }
    }

    /**
     * 继续阅读某个书架条目。
     *
     * 加载章节列表 + 定位进度章节后回调。
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

            val target = progress?.let { p ->
                chapters.firstOrNull { it.url == p.chapterUrl }
                    ?: chapters.firstOrNull { it.index == p.chapterIndex }
            } ?: chapters.first()

            onReady(chapters, target)
        }
    }

    /**
     * 补齐书架条目的「最新章节」与章节总数（封面墙展示用）。
     * 后台按需拉取目录，结果缓存避免重复请求，失败静默忽略。
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

    private fun itemKey(item: MediaItem): String = "${item.sourceId}:${item.id}"
}
