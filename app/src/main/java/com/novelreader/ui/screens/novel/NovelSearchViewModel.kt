package com.novelreader.ui.screens.novel

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
 * 小说搜索 ViewModel。
 *
 * 使用带进度的跨源搜索，边搜边更新结果与进度。
 * 并发线程数从偏好设置读取（可调）。
 */
class NovelSearchViewModel(
    private val contentRepository: ContentRepository,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 搜索进度状态（结果 + 进度 + 当前源） */
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** 模糊搜索开关 */
    private val _fuzzyEnabled = MutableStateFlow(PreferencesManager.DEFAULT_FUZZY_SEARCH)
    val fuzzyEnabled: StateFlow<Boolean> = _fuzzyEnabled.asStateFlow()

    /** 搜索历史 */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesManager.fuzzySearchFlow.collect { _fuzzyEnabled.value = it }
        }
        viewModelScope.launch {
            preferencesManager.searchHistoryFlow(SourceType.NOVEL).collect { _history.value = it }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setFuzzyEnabled(value: Boolean) {
        viewModelScope.launch { preferencesManager.setFuzzySearch(value) }
    }

    fun clearHistory() {
        viewModelScope.launch { preferencesManager.clearSearchHistory(SourceType.NOVEL) }
    }

    fun search() {
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) return

        // 记录搜索历史
        viewModelScope.launch { preferencesManager.addSearchHistory(SourceType.NOVEL, keyword) }

        // 取消上一次未完成的搜索
        searchJob?.cancel()

        searchJob = viewModelScope.launch {
            // 读取当前并发线程数与模糊搜索开关
            val concurrency = preferencesManager.searchConcurrencyFlow.first()
            val fuzzy = preferencesManager.fuzzySearchFlow.first()

            _state.value = SearchState(isSearching = true, hasSearched = true, fuzzyEnabled = fuzzy)
            contentRepository.searchWithProgress(SourceType.NOVEL, keyword, 1, concurrency, fuzzy)
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
