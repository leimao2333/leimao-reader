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
                }
        }
    }
}
