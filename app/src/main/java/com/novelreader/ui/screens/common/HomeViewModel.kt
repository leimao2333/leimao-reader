package com.novelreader.ui.screens.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.util.VideoFilters
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ExploreCategory
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
        /** 片库筛选：地区（空串 = 不限） */
        val selectedArea: String = "",
        /** 片库筛选：年份（空串 = 不限） */
        val selectedYear: String = "",
        /** 片库筛选：排序（空串 = 源默认；`time` 最新 / `hits` 最热 / `score` 评分） */
        val selectedOrder: String = "",
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
        /** 非致命提示（如「已自动切换到某源」），独立于 error，不随重新加载清除 */
        val notice: String? = null,
        /** 聚合模式下探测出的存活源 id 列表（分区视图加载时填充，网格视图复用） */
        val availableSources: List<String> = emptyList(),
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
            notice = null,
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

    // ===== 片库四维筛选（v1.3.5.0）=====
    //
    // 分类维度见 selectVideoCategory；以下三个维度共用一套「改值 → 清列表 → 重拉首屏」流程。
    // 三个维度互不影响：切地区不该把已选年份清掉，所以只改自己那一项。

    /** 切换地区（空串 = 不限） */
    fun selectVideoArea(area: String) = applyVideoFilter { it.copy(selectedArea = area) }

    /** 切换年份（空串 = 不限） */
    fun selectVideoYear(year: String) = applyVideoFilter { it.copy(selectedYear = year) }

    /** 切换排序（空串 = 源默认） */
    fun selectVideoOrder(order: String) = applyVideoFilter { it.copy(selectedOrder = order) }

    /**
     * 应用一次筛选变更：值没变就直接返回（避免重复请求），
     * 变了就清空列表、重置分页并回到网格视图重拉首屏。
     */
    private fun applyVideoFilter(transform: (VideoHomeState) -> VideoHomeState) {
        val current = _videoHome.value
        val next = transform(current)
        if (next == current) return
        _videoHome.value = next.copy(expanded = true, items = emptyList(), hasMore = true)
        loadVideoHome(reset = true)
    }

    /**
     * 只重算「继续观看」，不触发任何网络请求。
     *
     * 场景：从播放页 / 详情页返回首页时，观看进度刚变过，
     * 而这一块只在「首次进页 / 下拉刷新 / 切源」时重建，不刷新就看不到刚看的那部片。
     */
    fun refreshContinueWatching() {
        viewModelScope.launch {
            _videoHome.value = _videoHome.value.copy(
                continueWatching = buildContinueWatching(_videoHome.value.sources),
            )
        }
    }

    /** 下拉刷新（分区视图连「继续观看」一起刷新） */
    fun refreshVideoHome() {
        if (!_videoHome.value.expanded) {
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
     * 分区视图：继续观看 + 各分类（**跨源聚合**）各一排横滑。
     *
     * 聚合式源（v1.3.9.0）：不再让用户手动挑单个源（实测采集站失效比例极高，约 2/3 源已死），
     * 而是**自动探测所有可用源 → 合并它们的分类（同名合并）→ 每个分类聚合各源内容去重**。
     * 死源在探测阶段直接跳过，不再有「选到死源空屏」的问题。
     *
     * 请求量控制：存活源取前 [MAX_AGGREGATE_SOURCES] 个，分类合并后取前 [MAX_VIDEO_ROWS] 个，
     * 每个分类并发拉各源内容（[ROWS_CONCURRENCY] 路），单源超时由仓库层兜底。
     */
    private suspend fun loadVideoSections(sourceId: String) {
        _videoHome.value = _videoHome.value.copy(loading = true, loadingMore = false, error = null)

        val all = _videoHome.value.sources
        if (all.isEmpty()) {
            _videoHome.value = _videoHome.value.copy(
                loading = false, error = "还没有可用的视频源，去「源管理 → 导入源」添加",
            )
            return
        }

        // 1) 探测存活源（能拉到分类的源；分类为空的源退化为一次内容请求探测）
        val alive = coroutineScope {
            all.take(MAX_AGGREGATE_SOURCES).map { src -> async {
                val alive = runCatching {
                    contentRepository.exploreCategoriesOf(src.id).isNotEmpty()
                }.getOrDefault(false) || runCatching {
                    contentRepository.explore(src.id, "", 1).isNotEmpty()
                }.getOrDefault(false)
                if (alive) src else null
            } }.awaitAll().filterNotNull()
        }
        if (alive.isEmpty()) {
            _videoHome.value = _videoHome.value.copy(
                loading = false,
                error = "所有视频源都暂时无法连接，请检查网络后重试",
            )
            return
        }

        // 2) 拉各存活源的分类，按标题合并去重（保序）
        val allCats = alive.flatMap { src ->
            runCatching { contentRepository.exploreCategoriesOf(src.id) }.getOrDefault(emptyList())
        }
        val mergedCats = LinkedHashMap<String, ExploreCategory>()
        for (cat in allCats) if (cat.title.isNotBlank() && cat.title !in mergedCats) {
            mergedCats[cat.title] = cat
        }
        // 有的源不返回分类：用「全部」兜底一行
        val plan: List<Pair<String, String>> = if (mergedCats.isEmpty()) {
            listOf("" to "全部")
        } else {
            mergedCats.values.take(MAX_VIDEO_ROWS).map { it.url to it.title }
        }

        // 3) 每个分类：并发拉各存活源该分类的内容，合并去重
        val rows = mutableListOf<VideoRow>()
        for (chunk in plan.chunked(ROWS_CONCURRENCY)) {
            val results = coroutineScope {
                chunk.map { (catUrl, title) -> async {
                    val merged = coroutineScope {
                        alive.map { src -> async {
                            runCatching { contentRepository.explore(src.id, catUrl, 1) }
                                .getOrDefault(emptyList())
                        } }.awaitAll().flatten()
                    }
                    val seen = LinkedHashSet<String>()
                    val dedup = merged.filter { seen.add("${it.sourceId}:${it.id}") }
                    Triple(catUrl, title, dedup)
                } }.awaitAll()
            }
            for ((catUrl, title, items) in results) {
                if (items.isNotEmpty()) rows += VideoRow(catUrl, title, items)
            }
        }

        _videoHome.value = _videoHome.value.copy(
            loading = false,
            loadingMore = false,
            categories = mergedCats.values.toList(),
            rows = rows,
            availableSources = alive.map { it.id },
            error = if (rows.isEmpty()) "没有取到内容，可能是各源暂时不可用，稍后再试" else null,
        )
    }

    /**
     * 网格视图：单分类海报墙 + 触底分页（带片库筛选参数）。
     *
     * v1.3.9.0 聚合模式：展开分类后**聚合所有存活源该分类的内容**（分页），
     * 而非只拉单个源。存活源来自分区视图探测的 [VideoHomeState.availableSources]，
     * 若为空（直接进网格等边缘情况）则退化为当前源。
     */
    private suspend fun loadVideoGrid(sourceId: String, reset: Boolean) {
        if (reset) videoPage = 0
        val nextPage = videoPage + 1
        val snapshot = _videoHome.value
        val category = snapshot.selectedCategory
        val filters = VideoFilters.toFilterMap(
            area = snapshot.selectedArea,
            year = snapshot.selectedYear,
            order = snapshot.selectedOrder,
        )
        _videoHome.value = snapshot.copy(loading = reset, loadingMore = !reset, error = null)

        // 聚合源：可用源列表（含当前源去重）
        val sourcesToUse = (snapshot.availableSources.ifEmpty { listOf(sourceId) })
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?: listOf(sourceId)

        val fetched = coroutineScope {
            sourcesToUse.map { sid -> async {
                runCatching {
                    contentRepository.exploreFiltered(sid, category, nextPage, filters)
                }.getOrDefault(emptyList())
            } }.awaitAll().flatten()
        }

        videoPage = nextPage
        val current = _videoHome.value
        val seen = current.items.mapTo(HashSet()) { "${it.sourceId}:${it.id}" }
        val merged = if (reset) fetched else current.items + fetched
        _videoHome.value = current.copy(
            items = merged.distinctBy { "${it.sourceId}:${it.id}" },
            loading = false,
            loadingMore = false,
            hasMore = fetched.isNotEmpty(),
            error = if (reset && fetched.isEmpty()) "没有取到内容，可能是源暂时不可用或该分类为空" else null,
        )
    }

    // ===== 短剧频道（v1.3.9.0，对标抖音式竖屏沉浸短剧流） =====

    /**
     * 短剧频道状态。
     *
     * 数据 = 聚合所有「分类名含『短剧』」的可用源（实测豆瓣/茅台/iKun/电影天堂/猫眼/量子/360
     * 等 7 个活源都有短剧分类），短剧分类名作为顶部 Tab，选中分类后聚合各源该分类的海报。
     */
    data class ShortDramaState(
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        /** 短剧分类名（顶部 Tab，跨源去重保序） */
        val categories: List<String> = emptyList(),
        /** 当前选中分类名（空 = 未选） */
        val selectedCategory: String = "",
        /** 当前分类的海报（跨源聚合去重） */
        val items: List<MediaItem> = emptyList(),
        val error: String? = null,
    ) {
        val loaded: Boolean get() = items.isNotEmpty()
    }

    private val _shortDrama = MutableStateFlow(ShortDramaState())
    val shortDrama: StateFlow<ShortDramaState> = _shortDrama.asStateFlow()

    /** 短剧聚合计划：短剧分类名 → 各（源, 分类地址）；会话内缓存 */
    private var dramaPlan: Map<String, List<Pair<com.novelreader.domain.model.SourceInfo, String>>>? = null

    /** 各分类已拉到的页数与枯竭标记 */
    private val dramaPages = mutableMapOf<String, Int>()
    private val dramaExhausted = mutableSetOf<String>()
    private var dramaJob: kotlinx.coroutines.Job? = null

    /** 进入短剧 Tab：已有内容就不重复加载 */
    fun onShortDramaShown() {
        val st = _shortDrama.value
        if (!st.loading && !st.loaded && st.error == null) {
            loadShortDrama(reset = true)
        }
    }

    /** 刷新短剧（丢弃聚合计划重新探测） */
    fun refreshShortDrama() {
        dramaPlan = null
        dramaPages.clear()
        dramaExhausted.clear()
        _shortDrama.value = ShortDramaState(loading = true)
        loadShortDrama(reset = true)
    }

    /** 切换短剧分类 Tab */
    fun selectShortDramaCategory(category: String) {
        val st = _shortDrama.value
        if (category == st.selectedCategory) return
        _shortDrama.value = st.copy(selectedCategory = category, items = emptyList())
        loadShortDramaCategory(category, reset = true)
    }

    /** 当前分类触底加载下一页 */
    fun loadMoreShortDrama() {
        val st = _shortDrama.value
        if (st.loading || st.loadingMore || st.selectedCategory.isEmpty()) return
        loadShortDramaCategory(st.selectedCategory, reset = false)
    }

    private fun loadShortDrama(reset: Boolean) {
        dramaJob?.cancel()
        dramaJob = viewModelScope.launch {
            _shortDrama.value = _shortDrama.value.copy(loading = true, error = null)
            val plan = ensureDramaPlan()
            if (plan.isEmpty()) {
                _shortDrama.value = _shortDrama.value.copy(
                    loading = false,
                    error = "没有找到带「短剧」分类的可用视频源",
                )
                return@launch
            }
            val categories = plan.keys.toList()
            val first = categories.first()
            _shortDrama.value = _shortDrama.value.copy(
                loading = false,
                categories = categories,
                selectedCategory = first,
            )
            loadShortDramaCategory(first, reset = true)
        }
    }

    /** 加载某个短剧分类：聚合各源该分类的内容（分页） */
    private fun loadShortDramaCategory(category: String, reset: Boolean) {
        dramaJob?.cancel()
        dramaJob = viewModelScope.launch {
            val st = _shortDrama.value
            if (reset) _shortDrama.value = st.copy(loading = true, error = null)
            else _shortDrama.value = st.copy(loadingMore = true)

            val plan = ensureDramaPlan()
            val entries = plan[category].orEmpty()
            if (entries.isEmpty()) {
                _shortDrama.value = _shortDrama.value.copy(loading = false, loadingMore = false)
                return@launch
            }

            // 该分类下各（源, 分类地址）翻页；空页标记枯竭
            val active = entries.filter { (src, cat) -> dramaKey(src.id, cat) !in dramaExhausted }
            val nextPage = if (reset) 1 else (dramaPages[category] ?: 0) + 1
            val fetched = coroutineScope {
                active.map { (src, cat) -> async {
                    runCatching { contentRepository.explore(src.id, cat, nextPage) }
                        .getOrDefault(emptyList())
                        .also { if (it.isEmpty()) dramaExhausted += dramaKey(src.id, cat) }
                } }.awaitAll().flatten()
            }
            if (fetched.isNotEmpty()) dramaPages[category] = nextPage

            val current = _shortDrama.value
            val merged = if (reset) fetched else current.items + fetched
            _shortDrama.value = current.copy(
                // 打「短剧」标记：详情页 / 播放页据此默认竖屏播放 + 红果式选集
                items = merged.distinctBy { "${it.sourceId}:${it.id}" }
                    .map { it.copy(extras = it.extras + (EXTRA_SHORT_DRAMA to "1")) },
                loading = false,
                loadingMore = false,
                error = if (reset && fetched.isEmpty()) "该分类暂无内容" else null,
            )
        }
    }

    /** 探测所有源里带「短剧」分类的，按分类名聚合 → 各（源, 分类地址）。会话内缓存 */
    private suspend fun ensureDramaPlan(): Map<String, List<Pair<com.novelreader.domain.model.SourceInfo, String>>> {
        dramaPlan?.let { return it }
        val sources = _videoHome.value.sources.ifEmpty {
            sourceRepository?.getSourcesByType(SourceType.VIDEO)
                ?.filter { it.enabled }
                .orEmpty()
        }
        val found = coroutineScope {
            sources.map { src -> async {
                val cats = runCatching { contentRepository.exploreCategoriesOf(src.id) }
                    .getOrDefault(emptyList())
                    .filter { it.title.contains("短剧") }
                cats.map { cat -> cat.title to (src to cat.url) }
            } }.awaitAll().flatten()
        }
        val grouped = LinkedHashMap<String, MutableList<Pair<com.novelreader.domain.model.SourceInfo, String>>>()
        for ((title, entry) in found) {
            grouped.getOrPut(title) { mutableListOf() }.add(entry)
        }
        dramaPlan = grouped
        return grouped
    }

    private fun dramaKey(sourceId: String, catUrl: String) = "$sourceId|$catUrl"

    companion object {
        /** 分区视图最多展示几排（MoonTV 首页 3 排；采集站分类多，取 6 排） */
        private const val MAX_VIDEO_ROWS = 6

        /** 聚合模式最多探测几个源（实测约 1/3 存活，探测 12 个足够凑出可用源） */
        private const val MAX_AGGREGATE_SOURCES = 12

        /** 分区并发拉取路数 */
        private const val ROWS_CONCURRENCY = 3

        /** 「继续观看」最多展示条数 */
        private const val MAX_CONTINUE_ITEMS = 12

        /** 「继续观看」随条目带出的续播集数 extras key */
        const val EXTRA_RESUME_INDEX = "resumeIndex"

        /** 「继续观看」随条目带出的续播集地址 extras key（优先按地址匹配） */
        const val EXTRA_RESUME_URL = "resumeUrl"

        /** 短剧标记（extras key）：值为 "1" 时播放页默认竖屏 + 红果式选集 */
        const val EXTRA_SHORT_DRAMA = "isShortDrama"
    }
}
