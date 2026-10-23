package com.novelreader.ui.screens.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 视频搜索 ViewModel。
 */
class VideoSearchViewModel(
    private val contentRepository: ContentRepository,
    private val preferencesManager: PreferencesManager,
    private val bookRepository: com.novelreader.domain.repository.BookRepository,
    private val sourceRepository: com.novelreader.domain.repository.SourceRepository? = null,
) : ViewModel() {

    /**
     * 「按源搜索」（v1.8.1.1）：可选择只搜某一个源，其余源一个请求都不发，结果又快又准。
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

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** 已收藏的条目 key 集合（"sourceId|itemId"），供搜索结果收藏态高亮（v1.8.3.0 待办 #15） */
    private val _shelfKeys = MutableStateFlow<Set<String>>(emptySet())
    val shelfKeys: StateFlow<Set<String>> = _shelfKeys.asStateFlow()

    /** 长按搜索结果弹操作菜单的开关（v1.8.3.0 待办 #14，设置里可关） */
    private val _longPressShelfEnabled = MutableStateFlow(true)
    val longPressShelfEnabled: StateFlow<Boolean> = _longPressShelfEnabled.asStateFlow()

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

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var searchJob: Job? = null

    // ===== 关键词搜索翻页（v1.8.8.0，待办 #37）=====
    // 以前 `search()` 固定 `page = 1`，全链路没有「加载更多」，每个源只贡献第 1 页
    // 那 20~40 条 —— 视频有几十个源，看着不少，但同一关键词再往下就永远问不到了，
    // 这就是「1.8.1.0 还能搜很多、现在搜得少」的其中一半原因。
    private var searchPage = 1

    /** 是否还有下一页（触底时继续问） */
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /** 翻页去重累加器：翻页只处理新到的一页，不重算整个列表（同标签搜索 v1.8.4.1 的做法） */
    private val resultsAcc = com.novelreader.core.util.SearchResultMerge.Accumulator()

    init {
        viewModelScope.launch {
            preferencesManager.fuzzySearchFlow.collect { _fuzzyEnabled.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchHistoryFlow(SourceType.VIDEO).collect { _history.value = it }
        }
        // 按源搜索：载入已启用的源列表
        viewModelScope.launch {
            _sources.value = runCatching {
                sourceRepository?.getSourcesByType(SourceType.VIDEO)
                    ?.filter { it.enabled }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
        // 收藏变化时刷新收藏态（v1.8.3.0 待办 #15）
        viewModelScope.launch {
            bookRepository.observeBookshelf().collect { books ->
                _shelfKeys.value = books.map { "${it.sourceId}|${it.id}" }.toSet()
            }
        }
        viewModelScope.launch {
            preferencesManager.searchLongPressShelfFlow.collect { _longPressShelfEnabled.value = it }
        }
        // 搜索结果展示（v1.8.6.0 待办 #26）
        viewModelScope.launch {
            preferencesManager.searchGridVideoFlow.collect { _searchGrid.value = it }
        }
        // 宫格列数（v1.8.7.5）：与标签搜索、其它搜索页共用同一个全局偏好
        viewModelScope.launch {
            preferencesManager.searchGridColumnsFlow.collect { _gridColumns.value = it }
        }
    }

    /** 加入 / 移出收藏（搜索结果卡片直接操作，v1.8.3.0 待办 #15） */
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

    /** 切换搜索结果展示（v1.8.6.0 待办 #26） */
    fun setSearchGrid(value: Boolean) {
        _searchGrid.value = value
        viewModelScope.launch { preferencesManager.setSearchGridVideo(value) }
    }

    fun clearHistory() {
        viewModelScope.launch { preferencesManager.clearSearchHistory(SourceType.VIDEO) }
    }

    /** 长按删除单条搜索历史 */
    fun removeHistory(keyword: String) {
        viewModelScope.launch { preferencesManager.removeSearchHistory(SourceType.VIDEO, keyword) }
    }

    fun search() {
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) return

        viewModelScope.launch { preferencesManager.addSearchHistory(SourceType.VIDEO, keyword) }

        searchJob?.cancel()
        searchPage = 1
        _hasMore.value = false
        _loadingMore.value = false

        searchJob = viewModelScope.launch {
            val concurrency = preferencesManager.searchConcurrencyFlow.first()
            val fuzzy = preferencesManager.fuzzySearchFlow.first()
            _state.value = SearchState(isSearching = true, hasSearched = true, fuzzyEnabled = fuzzy)
            contentRepository.searchWithProgress(
                SourceType.VIDEO, keyword, 1, concurrency, fuzzy,
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
                    // 第一页收完：接管累加器，并开放翻页（v1.8.8.0）
                    if (!s.isSearching) {
                        resultsAcc.reset(s.results)
                        searchPage = 1
                        _hasMore.value = s.results.isNotEmpty() && s.error == null
                    }
                }
        }
    }

    /**
     * 加载下一页（v1.8.8.0，待办 #37）：同一关键词、同一批源，只把页码 +1。
     *
     * 每个源各自的第 N 页合并进来；整页都没有新作品时判定到底。
     * 与标签搜索一致：**一页没新增不等于到底**（同一批作品在多个源上重复），
     * 连续 [REPEAT_PAGE_TOLERANCE] 页没新增才停。
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
                    SourceType.VIDEO, keyword, next, concurrency, fuzzy,
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
                // 判据用唯一条目数而不是行数（v1.8.8.0）：同名作品被折叠时行数不涨，
                // 但多出一个播放源同样是新内容，不该算「这一页白问」。
                val newIds = resultsAcc.uniqueCount - idsBefore
                if (newIds > 0) {
                    repeatPages = 0
                    searchPage = next
                } else {
                    repeatPages++
                    if (repeatPages >= REPEAT_PAGE_TOLERANCE) {
                        _hasMore.value = false
                    } else {
                        // 这一页全是已见过的条目，但还不能判定到底：页码照样往前推
                        searchPage = next
                    }
                }
            } finally {
                _loadingMore.value = false
            }
        }
    }

    /** 连续「整页没新增」的页数（v1.8.8.0） */
    private var repeatPages = 0

    companion object {
        /**
         * 连续多少页「整页没有任何新东西」才判定到底（v1.8.8.0，待办 #37）。
         *
         * 与标签搜索同一口径：视频源上架的是同一批作品，第 2 页按标题去重后
         * 常常一条不剩，一页就停会让用户以为「只有一页」。
         */
        private const val REPEAT_PAGE_TOLERANCE = 5
    }
}
