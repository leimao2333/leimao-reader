package com.novelreader.ui.screens.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.util.TagQuery
import com.novelreader.data.cache.TagPlan
import com.novelreader.data.repository.TagRepository
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.source.category.isAdult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 标签搜索 ViewModel（v1.5.10.0 起；v1.6.1.0 重做 UI 后保留全量 + 新增热门标签）。
 *
 * - 标签来自 [TagRepository] 自动收集的各源发现分类（落盘缓存，冷启动即有）；
 * - **热门标签**：按标签覆盖源数倒序，取 top [HOT_TAG_LIMIT] 展示在搜索页顶部；
 * - **单标签搜索**：聚合所有拥有该分类的源的第 1 页内容，跨源去重；
 * - **复合搜索**：查询串里多个标签（空格分隔）分别聚合后**按标题求交集**
 *   （见 [TagQuery.intersect]），实现「玄幻 完结」式组合筛选；
 * - 视频结果把 18+ 成人源内容沉底（与首页/搜索一致）。
 */
class TagSearchViewModel(
    private val contentRepository: ContentRepository,
    private val tagRepository: TagRepository,
    private val sourceRepository: SourceRepository?,
    val type: SourceType,
    initialTag: String,
) : ViewModel() {

    /** 全部可用标签（自动收集） */
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags.asStateFlow()

    /**
     * 热门推荐标签（v1.6.1.0）：按覆盖源数倒序，取 top [HOT_TAG_LIMIT]。
     *
     * 在搜索页顶部作为「一键选择」入口，用户点选即触发复合搜索（多次复用 + 自动追加）。
     * 「覆盖源数」= 该标签下有多少 (sourceId, url) 对，越多说明越多源支持该分类。
     */
    private val _hotTags = MutableStateFlow<List<String>>(emptyList())
    val hotTags: StateFlow<List<String>> = _hotTags.asStateFlow()

    /** 标签池是否正在收集（首次使用要逐源探测，视频源较慢） */
    private val _collecting = MutableStateFlow(false)
    val collecting: StateFlow<Boolean> = _collecting.asStateFlow()

    /** 查询串（标签之间用空格分开） */
    private val _query = MutableStateFlow(initialTag.trim())
    val query: StateFlow<String> = _query.asStateFlow()

    /** 搜索结果 */
    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    /** 是否正在搜索 */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** 搜索进度（完成的标签数 / 总标签数） */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    /** 提示（标签未找到 / 搜索失败等，非致命） */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 最近一次成功搜索的标签（用于结果区标题） */
    private val _activeTags = MutableStateFlow<List<String>>(emptyList())
    val activeTags: StateFlow<List<String>> = _activeTags.asStateFlow()

    private var tagPlan: TagPlan = TagPlan.EMPTY
    private var searchJob: kotlinx.coroutines.Job? = null

    init {
        collectTags(initialTag.isNotBlank())
    }

    /** 收集标签池：先读缓存（毫秒级），空则现场收集 */
    private fun collectTags(thenSearch: Boolean) {
        viewModelScope.launch {
            _collecting.value = true
            tagPlan = runCatching { tagRepository.tags(type) }.getOrDefault(TagPlan.EMPTY)
            _allTags.value = tagPlan.tags
            _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
            _collecting.value = false
            if (thenSearch && _results.value.isEmpty()) search()
        }
    }

    /** 强制重新收集标签（源变了 / 分类没刷出来时用） */
    fun refreshTags() {
        viewModelScope.launch {
            _collecting.value = true
            tagPlan = runCatching { tagRepository.refresh(type) }.getOrDefault(TagPlan.EMPTY)
            _allTags.value = tagPlan.tags
            _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
            _collecting.value = false
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /**
     * 把标签追加到查询串（空格分隔）并立即搜索 —— 复合搜索入口（v1.5.10.0）。
     *
     * v1.6.1.0 改进：同步把 [activeTags] 也更新，让热们推荐 chip 立刻呈现「已选」状态，
     * 不用等搜索请求回来才反映。
     */
    fun addTag(tag: String) {
        if (tag.isBlank()) return
        val current = TagQuery.parse(_query.value)
        if (tag in current) return
        val next = current + tag
        _query.value = next.joinToString(" ")
        _activeTags.value = next
        search()
    }

    /** 从查询查询返回某标签（点已选 chip）。最后一个标签移除时同步清空结果 / 进度 */
    fun removeTag(tag: String) {
        val current = TagQuery.parse(_query.value) - tag
        _query.value = current.joinToString(" ")
        _activeTags.value = current
        if (current.isEmpty()) {
            searchJob?.cancel()
            _results.value = emptyList()
            _searching.value = false
            _notice.value = null
            _progress.value = null
        } else {
            search()
        }
    }

    /** 执行标签搜索（单标签 / 复合标签通用） */
    fun search() {
        val tags = TagQuery.parse(_query.value)
        if (tags.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _notice.value = null
            _progress.value = 0 to tags.size

            // 标签计划还没收集好就等一下（首次冷启动直进标签搜索的场景）
            if (tagPlan.plan.isEmpty()) {
                tagPlan = runCatching { tagRepository.tags(type) }.getOrDefault(TagPlan.EMPTY)
                _allTags.value = tagPlan.tags
                _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
            }

            val entriesPerTag = tags.map { tagPlan.entriesFor(it) }
            val missing = tags.filterIndexed { i, t -> entriesPerTag[i].isEmpty() }

            val fetched: List<List<MediaItem>> = coroutineScope {
                entriesPerTag.mapIndexed { _, entries ->
                    async {
                        if (entries.isEmpty()) {
                            emptyList()
                        } else {
                            val semaphore = Semaphore(8)
                            entries.map { (srcId, url) ->
                                async {
                                    semaphore.withPermit {
                                        runCatching { contentRepository.explore(srcId, url, 1) }
                                            .getOrDefault(emptyList())
                                    }
                                }
                            }.awaitAll().flatten()
                                .distinctBy { "${it.sourceId}:${it.id}" }
                        }
                    }
                }.awaitAll()
            }

            var final = TagQuery.intersect(fetched)
            if (type == SourceType.VIDEO) final = sinkAdults(final)

            _activeTags.value = tags
            _results.value = final
            _progress.value = null
            _searching.value = false
            _notice.value = when {
                missing.size == tags.size ->
                    "没有找到标签：${missing.joinToString("、")}（点右上角刷新标签试试）"
                missing.isNotEmpty() ->
                    "未找到标签：${missing.joinToString("、")}，已按其余标签搜索"
                final.isEmpty() -> "该标签组合下暂无内容"
                else -> null
            }
        }
    }

    /** 视频：18+ 成人源内容沉底（与首页 / 搜索一致） */
    private suspend fun sinkAdults(items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) return items
        val adultIds = runCatching {
            sourceRepository?.getSourcesByType(SourceType.VIDEO)
                .orEmpty()
                .filter { it.isAdult }
                .map { it.id }
                .toSet()
        }.getOrDefault(emptySet())
        if (adultIds.isEmpty()) return items
        return items.sortedBy { it.sourceId in adultIds }
    }

    companion object {
        /** 热门推荐标签上限（v1.6.1.0） */
        const val HOT_TAG_LIMIT = 12
    }
}
