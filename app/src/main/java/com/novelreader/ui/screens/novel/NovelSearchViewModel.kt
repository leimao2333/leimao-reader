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
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 搜索进度状态（结果 + 进度 + 当前源） */
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** 已在书架中的条目 key 集合（"sourceId|itemId"），供搜索卡片显示收藏态（#22） */
    private val _shelfKeys = MutableStateFlow<Set<String>>(emptySet())
    val shelfKeys: StateFlow<Set<String>> = _shelfKeys.asStateFlow()

    /** 模糊搜索开关 */
    private val _fuzzyEnabled = MutableStateFlow(PreferencesManager.DEFAULT_FUZZY_SEARCH)
    val fuzzyEnabled: StateFlow<Boolean> = _fuzzyEnabled.asStateFlow()

    /** 搜索历史 */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var searchJob: Job? = null

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
                    // 搜索收尾：后台验证结果章节数，剔除 0 章（目录为空）的失效结果
                    if (!s.isSearching && s.results.isNotEmpty()) {
                        verifyResults(s.results)
                    }
                }
        }
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
