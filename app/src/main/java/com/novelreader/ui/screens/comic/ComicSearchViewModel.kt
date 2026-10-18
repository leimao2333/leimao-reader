package com.novelreader.ui.screens.comic

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
 * 漫画搜索 ViewModel。
 */
class ComicSearchViewModel(
    private val contentRepository: ContentRepository,
    private val preferencesManager: PreferencesManager,
    private val sourceRepository: com.novelreader.domain.repository.SourceRepository? = null,
) : ViewModel() {

    /**
     * 「按源搜索」（v1.8.1.1）：可选择只搜某一个源，其余源一个请求都不发，结果又快又准。
     */
    private val _sources = MutableStateFlow<List<com.novelreader.domain.model.SourceInfo>>(emptyList())
    val sources: StateFlow<List<com.novelreader.domain.model.SourceInfo>> = _sources.asStateFlow()

    private val _selectedSourceId = MutableStateFlow<String?>(null)
    val selectedSourceId: StateFlow<String?> = _selectedSourceId.asStateFlow()

    /** 选择源（null = 全部源）。已有搜索词时立即按新范围重搜。 */
    fun selectSource(id: String?) {
        _selectedSourceId.value = id
        if (_query.value.isNotBlank()) search()
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _fuzzyEnabled = MutableStateFlow(PreferencesManager.DEFAULT_FUZZY_SEARCH)
    val fuzzyEnabled: StateFlow<Boolean> = _fuzzyEnabled.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesManager.fuzzySearchFlow.collect { _fuzzyEnabled.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchHistoryFlow(SourceType.COMIC).collect { _history.value = it }
        }
        // 按源搜索：载入已启用的源列表
        viewModelScope.launch {
            _sources.value = runCatching {
                sourceRepository?.getSourcesByType(SourceType.COMIC)
                    ?.filter { it.enabled }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setFuzzyEnabled(value: Boolean) {
        viewModelScope.launch { preferencesManager.setFuzzySearch(value) }
    }

    fun clearHistory() {
        viewModelScope.launch { preferencesManager.clearSearchHistory(SourceType.COMIC) }
    }

    /** 长按删除单条搜索历史 */
    fun removeHistory(keyword: String) {
        viewModelScope.launch { preferencesManager.removeSearchHistory(SourceType.COMIC, keyword) }
    }

    fun search() {
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) return

        viewModelScope.launch { preferencesManager.addSearchHistory(SourceType.COMIC, keyword) }

        searchJob?.cancel()

        searchJob = viewModelScope.launch {
            val concurrency = preferencesManager.searchConcurrencyFlow.first()
            val fuzzy = preferencesManager.fuzzySearchFlow.first()
            _state.value = SearchState(isSearching = true, hasSearched = true, fuzzyEnabled = fuzzy)
            contentRepository.searchWithProgress(
                SourceType.COMIC, keyword, 1, concurrency, fuzzy,
                sourceIds = _selectedSourceId.value?.let { setOf(it) },
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
