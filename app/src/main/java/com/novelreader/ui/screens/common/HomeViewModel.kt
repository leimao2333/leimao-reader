package com.novelreader.ui.screens.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.util.VideoFilters
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.category.isAdult
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.domain.repository.ReadingProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /** 影视首页 / 频道内容磁盘缓存（v1.5.7.0「加载一次之后固定」，测试可传 null） */
    private val videoCache: com.novelreader.data.cache.VideoHomeCache? = null,
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

    /** 重新加载书架条目的阅读进度（回前台时调用，进度可能已变化）。
     *  并发查询（此前串行逐条，书多时累积延迟；v1.4.8.0 改并发）。 */
    fun refreshProgress() {
        viewModelScope.launch {
            val books = _books.value
            if (books.isEmpty()) {
                _progressMap.value = emptyMap()
                return@launch
            }
            val map = coroutineScope {
                books.map { item -> async {
                    progressRepository.getProgress(item.sourceId, item.id)?.let { itemKey(item) to it }
                } }.awaitAll().filterNotNull().toMap()
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

    /**
     * 当前源列表里的成人（18+）源 id 集合（v1.5.8.0）。
     *
     * 视频首页 / 频道聚合时，来自成人源的内容统一沉底，不抢占首屏位置。
     */
    private fun adultSourceIds(sources: List<SourceInfo>): Set<String> =
        sources.filter { it.isAdult }.map { it.id }.toSet()

    /** 把来自成人源的条目稳定沉底（保持原有相对顺序） */
    private fun sinkAdults(items: List<MediaItem>, adultIds: Set<String>): List<MediaItem> =
        if (adultIds.isEmpty()) items else items.sortedBy { it.sourceId in adultIds }

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
        /** 首屏内容最近一次成功加载的时间戳（毫秒）。用于短时间切 Tab / 返回时复用缓存，避免重拉。 */
        val loadedAtMs: Long = 0L,
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
            // 冷启动竞态防护：源恢复（restoreSources）是异步的，若不等它完成就查源，
            // 首次进视频 Tab 会拿到空列表并显示「还没有可用的视频源」（v1.4.8.0）。
            runCatching { repo.awaitRestored() }
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
            // 缓存门控（v1.5.0.0 起改为会话内永久复用；v1.5.7.0 落盘跨会话复用）：
            // 内存没有内容时先读磁盘缓存直接上屏，不自动发网络请求——
            // 冷启动秒开，重新拉取只靠**刷新按钮**（refreshVideoHome）与**切源 / 筛选 /
            // 分类切换**。缓存作废（无缓存 / 源集变化）才自动加载一次。
            if (!next.loaded && !hydrateHomeFromCache()) {
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

    /** 读上次停留的视频频道栏目（v1.5.0.0 持久化） */
    suspend fun getVideoChannelIndex(): Int =
        runCatching { preferencesManager?.videoChannelIndexFlow?.first() }.getOrNull() ?: 0

    /** 保存视频频道栏目 */
    fun setVideoChannelIndex(index: Int) {
        viewModelScope.launch { runCatching { preferencesManager?.setVideoChannelIndex(index) } }
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

    /** 删除一条「继续观看」记录（长按海报触发，v1.5.10.0 历史编辑） */
    fun removeContinueWatching(item: com.novelreader.domain.model.MediaItem) {
        viewModelScope.launch {
            runCatching { progressRepository.removeProgress(item.sourceId, item.id) }
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
     * 请求量控制：存活源取前 [MAX_AGGREGATE_SOURCES] 个，分类合并后取前 [MAX_VIDEO_ROWS] 个。
     *
     * v1.4.8.0 加载提速：
     * - 探测改**单请求优先**（拉分类即判活），且整轮探测 8s 有界超时——此前死源会
     *   「分类失败再补一次内容探测」串行两个 12s，首页要等全部探测完才开始拉内容；
     * - 分类在探测阶段**顺带取到**，不再逐源串行重拉一遍；
     * - 各分类行**全部并发**且**每完成一行就先上屏**——此前 3 行一轮串行等待，
     *   最坏 2×单行耗时，用户盯着骨架屏干等。
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

        // 1) 探测存活源：单请求优先（能拉到分类即活），分类为空/失败再退化一次内容探测；
        //    每个源独立有界超时，确认存活就增量刷新可用源列表。
        //    v1.5.8.0「尽量所有源都聚合」：不再截断前 N 个源，探测全部启用源（并发 + 有界超时兜底）。
        val alive = java.util.Collections.synchronizedList(mutableListOf<com.novelreader.domain.model.SourceInfo>())
        val catsBySource = java.util.concurrent.ConcurrentHashMap<String, List<ExploreCategory>>()
        coroutineScope {
            all.map { src -> async {
                val cats = runCatching {
                    withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) { contentRepository.exploreCategoriesOf(src.id) }
                }.getOrNull().orEmpty()
                val ok = cats.isNotEmpty() || runCatching {
                    withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) { contentRepository.explore(src.id, "", 1).isNotEmpty() }
                }.getOrNull() == true
                if (ok) {
                    if (cats.isNotEmpty()) catsBySource[src.id] = cats
                    alive.add(src)
                    _videoHome.value = _videoHome.value.copy(availableSources = alive.toList().map { it.id })
                }
            } }.awaitAll()
        }
        if (alive.isEmpty()) {
            _videoHome.value = _videoHome.value.copy(
                loading = false,
                error = "所有视频源都暂时无法连接，请检查网络后重试",
            )
            return
        }

        // v1.5.8.0：存活源按「普通源优先、成人源沉底」排序，让成人内容不抢占首屏分区
        val adultIds = adultSourceIds(_videoHome.value.sources)
        val orderedAlive = alive.sortedBy { it.id in adultIds }

        // 2) 合并各存活源的分类（探测阶段已顺带取到），按标题去重保序
        val allCats = orderedAlive.flatMap { catsBySource[it.id].orEmpty() }
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

        // 3) 每个分类并发拉各存活源该分类的内容，合并去重；每完成一行先上屏
        val rows = java.util.Collections.synchronizedList(mutableListOf<VideoRow>())
        coroutineScope {
            plan.mapIndexed { _, (catUrl, title) -> async {
                val merged = coroutineScope {
                    orderedAlive.map { src -> async {
                        runCatching {
                            withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) { contentRepository.explore(src.id, catUrl, 1) }
                        }.getOrNull().orEmpty()
                    } }.awaitAll().flatten()
                }
                val seen = LinkedHashSet<String>()
                val dedup = merged.filter { seen.add("${it.sourceId}:${it.id}") }
                    // v1.6.5.0：分区标题（分类名）写进 tags，详情页标签小栏用
                    //（「全部」兜底行不是真分类，跳过）
                    .map { if (title.isNotBlank() && title != "全部") it.copy(tags = it.tags + title) else it }
                // v1.5.8.0：分区行内成人源内容沉底
                val sunk = sinkAdults(dedup, adultIds)
                if (sunk.isNotEmpty()) {
                    rows.add(VideoRow(catUrl, title, sunk))
                    _videoHome.value = _videoHome.value.copy(
                        loading = false,
                        rows = rows.toList().sortedBy { r -> plan.indexOfFirst { it.first == r.categoryId } },
                    )
                }
            } }.awaitAll()
        }

        _videoHome.value = _videoHome.value.copy(
            loading = false,
            loadingMore = false,
            categories = mergedCats.values.toList(),
            rows = rows.toList().sortedBy { r -> plan.indexOfFirst { it.first == r.categoryId } },
            availableSources = orderedAlive.map { it.id },
            error = if (rows.isEmpty()) "没有取到内容，可能是各源暂时不可用，稍后再试" else null,
            loadedAtMs = if (rows.isNotEmpty()) System.currentTimeMillis() else 0L,
        )
        if (rows.isNotEmpty()) saveHomeCache()
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
        val adultIds = adultSourceIds(current.sources)
        // v1.6.5.0：网格视图的分类标题（selectedCategory 是 url，标题在 expandedTitle）
        // 写进 tags，详情页标签小栏用
        val gridTag = current.expandedTitle.takeIf { it.isNotBlank() && it != "全部" }
        _videoHome.value = current.copy(
            items = sinkAdults(
                merged.distinctBy { "${it.sourceId}:${it.id}" }
                    .map { if (gridTag != null) it.copy(tags = it.tags + gridTag) else it },
                adultIds,
            ),
            loading = false,
            loadingMore = false,
            hasMore = fetched.isNotEmpty(),
            error = if (reset && fetched.isEmpty()) "没有取到内容，可能是源暂时不可用或该分类为空" else null,
            loadedAtMs = if (reset && fetched.isNotEmpty()) System.currentTimeMillis() else current.loadedAtMs,
        )
    }

    // ===== 内容磁盘缓存（v1.5.7.0「加载一次之后固定」）=====
    //
    // 进入视频页（含冷启动）先读缓存直接上屏，不自动发请求；重新拉取只靠刷新按钮、
    // 切源、换分类 / 筛选。缓存 payload 带启用视频源 id 集合，源集变了（导入 /
    // 删除 / 启停）旧聚合不再成立，缓存作废、自动重新加载一次。

    private val cacheGson = com.google.gson.Gson()

    /** 首页分区缓存 payload */
    private data class HomeCachePayload(
        val sourceIds: List<String> = emptyList(),
        val currentSourceId: String = "",
        val rows: List<VideoRow> = emptyList(),
        val categories: List<ExploreCategory> = emptyList(),
        val availableSources: List<String> = emptyList(),
    )

    /** 频道（短剧 / 电影 / 动漫）缓存 payload */
    private data class ChannelCachePayload(
        val sourceIds: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val selectedCategory: String = "",
        val items: List<MediaItem> = emptyList(),
    )

    private fun homeCacheKey() = "home"

    private fun channelCacheKey(channel: VideoChannel) = "channel_${channel.name}"

    /**
     * 当前启用视频源 id 集合（缓存有效性校验的基准）。
     *
     * `_videoHome.sources` 只有进过视频 Tab 才有值；冷启动直接落在某个频道上时
     * 还是空的，退化为查仓库（等源恢复完成再读）。
     */
    private suspend fun currentEnabledVideoIds(): Set<String> {
        _videoHome.value.sources.takeIf { it.isNotEmpty() }?.let { list ->
            return list.map { it.id }.toSet()
        }
        return runCatching {
            sourceRepository?.awaitRestored()
            sourceRepository?.getSourcesByType(SourceType.VIDEO)
                .orEmpty()
                .filter { it.enabled }
        }.getOrDefault(emptyList()).map { it.id }.toSet()
    }

    /** 从磁盘恢复首页分区内容；返回是否恢复成功（有缓存、源集一致、rows 非空） */
    private suspend fun hydrateHomeFromCache(): Boolean {
        val cache = videoCache ?: return false
        val raw = runCatching { cache.load(homeCacheKey()) }.getOrNull() ?: return false
        val payload = runCatching {
            cacheGson.fromJson(raw, HomeCachePayload::class.java)
        }.getOrNull() ?: return false
        if (payload.sourceIds.isEmpty() || payload.rows.isEmpty()) return false
        if (payload.sourceIds.toSet() != currentEnabledVideoIds()) return false
        val s = _videoHome.value
        _videoHome.value = s.copy(
            currentSourceId = payload.currentSourceId
                .takeIf { id -> s.sources.any { it.id == id } }
                ?: s.currentSourceId,
            rows = payload.rows,
            categories = payload.categories,
            availableSources = payload.availableSources.ifEmpty { payload.sourceIds },
            loading = false,
            loadingMore = false,
            error = null,
        )
        return true
    }

    /** 首页加载成功后落盘（失败静默，缓存只是加速） */
    private suspend fun saveHomeCache() {
        val cache = videoCache ?: return
        val s = _videoHome.value
        if (s.rows.isEmpty() || s.sources.isEmpty()) return
        runCatching {
            cache.save(
                homeCacheKey(),
                cacheGson.toJson(
                    HomeCachePayload(
                        sourceIds = currentEnabledVideoIds().toList(),
                        currentSourceId = s.currentSourceId,
                        rows = s.rows,
                        categories = s.categories,
                        availableSources = s.availableSources,
                    ),
                ),
            )
        }
    }

    /** 从磁盘恢复频道内容；返回是否恢复成功 */
    private suspend fun hydrateChannelFromCache(channel: VideoChannel): Boolean {
        val cache = videoCache ?: return false
        val raw = runCatching { cache.load(channelCacheKey(channel)) }.getOrNull() ?: return false
        val p = runCatching {
            cacheGson.fromJson(raw, ChannelCachePayload::class.java)
        }.getOrNull() ?: return false
        if (p.items.isEmpty() || p.categories.isEmpty()) return false
        if (p.sourceIds.toSet() != currentEnabledVideoIds()) return false
        stateOf(channel).value = VideoChannelState(
            categories = p.categories,
            selectedCategory = p.selectedCategory.takeIf { it in p.categories }
                ?: p.categories.first(),
            items = p.items,
        )
        return true
    }

    /** 频道内容变化（首屏 / 翻页）后落盘 */
    private suspend fun saveChannelCache(channel: VideoChannel) {
        val cache = videoCache ?: return
        val st = stateOf(channel).value
        if (st.items.isEmpty() || st.categories.isEmpty()) return
        runCatching {
            cache.save(
                channelCacheKey(channel),
                cacheGson.toJson(
                    ChannelCachePayload(
                        sourceIds = currentEnabledVideoIds().toList(),
                        categories = st.categories,
                        selectedCategory = st.selectedCategory,
                        items = st.items,
                    ),
                ),
            )
        }
    }

    // ===== 短剧频道（v1.3.9.0，对标抖音式竖屏沉浸短剧流） =====

    /**
     * 短剧频道状态。
     *
     * 数据 = 聚合所有「分类名含『短剧』」的可用源（实测豆瓣/茅台/iKun/电影天堂/猫眼/量子/360
     * 等 7 个活源都有短剧分类），短剧分类名作为顶部 Tab，选中分类后聚合各源该分类的海报。
     */
    /**
     * 视频频道状态（短剧 / 电影 / 动漫三个频道共用，v1.5.0.0 从 ShortDramaState 泛化）。
     *
     * 结构对标红果短剧 / 各大视频 App 的频道页：分类 Tab + 海报网格 + 触底分页。
     */
    data class VideoChannelState(
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        /** 分类名（顶部 Tab，跨源去重保序） */
        val categories: List<String> = emptyList(),
        /** 当前选中分类名（空 = 未选） */
        val selectedCategory: String = "",
        /** 当前分类的海报（跨源聚合去重） */
        val items: List<MediaItem> = emptyList(),
        val error: String? = null,
    ) {
        val loaded: Boolean get() = items.isNotEmpty()
    }

    private val _shortDrama = MutableStateFlow(VideoChannelState())
    val shortDrama: StateFlow<VideoChannelState> = _shortDrama.asStateFlow()

    private val _movieChannel = MutableStateFlow(VideoChannelState())
    val movieChannel: StateFlow<VideoChannelState> = _movieChannel.asStateFlow()

    private val _animeChannel = MutableStateFlow(VideoChannelState())
    val animeChannel: StateFlow<VideoChannelState> = _animeChannel.asStateFlow()

    /** 频道定义：入口 →（分类关键词, state, 打标用的频道名） */
    private enum class VideoChannel(
        val title: String,
        val keywords: List<String>,
    ) {
        SHORT_DRAMA("短剧", listOf("短剧")),
        MOVIE("电影", listOf("电影")),
        ANIME("动漫", listOf("动漫", "动画", "番剧"));

        /**
         * 分类名是否属于本频道。
         *
         * 比单纯 `contains(keyword)` 更精准：分类名含关键词，但要排除「解说 / 剪辑 /
         * 预告 / 花絮 / 混剪 / 片花 / 合集 / 经典回顾」这类非正片栏目，
         * 否则「电影」频道会混入「电影解说」「微电影解说」，「动漫」混入「动漫剪辑」。
         */
        fun matchesCategory(title: String): Boolean {
            if (title.isBlank()) return false
            if (CATEGORY_EXCLUDE_WORDS.any { title.contains(it) }) return false
            return keywords.any { title.contains(it) }
        }

        /**
         * 分类 Tab 排序权重（越小越靠前）。
         *
         * 0 = 纯正片分类（分类名就是关键词本身，如「电影」「动漫」「动画」）；
         * 1 = 带修饰的正片分类（如「国产动漫」「日本动漫」「最新电影」）；
         * 2 = 其余（更宽泛的匹配项）。保证「胡乱排」变成稳定、可预期的顺序。
         */
        fun categoryRank(title: String): Int = when {
            keywords.any { title == it } -> 0
            else -> 1
        }
    }

    /** 频道运行时（各自独立的聚合计划缓存 / 分页游标 / 任务） */
    private class ChannelRuntime {
        var plan: Map<String, List<Pair<com.novelreader.domain.model.SourceInfo, String>>>? = null
        val pages = mutableMapOf<String, Int>()
        val exhausted = mutableSetOf<String>()
        var job: kotlinx.coroutines.Job? = null
    }

    private val channelRuntimes = mutableMapOf<VideoChannel, ChannelRuntime>()

    private fun runtimeOf(channel: VideoChannel) =
        channelRuntimes.getOrPut(channel) { ChannelRuntime() }

    private fun stateOf(channel: VideoChannel): MutableStateFlow<VideoChannelState> = when (channel) {
        VideoChannel.SHORT_DRAMA -> _shortDrama
        VideoChannel.MOVIE -> _movieChannel
        VideoChannel.ANIME -> _animeChannel
    }

    /** 进入短剧 Tab：已有内容就不重复加载 */
    fun onShortDramaShown() = onChannelShown(VideoChannel.SHORT_DRAMA)

    /** 进入电影 Tab */
    fun onMovieChannelShown() = onChannelShown(VideoChannel.MOVIE)

    /** 进入动漫 Tab */
    fun onAnimeChannelShown() = onChannelShown(VideoChannel.ANIME)

    private fun onChannelShown(channel: VideoChannel) {
        val st = stateOf(channel).value
        if (st.loading || st.loaded) return
        viewModelScope.launch {
            // v1.5.7.0「加载一次之后固定」：内存没有内容时先读磁盘缓存（冷启动秒开），
            // 命中就不发请求；无缓存 / 源集变了才自动探测加载。刷新按钮始终可强制重拉。
            if (!hydrateChannelFromCache(channel)) {
                loadChannel(channel, reset = true)
            }
        }
    }

    /** 刷新短剧（丢弃聚合计划重新探测） */
    fun refreshShortDrama() = refreshChannel(VideoChannel.SHORT_DRAMA)

    /** 刷新电影频道 */
    fun refreshMovieChannel() = refreshChannel(VideoChannel.MOVIE)

    /** 刷新动漫频道 */
    fun refreshAnimeChannel() = refreshChannel(VideoChannel.ANIME)

    private fun refreshChannel(channel: VideoChannel) {
        val rt = runtimeOf(channel)
        rt.plan = null
        rt.pages.clear()
        rt.exhausted.clear()
        // 刷新不清空已有内容（v1.5.7.0）：探测 + 拉取要几秒，把旧内容顶成转圈
        // 就是「一按刷新页面空白」；等新数据回来后 reset 加载会整体替换
        stateOf(channel).value = stateOf(channel).value.copy(loading = true, error = null)
        loadChannel(channel, reset = true)
    }

    /** 切换短剧分类 Tab */
    fun selectShortDramaCategory(category: String) =
        selectChannelCategory(VideoChannel.SHORT_DRAMA, category)

    /** 切换电影频道分类 Tab */
    fun selectMovieChannelCategory(category: String) =
        selectChannelCategory(VideoChannel.MOVIE, category)

    /** 切换动漫频道分类 Tab */
    fun selectAnimeChannelCategory(category: String) =
        selectChannelCategory(VideoChannel.ANIME, category)

    private fun selectChannelCategory(channel: VideoChannel, category: String) {
        val flow = stateOf(channel)
        val st = flow.value
        if (category == st.selectedCategory) return
        flow.value = st.copy(selectedCategory = category, items = emptyList())
        loadChannelCategory(channel, category, reset = true)
    }

    /** 当前分类触底加载下一页 */
    fun loadMoreShortDrama() = loadMoreChannel(VideoChannel.SHORT_DRAMA)

    /** 电影频道触底加载下一页 */
    fun loadMoreMovieChannel() = loadMoreChannel(VideoChannel.MOVIE)

    /** 动漫频道触底加载下一页 */
    fun loadMoreAnimeChannel() = loadMoreChannel(VideoChannel.ANIME)

    private fun loadMoreChannel(channel: VideoChannel) {
        val st = stateOf(channel).value
        if (st.loading || st.loadingMore || st.selectedCategory.isEmpty()) return
        loadChannelCategory(channel, st.selectedCategory, reset = false)
    }

    private fun loadChannel(channel: VideoChannel, reset: Boolean) {
        val rt = runtimeOf(channel)
        rt.job?.cancel()
        rt.job = viewModelScope.launch {
            val flow = stateOf(channel)
            flow.value = flow.value.copy(loading = true, error = null)
            val plan = ensureChannelPlan(channel, rt)
            if (plan.isEmpty()) {
                flow.value = flow.value.copy(
                    loading = false,
                    error = "没有找到带「${channel.title}」分类的可用视频源",
                )
                return@launch
            }
            val categories = plan.keys.toList()
            val first = categories.first()
            flow.value = flow.value.copy(
                loading = false,
                categories = categories,
                selectedCategory = first,
            )
            loadChannelCategory(channel, first, reset = true)
        }
    }

    /** 加载某个分类：聚合各源该分类的内容（分页） */
    private fun loadChannelCategory(channel: VideoChannel, category: String, reset: Boolean) {
        val rt = runtimeOf(channel)
        rt.job?.cancel()
        rt.job = viewModelScope.launch {
            val flow = stateOf(channel)
            val st = flow.value
            if (reset) flow.value = st.copy(loading = true, error = null)
            else flow.value = st.copy(loadingMore = true)

            val plan = ensureChannelPlan(channel, rt)
            val entries = plan[category].orEmpty()
            if (entries.isEmpty()) {
                flow.value = flow.value.copy(loading = false, loadingMore = false)
                return@launch
            }

            // 该分类下各（源, 分类地址）翻页；空页标记枯竭
            val active = entries.filter { (src, cat) -> dramaKey(src.id, cat) !in rt.exhausted }
            val nextPage = if (reset) 1 else (rt.pages[category] ?: 0) + 1
            val fetched = coroutineScope {
                active.map { (src, cat) -> async {
                    runCatching {
                        withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
                            contentRepository.explore(src.id, cat, nextPage)
                        }
                    }.getOrNull().orEmpty()
                        .also { if (it.isEmpty()) rt.exhausted += dramaKey(src.id, cat) }
                } }.awaitAll().flatten()
            }
            if (fetched.isNotEmpty()) rt.pages[category] = nextPage

            val current = flow.value
            val merged = if (reset) fetched else current.items + fetched
            val adultIds = adultSourceIds(_videoHome.value.sources)
            flow.value = current.copy(
                // 短剧条目打「短剧」标记：详情页 / 播放页据此默认竖屏播放 + 红果式选集
                items = sinkAdults(
                    merged.distinctBy { "${it.sourceId}:${it.id}" }
                        .map {
                            // v1.6.5.0：分类名写进 tags，详情页标签小栏用
                            val tagged = it.copy(tags = it.tags + category)
                            if (channel == VideoChannel.SHORT_DRAMA) {
                                tagged.copy(extras = tagged.extras + (EXTRA_SHORT_DRAMA to "1"))
                            } else tagged
                        },
                    adultIds,
                ),
                loading = false,
                loadingMore = false,
                error = if (reset && fetched.isEmpty()) "该分类暂无内容" else null,
            )
            // 内容有变化就落盘（首屏 / 翻页都算），下次冷启动直接上屏这批
            if (fetched.isNotEmpty()) saveChannelCache(channel)
        }
    }

    /** 探测所有源里带目标关键词分类的，按分类名聚合 → 各（源, 分类地址）。会话内缓存 */
    private suspend fun ensureChannelPlan(
        channel: VideoChannel,
        rt: ChannelRuntime,
    ): Map<String, List<Pair<com.novelreader.domain.model.SourceInfo, String>>> {
        rt.plan?.let { return it }
        // 冷启动竞态优化：首页正在探测存活源时先等它完成（最多 15s），
        // 频道 plan 直接用存活源，避免重复探测全部源（死源每个都要等超时）
        if (_videoHome.value.availableSources.isEmpty() && _videoHome.value.loading) {
            val deadline = System.currentTimeMillis() + 15_000L
            while (_videoHome.value.availableSources.isEmpty() &&
                _videoHome.value.loading &&
                System.currentTimeMillis() < deadline
            ) {
                delay(300)
            }
        }
        // 优先用首页探测出的存活源（availableSources）——避免对已知死源重复发起分类请求；
        // 首页还没探测完（冷启动）时退化为全部启用源，并等源恢复完成再读
        val sources = _videoHome.value.availableSources
            .mapNotNull { id -> _videoHome.value.sources.firstOrNull { it.id == id } }
            .ifEmpty {
                val all = _videoHome.value.sources.ifEmpty {
                    runCatching { sourceRepository?.awaitRestored() }
                    sourceRepository?.getSourcesByType(SourceType.VIDEO)
                        ?.filter { it.enabled }
                        .orEmpty()
                }
                // v1.5.8.0「尽量所有源都聚合」：不截断，聚合全部启用源
                all
            }
            // v1.5.8.0：成人源沉底，让成人内容在频道聚合中靠后
            .sortedBy { it.isAdult }
        val found = coroutineScope {
            sources.map { src -> async {
                val cats = runCatching {
                    withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) { contentRepository.exploreCategoriesOf(src.id) }
                }.getOrNull().orEmpty()
                    .filter { cat -> channel.matchesCategory(cat.title) }
                cats.map { cat -> cat.title to (src to cat.url) }
            } }.awaitAll().flatten()
        }
        // 分类 Tab 排序：正片分类优先（纯「电影/动漫」等排最前），解说/剪辑类靠后，
        // 同一分类名下跨源聚合。避免分类顺序随源探测先后而「胡乱排」。
        val grouped = LinkedHashMap<String, MutableList<Pair<com.novelreader.domain.model.SourceInfo, String>>>()
        for ((title, entry) in found.sortedBy { (title, _) -> channel.categoryRank(title) }) {
            grouped.getOrPut(title) { mutableListOf() }.add(entry)
        }
        rt.plan = grouped
        return grouped
    }

    private fun dramaKey(sourceId: String, catUrl: String) = "$sourceId|$catUrl"

    companion object {
        /** 分区视图最多展示几排（v1.5.8.0 从 6 提到 12，尽量展示所有聚合出的分类） */
        private const val MAX_VIDEO_ROWS = 12

        /**
         * 聚合模式探测的源数量上限（v1.5.8.0 起不再截断，此常量仅作兜底防极端异常）。
         *
         * 历史：实测约 1/3 存活，曾取前 12 个；现改为「尽量所有源都聚合」，
         * 探测全部启用源（并发 + 单源 10s 有界超时，死源快速失败不拖累整页）。
         */
        private const val MAX_AGGREGATE_SOURCES = 512

        /** 单个源探测 / 分类行内容请求的有界超时（毫秒）——死源快速失败，不拖累整页 */
        private const val SOURCE_PROBE_TIMEOUT_MS = 10_000L

        /** 「继续观看」最多展示条数 */
        private const val MAX_CONTINUE_ITEMS = 12

        /** 「继续观看」随条目带出的续播集数 extras key */
        const val EXTRA_RESUME_INDEX = "resumeIndex"

        /** 「继续观看」随条目带出的续播集地址 extras key（优先按地址匹配） */
        const val EXTRA_RESUME_URL = "resumeUrl"

        /** 短剧标记（extras key）：值为 "1" 时播放页默认竖屏 + 红果式选集 */
        const val EXTRA_SHORT_DRAMA = "isShortDrama"

        /**
         * 频道分类的排除词：分类名含这些词即视为「非正片」栏目，不进电影 / 动漫 / 短剧频道。
         * 覆盖「解说 / 剪辑 / 预告 / 花絮 / 混剪 / 片花 / 合集 / 经典回顾 / 片段」等，
         * 避免「电影」频道混入「电影解说」、「动漫」混入「动漫剪辑」。
         */
        private val CATEGORY_EXCLUDE_WORDS = listOf(
            "解说", "剪辑", "预告", "花絮", "混剪", "片花", "合集", "片段",
            "经典回顾", "吐槽", "评论", "盘点", "榜单", "排行榜",
        )
    }
}
