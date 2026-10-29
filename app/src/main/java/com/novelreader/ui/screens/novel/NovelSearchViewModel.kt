package com.novelreader.ui.screens.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 小说搜索 ViewModel。
 *
 * 使用带进度的跨源搜索，边搜边更新结果与进度。
 * 并发线程数从偏好设置读取（可调）。
 */
class NovelSearchViewModel(
    private val contentRepository: ContentRepository,
    private val preferencesManager: PreferencesManager,
    private val bookRepository: com.novelreader.domain.repository.BookRepository,
    private val sourceRepository: com.novelreader.domain.repository.SourceRepository? = null,
) : ViewModel() {

    /**
     * 「按源搜索」（v1.8.1.1）：可选择只搜某一个源。
     *
     * 全部源一起搜虽然全，但几十个源的慢请求会互相拖累；想找某部作品时直接挑源更快。
     */
    private val _sources = MutableStateFlow<List<com.novelreader.domain.model.SourceInfo>>(emptyList())
    val sources: StateFlow<List<com.novelreader.domain.model.SourceInfo>> = _sources.asStateFlow()

    private val _selectedSourceIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedSourceIds: StateFlow<Set<String>> = _selectedSourceIds.asStateFlow()

    /** 选择源（**多选**；空集合 = 全部源，v1.8.1.2）。已有搜索词时立即按新范围重搜。 */
    fun setSelectedSources(ids: Set<String>) {
        _selectedSourceIds.value = ids
        if (_query.value.isNotBlank()) search()
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 搜索进度状态（结果 + 进度 + 当前源） */
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** 已在书架中的条目 key 集合（"sourceId|itemId"），供搜索卡片显示收藏态（#22） */
    private val _shelfKeys = MutableStateFlow<Set<String>>(emptySet())
    val shelfKeys: StateFlow<Set<String>> = _shelfKeys.asStateFlow()

    /** 长按搜索结果弹操作菜单的开关（v1.8.3.0 待办 #14，设置里可关） */
    private val _longPressShelfEnabled = MutableStateFlow(true)
    val longPressShelfEnabled: StateFlow<Boolean> = _longPressShelfEnabled.asStateFlow()

    /** 模糊搜索开关 */
    private val _fuzzyEnabled = MutableStateFlow(PreferencesManager.DEFAULT_FUZZY_SEARCH)
    val fuzzyEnabled: StateFlow<Boolean> = _fuzzyEnabled.asStateFlow()

    /** 搜索结果展示：true = 宫格，false = 列表（v1.8.6.0 待办 #26） */
    private val _searchGrid = MutableStateFlow(false)
    val searchGrid: StateFlow<Boolean> = _searchGrid.asStateFlow()

    /**
     * 宫格列数（v1.8.7.5）：0 = 自适应（按内容类型），2~5 = 用户指定。
     *
     * 全局共用一份偏好（设置页可改），各搜索页只负责读 —— 列数取决于屏幕能看清
     * 几张封面，属于设备属性，不该每个页面各记一套。
     */
    private val _gridColumns = MutableStateFlow(0)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    /** 搜索历史 */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var searchJob: Job? = null

    // ===== 关键词搜索翻页（v1.9.2.6）=====
    // 与漫画 / 视频搜索同一套口径：此前 `search()` 固定 `page = 1`，没有「加载更多」，
    // 单源搜索时看到的永远是该源首页那二三十条。
    private var searchPage = 1

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val resultsAcc = com.novelreader.core.util.SearchResultMerge.Accumulator()

    private var repeatPages = 0

    /** 搜索收尾后验证结果章节数的任务（剔除 0 章失效结果） */
    private var verifyJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesManager.fuzzySearchFlow.collect { _fuzzyEnabled.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchHistoryFlow(SourceType.NOVEL).collect { _history.value = it }
        }
        // 书架变化时刷新收藏态（加入/移出后按钮文案实时更新）
        viewModelScope.launch {
            bookRepository.observeBookshelf().collect { books ->
                _shelfKeys.value = books.map { "${it.sourceId}|${it.id}" }.toSet()
            }
        }
        // 长按菜单开关（v1.8.3.0 待办 #14）
        viewModelScope.launch {
            preferencesManager.searchLongPressShelfFlow.collect { _longPressShelfEnabled.value = it }
        }
        // 搜索结果展示（v1.8.6.0 待办 #26）：与设置页共用同一个 key，改哪边都即时生效
        viewModelScope.launch {
            preferencesManager.searchGridNovelFlow.collect { _searchGrid.value = it }
        }
        // 宫格列数（v1.8.7.5）：与标签搜索、其它搜索页共用同一个全局偏好
        viewModelScope.launch {
            preferencesManager.searchGridColumnsFlow.collect { _gridColumns.value = it }
        }
        // 按源搜索：载入已启用的源列表
        viewModelScope.launch {
            _sources.value = runCatching {
                sourceRepository?.getSourcesByType(SourceType.NOVEL)
                    ?.filter { it.enabled }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    /** 加入 / 移出书架（搜索结果卡片直接操作，#22） */
    fun toggleShelf(item: com.novelreader.domain.model.MediaItem) {
        viewModelScope.launch {
            val key = "${item.sourceId}|${item.id}"
            if (key in _shelfKeys.value) bookRepository.removeFromBookshelf(item)
            else bookRepository.addToBookshelf(item)
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setFuzzyEnabled(value: Boolean) {
        viewModelScope.launch { preferencesManager.setFuzzySearch(value) }
    }

    /** 切换搜索结果展示（v1.8.6.0 待办 #26）：立即生效并写回偏好 */
    fun setSearchGrid(value: Boolean) {
        _searchGrid.value = value
        viewModelScope.launch { preferencesManager.setSearchGridNovel(value) }
    }

    fun clearHistory() {
        viewModelScope.launch { preferencesManager.clearSearchHistory(SourceType.NOVEL) }
    }

    /** 长按删除单条搜索历史 */
    fun removeHistory(keyword: String) {
        viewModelScope.launch { preferencesManager.removeSearchHistory(SourceType.NOVEL, keyword) }
    }

    fun search() {
        search(_query.value)
    }

    /** 以指定关键词搜索（作者跳转等场景直接调用，会同步更新输入框） */
    fun search(keywordInput: String) {
        val keyword = keywordInput.trim()
        if (keyword.isEmpty()) return
        _query.value = keyword

        // 记录搜索历史
        viewModelScope.launch { preferencesManager.addSearchHistory(SourceType.NOVEL, keyword) }

        // 取消上一次未完成的搜索
        searchJob?.cancel()
        searchPage = 1
        repeatPages = 0
        _hasMore.value = false
        _loadingMore.value = false

        searchJob = viewModelScope.launch {
            // 读取当前并发线程数与模糊搜索开关
            val concurrency = preferencesManager.searchConcurrencyFlow.first()
            val fuzzy = preferencesManager.fuzzySearchFlow.first()

            _state.value = SearchState(isSearching = true, hasSearched = true, fuzzyEnabled = fuzzy)
            contentRepository.searchWithProgress(
                SourceType.NOVEL, keyword, 1, concurrency, fuzzy,
                sourceIds = _selectedSourceIds.value.takeIf { it.isNotEmpty() },
            )
                .catch { e ->
                    _state.value = _state.value.copy(
                        isSearching = false,
                        error = e.message ?: "搜索失败",
                    )
                }
                .collect { s ->
                    _state.value = s
                    // 搜索收尾：后台验证结果章节数，剔除 0 章（目录为空）的失效结果
                    if (!s.isSearching && s.results.isNotEmpty()) {
                        resultsAcc.reset(s.results)
                        searchPage = 1
                        _hasMore.value = s.error == null
                        verifyResults(s.results)
                    }
                }
        }
    }

    /**
     * 加载下一页（v1.9.2.6）。口径与漫画 / 视频搜索一致：
     * 同一关键词、同一批源，页码 +1；连续 [REPEAT_PAGE_TOLERANCE] 页没新增才判定到底。
     *
     * 注意**不重复跑 [verifyResults]**：那一步是「剔除 0 章死链」的辅助校验，
     * 每页都跑等于把翻页变成两倍请求量；第一页验过就够了。
     */
    fun loadMore() {
        if (_loadingMore.value || !_hasMore.value) return
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) return
        val next = searchPage + 1
        _loadingMore.value = true
        viewModelScope.launch {
            try {
                val concurrency = preferencesManager.searchConcurrencyFlow.first()
                val fuzzy = preferencesManager.fuzzySearchFlow.first()
                var pageResults: List<com.novelreader.domain.model.MediaItem> = emptyList()
                contentRepository.searchWithProgress(
                    SourceType.NOVEL, keyword, next, concurrency, fuzzy,
                    sourceIds = _selectedSourceIds.value.takeIf { it.isNotEmpty() },
                ).catch { /* 单页失败就当这页没有 */ }
                    .collect { s -> if (!s.isSearching) pageResults = s.results }
                if (pageResults.isEmpty()) {
                    _hasMore.value = false
                    return@launch
                }
                val idsBefore = resultsAcc.uniqueCount
                val merged = resultsAcc.addAll(pageResults)
                _state.value = _state.value.copy(results = merged)
                val newIds = resultsAcc.uniqueCount - idsBefore
                if (newIds > 0) {
                    repeatPages = 0
                    searchPage = next
                } else {
                    repeatPages++
                    if (repeatPages >= REPEAT_PAGE_TOLERANCE) _hasMore.value = false
                    else searchPage = next
                }
            } finally {
                _loadingMore.value = false
            }
        }
    }

    private companion object {
        /** 连续多少页「整页没有任何新东西」才判定到底（与漫画 / 视频搜索同口径） */
        const val REPEAT_PAGE_TOLERANCE = 5
    }

    /**
     * 搜索完成后，后台验证结果的章节数，把「0 章」（目录为空）的失效结果从列表剔除。
     * 并发 3 路、逐个 item 调 chapterList；验证结果增量回写，不阻塞已展示的列表。
     */
    private fun verifyResults(results: List<com.novelreader.domain.model.MediaItem>) {
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            val semaphore = Semaphore(3)
            coroutineScope {
                results.map { item ->
                    async {
                        semaphore.withPermit {
                            // 单条验证 8s 有界超时：验证只是「剔除 0 章死链」的辅助手段，
                            // 超时（null）视为「未验证」保留条目，只有明确拿到「0 章」才剔除。
                            val chapters = runCatching {
                                kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                                    contentRepository.chapterList(item)
                                }
                            }.getOrNull()
                            if (chapters != null && chapters.isEmpty()) {
                                // 该条目 0 章，从当前结果中剔除
                                val key = "${item.sourceId}|${item.id}"
                                _state.value = _state.value.copy(
                                    results = _state.value.results.filter {
                                        "${it.sourceId}|${it.id}" != key
                                    }
                                )
                            }
                        }
                    }
                }.forEach { it.await() }
            }
        }
    }
}
