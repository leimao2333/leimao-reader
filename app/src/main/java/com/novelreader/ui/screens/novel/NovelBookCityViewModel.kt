package com.novelreader.ui.screens.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.util.HomeFeedPlan
import com.novelreader.domain.model.CategorySource
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 独立小说书城页 ViewModel（TODO 第十五章 C4）。
 *
 * 数据源刻意复用首页「推荐 / 发现」的同一套，不另起炉灶：
 * [ContentRepository.exploreSources] 枚举小说源的发现分类 →
 * [HomeFeedPlan.build] 跨源合并成栏目表 → [ContentRepository.explore] 按源内地址聚合条目。
 * 于是书城页与首页推荐看到的是同一批源、同一套去重与轮转规则。
 */
class NovelBookCityViewModel(
    private val contentRepository: ContentRepository,
    /** 内容类型（D26）：同一套榜单 / 分类框架服务小说与漫画 */
    private val type: SourceType = SourceType.NOVEL,
) : ViewModel() {

    /** 一个榜单：展示名 + 用来从书源分类名里认领该榜的语义词 */
    data class RankBoard(
        val title: String,
        val keywords: List<String>,
    )

    companion object {
        /**
         * 三个榜单是从现有聚合推荐流里「切」出来的，不是新数据源。
         *
         * 书源没有统一的「热门 / 完本 / 新书」分榜字段（各源分类名千差万别），
         * 所以按分类名的语义把已有分类归并成榜 —— 与首页
         * [HomeFeedPlan.isRecommendLike] 判定「推荐语义」是同一口径。
         */
        val RANK_BOARDS: List<RankBoard> = listOf(
            RankBoard("热门榜", listOf("热门", "排行", "榜单", "人气", "精选")),
            RankBoard("完本榜", listOf("完本", "完结")),
            RankBoard("新书榜", listOf("新书", "最新")),
        )
        private const val MAX_SOURCES_PER_BOARD = 8
        private const val PROBE_TIMEOUT_MS = 8_000L
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 聚合到的源数（顶栏提示「聚合 N 个源」，与发现页一致） */
    private val _aliveCount = MutableStateFlow(0)
    val aliveCount: StateFlow<Int> = _aliveCount.asStateFlow()

    private val _selectedBoard = MutableStateFlow(0)
    val selectedBoard: StateFlow<Int> = _selectedBoard.asStateFlow()

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    /** 当前推荐计划；榜单切分就在这张跨源栏目表上做 */
    private var plan: HomeFeedPlan.Plan? = null
    /** 每个榜单已经挑好的 (源, 源内地址) 目标 */
    private var boardTargets: List<List<CategorySource>> = emptyList()
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        refresh()
    }

    /** 重新枚举书源并加载当前榜单（可重复调用） */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val sources = runCatching { contentRepository.exploreSources(type) }
                .getOrDefault(emptyList())
            if (sources.isEmpty()) {
                // 没有书源时清空旧结果，避免切源后还显示上一批书
                plan = null
                boardTargets = emptyList()
                _aliveCount.value = 0
                _items.value = emptyList()
                _error.value = "没有可用的小说书源，请先在「源管理」导入书源"
                _loading.value = false
                return@launch
            }
            val order = sources.map { it.first.id }
            val catsBySource = sources.associate { it.first.id to it.second }
            // maxRows 只影响首页横滑排；书城真正用到的是 urls 这张跨源栏目表
            val built = HomeFeedPlan.build(
                sourceOrder = order,
                catsBySource = catsBySource,
                maxRows = 12,
                maxSourcesPerRow = MAX_SOURCES_PER_BOARD,
            )
            plan = built
            boardTargets = RANK_BOARDS.map { targetsFor(it, built) }
            _aliveCount.value = sources.size
            aggregateBoard(_selectedBoard.value)
            _loading.value = false
        }
    }

    /** 切换榜单；index 越界或与当前相同时忽略 */
    fun selectBoard(index: Int) {
        if (index !in RANK_BOARDS.indices || index == _selectedBoard.value) return
        _selectedBoard.value = index
        if (plan == null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            aggregateBoard(index)
            _loading.value = false
        }
    }

    /**
     * 按语义词挑出该榜要聚合的 (源, 分类地址)。
     * 没有任何分类命中时退回首页「推荐」首排，保证榜单不是空壳。
     */
    private fun targetsFor(board: RankBoard, built: HomeFeedPlan.Plan): List<CategorySource> {
        val seen = HashSet<String>()
        val picked = ArrayList<CategorySource>()
        for ((name, entries) in built.urls) {
            if (board.keywords.none { name.contains(it, ignoreCase = true) }) continue
            for (entry in entries) {
                if (seen.add("${entry.sourceId}|${entry.url}")) picked.add(entry)
            }
        }
        if (picked.isEmpty()) {
            picked.addAll(built.rows.firstOrNull()?.targets.orEmpty())
        }
        return picked.take(MAX_SOURCES_PER_BOARD)
    }

    /** 跨源聚合当前榜单条目：与发现页同款去重 + 打榜名标签 */
    private suspend fun aggregateBoard(index: Int) {
        val targets = boardTargets.getOrNull(index).orEmpty()
        if (targets.isEmpty()) {
            _items.value = emptyList()
            _error.value = "暂无可用的榜单数据"
            return
        }
        val boardTitle = RANK_BOARDS[index].title
        _error.value = null
        val merged = coroutineScope {
            targets.map { t -> async {
                runCatching {
                    withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        contentRepository.explore(t.sourceId, t.url, 1)
                    }
                }.getOrNull().orEmpty()
            } }.awaitAll().flatten()
        }
        val seen = LinkedHashSet<String>()
        val dedup = merged.filter { seen.add("${it.sourceId}:${it.id}") }
            // 与发现页一致：把浏览上下文写进 tags，详情页标签小栏据此展示
            .map { it.copy(tags = it.tags + boardTitle) }
        // 期间用户可能又切了榜单，旧结果不要盖掉新榜单
        if (_selectedBoard.value != index) return
        _items.value = dedup
        _error.value = if (dedup.isEmpty()) "该榜单暂无内容" else null
    }
}
