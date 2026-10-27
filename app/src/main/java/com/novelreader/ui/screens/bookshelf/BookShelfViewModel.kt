package com.novelreader.ui.screens.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
            items.forEach { item ->
                val key = itemKey(item)
                if (_lastChapterMap.value.containsKey(key)) return@forEach
                val chapters = runCatching { contentRepository.chapterList(item) }.getOrDefault(emptyList())
                if (chapters.isEmpty()) return@forEach
                // v1.8.8.5：这次请求本来就是为「最新章节 / 章节总数」发的，
                // 顺手把整份目录塞进导航缓存 —— 用户随后点击这本书时直接命中，
                // 不用再等一次目录请求（点击响应的关键）。
                com.novelreader.ui.navigation.NavCache.putChapters(item, chapters)
                _chapterCountMap.value = _chapterCountMap.value + (key to chapters.size)
                val last = chapters.lastOrNull()?.title
                if (!last.isNullOrBlank()) {
                    _lastChapterMap.value = _lastChapterMap.value + (key to last)
                }
            }
        }
    }
}
