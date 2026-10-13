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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    // ===== 书架分组（v1.3.0.0 对标 legado 书架分组） =====

    /** 所有已用分组名 */
    private val _groupNames = MutableStateFlow<List<String>>(emptyList())
    val groupNames: StateFlow<List<String>> = _groupNames.asStateFlow()

    /** 当前选中的分组筛选（null = 全部；"" = 未分组） */
    private val _selectedGroup = MutableStateFlow<String?>(null)
    val selectedGroup: StateFlow<String?> = _selectedGroup.asStateFlow()

    /**
     * 初始化订阅。
     *
     * **必须放在所有属性声明之后**：`viewModelScope` 跑在 `Dispatchers.Main.immediate`，
     * 在主线程 `launch` 会立即执行协程体。若数据源恰好同步 emit（Room / DataStore
     * 一般会先挂起，但不保证），就会在这些 `MutableStateFlow` 还没赋值时写入 → NPE。
     * 之前 init 写在 `_groupNames` 前面，测试里用 `flowOf` 一喂就炸，已挪到这里。
     */
    init {
        observeBooks()
        observeGroups()
    }

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

    // ===== 影视首页（v1.3.3.0 起步，v1.3.4.0 改为 MoonTV 式多分区）=====

    /**
     * 影视首页的一个分区 = 一排横滑海报。
     *
     * 对标 MoonTV 首页的「热门电影 / 热门剧集 / 热门综艺」，
     * 但数据来自采集站的一级分类，所以分区数量由源自己决定。
     */
    data class VideoRow(
        /** 分类 id（空串 = 全部） */
        val categoryId: String,
        val title: String,
        val items: List<MediaItem>,
    )

    /**
     * 「继续观看」条目。
     *
     * [progress] 优先取时间进度（`playPositionMs / durationMs`），
     * 拿不到总时长时退化为集数进度；[episodeLabel] 形如 `3/24`。
     */
    data class ContinueItem(
        val item: MediaItem,
        val progress: Float,
        val episodeLabel: String,
    )

    /**
     * 影视首页状态。
     *
     * 两种视图共用一份状态，靠 [expanded] 区分：
     * - `false` → **分区视图**（继续观看 + 各一级分类横滑排），数据在 [rows]
     * - `true`  → **网格视图**（单个分类的海报墙 + 触底分页），数据在 [items]
     */
    data class VideoHomeState(
        val sources: List<com.novelreader.domain.model.SourceInfo> = emptyList(),
        val currentSourceId: String = "",
        val categories: List<com.novelreader.domain.model.ExploreCategory> = emptyList(),
        /** 当前分类 id（空串 = 全部） */
        val selectedCategory: String = "",
        /** 网格视图内容 */
        val items: List<MediaItem> = emptyList(),
        /** 分区视图内容 */
        val rows: List<VideoRow> = emptyList(),
        /** 继续观看（来自本地进度表，与所选源无关） */
        val continueWatching: List<ContinueItem> = emptyList(),
        /** 是否处于「分类网格」视图（点分区标题「查看更多」或分类 chip 进入） */
        val expanded: Boolean = false,
        /** 网格视图标题（"" = 全部） */
        val expandedTitle: String = "",
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null,
    ) {
        val currentSourceName: String
            get() = sources.firstOrNull { it.id == currentSourceId }?.name.orEmpty()

        /** 当前视图是否已有内容（判断要不要重新拉首屏） */
        val loaded: Boolean
            get() = if (expanded) items.isNotEmpty() else rows.isNotEmpty()
    }

    private val _videoHome = MutableStateFlow(VideoHomeState())
    val videoHome: StateFlow<VideoHomeState> = _videoHome.asStateFlow()

    /** 网格视图已加载到第几页（分页游标） */
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
            val next = state.copy(
                sources = sources,
                currentSourceId = current,
                categories = if (sourceChanged) emptyList() else state.categories,
                items = if (sourceChanged) emptyList() else state.items,
                rows = if (sourceChanged) emptyList() else state.rows,
                // 换源后原来的「展开分类」不再适用，退回分区视图
                expanded = if (sourceChanged) false else state.expanded,
                expandedTitle = if (sourceChanged) "" else state.expandedTitle,
                selectedCategory = if (sourceChanged) "" else state.selectedCategory,
            )
            // 「继续观看」来自本地进度表，与所选源无关，每次进页都刷新
            val continueItems = buildContinueWatching(sources)
            _videoHome.value = next.copy(continueWatching = continueItems)
            if (sourceChanged || !next.loaded) {
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
            rows = emptyList(),
            expanded = false,
            expandedTitle = "",
        )
        viewModelScope.launch { runCatching { preferencesManager?.setVideoHomeSource(sourceId) } }
        loadVideoHome(reset = true)
    }

    /** 展开某个分类的网格视图（分区标题右侧「查看更多」） */
    fun expandVideoCategory(categoryId: String, title: String) {
        _videoHome.value = _videoHome.value.copy(
            expanded = true,
            expandedTitle = title,
            selectedCategory = categoryId,
            items = emptyList(),
            hasMore = true,
        )
        loadVideoHome(reset = true)
    }

    /** 从分类网格返回分区视图 */
    fun collapseVideoCategory() {
        if (!_videoHome.value.expanded) return
        _videoHome.value = _videoHome.value.copy(
            expanded = false,
            expandedTitle = "",
            selectedCategory = "",
            items = emptyList(),
            hasMore = true,
        )
        loadVideoHome(reset = true)
    }

    /** 网格视图内切换分类（空串 = 全部） */
    fun selectVideoCategory(categoryId: String) {
        val s = _videoHome.value
        if (categoryId == s.selectedCategory && s.expanded) return
        val title = s.categories.firstOrNull { it.url == categoryId }?.title.orEmpty().ifBlank { "全部" }
        _videoHome.value = s.copy(
            expanded = true,
            expandedTitle = title,
            selectedCategory = categoryId,
            items = emptyList(),
            hasMore = true,
        )
        loadVideoHome(reset = true)
    }

    /**
     * 只重算「继续观看」，不触发任何网络请求。
     *
     * 场景：从播放页 / 详情页返回首页时，观看进度刚变过，
     * 而这一块只在「首次进页 / 下拉刷新 / 切源」时重建，不刷新就会看不到刚看的那部片。
     */
    fun refreshContinueWatching() {
        viewModelScope.launch {
            _videoHome.value = _videoHome.value.copy(
                continueWatching = buildContinueWatching(_videoHome.value.sources),
            )
        }
    }

    /** 下拉刷新（分区视图连「继续观看」一起刷新） */
    fun refreshVideoHome() {        if (!_videoHome.value.expanded) {
            viewModelScope.launch {
                _videoHome.value =
                    _videoHome.value.copy(continueWatching = buildContinueWatching(_videoHome.value.sources))
            }
        }
        loadVideoHome(reset = true)
    }

    /** 触底加载下一页（仅网格视图有效） */
    fun loadMoreVideo() {
        val s = _videoHome.value
        if (!s.expanded) return
        if (s.loading || s.loadingMore || !s.hasMore) return
        loadVideoHome(reset = false)
    }

    /**
     * 「继续观看」列表：本地进度表按最近播放倒序，取前 N 条。
     *
     * 与所选源无关 —— 换源不该让「上次看到哪」消失。
     */
    private suspend fun buildContinueWatching(
        sources: List<com.novelreader.domain.model.SourceInfo>,
    ): List<ContinueItem> {
        val records = runCatching { progressRepository.recentReadings(SourceType.VIDEO) }
            .getOrDefault(emptyList())
        if (records.isEmpty()) return emptyList()
        val sourceNames = sources.associate { it.id to it.name }
        return records.take(MAX_CONTINUE_ITEMS).map { r ->
            val item = MediaItem(
                id = r.itemId,
                title = r.title,
                cover = r.cover,
                author = r.author,
                type = SourceType.VIDEO,
                detailUrl = r.detailUrl,
                sourceId = r.sourceId,
                sourceName = sourceNames[r.sourceId].orEmpty(),
                // 续播定位：详情页读到这两个字段就直接跳到上次那一集并续播
                extras = buildMap {
                    put(EXTRA_RESUME_INDEX, r.chapterIndex.toString())
                    if (r.chapterUrl.isNotBlank()) put(EXTRA_RESUME_URL, r.chapterUrl)
                },
            )
            // 时间进度优先；拿不到总时长（老记录 / 直播流）时退回集数进度
            val progress = com.novelreader.core.util.WatchProgress.fraction(
                playPositionMs = r.playPositionMs,
                durationMs = r.durationMs,
                chapterIndex = r.chapterIndex,
                totalChapter = r.totalChapter,
            )
            val label = com.novelreader.core.util.WatchProgress.episodeLabel(
                chapterIndex = r.chapterIndex,
                totalChapter = r.totalChapter,
            )
            ContinueItem(item, progress, label)
        }
    }

    private fun loadVideoHome(reset: Boolean) {
        val state = _videoHome.value
        val sourceId = state.currentSourceId
        if (sourceId.isBlank()) {
            _videoHome.value = state.copy(
                loading = false,
                loadingMore = false,
                error = "还没有可用的视频源，去「源管理 → 导入源」添加",
            )
            return
        }
        videoJob?.cancel()
        videoJob = viewModelScope.launch {
            if (state.expanded) loadVideoGrid(sourceId, reset) else loadVideoSections(sourceId)
        }
    }

    /**
     * 分区视图：继续观看 + 各一级分类各拉一页，组成多排横滑。
     *
     * 请求量控制：分类先 `ac=list` 拿一次，随后每个分类一次 `ac=detail&pg=1`。
     * 一份配置里分类可能十几个，所以只取前 [MAX_VIDEO_ROWS] 个，
     * 并限定 [ROWS_CONCURRENCY] 路并发 —— 弱站被十几个并发请求打挂过，不敢全放。
     *
     * 失败处理：个别分类失败只丢那一排；**首批全灭**才判定源不可达，
     * 立刻返回错误，不再白等后面几批超时。
     */
    private suspend fun loadVideoSections(sourceId: String) {
        _videoHome.value = _videoHome.value.copy(loading = true, loadingMore = false, error = null)

        // 分类接口都打不通 = 源当前不可达，没必要再去拉内容
        val categories = runCatching { contentRepository.exploreCategoriesOf(sourceId) }.getOrElse {
            _videoHome.value = _videoHome.value.copy(
                loading = false,
                loadingMore = false,
                error = "连接不上该视频源，请检查网络或换个源试试",
            )
            return
        }

        // 部分源的 ac=list 不返回 class：退化成单排「全部」，至少还有内容可看
        val plan: List<Pair<String, String>> = if (categories.isEmpty()) {
            listOf("" to "全部")
        } else {
            categories.take(MAX_VIDEO_ROWS).map { it.url to it.title }
        }

        val rows = mutableListOf<VideoRow>()
        var anyFailed = false
        var anyOk = false
        for (chunk in plan.chunked(ROWS_CONCURRENCY)) {
            val results = coroutineScope {
                chunk.map { (id, title) ->
                    async {
                        Triple(
                            id,
                            title,
                            runCatching { contentRepository.explore(sourceId, id, 1) }.getOrNull(),
                        )
                    }
                }.awaitAll()
            }
            for ((id, title, items) in results) {
                if (items == null) {
                    anyFailed = true
                    continue
                }
                anyOk = true
                if (items.isNotEmpty()) rows += VideoRow(id, title, items)
            }
            if (!anyOk && anyFailed) break
        }

        _videoHome.value = _videoHome.value.copy(
            loading = false,
            loadingMore = false,
            categories = categories,
            rows = rows,
            error = when {
                rows.isNotEmpty() -> null
                anyFailed -> "连接不上该视频源，请检查网络或换个源试试"
                else -> "这个源暂时没有可展示的内容，换一个源试试"
            },
        )
    }

    /** 网格视图：单分类海报墙 + 触底分页 */
    private suspend fun loadVideoGrid(sourceId: String, reset: Boolean) {
        if (reset) videoPage = 0
        val nextPage = videoPage + 1
        _videoHome.value = _videoHome.value.copy(
            loading = reset,
            loadingMore = !reset,
            error = null,
        )

        val category = _videoHome.value.selectedCategory
        val items = runCatching { contentRepository.explore(sourceId, category, nextPage) }
            .getOrDefault(emptyList())

        videoPage = nextPage
        val current = _videoHome.value
        val merged = if (reset) items else current.items + items
        _videoHome.value = current.copy(
            items = merged.distinctBy { "${it.sourceId}:${it.id}" },
            loading = false,
            loadingMore = false,
            hasMore = items.isNotEmpty(),
            error = if (reset && items.isEmpty()) "没有取到内容，可能是源暂时不可用或该分类为空" else null,
        )
    }

    companion object {
        /** 分区视图最多展示几排（MoonTV 首页 3 排；采集站分类多，取 6 排） */
        private const val MAX_VIDEO_ROWS = 6

        /** 分区并发拉取路数 */
        private const val ROWS_CONCURRENCY = 3

        /** 「继续观看」最多展示条数 */
        private const val MAX_CONTINUE_ITEMS = 12

        /** 「继续观看」随条目带出的续播集数 extras key */
        const val EXTRA_RESUME_INDEX = "resumeIndex"

        /** 「继续观看」随条目带出的续播集地址 extras key（优先按地址匹配） */
        const val EXTRA_RESUME_URL = "resumeUrl"
    }
}
