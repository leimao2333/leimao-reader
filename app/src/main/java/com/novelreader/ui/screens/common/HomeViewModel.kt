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
    /** 视频源列表（影视首页用，v1.3.3.0） */
    private val sourceRepository: com.novelreader.domain.repository.SourceRepository? = null,
    /** 记忆影视首页所选源 */
    private val preferencesManager: com.novelreader.core.preferences.PreferencesManager? = null,
) : ViewModel() {

    /** 本地 TXT 导入结果提示（首页 Toast 展示） */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun dismissImportMessage() {
        _importMessage.value = null
    }

    /** 导入本地 TXT 书：解析章节 → 存入本地书库 → 加入书架（v1.2.0.0） */
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

    // ===== 书架分组（v1.3.0.0 对标 legado 书架分组） =====

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

    /** 更换书架条目封面（长按书 → 更换封面，v1.2.3.0） */
    fun updateCover(item: MediaItem, coverUrl: String) {
        if (coverUrl.isBlank()) return
        viewModelScope.launch { bookRepository.updateBookCover(item, coverUrl) }
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

    // ===== 影视首页（v1.3.3.0，对标 MoonTV 主页）=====

    /**
     * 影视首页状态。
     *
     * 只服务于「视频」Tab：源选择 + 一级分类 + 海报墙（分页）。
     */
    data class VideoHomeState(
        val sources: List<com.novelreader.domain.model.SourceInfo> = emptyList(),
        val currentSourceId: String = "",
        val categories: List<com.novelreader.domain.model.ExploreCategory> = emptyList(),
        /** 当前分类 id（空串 = 全部） */
        val selectedCategory: String = "",
        val items: List<MediaItem> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null,
    ) {
        val currentSourceName: String
            get() = sources.firstOrNull { it.id == currentSourceId }?.name.orEmpty()
    }

    private val _videoHome = MutableStateFlow(VideoHomeState())
    val videoHome: StateFlow<VideoHomeState> = _videoHome.asStateFlow()

    /** 已加载到第几页（分页游标） */
    private var videoPage = 1
    private var videoJob: kotlinx.coroutines.Job? = null

    /**
     * 进入影视 Tab 时调用：刷新源列表（无网络开销），
     * 源变化或尚无内容时自动加载首屏。幂等，可重复调用。
     */
    fun onVideoTabShown() {
        viewModelScope.launch {
            val repo = sourceRepository ?: return@launch
            val sources = runCatching { repo.getSourcesByType(SourceType.VIDEO) }
                .getOrDefault(emptyList())
                .filter { it.enabled }
            val saved = runCatching { preferencesManager?.getVideoHomeSource().orEmpty() }.getOrDefault("")
            val state = _videoHome.value
            val current = sources.firstOrNull { it.id == saved }?.id
                ?: state.currentSourceId.takeIf { id -> sources.any { it.id == id } }
                ?: sources.firstOrNull()?.id.orEmpty()
            val sourceChanged = current != state.currentSourceId
            _videoHome.value = state.copy(
                sources = sources,
                currentSourceId = current,
                categories = if (sourceChanged) emptyList() else state.categories,
                items = if (sourceChanged) emptyList() else state.items,
                selectedCategory = if (sourceChanged) "" else state.selectedCategory,
            )
            if (sourceChanged || _videoHome.value.items.isEmpty()) {
                loadVideoHome(reset = true)
            }
        }
    }

    /** 切换影视首页的源 */
    fun selectVideoSource(sourceId: String) {
        if (sourceId == _videoHome.value.currentSourceId) return
        _videoHome.value = _videoHome.value.copy(
            currentSourceId = sourceId,
            categories = emptyList(),
            selectedCategory = "",
            items = emptyList(),
        )
        viewModelScope.launch { runCatching { preferencesManager?.setVideoHomeSource(sourceId) } }
        loadVideoHome(reset = true)
    }

    /** 切换分类（空串 = 全部） */
    fun selectVideoCategory(categoryId: String) {
        if (categoryId == _videoHome.value.selectedCategory) return
        _videoHome.value = _videoHome.value.copy(
            selectedCategory = categoryId,
            items = emptyList(),
            hasMore = true,
        )
        loadVideoHome(reset = true)
    }

    /** 下拉刷新 */
    fun refreshVideoHome() = loadVideoHome(reset = true)

    /** 触底加载下一页 */
    fun loadMoreVideo() {
        val s = _videoHome.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        loadVideoHome(reset = false)
    }

    private fun loadVideoHome(reset: Boolean) {
        val sourceId = _videoHome.value.currentSourceId
        if (sourceId.isBlank()) {
            _videoHome.value = _videoHome.value.copy(
                loading = false,
                loadingMore = false,
                error = "还没有可用的视频源，去「源管理 → 导入源」添加",
            )
            return
        }
        videoJob?.cancel()
        videoJob = viewModelScope.launch {
            if (reset) videoPage = 0
            val nextPage = videoPage + 1
            _videoHome.value = _videoHome.value.copy(
                loading = reset,
                loadingMore = !reset,
                error = null,
            )

            // 分类只在首屏 / 换源时拉取（适配器内部有缓存）
            var categoriesUnreachable = false
            val categories = if (reset && _videoHome.value.categories.isEmpty()) {
                runCatching { contentRepository.exploreCategoriesOf(sourceId) }.getOrElse {
                    // 分类接口都打不通，说明源当前不可达；再去拉列表只会白等一轮超时
                    categoriesUnreachable = true
                    emptyList()
                }
            } else {
                _videoHome.value.categories
            }

            if (categoriesUnreachable && reset) {
                _videoHome.value = _videoHome.value.copy(
                    loading = false,
                    loadingMore = false,
                    error = "连接不上该视频源，请检查网络或换个源试试",
                )
                return@launch
            }

            val category = _videoHome.value.selectedCategory
            val items = runCatching { contentRepository.explore(sourceId, category, nextPage) }
                .getOrDefault(emptyList())

            videoPage = nextPage
            val current = _videoHome.value
            val merged = if (reset) items else current.items + items
            _videoHome.value = current.copy(
                categories = categories,
                items = merged.distinctBy { "${it.sourceId}:${it.id}" },
                loading = false,
                loadingMore = false,
                hasMore = items.isNotEmpty(),
                error = if (reset && items.isEmpty()) "没有取到内容，可能是源暂时不可用或该分类为空" else null,
            )
        }
    }
}
