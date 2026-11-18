package com.novelreader.ui.screens.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.util.AutoGroupInput
import com.novelreader.core.util.BookConflictDetector
import com.novelreader.core.util.BookEntry
import com.novelreader.core.util.ConflictGroup
import com.novelreader.core.util.BookshelfAutoGroup
import com.novelreader.core.util.ShelfCompanionGroup
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.domain.repository.ReadingProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

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
    /** 删书清理器（v1.8.3.0）：书架行 + 章节缓存 + 阅读进度 + 漫画图片缓存一起清 */
    private val shelfPurger: com.novelreader.core.bookshelf.ShelfPurger? = null,
    /** 本地漫画库（D20）：CBZ / 图片文件夹导入 */
    private val localComicStore: com.novelreader.core.local.LocalComicStore? = null,
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

    // ===== 分组（v1.8.3.0 待办 #6：小说 / 漫画 / 视频分组完全分开） =====

    /** 当前分组 tab 对应的类型（null = 全部，合并三类分组名去重） */
    private val _groupType = MutableStateFlow<com.novelreader.domain.model.SourceType?>(null)

    /** 当前可见分组名（供「批量移动到分组」选择） */
    val groupNames: StateFlow<List<String>> = _groupType
        .flatMapLatest { type ->
            if (type == null) {
                combine(
                    bookRepository.observeGroupNames(com.novelreader.domain.model.SourceType.NOVEL),
                    bookRepository.observeGroupNames(com.novelreader.domain.model.SourceType.COMIC),
                    bookRepository.observeGroupNames(com.novelreader.domain.model.SourceType.VIDEO),
                ) { a, b, c -> (a + b + c).distinct().sorted() }
            } else {
                bookRepository.observeGroupNames(type)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 切换分组 tab 时同步类型，让分组名列表跟随（0=全部 1=小说 2=漫画 3=视频） */
    fun setGroupTab(tab: Int) {
        _groupType.value = when (tab) {
            1 -> com.novelreader.domain.model.SourceType.NOVEL
            2 -> com.novelreader.domain.model.SourceType.COMIC
            3 -> com.novelreader.domain.model.SourceType.VIDEO
            else -> null
        }
    }

    // ===== 智能伴生分组（C6：在读 / 未读 / 已读完） =====

    /**
     * 智能分组结果：分组 → 组内书籍在书架列表里的下标。
     *
     * 这里刻意不查 `BookDao.observeShelfStates`：它只回 `books.bookId`（Room 自增主键），
     * 而 `MediaItem` 不带这个主键，拿回来也映射不到界面条目，没法过滤列表。
     * 归类需要的两个输入（是否有进度 / 是否读完）内存里都有：
     * `_progressMap` 提供进度，`totalChapter` 配合 [BookshelfAutoGroup.approxFinished] 判断读完，
     * 判定口径与 DAO 里那段 SQL 完全一致。所以下标只作组内占位标识，
     * UI 过滤请用 [companionGroupOf]（按 itemKey 映射，稳定）。
     */
    private val _companionGroups = MutableStateFlow<Map<ShelfCompanionGroup, List<Long>>>(emptyMap())
    val companionGroups: StateFlow<Map<ShelfCompanionGroup, List<Long>>> = _companionGroups.asStateFlow()

    /** itemKey → 智能分组（界面按此过滤，避免依赖列表下标） */
    private val _companionGroupOf = MutableStateFlow<Map<String, ShelfCompanionGroup>>(emptyMap())
    val companionGroupOf: StateFlow<Map<String, ShelfCompanionGroup>> = _companionGroupOf.asStateFlow()

    /** 当前选中的智能分组；null = 「全部」（不按智能分组过滤） */
    private val _selectedCompanion = MutableStateFlow<ShelfCompanionGroup?>(null)
    val selectedCompanion: StateFlow<ShelfCompanionGroup?> = _selectedCompanion.asStateFlow()

    /**
     * 切换智能分组筛选。
     *
     * - 传 null（点「全部」）：总是回到不过滤；
     * - 再次点击当前已选分组：取消筛选，等价于「全部」；
     * - 其他情况：切到该分组。
     *
     * 该筛选与上方「全部 / 小说 / 漫画 / 视频」tab 是**叠加**关系，互不覆盖。
     */
    fun toggleCompanionFilter(group: ShelfCompanionGroup?) {
        val current = _selectedCompanion.value
        _selectedCompanion.value = when {
            group == null -> null
            group == current -> null
            else -> group
        }
    }

    /**
     * 计算智能分组快照（C6）。
     *
     * @return Pair(分组 → 下标列表, itemKey → 分组)
     */
    private fun computeCompanionGroups(
        books: List<MediaItem>,
        progressMap: Map<String, ReadingProgress>,
    ): Pair<Map<ShelfCompanionGroup, List<Long>>, Map<String, ShelfCompanionGroup>> {
        val inputs = books.mapIndexed { index, item ->
            val progress = progressMap[itemKey(item)]
            AutoGroupInput(
                bookId = index.toLong(),
                hasProgress = progress != null,
                // totalChapter <= 0（目录未知）时 approxFinished 返回 false，不会把新书误判为读完
                finished = progress != null &&
                    BookshelfAutoGroup.approxFinished(progress.chapterIndex, progress.totalChapter),
            )
        }
        val groups = BookshelfAutoGroup.classify(inputs)
        val groupOf = buildMap {
            groups.forEach { (group, ids) ->
                ids.forEach { id -> books.getOrNull(id.toInt())?.let { put(itemKey(it), group) } }
            }
        }
        return groups to groupOf
    }

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
                // 并发查询进度（此前串行逐条，书多时累积延迟；v1.4.8.0 改并发）
                val map = coroutineScope {
                    list.map { item -> async {
                        progressRepository.getProgress(item.sourceId, item.id)?.let { itemKey(item) to it }
                    } }.awaitAll().filterNotNull().toMap()
                }
                _progressMap.value = map
                // 进度与列表同时更新，顺手刷新智能分组快照（C6），避免两处数据不同步
                val (groups, groupOf) = computeCompanionGroups(list, map)
                _companionGroups.value = groups
                _companionGroupOf.value = groupOf
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
     * 导入本地漫画（D20）：CBZ 整包按「一话」处理，落盘到私有目录后加入书架。
     *
     * 之所以复制而不是直接记 Uri：SAF 的 `content://` 授权通常只在本次会话有效，
     * 重启后书架里的漫画会全部变成「无法打开」。
     */
    fun importLocalComic(uri: android.net.Uri, displayName: String?) {
        val store = localComicStore ?: return
        viewModelScope.launch {
            runCatching { store.importCbz(uri, displayName) }
                .onSuccess { meta ->
                    val adapter = com.novelreader.source.local.LocalComicAdapter(meta, store)
                    runCatching { bookRepository.addToBookshelf(adapter.item()) }
                    _importMessage.value = "已导入「${meta.title}」（${meta.chapterCount} 话）"
                }
                .onFailure { e ->
                    _importMessage.value = "导入失败：${e.message ?: "未知错误"}"
                }
        }
    }

    /**
     * 导入本地漫画文件夹（D20）：顶层子目录各成一话，没有子目录则整个目录作为第 1 话。
     */
    fun importLocalComicFolder(treeUri: android.net.Uri, displayName: String?) {
        val store = localComicStore ?: return
        viewModelScope.launch {
            runCatching { store.importFolder(treeUri, displayName) }
                .onSuccess { meta ->
                    val adapter = com.novelreader.source.local.LocalComicAdapter(meta, store)
                    runCatching { bookRepository.addToBookshelf(adapter.item()) }
                    _importMessage.value = "已导入「${meta.title}」（${meta.chapterCount} 话）"
                }
                .onFailure { e ->
                    _importMessage.value = "导入失败：${e.message ?: "未知错误"}"
                }
        }
    }

    /**
     * 导入本地 TXT 书（v1.2.0.0）：解析章节 → 存入本地书库 → 加入书架。
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

    /**
     * 移除收藏（v1.8.3.0 待办 #4）。
     *
     * 删除书籍 = 同时清掉章节缓存、阅读进度与漫画图片缓存，
     * 否则重新收藏后会读回旧进度 / 旧缓存。
     */
    fun remove(item: MediaItem) {
        viewModelScope.launch {
            val purger = shelfPurger
            if (purger != null) purger.purge(listOf(item))
            else bookRepository.removeFromBookshelf(item)
        }
    }

    /** 批量移除收藏（v1.8.3.0 待办 #5：走批量 SQL + 一并清缓存与进度） */
    fun removeBatch(items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val purger = shelfPurger
            if (purger != null) purger.purge(items)
            else bookRepository.removeFromBookshelfBatch(items)
        }
    }

    // ===== 冲突检测 / 去重（C9）=====

    /** 书架里疑似「同一本书的多个来源」分组（空 = 未检测到 / 未运行） */
    private val _conflicts = MutableStateFlow<List<ConflictGroup>>(emptyList())
    val conflicts: StateFlow<List<ConflictGroup>> = _conflicts.asStateFlow()

    /**
     * 检测书架内的重复书籍（C9）。把每本 MediaItem 映射成 [BookEntry]，章节数取已补齐的
     * [chapterCountMap]，交给纯逻辑 [BookConflictDetector.detect]。只在同一类型内比较
     * （小说不会和漫画/视频混成一组），避免跨类型误判。
     *
     * @return 检测到的冲突组数（供 UI 提示「未发现重复」）
     */
    fun detectConflicts(): Int {
        val byType = _books.value.groupBy { it.type }
        val groups = byType.values.flatMap { items ->
            val entries = items.map { item ->
                BookEntry(
                    id = itemKey(item),
                    title = item.title,
                    author = item.author,
                    sourceId = item.sourceId,
                    sourceName = item.sourceName.ifBlank { null },
                    wordCount = null, // MediaItem 无字数字段；章节数已足够作为保留建议依据
                    coverUrl = item.cover,
                    chapterCount = _chapterCountMap.value[itemKey(item)],
                    updatedAt = item.addedAt.takeIf { it > 0 },
                )
            }
            BookConflictDetector.detect(entries)
        }
        _conflicts.value = groups
        return groups.size
    }

    /** 关闭冲突检测结果面板 */
    fun clearConflicts() {
        _conflicts.value = emptyList()
    }

    /**
     * 应用去重建议：删掉一组里除 [keepKey] 之外的所有条目（走批量清理，连缓存与进度）。
     * [keepKey] 是建议保留的条目 id（来自 [ConflictGroup.recommendedId]），用户可改成组内其他 id。
     */
    fun keepOnlyInGroup(group: ConflictGroup, keepKey: String) {
        val toRemove = group.members.filter { it.id != keepKey }
        val items = _books.value.filter { it.itemKey() in toRemove.map { e -> e.id }.toSet() }
        removeBatch(items)
        _conflicts.value = _conflicts.value.filter { it !== group }
    }

    /** 批量清除已缓存章节（保留书架条目；v1.8.3.0 待办 #5） */
    fun clearCacheBatch(items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val purger = shelfPurger
            if (purger != null) purger.purgeCacheOnly(items)
        }
    }

    /** 批量移动到分组（空串 = 移出分组；v1.8.3.0 待办 #5） */
    fun setGroupBatch(items: List<MediaItem>, group: String) {
        if (items.isEmpty()) return
        viewModelScope.launch { bookRepository.setBookGroupBatch(items, group) }
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
        // v1.8.8.5：「点一下几秒没反应」的根因在这里。
        // 旧实现无条件先发一次 `chapterList` 网络请求，拿到整份目录才回调 → 导航。
        // 目录动辄几百上千章，普通源要一两秒，慢源要好几秒，这段时间界面**毫无反应**。
        //
        // 现在先吃目录预热缓存（书架补「最新章节」时已顺手存进来，见 enrichChapterInfo）：
        // 命中就**同步**回调，点击当帧就能导航；进度直接用 `_progressMap`
        // （书架列表加载时已读进内存），不再额外查一次数据库。
        val cached = com.novelreader.ui.navigation.NavCache.getChapters(item)
        if (cached.isNotEmpty()) {
            onReady(cached, resolveTarget(item, cached))
            return
        }
        viewModelScope.launch {
            val chapters = runCatching { contentRepository.chapterList(item) }
                .getOrDefault(emptyList())
            if (chapters.isEmpty()) {
                onReady(emptyList(), null)
                return@launch
            }
            com.novelreader.ui.navigation.NavCache.putChapters(item, chapters)
            onReady(chapters, resolveTarget(item, chapters))
        }
    }

    /** 在章节表里定位上次读到的章节；找不到就退回第一章 */
    private fun resolveTarget(item: MediaItem, chapters: List<ChapterItem>): ChapterItem? {
        if (chapters.isEmpty()) return null
        val p = _progressMap.value[itemKey(item)]
        return p?.let { prog ->
            chapters.firstOrNull { it.url == prog.chapterUrl }
                ?: chapters.firstOrNull { it.index == prog.chapterIndex }
        } ?: chapters.first()
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
            val todo = items.filterNot { _lastChapterMap.value.containsKey(itemKey(it)) }
            if (todo.isEmpty()) return@launch

            // 串行预热在几十本的书架上要跑几十秒，用户点到的书往往还没轮到；
            // 3 路并发既不把源站点打挂，也能在头一两秒覆盖住首屏可见的书。
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            val lastMap = HashMap<String, String>(todo.size)
            val countMap = HashMap<String, Int>(todo.size)

            kotlinx.coroutines.coroutineScope {
                todo.forEach { item ->
                    launch {
                        val key = itemKey(item)
                        val chapters = gate.withPermit {
                            runCatching { contentRepository.chapterList(item) }.getOrDefault(emptyList())
                        }
                        if (chapters.isEmpty()) {
                            // 负缓存：视频条目 / 失效源永远取不到目录，不写占位的话
                            // 每次书架变化（收藏、改分组、进度回写）都会重打一次请求
                            lastMap[key] = ""
                            return@launch
                        }
                        // v1.8.8.5：这次请求本来就是为「最新章节 / 章节总数」发的，
                        // 顺手把整份目录塞进导航缓存 —— 用户随后点击这本书时直接命中，
                        // 不用再等一次目录请求（点击响应的关键）。
                        com.novelreader.ui.navigation.NavCache.putChapters(item, chapters)
                        countMap[key] = chapters.size
                        lastMap[key] = chapters.lastOrNull()?.title.orEmpty()
                    }
                }
            }

            // 一次性合并写入：之前每本书各写一次 StateFlow，N 本书 = N 次整屏重组
            if (countMap.isNotEmpty()) {
                _chapterCountMap.value = _chapterCountMap.value + countMap
            }
            if (lastMap.isNotEmpty()) {
                _lastChapterMap.value = _lastChapterMap.value + lastMap
            }
        }
    }
}
