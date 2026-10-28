package com.novelreader.ui.screens.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.network.RequestBudget
import com.novelreader.core.search.EntryKind
import com.novelreader.core.search.RecallBatch
import com.novelreader.core.search.RecallOutcome
import com.novelreader.core.search.RecallResult
import com.novelreader.core.search.RecallTask
import com.novelreader.core.search.SlowSourceCache
import com.novelreader.core.search.SourceHealth
import com.novelreader.core.search.TagRecallScheduler
import com.novelreader.core.util.SearchResultMerge
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagResultPolicy
import com.novelreader.core.util.TagSuggestions
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.core.util.TagProvenance
import com.novelreader.core.util.TagQuery
import com.novelreader.core.util.TagQuerySpec
import com.novelreader.core.util.TagTagger
import com.novelreader.core.util.TagVerdicts
import com.novelreader.data.cache.TagPlan
import com.novelreader.data.repository.TagRepository
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.source.category.isAdult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 标签搜索 ViewModel（v1.5.10.0 起；v1.6.1.0 重做 UI 后保留全量 + 新增热门标签；
 * v1.7.0.0 重修复合搜索；v1.7.9.9 改为并集相交；v1.8.9.1 按「召回 / 判定分离」重构）。
 *
 * v1.8.9.1 的四条铁律（改这条链路之前请先读完）：
 * 1. **超时 = 未知，不是没结果** —— 召回层用 [RecallOutcome] 区分，别再用 `emptyList()` 表示一切；
 * 2. **慢 ≠ 不匹配** —— 慢源只影响排序，不影响取舍（[SourceHealth] 只调顺序）；
 * 3. **首包与全量解耦** —— 每批回来就推界面，软截止先推空快照，别让用户对着转圈等最慢的源；
 * 4. **「全部出现」由任务终态判定** —— `pending` 归零才算完，不看结果是否稳定。
 *
 * - 标签来自 [TagRepository] 自动收集的各源发现分类（落盘缓存，冷启动即有）；
 * - **热门标签**：按标签覆盖源数倒序，取 top [HOT_TAG_LIMIT] 展示在搜索页顶部；
 * - **单标签搜索**：聚合所有拥有该分类的源的第 1 页内容，跨源去重；
 * - **复合搜索**：每个标签先取「它自己全部译名 / 转义写法 / 全部源」的并集，
 *   再把各标签的并集按归一化标题求交（「无码 全彩」= 全量无码 ∪ ∩ 全量全彩 ∪）。
 *   跨源同名作品视为同一部，不再要求两个标签落在同一个源上；
 * - 视频结果把 18+ 成人源内容沉底（与首页/搜索一致）。
 */
enum class SearchPhase {
    IDLE,
    /** 还在收，结果会继续增加 */
    STREAMING,
    /** 全部任务正常终态（success / empty），结果完整 */
    DONE_COMPLETE,
    /** 有源超时或报错，结果可能不全 */
    DONE_PARTIAL,
}
class TagSearchViewModel(
    private val contentRepository: ContentRepository,
    private val tagRepository: TagRepository,
    private val sourceRepository: SourceRepository?,
    val type: SourceType,
    initialTag: String,
    private val preferencesManager: com.novelreader.core.preferences.PreferencesManager? = null,
    /** 本地标签索引（v1.8.1.2）：建好后标签搜索直接查它，不再受源站有无分类页限制 */
    private val tagIndexRepository: com.novelreader.data.repository.TagIndexRepository? = null,
) : ViewModel() {

    /** 索引构建状态（v1.8.1.2） */
    private val _indexing = MutableStateFlow(false)
    val indexing: StateFlow<Boolean> = _indexing.asStateFlow()

    /** 是否已暂停（v1.8.1.9，遍历可暂停 / 继续） */
    private val _indexPaused = MutableStateFlow(false)
    val indexPaused: StateFlow<Boolean> = _indexPaused.asStateFlow()

    private val _indexMessage = MutableStateFlow<String?>(null)
    val indexMessage: StateFlow<String?> = _indexMessage.asStateFlow()

    /** UI 侧（如导出失败）直接把消息写进索引弹窗的状态条 */
    fun notifyIndexMessage(message: String) {
        _indexMessage.value = message
    }

    private val _indexCount = MutableStateFlow(0)
    val indexCount: StateFlow<Int> = _indexCount.asStateFlow()

    /**
     * 每个标签各收录了多少部（待办 #47）。
     *
     * 标签页在 chip 上显示「已收录 N 部」。一次 GROUP BY 拿全表，UI 按 tag 查表 ——
     * 不要做成「每个 chip 查一次库」。索引状态变化与每次搜索后刷新。
     */
    private val _tagCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val tagCounts: StateFlow<Map<String, Int>> = _tagCounts.asStateFlow()

    /**
     * 本轮结果里「本地索引已收录」的条目键（`sourceId|itemId`，待办 #47）。
     *
     * 直接由本地查询结果 `localItems` 推出来，不再查库 —— 网络结果里也可能有已收录的，
     * 但那种「标签没完全匹配、却被索引收录」的情况很少，不值得为此多打一次 IN 查询
     * （SQLite 的 IN 参数还有 999 上限，大结果集得分批）。
     */
    private val _indexedKeys = MutableStateFlow<Set<String>>(emptySet())
    val indexedKeys: StateFlow<Set<String>> = _indexedKeys.asStateFlow()

    /** 收录总数 + 各标签计数一起刷新（两者同源，分开查会读到不一致的中间态） */
    private suspend fun refreshIndexStats(repo: com.novelreader.data.repository.TagIndexRepository) {
        _indexCount.value = runCatching { repo.count(type) }.getOrDefault(0)
        _tagCounts.value = runCatching { repo.tagCounts(type) }.getOrDefault(emptyMap())
    }

    /**
     * 视觉模型总开关（v1.8.7.2，默认关）：关着时视觉打标按钮禁用。
     *
     * 必须声明在 `init` 之前 —— Kotlin 的属性初始化与 init 块按书写顺序执行，
     * 写在 init 后面的话，init 里那个 collect 闭包读到的还是 null。
     */
    private val _visualTaggingEnabled = MutableStateFlow(false)
    val visualTaggingEnabled: StateFlow<Boolean> = _visualTaggingEnabled.asStateFlow()

    /**
     * 宫格列数（v1.8.7.5）：0 = 自适应（视频 3 列、小说 / 漫画 2 列），2~5 = 固定。
     *
     * 这个值是**全局共用**的（与普通搜索结果同一个偏好）—— 列数取决于屏幕能看清
     * 几张封面，属于设备属性，按页面各记一套只会让用户觉得设置没生效。
     */
    private val _gridColumns = MutableStateFlow(0)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    init {
        // 索引任务跑在 repository 层（离开页面也继续，v1.8.1.9），
        // 这里只负责把任务状态映射到 UI：重建 ViewModel 后回来还能看到进度。
        tagIndexRepository?.let { repo ->
            viewModelScope.launch {
                repo.indexState.collect { state ->
                    _indexing.value = state.running
                    _indexPaused.value = state.paused
                    _indexMessage.value = state.message.takeIf { it.isNotBlank() }
                    if (!state.running) refreshIndexStats(repo)
                }
            }
        }
        // 结果布局偏好（v1.8.7.1）：读不到（从未设置）时保留构造时的类型兜底值
        preferencesManager?.let { prefs ->
            viewModelScope.launch {
                prefs.searchGridTagFlow.collect { stored ->
                    if (stored != null) _searchGrid.value = stored
                }
            }
            // 视觉模型总开关（v1.8.7.2，默认关）：关着时按钮禁用、也不会碰模型
            viewModelScope.launch {
                prefs.visualTaggingFlow.collect { _visualTaggingEnabled.value = it }
            }
            // 宫格列数（v1.8.7.5）：与普通搜索结果共用一个偏好，改一处两处同时生效
            viewModelScope.launch {
                prefs.searchGridColumnsFlow.collect { _gridColumns.value = it }
            }
            // 标签搜索并发（v1.8.8.4）：设置页滑块的值，下一次搜索按它建 Semaphore
            viewModelScope.launch {
                prefs.tagSearchConcurrencyFlow.collect { tagConcurrency = it }
            }
        }
    }

    /**
     * 构建本地标签索引（v1.8.1.2）。
     *
     * 遍历已启用的源：抓列表 → 逐部拉详情 → 读出源站声明的题材 → 入库。
     * 建好后 [search] 会优先查这份索引，复合筛选因此是真的按标签求交。
     */
    fun buildIndex() {
        val repo = tagIndexRepository ?: run {
            _indexMessage.value = "当前环境不支持本地索引"
            return
        }
        // v1.8.1.9：任务交给仓库级协程 —— 关掉弹窗去阅读、标签页被销毁重建，
        // 遍历都不会中断（「边写边看书」）；进度通过 repo.indexState 回流。
        repo.startBuild(type, _selectedSourceIds.value)
    }

    /** 暂停全量索引（v1.8.1.9）：停在源边界，已落库的标签不丢 */
    fun pauseIndexing() {
        tagIndexRepository?.pauseIndexing()
    }

    /** 从暂停处继续 */
    fun resumeIndexing() {
        tagIndexRepository?.resumeIndexing()
    }

    /** 彻底停止遍历 */
    fun stopIndexing() {
        tagIndexRepository?.stopIndexing()
    }

    /**
     * 封面视觉打标（v1.8.1.4）：对已收录作品的封面跑 WD14 模型，
     * 输出的 Danbooru 标签映射进受控词表后以 `origin = 视觉` 入库。
     *
     * 模型约 356MB，仅在 Wi-Fi 下按需下载（[TagIndexRepository.runVisualTagging] 负责）。
     * v1.8.7.2 起受设置页「启用视觉模型打标」总开关约束（默认关）：
     * 关着的时候这里直接拒绝，模型文件连检查都不检查。
     */
    fun runVisualTagging() {
        if (!_visualTaggingEnabled.value) {
            _indexMessage.value = "视觉打标已在「设置 → 标签」里关闭 —— 打开后可用"
            return
        }
        val repo = tagIndexRepository ?: run {
            _indexMessage.value = "当前环境不支持视觉打标"
            return
        }
        if (_indexing.value) return
        viewModelScope.launch {
            _indexing.value = true
            _indexMessage.value = "准备视觉模型…"
            try {
                val count = repo.runVisualTagging(type) { message -> _indexMessage.value = message }
                refreshIndexStats(repo)
                _indexMessage.value = if (count > 0) {
                    "视觉打标完成：$count 部封面已入库"
                } else {
                    "没有待打标的封面（先搜索一次收录内容）"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TagSearch", "视觉打标失败", error)
                _indexMessage.value = "视觉打标失败：${error.message ?: "未知错误"}"
            } finally {
                _indexing.value = false
            }
        }
    }

    /** 导出云端打标表 JSON（收录清单 + 当前标签），供 PC 端工具补充打标；失败返回 null */
    suspend fun exportTagTableJson(): String? = tagIndexRepository?.let { repo ->
        runCatching { com.novelreader.core.util.TagTable.toJson(repo.buildTagTable(type)) }
            .onFailure { android.util.Log.w("TagSearch", "导出收录清单失败", it) }
            .getOrNull()
    }

    /**
     * 流式导出到 SAF（v1.8.3.0 待办 #20）。
     *
     * 与设置页的「标签导出」共用 [com.novelreader.data.repository.TagIndexRepository.exportTagTable]：
     * 分页查库、边查边写，不再先在内存里拼一个十几 MB 的 JSON 字符串。
     */
    suspend fun exportTagTableToUri(
        context: android.content.Context,
        uri: android.net.Uri,
        onProgress: (Float) -> Unit = {},
    ): Boolean {
        val repo = tagIndexRepository ?: return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                    repo.exportTagTable(listOf(type), w) { done, total ->
                        onProgress(if (total > 0) done.toFloat() / total else 0f)
                    }
                } ?: error("无法写入所选位置")
                true
            }.getOrElse {
                android.util.Log.w("TagSearch", "导出收录清单失败", it)
                false
            }
        }
    }

    /**
     * 从云端拉取打标表并合并（v1.8.1.5）：PC 端工具推到 GitHub 的
     * 独立打标仓库（leimao2333/leimao-tagger）的 `data/tag_table.json`，App 侧「同名标签取更高置信度」幂等合并。
     */
    fun importTagTableFromUrl(url: String) {
        val repo = tagIndexRepository ?: run {
            _indexMessage.value = "当前环境不支持本地索引"
            return
        }
        val target = url.trim()
        if (target.isEmpty() || _indexing.value) return
        viewModelScope.launch {
            _indexing.value = true
            _indexMessage.value = "下载云端打标表…"
            try {
                val payload = repo.downloadCloudTable(target)
                _indexMessage.value = "合并中（云端 ${payload.items.size} 条）…"
                val count = repo.importTagTable(payload, type)
                refreshIndexStats(repo)
                _indexMessage.value = "导入完成：$count 部作品已合并进本地标签层"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TagSearch", "云端打标表导入失败", error)
                _indexMessage.value = "导入失败：${error.message ?: "未知错误"}"
            } finally {
                _indexing.value = false
            }
        }
    }

    /**
     * 「按源搜索」：已启用的源列表 + 当前选中的源（null = 全部源）（v1.8.1.2）。
     *
     * 标签池是跨源收集的；只搜某一个源能避免几十个源一起等的长尾，也便于核对某个源的内容。
     */
    private val _sources = MutableStateFlow<List<com.novelreader.domain.model.SourceInfo>>(emptyList())
    val sources: StateFlow<List<com.novelreader.domain.model.SourceInfo>> = _sources.asStateFlow()

    private val _selectedSourceIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedSourceIds: StateFlow<Set<String>> = _selectedSourceIds.asStateFlow()

    /** 选择源（**多选**；空集合 = 全部源，v1.8.1.2）。已有查询时立即按新范围重搜。 */
    fun setSelectedSources(ids: Set<String>) {
        _selectedSourceIds.value = ids
        if (_query.value.isNotBlank()) search()
    }

    /** 全部可用标签（自动收集） */
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags.asStateFlow()

    /**
     * 全部标签（按覆盖源数倒序）（v1.6.5.0）。
     *
     * 「更多」面板**直接展示所有标签**（不再只给热门 top N）——标签池动辄上百个，
     * 只放热门等于大部分标签用户永远点不到。排序仍按覆盖源数，覆盖面广的在前。
     */
    private val _tagsByCoverage = MutableStateFlow<List<String>>(emptyList())
    val tagsByCoverage: StateFlow<List<String>> = _tagsByCoverage.asStateFlow()

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

    /**
     * 结果展示方式（v1.8.7.1）：true = 宫格，false = 列表。
     *
     * 标签搜索此前是按类型写死的（视频三列海报墙、小说 / 漫画列表），
     * 想「看封面挑」或「看作者 / 字数挑」只能换页面。现在与其它搜索页统一：
     * 顶栏一个图标随时切，偏好按**标签搜索**单独记（不与普通搜索共用，
     * 因为同一个用户在「关键词搜索」和「标签筛选」时想要的形态经常不同）。
     *
     * 缺省值按类型兜底（视频宫格、其余列表），保证没动过开关的人观感不变。
     */
    private val _searchGrid = MutableStateFlow(type == SourceType.VIDEO)
    val searchGrid: StateFlow<Boolean> = _searchGrid.asStateFlow()

    /** 切换结果布局（v1.8.7.1）：立即生效并写回偏好 */
    fun setSearchGrid(value: Boolean) {
        _searchGrid.value = value
        preferencesManager?.let { prefs ->
            viewModelScope.launch { prefs.setSearchGridTag(value) }
        }
    }

    /** 设置宫格列数（0 = 自适应）：立即生效并写回偏好 */
    fun setGridColumns(value: Int) {
        val clamped = value.coerceIn(0, 5)
        _gridColumns.value = clamped
        preferencesManager?.let { prefs ->
            viewModelScope.launch { prefs.setSearchGridColumns(clamped) }
        }
    }

    /**
     * 输入提示词（v1.8.6.0 待办 #27）。
     *
     * 输入框只是一个纯字符串，标签池上百个时用户只能凭记忆盲打，打错一个字就搜不到。
     * 这里基于**本地标签池**（[tagsByCoverage] / [allTags]，不引任何网络请求）给出候选：
     * 前缀优先、同分按覆盖源数排（[tagsByCoverage] 本身就是覆盖源数倒序）。
     * 多标签空格组合时只提示**正在输入的那一个词**，已写进查询的标签不再重复出现。
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /** 搜索结果 */
    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    /** 是否正在搜索 */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** 搜索进度（完成的标签数 / 总标签数） */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    /**
     * 搜索进度计数器（v1.7.1.0）。
     *
     * **必须声明在 [init] 之前**：init 里的 [collectTags] 会同步调用 [search]，
     * 而 search 的第一批语句就有 [resetProgress]。写在 init 之后的话，Kotlin 的
     * 字段初始化顺序会让这里还是 null，直接 NPE 闪退（v1.8.7.5 漏到设备上的真实崩溃）。
     */
    private val progressDone = java.util.concurrent.atomic.AtomicInteger(0)
    private val progressTotal = java.util.concurrent.atomic.AtomicInteger(0)

    /** 提示（标签未找到 / 搜索失败等，非致命） */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 最近一次成功搜索的标签（用于结果区标题） */
    private val _activeTags = MutableStateFlow(
        initialTag.trim().takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty(),
    )
    val activeTags: StateFlow<List<String>> = _activeTags.asStateFlow()

    private var tagPlan: TagPlan = TagPlan.EMPTY
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchGeneration = 0L

    // ===== 分页（v1.8.1.2）=====
    /**
     * 一个搜索入口（v1.8.9.1 起带上写法与种类）。
     *
     * 以前入口只有 `(sourceId, url)` 两元组，调度层没法知道「这条入口用的是哪个近义词」，
     * 于是只能用「每源最多 2 条」这种粗暴截断控制请求量。现在写法与优先级一起带下来，
     * 才能按「主词先发、长尾后发、收敛即停」来调度。
     */
    private data class TagEntry(
        val sourceId: String,
        val url: String,
        val term: String,
        val termPriority: Int,
        val kind: EntryKind,
    )

    /** 本次搜索的上下文：滚到底时按同一批入口继续抓下一页 */
    private data class PagingContext(
        val tags: List<String>,
        val entriesPerTag: List<List<TagEntry>>,
        val sources: List<String>,
        val single: Boolean,
        val union: List<LinkedHashMap<String, MediaItem>>,
    )

    /**
     * 一页结果的「打标证据」（v1.8.4.1）。
     *
     * [proven] 只记录**真实分类页**命中的标签：作品出现在某个标签的分类页里，
     * 才算「它属于这个标签」。纯关键词入口搜到的是「标题里出现了这个词」，
     * 不能当成归属证据 —— 否则《全面接触》会被打上「全彩」。
     */
    private data class PageResult(
        val items: List<MediaItem>,
        /** `sourceId:id` → 该作品被真实分类页证明过的标签 */
        val proven: Map<String, Set<String>> = emptyMap(),
    )

    /** 本次搜索已经写进索引的作品（`sourceId:id`），翻页时避免重复写库 */
    private val indexedInSearch = mutableSetOf<String>()

    private var paging: PagingContext? = null

    /**
     * 本轮搜索的**任务终态**（v1.8.9.1）。
     *
     * 以前这里是一张 `deadSources`：某个源超时一次就把整轮拉黑，连它其它写法的
     * 结果一起丢掉 —— 一个源认不出英文写法，整源下线，结果自然越来越少。
     * 现在超时只记进 [sourceStatus]（= 未知），源照样会被问，只是排到后一波。
     */
    private val sourceStatus = java.util.concurrent.ConcurrentHashMap<String, RecallOutcome>()

    /**
     * 本轮**真的给出过回答**的源（success / empty 任一）。
     *
     * 判定层用它区分两种「源不可用」：一条结果既然是从这个源抓回来的，说明这个源
     * 这次是通的 —— 它别的写法超时只意味着「可能还有更多」，不该把已经拿到的
     * 结果降级成 UNKNOWN。只有**完全没回话**的源才让结果变 unknown。
     */
    private val sourceReturned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 搜索阶段（v1.8.9.1）：界面据此区分「还在补」和「真的完了」。
     *
     * 判据是**任务终态归零**，不是「结果连续几次没变化」—— 后者在慢源上会误判：
     * 结果只是暂时停住，源其实还在返回。
     */
    private val _phase = MutableStateFlow(SearchPhase.IDLE)
    val phase: StateFlow<SearchPhase> = _phase.asStateFlow()

    /**
     * 首页是否已经有足够多的「确凿结果」（v1.8.9.1）。
     *
     * 首页判定层在确凿结果 ≥ 20 条时会把「没证据」的（源超时 / 关键词检索宽松命中）
     * 整批挡掉；翻页必须用同一口径，否则第 2 页起又会把无关内容追加回来 ——
     * 真机上表现为「往下滑一堆和标签没关系的东西」。
     */
    private var dropUnproven = false

    /** 本轮因超时/报错而「不知道有没有」的源数量 */
    private val _slowSourceCount = MutableStateFlow(0)
    val slowSourceCount: StateFlow<Int> = _slowSourceCount.asStateFlow()

    // ===== 并发额度（v1.8.8.4）=====
    /**
     * 普通源的并发额度，**一次搜索共用一个**。
     *
     * 以前每页（`fetchPage`）都 `Semaphore(TAG_SEARCH_CONCURRENCY)` 新建一个，
     * 于是「首屏 + 后台预取 3 页 + 用户触底翻页」各自 24 路并行，实际能叠到近百个
     * 请求同时打出去 —— 用户把滑块调到 8 也没用，因为额度根本没共享。现在统一由
     * [search] 按用户设置建一次，翻页和预取都从这一个额度里取。
     */
    private var pageSemaphore: Semaphore =
        Semaphore(com.novelreader.core.preferences.PreferencesManager.DEFAULT_TAG_SEARCH_CONCURRENCY)

    /**
     * Venera 源的并发额度（v1.8.8.4；v1.8.9.1 曾删掉，**又加回来了**）。
     *
     * 删掉的代价是真机实测出来的：标签搜索一口气派几十个 explore 进引擎，
     * 引擎内部的 HTTP 通道被占满，此时点开一部作品，详情页的 `info` 调用排队到
     * 30 秒超时 —— 用户看到的就是「点进去一直转圈，进不去」。
     *
     * 引擎自己的「交互 / 后台」分道帮不上忙：`explore` 本来就走在后台通道，
     * 真正被打满的是两条通道共用的 HTTP 额度。所以应用层必须给标签搜索单独压一道，
     * 让交互请求始终有位置。
     */
    private var veneraSemaphore: Semaphore = Semaphore(VENERA_TAG_CONCURRENCY)

    /** 本次搜索里哪些源是 Venera 格式（由 [search] 填充，翻页 / 预取沿用） */
    @Volatile
    private var veneraSourceIds: Set<String> = emptySet()

    /** 本次搜索涉及的成人源 id（分级 / 属性类标签只采信它们的分类页证据） */
    @Volatile
    private var adultSourceIds: Set<String> = emptySet()

    /** 用户设置的标签搜索并发（设置页滑块），下一次搜索生效 */
    @Volatile
    private var tagConcurrency: Int =
        com.novelreader.core.preferences.PreferencesManager.DEFAULT_TAG_SEARCH_CONCURRENCY

    private var nextPage = 2

    /**
     * 连续「整页都没有新内容」的次数（v1.8.8.0，待办 #35）。
     *
     * 以前只要一页没新增就直接判定「到底了」，而视频场景下这件事非常常见：
     * 几十个采集站上架的是同一批作品，第 2 页按标题去重后往往一条都不剩，
     * 于是「翻到第二页就没了」。现在要连续 [REPEAT_PAGE_TOLERANCE] 页都没新增才停，
     * 中间继续往后问 —— 真正的到底（整页 0 条）仍然立刻停。
     */
    private var repeatPages = 0

    private var preloadJob: kotlinx.coroutines.Job? = null
    private val _hasMore = MutableStateFlow(false)

    /** 还有没有下一页（触底加载用） */
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 本次搜索动用了几成源（v1.8.7.5）：顶部要显示「用 N 个源」，用户想知道覆盖面 */
    private val _sourceCount = MutableStateFlow(0)
    val sourceCount: StateFlow<Int> = _sourceCount.asStateFlow()

    /** 正在加载第几页（v1.8.7.5）：0 = 没在翻页。页脚拿它显示进度 */
    private val _loadingPage = MutableStateFlow(0)
    val loadingPage: StateFlow<Int> = _loadingPage.asStateFlow()

    /**
     * 最近一次「整页都没新内容」的页码（v1.8.7.8，0 = 没有过）。
     *
     * 到这里自动翻会停下；如果不告诉用户是哪一页停的，底部就只剩一片空白，
     * 看上去像坏了。页脚用这个数字说明「源站在第 N 页把上一页又返回了一次」。
     */
    private val _emptyPage = MutableStateFlow(0)
    val emptyPage: StateFlow<Int> = _emptyPage.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)

    /** 是否正在加载下一页 */
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /**
     * 复合搜索产出：结果 + 展示说明（[note]）。
     *
     * [note] 用来解释「为什么这批结果长这样」—— 走了组合关键词直搜、还是只拿到了单组内容，
     * 都必须写清楚，不能让用户以为这就是严格交集。
     */
    private data class CompoundOutcome(
        val items: List<MediaItem>,
        val note: String? = null,
        /** 严格求交时每个大集的并集，分页继续累积用（v1.8.1.2） */
        val union: List<LinkedHashMap<String, MediaItem>>? = null,
        /** 打标证据：`sourceId:id` → 真实分类页证明过的标签（v1.8.4.1） */
        val proven: Map<String, Set<String>> = emptyMap(),
    ) {
        companion object {
            val EMPTY = CompoundOutcome(emptyList())
        }
    }

    init {
        collectTags(initialTag.isNotBlank())
        viewModelScope.launch {
            _sources.value = runCatching {
                sourceRepository?.getSourcesByType(type)?.filter { it.enabled }.orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    /** 收集标签池：先读缓存（毫秒级），空则现场收集 */
    private fun collectTags(thenSearch: Boolean) {
        viewModelScope.launch {
            _collecting.value = true
            tagPlan = runCatching { tagRepository.tags(type) }.getOrDefault(TagPlan.EMPTY)
            _allTags.value = tagPlan.tags
            _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
            _tagsByCoverage.value = tagPlan.hotTags(0)
            _collecting.value = false
            // 标签池刚到位时补算一次提示：用户可能在池子到之前就输入了一半
            _suggestions.value = computeSuggestions(_query.value)
            if (thenSearch && _results.value.isEmpty()) search()
        }
    }

    /** 强制重新收集标签（源变了 / 分类没刷出来时用） */
    /**
     * 清空当前类型（漫画 / 小说 / 视频）已收录的标签池（v1.8.9.2）。
     *
     * 标签池是从各源分类聚合出来的，站点改版、误收噪声分类、早期一次错误的成人
     * 判定都会让它变脏；「刷新标签」只在旧池子上覆盖，脏东西可能留在里面。
     * 这里直接把缓存删掉，下次进页面会重新收集一份干净的。
     */
    fun clearCollectedTags() {
        viewModelScope.launch {
            _collecting.value = true
            val removed = runCatching { tagRepository.clear(type) }.getOrDefault(false)
            // 内存里的展示状态一起复位，否则界面上还挂着旧的 929 个标签
            tagPlan = TagPlan.EMPTY
            _allTags.value = emptyList()
            _hotTags.value = emptyList()
            _tagsByCoverage.value = emptyList()
            _activeTags.value = emptyList()
            _suggestions.value = emptyList()
            _results.value = emptyList()
            _phase.value = SearchPhase.IDLE
            _collecting.value = false
            _notice.value = if (removed) {
                "已清空「${typeLabel(type)}」的收录标签，重新进入本页会重新收集"
            } else {
                "「${typeLabel(type)}」本来就没有收录标签缓存，无需清空"
            }
        }
    }

    fun refreshTags() {
        viewModelScope.launch {
            _collecting.value = true
            tagPlan = runCatching { tagRepository.refresh(type) }.getOrDefault(TagPlan.EMPTY)
            _allTags.value = tagPlan.tags
            _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
            _tagsByCoverage.value = tagPlan.hotTags(0)
            _collecting.value = false
            // 标签池刚到位时补算一次提示：用户可能在池子到之前就输入了一半
            _suggestions.value = computeSuggestions(_query.value)
        }
    }

    fun setQuery(value: String) {
        _query.value = value
        _activeTags.value = emptyList()
        _suggestions.value = computeSuggestions(value)
    }

    /** 收起提示词（点面板右上角 × / 开始搜索时） */
    fun clearSuggestions() {
        if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
    }

    /**
     * 点选提示词（v1.8.7.1 改为「补全即止」）。
     *
     * v1.8.6.0 的实现是「补全 + 立刻搜索」：点一个词就等于提交，想再输第二个标签
     * 必须先等搜索跑完、再把手指挪回输入框 —— 而标签搜索的主场景恰恰是
     * **多个标签组合**（「无码 全彩」），等于每输一个词被打断一次。
     *
     * 现在与搜索框补全的通行行为对齐：
     * - 只把正在输入的那一段换成完整标签，末尾补空格（[TagSuggestions.completeForContinue]）；
     * - **不触发搜索**，键盘与光标都留在原地，用户可以直接接着打下一个标签；
     * - 补全后该词已是池内完整标签，提示会自动收起，不需要额外的「已关闭」状态。
     *
     * 想搜就按键盘搜索键或右下角搜索按钮 —— 提交动作只由用户显式发起。
     */
    fun applySuggestion(tag: String) {
        val name = tag.trim()
        if (name.isEmpty()) return
        _query.value = TagSuggestions.completeForContinue(_query.value, name)
        _activeTags.value = emptyList()
        // 兜底：词表外写法（补全后 currentWord 仍能命中候选）时也把面板收掉
        _suggestions.value = emptyList()
    }

    /**
     * 计算提示词：纯本地、前缀优先、同分按覆盖源数排、只提示当前正在输入的词。
     * 排序规则全部在 [TagSuggestions.of]，这里只负责喂「按覆盖源数倒序的池子」。
     */
    private fun computeSuggestions(raw: String): List<String> {
        val pool = _tagsByCoverage.value.ifEmpty { _allTags.value }
        return TagSuggestions.of(pool, raw)
    }

    private fun logicalTagsInQuery(query: String): List<String> =
        _activeTags.value.takeIf { it.isNotEmpty() && it.joinToString(" ") == query }
            ?: TagQuery.parse(query)

    /**
     * 把一个语法条件展开成「一组等价写法」（v1.8.1.3）。
     *
     * - 普通标签：受控词表里的全部译名（[TagAlias.aliasesOf]）；
     * - 通配：先在受控词表 + 标签池里匹配出真实标签，再各自展开译名 ——
     *   `魔法*` 会命中「魔法」「魔法少女」等，而不是拿星号去请求源站。
     */
    private fun expandClauseNames(spec: TagQuerySpec.Clause): Set<String> {
        if (!spec.wildcard) return TagAlias.aliasesOf(spec.name)
        val vocabulary = buildList {
            TagAlias.GROUPS.forEach { addAll(it) }
            addAll(tagPlan.tags)
            addAll(tagPlan.plan.keys)
        }
        return TagQuerySpec.expandWildcard(spec.name, vocabulary)
            .flatMap { matched -> TagAlias.aliasesOf(matched) }
            .toSet()
    }

    /**
     * 排除条件：网络结果不带标签，只能按标题包含关系过滤（索引侧已由 SQL 整条剔除）。
     *
     * 术语用 [TagAlias.searchTermsFor] 展开，`-无码` 也能排掉标题写着「無修正」的作品。
     */
    private fun withoutExcluded(
        items: List<MediaItem>,
        excludeTerms: List<String>,
    ): List<MediaItem> {
        if (excludeTerms.isEmpty()) return items
        return items.filterNot { item ->
            val key = TagQuery.normalizeTitle(item.title)
            excludeTerms.any { term -> key.contains(term) }
        }
    }

    /**
     * 把标签追加到查询串（空格分隔）并立即搜索 —— 复合搜索入口（v1.5.10.0）。
     *
     * v1.6.1.0 改进：同步把 [activeTags] 也更新，让热们推荐 chip 立刻呈现「已选」状态，
     * 不用等搜索请求回来才反映。
     */
    fun addTag(tag: String) {
        if (tag.isBlank()) return
        _suggestions.value = emptyList()
        val current = TagAlias.dedupeAliases(logicalTagsInQuery(_query.value))
        val next = TagAlias.dedupeAliases(current + tag)
        if (next.size == current.size) {
            _activeTags.value = current.map(TagAlias::canonicalOf).distinct()
            return
        }
        _query.value = next.joinToString(" ")
        _activeTags.value = next.map(TagAlias::canonicalOf).distinct()
        search()
    }

    /** 从查询查询返回某标签（点已选 chip）。最后一个标签移除时同步清空结果 / 进度 */
    fun removeTag(tag: String) {
        _suggestions.value = emptyList()
        val parsed = logicalTagsInQuery(_query.value)
        val aliases = TagAlias.aliasesOf(tag)
        val current = parsed.filterNot { candidate ->
            TagAlias.aliasesOf(candidate).any { it in aliases }
        }
        _query.value = current.joinToString(" ")
        _activeTags.value = TagAlias.dedupeAliases(current.map(TagAlias::canonicalOf))
        if (current.isEmpty()) {
            searchGeneration += 1
            searchJob?.cancel()
            _results.value = emptyList()
            _searching.value = false
            _notice.value = null
            _progress.value = null
        } else {
            search()
        }
    }

    /**
     * 触底加载下一页（v1.8.1.2）。
     *
     * 只抓一页、增量追加，不重跑整次搜索 —— 用户往下滚多少就抓多少。
     *
     * @param force true = 用户点了「继续往下翻」，越过上一次的结束判定硬往前问一页
     *   （v1.8.7.8）。聚合了几十个源一起翻，常常是「多数源到底了、少数源还有」：
     *   自动翻到整页无新增就停是必要的（否则会无限问下去），但**不能顺手把向下的
     *   路也堵死** —— 到底却什么都不显示、也没有往下的入口，用户只会觉得「坏了」。
     */
    fun loadMore(force: Boolean = false) {
        if (_loadingMore.value) return
        if (!_hasMore.value && !force) return
        val ctx = paging ?: return
        val generation = searchGeneration
        if (force) {
            // 手动继续：先恢复可翻标记，让下面能真正发出请求
            _hasMore.value = true
            _emptyPage.value = 0
            // 手动「继续往下翻」是用户的明确意图，空页计数也归零，
            // 否则点一次只多问一页就又停下（v1.8.8.0）
            repeatPages = 0
        }
        // 用户主动「加载更多」优先：预取还在跑的话先停掉，否则两边会翻到同一页
        preloadJob?.cancel()
        _loadingMore.value = true
        _loadingPage.value = nextPage
        // 进度**不在这里清零**（v1.8.7.6）：翻页属于同一次搜索，按下页就把计数重置
        // 会让顶部那条进度条每翻一页从头跑一遍，看着像「永远跑不完」。
        // 现在整次搜索用一条累计进度：已完成的请求只增不减。
        viewModelScope.launch {
            try {
                val page = nextPage
                val result = fetchPage(ctx, page)
                if (generation != searchGeneration) return@launch
                if (result.items.isEmpty()) {
                    // 一个源都没给内容：这批源确实到底了
                    _hasMore.value = false
                } else {
                    val (added, newIds) = appendResultsCounted(result.items)
                    nextPage = page + 1
                    if (added > 0 || newIds > 0) {
                        // 翻页同样打标（v1.8.4.1）：以前只有第一页进索引，滚十页标签层不动
                        if (added > 0) indexProvenance(result.items, result.proven)
                        _emptyPage.value = 0
                        repeatPages = 0
                    } else {
                        // 整页都是「同一个源的同一条」——源站把最后一页反复返回（v1.8.8.0，待办 #35）。
                        // 注意判据是**唯一条目数**：只要多出新的 `sourceId:id`（哪怕同名被折叠、
                        // 行数没涨），就说明还有新东西，不能算空页。
                        repeatPages++
                        if (repeatPages >= REPEAT_PAGE_TOLERANCE) {
                            _hasMore.value = false
                            _emptyPage.value = page
                            return@launch
                        }
                    }
                    android.util.Log.d(
                        "TagSearch",
                        "第 $page 页取回 ${result.items.size} 条，新增 $added 行 / $newIds 条" +
                            "（连续空页 $repeatPages）",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TagSearch", "加载下一页失败，page=$nextPage", error)
                _hasMore.value = false
            } finally {
                _loadingMore.value = false
                _loadingPage.value = 0
                // v1.8.7.6：进度改为整次搜索累计，翻完一页**不清零**（以前清零是为了
                // 不让顶部停在最后一页的分母上，但代价是每页都从头跑一遍进度条）。
                // 清零只在开一次新搜索时进行。
            }
        }
    }

    /**
     * 首次搜索完成后**后台预取 3 页**（v1.8.1.2）。
     *
     * 用户滚到底时数据往往已经在那儿了，滚动不会卡在「等下一页请求」上。
     */
    private fun preloadNextPages(generation: Long) {
        val ctx = paging ?: return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            try {
            var loaded = 0
            // v1.8.8.0（待办 #35）：整页没新增不再直接收工，容忍连续空页；
            // attempts 兜住「一直没新增」的情况，避免无限往后问。
            var emptyStreak = 0
            var attempts = 0
            while (loaded < PRELOAD_PAGES && _hasMore.value &&
                attempts < PRELOAD_PAGES + REPEAT_PAGE_TOLERANCE
            ) {
                attempts++
                if (generation != searchGeneration) return@launch
                val page = nextPage
                val result = try {
                    fetchPage(ctx, page)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("TagSearch", "预加载失败，page=$page", error)
                    PageResult(emptyList())
                }
                if (generation != searchGeneration) return@launch
                if (result.items.isEmpty()) {
                    _hasMore.value = false
                    return@launch
                }
                val (added, newIds) = appendResultsCounted(result.items)
                nextPage = page + 1
                if (added == 0 && newIds == 0) {
                    emptyStreak++
                    if (emptyStreak >= REPEAT_PAGE_TOLERANCE) {
                        // 连续几页都是同一个源的同一条：这才是真的到底，记下页码给页脚用（v1.8.7.8）
                        _hasMore.value = false
                        _emptyPage.value = page
                        return@launch
                    }
                    continue
                }
                emptyStreak = 0
                repeatPages = 0
                _emptyPage.value = 0
                loaded++
                // 预取的页同样进标签层：用户滚到底看到的这批，本来就已经抓回来了
                indexProvenance(result.items, result.proven)
            }
            } finally {
                // 预取的计数同样计入总进度（v1.8.7.6），不清零 ——
                // 清零的话顶部那条总进度会被每页重置，看着永远跑不完
            }
        }
    }

    /**
     * 抓某一页（单标签 / 复合求交两种形态），并带上打标证据。
     *
     * v1.8.9.1 起**只有两种形态**：单标签取并集、复合标签取各集求交。
     * 旧版的第三种「组合关键词直搜」是「有标签没分类页」时的特判，现在已经由
     * 判定层接管（分级词按 unknown 保留），那条分支连同 `allSearchOnly` 一起删掉了。
     */
    private suspend fun fetchPage(ctx: PagingContext, page: Int): PageResult = when {
        ctx.single && ctx.entriesPerTag[0].isNotEmpty() -> {
            val only = ctx.tags.firstOrNull()
            val proven = mutableMapOf<String, MutableSet<String>>()
            val result = recallEntries(ctx.entriesPerTag, page = page)
            result.responses.forEach { response ->
                if (response.task.kind != EntryKind.CATEGORY) return@forEach
                response.items.forEach { item ->
                    if (only != null) {
                        proven.getOrPut(itemKey(item)) { mutableSetOf() }.add(only)
                    }
                }
            }
            val items = result.responses.flatMap { it.items }
                .distinctBy { "${it.sourceId}:${it.id}" }
            // 翻页同样要过判定层：不过的话第 2 页起又会把无关内容原样追加回来
            PageResult(rankOffMain(items, ctx.tags, proven, pagingMinResults()), proven)
        }
        // 有标签在源里没有真实分类页 → 组合筛选在数据层不成立，退回各集并集。
        // 取舍交给判定层，不再用一刀切的标题过滤把这些内容砍掉。
        ctx.entriesPerTag.any { list -> list.none { it.kind == EntryKind.CATEGORY } } -> {
            val result = recallEntries(ctx.entriesPerTag, page = page)
            val items = result.responses.flatMap { it.items }
                .distinctBy { "${it.sourceId}:${it.id}" }
            PageResult(rankOffMain(items, ctx.tags, emptyMap(), pagingMinResults()))
        }
        else -> {
            val categoryEntries = ctx.entriesPerTag.map { list ->
                list.filter { it.kind == EntryKind.CATEGORY }
            }
            val strict = strictIntersection(categoryEntries, page, {}, ctx.union)
            PageResult(strict.matched, provenOf(strict, ctx.tags))
        }
    }

    /** 从求交结果里取出「同源命中」的证据：哪部作品被哪些标签的分类页证明过 */
    private fun provenOf(result: StrictResult, tags: List<String>): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        result.matched.forEach { item ->
            val proofKey = TagProvenance.proofKey(item.sourceId, item.title) ?: return@forEach
            result.provenKeysPerTag.forEachIndexed { index, keys ->
                val tag = tags.getOrNull(index) ?: return@forEachIndexed
                if (proofKey !in keys) return@forEachIndexed
                out.getOrPut(itemKey(item)) { mutableSetOf() }.add(tag)
            }
        }
        return out
    }

    /** 作品在索引里的唯一键（与 `TagIndexRepository.indexItems` 的入参口径一致） */
    private fun itemKey(item: MediaItem): String = "${item.sourceId}:${item.id}"

    /**
     * 边搜边标记（v1.8.4.1）：**每一页**的结果都进标签层，且只补有证据的标签。
     *
     * @param proven 该作品被真实分类页证明过的标签（关键词命中不算）
     * @param withContext false = 只按作品自身字段刷新（索引里已有的结果走这条，
     *   避免把「用户搜过这个标签」重复加成）
     */
    private suspend fun indexProvenance(
        items: List<MediaItem>,
        proven: Map<String, Set<String>> = emptyMap(),
    ) {
        val repo = tagIndexRepository ?: return
        if (items.isEmpty()) return
        // 同一次搜索里已经写过的作品不再写（翻页 / 预取会重复抓到同一部）
        val fresh = items.filter { it.sourceId.isNotBlank() && it.id.isNotBlank() }
            .filter { indexedInSearch.add(itemKey(it)) }
        if (fresh.isEmpty()) return
        // 分级 / 属性类标签只给成人源写进索引（v1.8.9.2）：一般向源的分类页有时会把
        // 整站目录挂在任意分类下，那种「证据」一旦落库就变成永久脏标签 ——
        // 之后每次搜索都作为「本地命中」直接合并进结果，绕过全部判定，越搜越脏。
        val adultIds = adultSourceIds
        val safeProven = if (adultIds.isEmpty()) {
            proven
        } else {
            buildMap {
                fresh.forEach { item ->
                    val provenTags = proven[itemKey(item)] ?: return@forEach
                    val kept = provenTags.filter {
                        !TagAlias.isExplicitAdultIntent(it) || item.sourceId in adultIds
                    }
                    if (kept.isNotEmpty()) put(itemKey(item), kept.toSet())
                }
            }
        }
        val groups = TagProvenance.groupByProof(fresh, safeProven, ::itemKey)
        // 分组本身是纯内存计算（快），真正的打标与写库在 [TagIndexRepository.indexItems]
        // 里切到 IO；这里再包一层是为了让「一组一组写」与最后的计数在同一个后台协程里。
        withContext(Dispatchers.IO) {
            groups.forEach { (tags, group) ->
                val context = tags.map { TagAlias.canonicalOf(it) to CONTEXT_PAGE_CONFIDENCE }
                runCatching { repo.indexItems(type, group, context) }
            }
            refreshIndexStats(repo)
        }
    }

    /**
     * 增量追加结果（先按 `sourceId:id` 再按标题去重，顺序保持）。
     *
     * @return 真正**新增**了几条 —— 0 说明这一页全是已有内容（源站把最后一页反复返回），
     *   调用方据此判断「这个源已经翻到底」，不再无限问下去。
     */
    private fun appendResults(items: List<MediaItem>): Int {
        if (items.isEmpty()) return 0
        val before = _results.value.size
        _results.value = resultsAcc.addAll(items)
        return (_results.value.size - before).coerceAtLeast(0)
    }

    /**
     * 同 [appendResults]，但同时报告「唯一条目数」的增量（v1.8.8.0，待办 #35）。
     *
     * 翻页到底的判定必须看这个：视频源上架同一批作品，第 2 页常常**行数不涨**
     * （同名作品被折叠进已有卡片），但唯一条目数是涨的 —— 那说明这一页确实带来了新东西
     * （同一部片又多了一个播放源），不该被当成「没有新内容」而停下。
     */
    private fun appendResultsCounted(items: List<MediaItem>): Pair<Int, Int> {
        if (items.isEmpty()) return 0 to 0
        val idsBefore = resultsAcc.uniqueCount
        val rowsBefore = _results.value.size
        _results.value = resultsAcc.addAll(items)
        return (_results.value.size - rowsBefore).coerceAtLeast(0) to
            (resultsAcc.uniqueCount - idsBefore).coerceAtLeast(0)
    }

    /** 结果累加器：翻页只处理新到的一页，不重算整个列表 */
    private val resultsAcc = SearchResultMerge.Accumulator()

    /**
     * 执行标签搜索（单标签 / 复合标签通用）。
     *
     * v1.7.0.0 三处口径修正：
     * 1. **池里没有的标签不再拖垮整次搜索**：以前它照样参与交集，任何空集合都会让
     *    交集清空，而提示却写着「已按其余标签搜索」—— 说好了搜索却返回空，
     *    这正是「复合搜索没作用」最直接的一条。现在这些标签直接剔除，只在提示里点名。
     * 2. **复合搜索按「每标签全量并集求交」**（v1.7.9.9 起，见 [searchCompound]），
     *    不再限定「同源同时拥有全部入口」，避免源边界把同名作品切掉。
     * 3. **只打已启用的源**（见 [enabledSourceIds]）：标签池是含禁用源收集的，
     *    以前搜索也照着池子全打一遍，用户停用的源照样被请求、每个都要干等到超时。
     */
    // ===== 搜索进度（v1.7.1.0）：把「已完成请求数 / 当前已知请求总数」实时上屏 =====
    // 计数器本体声明在文件上方（_progress 旁边），因为 init 会同步跑到 resetProgress。

    private fun resetProgress() {
        progressDone.set(0)
        progressTotal.set(0)
    }

    /** 登记本批将发出 [n] 个请求（累加进总量） */
    private fun addProgressTotal(n: Int) {
        if (n <= 0) return
        progressTotal.addAndGet(n)
        publishProgress()
    }

    /** 完成 1 个请求 */
    private fun stepProgress() {
        progressDone.incrementAndGet()
        publishProgress()
    }

    private fun publishProgress() {
        val total = progressTotal.get()
        if (total <= 0) return
        _progress.value = progressDone.get().coerceAtMost(total) to total
    }

    /**
     * 用户点开某条结果、即将离开搜索页时调用（v1.8.9.2）。
     *
     * 召回与翻页都是长尾任务：用户既然已经挑中了一条，后面的结果对他没用了。
     * 但它们会继续占着 Venera 引擎那条**全应用共用**的 JS 线程（实测有源一次
     * 返回 2.3MB JSON，解析期间 JS 线程无法做别的），详情页的 `info` 于是排队
     * 到 30 秒超时 —— 用户侧看到的就是「点结果一直转圈，进不去」。
     * 这里把后台作业停掉，界面上已有的结果原样保留。
     */
    fun abandonBackgroundWork() {
        val runningSearch = searchJob?.isActive == true
        val runningPreload = preloadJob?.isActive == true
        if (!runningSearch && !runningPreload) return
        // 首屏召回被中途停掉 → 记一笔，返回搜索页时由 [resumeAfterReturn] 补完（v1.8.9.6）
        if (runningSearch) recallAbandoned = true
        searchJob?.cancel()
        preloadJob?.cancel()
        searchJob = null
        preloadJob = null
        _searching.value = false
        // 还有源没回来，但用户已经不需要了：状态收成「部分」，别一直显示「仍在收集」
        if (_phase.value == SearchPhase.STREAMING) _phase.value = SearchPhase.DONE_PARTIAL
    }

    /**
     * 首屏召回是否被 [abandonBackgroundWork] 中途停掉（v1.8.9.6）。
     *
     * 点开结果时停掉召回是为了腾出全应用共用的执行线程，但**回来之后必须补上** ——
     * 否则顶部会一直挂着「部分源未返回」，列表也永远停在那半截结果上，
     * 用户看到的就是「进去看了一部，回来就不继续搜索了」。
     */
    private var recallAbandoned = false

    /**
     * 从详情页 / 阅读页返回搜索页时调用：把上次被中断的首屏召回补完（v1.8.9.6）。
     *
     * 只补**一个结果都没回来的源**（[sourceReturned] 里没有的）：已经回来的源再问一遍
     * 纯属浪费，而且它们的结果已经在列表里了。补回来的条目走 [appendResultsCounted]
     * 增量追加去重，不会把用户已经看到的顺序打乱。
     */
    fun resumeAfterReturn() {
        if (!recallAbandoned) return
        recallAbandoned = false
        val ctx = paging ?: return
        if (_searching.value || searchJob?.isActive == true) return
        val pendingEntries = ctx.entriesPerTag.map { list ->
            list.filter { it.sourceId !in sourceReturned }
        }
        if (pendingEntries.all { it.isEmpty() }) return
        val generation = searchGeneration
        searchJob = viewModelScope.launch {
            _searching.value = true
            _phase.value = SearchPhase.STREAMING
            try {
                val tasks = pendingEntries.flatMapIndexed { index, entries ->
                    tasksOf(entries, index, 1)
                }
                if (tasks.isEmpty()) return@launch
                val result = recall(tasks)
                if (generation != searchGeneration) return@launch
                val items = result.responses.flatMap { it.items }
                    .distinctBy { "${it.sourceId}:${it.id}" }
                if (items.isNotEmpty()) {
                    // 证据口径与首屏一致：只有分类页入口命中的才算「属于这个标签」
                    val proven = mutableMapOf<String, MutableSet<String>>()
                    result.responses.forEach { response ->
                        if (response.task.kind != EntryKind.CATEGORY) return@forEach
                        val tag = ctx.tags.getOrNull(response.task.tagIndex) ?: return@forEach
                        response.items.forEach { item ->
                            proven.getOrPut(itemKey(item)) { mutableSetOf() }.add(tag)
                        }
                    }
                    val ranked = rankOffMain(items, ctx.tags, proven)
                    // 排除词口径必须和 [search] 里完全一致（别名组展开 + 标题归一化），
                    // 否则补回来的这批会把用户明确排除的内容又放进来
                    val excludeTerms = TagQuerySpec.parse(_query.value)
                        .filter { it.exclude }
                        .flatMap { spec -> TagAlias.searchTermsFor(spec.name) + spec.name }
                        .map(TagQuery::normalizeTitle)
                        .filter { it.isNotEmpty() }
                    val network = withoutExcluded(filterAdults(ranked, ctx.tags), excludeTerms)
                    appendResultsCounted(network)
                    indexProvenance(network, proven)
                }
                _phase.value =
                    if (result.partial) SearchPhase.DONE_PARTIAL else SearchPhase.DONE_COMPLETE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TagSearch", "补完被中断的召回失败", error)
            } finally {
                if (generation == searchGeneration) {
                    _searching.value = false
                    _progress.value = null
                }
            }
        }
    }

    fun search() {
        _suggestions.value = emptyList()
        val firstParse = logicalTagsInQuery(_query.value)
        if (firstParse.isEmpty()) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _notice.value = null
            resetProgress()
            // 新一次搜索：总进度与「空页」记录都从头算（翻页不清零，只有这里清）
            _progress.value = null
            _emptyPage.value = 0
            repeatPages = 0

            try {
                // v1.8.8.4：按用户设置重建本次搜索的并发额度。放在 ensurePlan 之前，
                // 让「收集标签」与「抓内容」用同一套额度；改设置后下一次搜索即生效。
                val concurrency = preferencesManager?.tagSearchConcurrencyFlow?.first()
                    ?: tagConcurrency
                tagConcurrency = concurrency
                pageSemaphore = Semaphore(concurrency)
                // Venera 引擎是单线程 + 共用 HTTP 额度，给它更窄的一道
                veneraSemaphore = Semaphore(minOf(VENERA_TAG_CONCURRENCY, concurrency))
                veneraSourceIds = veneraIdsOf()
                // 新一轮搜索：本轮终态表从头开始。**这里不再有「死源」** ——
                // 超时只记进 sourceStatus（= 未知），源照样会被问到（v1.8.9.1）。
                sourceStatus.clear()
                sourceReturned.clear()
                _slowSourceCount.value = 0
                dropUnproven = false
                _phase.value = SearchPhase.STREAMING
                ensurePlan()
                // v1.8.1.3 查询语法：-排除 / 大类:标签 / 通配 *
                val specs = TagQuerySpec.parse(_query.value)
                val positiveSpecs = specs.filterNot { it.exclude }
                if (positiveSpecs.isEmpty()) {
                    _results.value = emptyList()
                    _notice.value = "只写了排除条件（-标签），至少留一个要搜索的标签"
                    return@launch
                }
                // 每个正条件展开成「一组等价写法」：普通标签取别名组，通配按受控词表 + 标签池展开
                val expandedPerSpec = positiveSpecs.map(::expandClauseNames)
                // 展开为空（通配没匹配到任何词）的条件直接视为未知标签
                val uniqueSpecs = positiveSpecs.mapIndexed { i, spec -> spec to expandedPerSpec[i] }
                    .filter { (spec, names) -> names.isNotEmpty() || !spec.wildcard }
                    .distinctBy { (spec, names) -> spec.category to names }
                if (uniqueSpecs.isEmpty()) {
                    // 全是通配且一个词都没匹配到（如 `zzzz*`）
                    _results.value = emptyList()
                    _notice.value = "通配没有匹配到任何标签，换个写法试试"
                    return@launch
                }

                val tags = uniqueSpecs.map { (spec, _) -> spec.name }
                val namesPerTag = uniqueSpecs.map { it.second }
                val clauses = uniqueSpecs.map { (spec, names) ->
                    com.novelreader.data.repository.TagIndexRepository.TagClause(names, spec.category)
                }
                // 排除条件：索引侧整条剔除；网络侧按标题包含关系过滤（源站结果不带标签）
                val excludeNames = specs.filter { it.exclude }
                    .flatMap(::expandClauseNames)
                    .toSet()
                val excludeTerms = specs.filter { it.exclude }
                    .flatMap { spec -> TagAlias.searchTermsFor(spec.name) + spec.name }
                    .map(TagQuery::normalizeTitle)
                    .filter { it.isNotEmpty() }

                // v1.8.1.2：**索引与搜索不冲突**。
                // 先看本地索引里已经收录的，有就立刻显示 —— 但**不 return**，
                // 网络搜索照跑，新结果合并进来、再回写索引，越搜越全。
                // v1.8.1.3：索引查询带上大类 / 排除 / 成人整类过滤，并返回置信度排序。
                // v1.8.9.7（待办 #47）：顺带刷新各标签的收录数，chip 角标与结果同步。
                val indexedTotal = tagIndexRepository?.let { repo ->
                    refreshIndexStats(repo)
                    _indexCount.value
                } ?: 0
                var localItems: List<MediaItem> = emptyList()
                if (indexedTotal > 0 && clauses.isNotEmpty()) {
                    val hideAdult = preferencesManager?.hideAdultFlow?.first() ?: true
                    val explicitAdult = tags.any(TagAlias::isExplicitAdultIntent) ||
                        uniqueSpecs.any { (spec, _) -> spec.category == TagTagger.CATEGORY_ADULT }
                    localItems = tagIndexRepository?.search(
                        type = type,
                        clauses = clauses,
                        excludeTags = excludeNames,
                        hideAdult = hideAdult && !explicitAdult,
                    ).orEmpty()
                    if (localItems.isNotEmpty()) {
                        _results.value = localItems
                    }
                    // 待办 #47：这些就是「已收录」的，标记出来供卡片显示角标
                    _indexedKeys.value = localItems.map { "${it.sourceId}|${it.id}" }.toSet()
                } else {
                    _indexedKeys.value = emptySet()
                }

                // 分级 / 属性类标签的判定要用：哪些源本身是成人源
                adultSourceIds = runCatching {
                    sourceRepository?.getSourcesByType(type)
                        ?.filter { it.isAdult }
                        ?.map { it.id }
                        ?.toSet()
                        .orEmpty()
                }.getOrDefault(emptySet())
                val enabledIds = enabledSourceIds()
                if (enabledIds != null && enabledIds.isEmpty()) {
                    _activeTags.value = emptyList()
                    _results.value = emptyList()
                    _notice.value = "没有已启用的源，先去源管理里打开一个"
                    return@launch
                }
                val variantsPerTag = uniqueSpecs.map { (spec, names) ->
                    names.flatMap { name -> TagAlias.matchesInPool(name, tagPlan.tags) }
                        .distinct()
                        .ifEmpty { listOf(spec.name) }
                }
                // 按源搜索：选了某个源就只保留它的入口（优先级高于「已启用源」集合）
                val allowedIds = _selectedSourceIds.value.takeIf { it.isNotEmpty() } ?: enabledIds
                val entriesPerTag: List<List<TagEntry>> = uniqueSpecs.mapIndexed { index, (spec, _) ->
                    variantsPerTag[index]
                        .flatMapIndexed { variantIndex, variant ->
                            tagPlan.searchEntriesFor(variant, spec.name).map { (sourceId, url) ->
                                TagEntry(
                                    sourceId = sourceId,
                                    url = url,
                                    term = variant,
                                    termPriority = variantIndex,
                                    kind = if (isSearchUrl(url)) EntryKind.KEYWORD else EntryKind.CATEGORY,
                                )
                            }
                        }
                        // 同一 (源, URL) 只留一条：池子里多个等价写法会给出重复入口，
                        // 全发出去请求数平白翻倍。**不再限制「每源最多 N 条」** ——
                        // 请求量由调度层按写法优先级分批 + 收敛早停控制（v1.8.9.1）。
                        .distinctBy { it.sourceId to it.url }
                        .let { list -> if (allowedIds == null) list else list.filter { it.sourceId in allowedIds } }
                }
                val known = tags.filterIndexed { i, _ -> entriesPerTag[i].isNotEmpty() }
                val missing = tags.filterNot { it in known }
                val knownEntries = entriesPerTag.filter { it.isNotEmpty() }

                // 流式上屏（v1.8.1.1）：只要有结果就先显示，后面的源陆续补进来。
                // 取舍交给判定层：分级词 unknown 保留、题材词按标题校验，
                // 不再有「全是关键词入口才过滤」这种整轮开关（v1.8.9.1）。
                //
                // 两条性能约束（v1.8.9.1，真机 ANR 之后加的）：
                // 1. 判定 + 排序全部在 [Dispatchers.Default] 上跑，主线程只接结果；
                // 2. 按 [STREAM_EMIT_INTERVAL_MS] 节流 —— 每来一批就重排全部累计结果
                //    是 O(n²)，246 个请求会把主线程占死。最后一批不推也没关系，
                //    搜索收尾时会用累加器整体刷一次。
                var lastEmitMs = 0L
                val updatePartialResults: suspend (List<MediaItem>) -> Unit = { partial ->
                    val now = android.os.SystemClock.uptimeMillis()
                    if (generation == searchGeneration &&
                        now - lastEmitMs >= STREAM_EMIT_INTERVAL_MS
                    ) {
                        lastEmitMs = now
                        val visible = withContext(Dispatchers.Default) {
                            val ranked = rankByVerdict(partial, known, emptyMap())
                            // 索引里已经查到的要先并进来：流式上屏是**覆盖**写 results，
                            // 光用网络这批会把眼前已有的本地结果顶掉，看着像「越搜越少」。
                            if (localItems.isEmpty()) ranked
                            else SearchResultMerge.merge(localItems, ranked)
                        }
                        if (visible.isNotEmpty()) _results.value = visible
                    }
                }
                val translatedNotice = tags.mapIndexedNotNull { i, tag ->
                    val variants = variantsPerTag[i]
                    val self = TagQuery.normalizeTitle(tag)
                    val terms = TagAlias.searchTermsFor(tag)
                    val hasKeywordEntries = entriesPerTag[i].any { it.kind == EntryKind.KEYWORD }
                    when {
                        terms.size > 1 && hasKeywordEntries ->
                            "搜索「${tag}」时同时检索：${terms.joinToString("、")}"
                        variants.any { TagQuery.normalizeTitle(it) != self } ->
                            "「${tag}」已按 ${variants.joinToString("、")} 搜索"
                        else -> null
                    }
                }.joinToString("；")

                // 复合搜索把「大集」**完整**摊开显示（v1.8.1.0 起不再只给前 5 个）：
                // 用户能一眼确认每一组到底包含哪些转译写法，也就明白结果是
                // 「每组各命中一个」而不是「随便命中一个」。
                val compoundNote = if (tags.size > 1) {
                    tags.joinToString("  ∩  ") { tag ->
                        val group = TagAlias.searchTermsFor(tag).ifEmpty { listOf(tag) }
                        "「$tag」组 ${group.size} 个写法：${group.joinToString("、")}"
                    }
                } else {
                    null
                }

                if (known.isEmpty()) {
                    // 网络侧一个可搜入口都没有：只要索引命中就直接展示，
                    // 不再像旧版那样把已经查到的结果一起清空。
                    if (localItems.isNotEmpty()) {
                        _activeTags.value = tags.map(TagAlias::canonicalOf).distinct()
                        _results.value = localItems
                        _notice.value = "本地索引命中 ${localItems.size} 部；网络源没有这些标签的入口"
                        return@launch
                    }
                    _activeTags.value = emptyList()
                    _results.value = emptyList()
                    _notice.value = listOfNotNull(
                        translatedNotice.takeIf { it.isNotBlank() },
                        "未找到标签：${missing.joinToString("、")}，" +
                            if (missing.size == tags.size) {
                                "换个写法，或点「已收录」旁的入口建本地索引"
                            } else {
                                "复合搜索要求全部标签都存在"
                            },
                    ).joinToString("；")
                    return@launch
                }

                // v1.8.1.3：语法修饰（排除 / 大类 / 通配）也写进提示 ——
                // 用户写了 `-有码` 却没看到任何说明时，会以为条件被吞了。
                val syntaxNote = specs
                    .filter { it.exclude || it.category != null || it.wildcard }
                    .joinToString("；") { spec ->
                        buildString {
                            append(TagQuerySpec.describe(spec))
                            if (spec.wildcard) append("（展开 ${expandClauseNames(spec).size} 个写法）")
                        }
                    }
                    .takeIf { it.isNotBlank() }
                val headNote = listOfNotNull(
                    syntaxNote,
                    compoundNote ?: translatedNotice.takeIf { it.isNotBlank() },
                ).joinToString("；").takeIf { it.isNotBlank() }

                _notice.value = headNote
                // ===== 翻页上下文**先建好**（v1.8.9.6）=====
                // 以前 `paging` 只在整轮召回**结束之后**才赋值，于是踩了一个必然的坑：
                // 用户在首屏还在流式收集时就点开某条结果 → [abandonBackgroundWork] 取消
                // searchJob → 永远走不到赋值那一步 → `paging` 恒为 null →
                // 返回搜索页后 `loadMore()` 第一行 `paging ?: return` 直接退出，
                // 界面上就是「进去看了一部，回来怎么滑都不再加载了」。
                // 现在召回**开始前**就把上下文建好（此时入口已经确定，不需要召回结果），
                // 取消只会让这一页的结果不完整，不会把「继续往下翻」的能力一起砍掉。
                val usedSources = knownEntries.flatten().map { it.sourceId }.distinct()
                paging = PagingContext(
                    tags = known,
                    entriesPerTag = knownEntries,
                    sources = usedSources,
                    single = knownEntries.size == 1,
                    // 交集缓存要等召回才产生；复合求交在翻页时只把它当「已知集合」参考，
                    // 给空表只是少一点优化，不影响正确性（下面召回完会补上）。
                    union = emptyList(),
                )
                nextPage = 2
                _hasMore.value = true
                _sourceCount.value = usedSources.size
                // ===== 召回（v1.8.9.1）=====
                // 单标签与复合标签走同一个调度层，区别只在「怎么合并」：
                // 单标签 = 并集，复合 = 各标签并集再求交。判定（哪条算命中）统一在结果层做。
                val outcome: CompoundOutcome
                if (knownEntries.size == 1) {
                    val only = known.firstOrNull()
                    val result = recallEntries(knownEntries, page = 1, onItems = updatePartialResults)
                    // 分类页入口的结果才算「这部作品属于该标签」的证据
                    val proven = mutableMapOf<String, MutableSet<String>>()
                    result.responses.forEach { response ->
                        if (response.task.kind != EntryKind.CATEGORY) return@forEach
                        response.items.forEach { item ->
                            if (only != null) {
                                proven.getOrPut(itemKey(item)) { mutableSetOf() }.add(only)
                            }
                        }
                    }
                    outcome = CompoundOutcome(
                        items = result.responses.flatMap { it.items }
                            .distinctBy { "${it.sourceId}:${it.id}" },
                        proven = proven,
                    )
                } else {
                    outcome = searchCompound(knownEntries, known, updatePartialResults)
                }
                val searchResult = outcome.items
                if (generation != searchGeneration) return@launch
                // 同一部作品在不同源上标题写法可能不同（繁简 / 带作者后缀），
                // 按作品身份键再去重一次，避免结果里出现两条「同一部」。
                // **取舍只走判定层**（[rankByVerdict]）：分级词 unknown 保留、
                // 题材词按标题校验、数量不足自动兜底，不再有「整轮标题硬过滤」的开关。
                val ranked = rankOffMain(searchResult, known, outcome.proven)
                // 条数变少 = 判定层挡掉了「没证据」的那批 → 翻页沿用同一口径
                dropUnproven = ranked.size < searchResult.size
                val network = withoutExcluded(
                    // 先按相关性排序，再把成人源内容沉底 —— 顺序反了的话
                    // [TagResultPolicy.rank] 会按命中数重排，成人沉底就白做了。
                    filterAdults(ranked, tags),
                    excludeTerms,
                )
                // 索引里的是「按真实题材命中」的，不再过标题过滤；与网络结果合并去重。
                // v1.8.4.1：先按 `sourceId:id` 收敛再按标题收敛 —— 同一部作品在索引里
                // 存的是旧标题、网络返回的是新标题时，只按标题去重会留下两条同一个 id，
                // 列表（LazyColumn key = sourceId:id）会直接崩。
                val final = withoutExcluded(
                    SearchResultMerge.merge(dropNonAdultForExplicitTags(localItems, tags), network),
                    excludeTerms,
                )
                if (generation != searchGeneration) return@launch
                _activeTags.value = known.map(TagAlias::canonicalOf).distinct()
                // 用累加器接管当前结果：之后翻页只增量处理新到的一页（v1.8.4.1）
                _results.value = resultsAcc.reset(final)
                // 边搜边标记（v1.8.1.2）+ **证据化**打标（v1.8.4.1）。
                // 以前是「整批同一个上下文」：只要每个标签都有分类页入口，就把所有结果
                // 一起打上这些标签。但同一次搜索里往往混着关键词入口，那些结果只是
                // 「标题里出现了这个词」，《全面接触》就这样被贴上了「全彩」。
                // 现在证据按**作品**记：只有这部作品自己从某个标签的分类页里被抓到过，
                // 才补那个标签；索引里已有的结果只按自身字段刷新，不重复加成。
                indexedInSearch.clear()
                if (network.isNotEmpty()) {
                    viewModelScope.launch { indexProvenance(network, outcome.proven) }
                }
                // 分页上下文在召回**之前**就建好了（见上面 v1.8.9.6 的说明），这里只补
                // 召回才产出的交集缓存；只有「一条结果都没有」时才收回翻页能力。
                if (final.isNotEmpty()) {
                    paging = paging?.copy(union = outcome.union.orEmpty())
                    // nextPage 已在召回前置为 2；这里不再重置，避免中途取消后重复问第 2 页
                    _hasMore.value = true
                    preloadNextPages(generation)
                } else {
                    paging = null
                    _hasMore.value = false
                    _sourceCount.value = 0
                }
                val tail = when {
                    final.isNotEmpty() -> outcome.note
                    known.size > 1 -> "没有作品同时属于「${known.joinToString("」「")}」（每个大集里各要命中至少一个标签）"
                    else -> "「${known.first()}」下暂无内容"
                }
                _notice.value = listOfNotNull(headNote, tail, partialNote())
                    .joinToString("；")
                    .takeIf { it.isNotBlank() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("TagSearch", "标签搜索失败，type=$type", error)
                if (generation == searchGeneration) {
                    _results.value = emptyList()
                    _notice.value = "标签搜索失败，请重试或检查书源配置"
                }
            } finally {
                if (generation == searchGeneration) {
                    _progress.value = null
                    _searching.value = false
                    // 走到这里还是 STREAMING，说明中途提前收场（没入口 / 无标签 / 报错）——
                    // 界面不该继续显示「仍在收集」。正常收尾时 phase 已是 DONE_*，保持不动。
                    if (_phase.value == SearchPhase.STREAMING) _phase.value = SearchPhase.IDLE
                }
            }
        }
    }

    /** 计划为空时补一次（冷启动直进标签搜索） */
    private suspend fun ensurePlan() {
        if (tagPlan.plan.isNotEmpty()) return
        tagPlan = tagRepository.tags(type)
        _allTags.value = tagPlan.tags
        _hotTags.value = tagPlan.hotTags(HOT_TAG_LIMIT)
        _tagsByCoverage.value = tagPlan.hotTags(0)
        _suggestions.value = computeSuggestions(_query.value)
    }

    /**
     * 该类型下**已启用**的源 id 集合；查不到（没有 SourceRepository / 异常）返回 null = 不过滤。
     *
     * [TagRepository] 的标签池是**含禁用源**收集的（v1.6.5.0 起的有意口径：停用某源不该
     * 让它的标签从池子里消失），但真正发请求必须只打已启用的源 —— 否则用户停用了一个源，
     * 标签搜索依旧去请求它，每个都要干等到超时才罢休（默认 20s）。实测在一次复合搜索里，
     * 12 个参与源中有 10 个是已停用的，点一下标签要等好几分钟才出结果。
     * 没有源仓库时返回 null；读取失败则向上报错，不用「不过滤」掩盖故障。
     */
    private suspend fun enabledSourceIds(): Set<String>? = try {
        sourceRepository?.getSourcesByType(type)?.filter { it.enabled }?.map { it.id }?.toSet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.e("TagSearch", "读取已启用书源失败，type=$type", error)
        throw error
    }

    /**
     * 本次搜索里哪些源走 Venera 引擎（v1.8.8.4）。
     *
     * 这些源的请求必须走 [veneraSemaphore] 的窄额度：引擎只有一个 JS 线程、一条共用
     * 的 HTTP 通道，标签搜索把额度吃满时，用户点开详情就只能干等（实测 30 秒超时）。
     * 读失败就当作「没有 Venera 源」，退化成统一额度，不影响搜索本身。
     */
    private suspend fun veneraIdsOf(): Set<String> = try {
        sourceRepository?.getSourcesByType(type)
            ?.filter { it.format == SourceFormat.VENERA }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w("TagSearch", "读取 Venera 源列表失败，type=$type", error)
        emptySet()
    }

    /**
     * 召回一批任务（v1.8.9.1）—— 调度层、慢源缓存、健康度的唯一入口。
     *
     * 三件事都在这里收口，别在调用点重复实现：
     * - 缓存命中直接返回（慢源第二次搜索就能立刻参与首包）；
     * - 超时 / 报错只记进 [RecallResult.sourceStatus]，**不拉黑源**；
     * - 每批回来就把累计结果推给 [onItems]，界面边出边补。
     */
    private suspend fun recall(
        tasks: List<RecallTask>,
        onItems: suspend (List<MediaItem>) -> Unit = {},
        onBatch: (suspend (RecallBatch) -> Unit)? = null,
    ): RecallResult {
        if (tasks.isEmpty()) return RecallResult(emptyList(), emptyMap())
        val concurrency = tagConcurrency
        val scheduler = TagRecallScheduler(
            globalPool = pageSemaphore,
            batchSize = RECALL_BATCH_SIZE,
            // 窗口按「总并发 / 批大小」推：用户把滑块调大时窗口跟着开，
            // 调小时窗口也跟着收，滑块才真的有效（v1.8.8.4 的教训）
            windowSize = maxOf(2, concurrency / RECALL_BATCH_SIZE),
            requestTimeoutMs = SEARCH_REQUEST_TIMEOUT_MS,
            // 历史超时率偏高的源排到后一波（只调顺序，不跳过）
            slowSources = tasks.map { it.sourceId }.distinct()
                .filter { SourceHealth.isSlow(it) }
                .toSet(),
            execute = { task -> executeRecall(task) },
        )
        addProgressTotal(tasks.size)
        val collected = mutableListOf<MediaItem>()
        val lock = Any()
        val result = scheduler.run(tasks) { batch ->
            repeat(batch.responses.size) { stepProgress() }
            val fresh = batch.responses.flatMap { it.items }
            if (fresh.isNotEmpty()) {
                val snapshot = synchronized(lock) {
                    collected.addAll(fresh)
                    collected.distinctBy { "${it.sourceId}:${it.id}" }
                }
                onItems(snapshot)
            }
            onBatch?.invoke(batch)
        }
        // 诊断日志（v1.8.9.1）：排查「为什么这一屏长这样」时，一眼看出是源没返回、
        // 还是返回了但被判定层挡掉。只记源标识 / 入口种类 / 条数，不记网址。
        result.responses.groupBy { it.task.sourceId }.forEach { (sourceId, list) ->
            android.util.Log.d(
                "TagSearch",
                "源 $sourceId：${list.size} 次请求，共 ${list.sumOf { it.items.size }} 条，" +
                    "终态 ${list.groupingBy { it.outcome }.eachCount()}",
            )
        }
        // 健康度与缓存：只影响后续调度顺序与下次首包，**不参与本次取舍**
        result.responses.forEach { response ->
            SourceHealth.record(response.task.sourceId, response.outcome, response.latencyMs)
            if (response.outcome == RecallOutcome.SUCCESS || response.outcome == RecallOutcome.EMPTY) {
                sourceReturned.add(response.task.sourceId)
            }
            if (response.items.isNotEmpty()) {
                SlowSourceCache.put(
                    SlowSourceCache.key(
                        response.task.sourceId,
                        response.task.term,
                        response.task.page,
                    ),
                    response.items,
                )
            }
        }
        result.sourceStatus.forEach { (sourceId, outcome) -> sourceStatus[sourceId] = outcome }
        // 只统计第一页：翻页 / 预取的源状态会把「N 个源未返回」改得和收尾提示对不上
        if (tasks.any { it.page == 1 }) {
            _slowSourceCount.value = result.sourceStatus.count { it.value.isFailure }
        }
        return result
    }

    /**
     * 按「入口组」召回一页，并把本轮终态落到 [phase]（v1.8.9.1）。
     *
     * 这是首屏与翻页共用的入口：把入口摊平成任务、记下本轮任务表（供慢源重试用）、
     * 收尾时把 pending 归零的结果折算成 [SearchPhase]。
     * **「全部出现」只看这里 —— 任务全部进终态即 done，不看结果是否还在变。**
     */
    private suspend fun recallEntries(
        entriesPerTag: List<List<TagEntry>>,
        page: Int,
        onItems: suspend (List<MediaItem>) -> Unit = {},
        onBatch: (suspend (RecallBatch) -> Unit)? = null,
    ): RecallResult {
        val tasks = entriesPerTag.flatMapIndexed { index, entries -> tasksOf(entries, index, page) }
        val result = recall(tasks, onItems = onItems, onBatch = onBatch)
        // 只有第一页决定 phase：翻页不会把已经 DONE 的搜索打回 STREAMING
        if (page == 1) {
            _phase.value = if (result.partial) SearchPhase.DONE_PARTIAL else SearchPhase.DONE_COMPLETE
        }
        return result
    }

    /**
     * 结果层取舍（v1.8.9.1）：逐条 × 逐标签做三态判定，再交给 [TagResultPolicy] 排序。
     *
     * 这里是**唯一**决定「这条结果留不留、排多前」的地方：
     * - 分类页证明过 → matched；源这次没回话 → unknown；分级词不进标题 → unknown；
     *   标题里确实没有这个题材词 → mismatched（但数量不足 20 条时会兜底补回）。
     * - [sourceReturned] 里有的源不算「没回话」：一条结果既然从它抓回来了，
     *   它别的写法超时只说明「可能还有更多」，不该把已有结果降级。
     *
     * **必须在后台线程调用**（见 [rankOffMain]）：一次搜索要算几万次判定，
     * 放主线程会卡到输入事件超时（v1.8.9.1 真机 ANR 复现过）。
     */
    private fun rankByVerdict(
        items: List<MediaItem>,
        tags: List<String>,
        proven: Map<String, Set<String>>,
        minResults: Int = TagResultPolicy.MIN_RESULTS,
    ): List<MediaItem> {
        if (items.isEmpty() || tags.isEmpty()) return items
        // 写法归一化 / 分级词判定每个标签只算一次，逐条时只剩字符串包含
        val contexts = tags.map(TagVerdicts::contextOf)
        // 源表没拿到时（字段为空）不要一刀切成「都不是成人源」：那会把分级词搜索
        // 直接清空。信息缺失时按旧口径处理，宁可留几条可疑的，也不给空结果页。
        val adultIds = adultSourceIds
        val adultKnown = adultIds.isNotEmpty()
        val candidates = items.map { item ->
            val provenTags = proven[itemKey(item)].orEmpty()
            val outcome = if (item.sourceId in sourceReturned) null else sourceStatus[item.sourceId]
            val title = TagVerdicts.normalizeTitle(item.title)
            val adultSource = !adultKnown || item.sourceId in adultIds
            TagResultPolicy.Candidate(
                item = item,
                verdicts = contexts.map { ctx ->
                    // 分级 / 属性类标签**只认成人源自己的分类页**：普通源的分区里常把
                    // 整站目录挂在任意分类下（实测出现过用一般向作品凑数的源），
                    // 那种「证据」正是「一堆和标签没关系的东西」的来源。
                    val provenHere = ctx.tag in provenTags &&
                        (!ctx.isExplicitAdult || adultSource)
                    ctx.verdict(title, provenHere, outcome, adultSource)
                },
            )
        }
        return TagResultPolicy.rank(candidates, minResults)
    }

    /** 把判定 + 排序挪到后台线程：主线程只负责把结果丢给 Compose */
    private suspend fun rankOffMain(
        items: List<MediaItem>,
        tags: List<String>,
        proven: Map<String, Set<String>>,
        minResults: Int = TagResultPolicy.MIN_RESULTS,
    ): List<MediaItem> = if (items.isEmpty() || tags.isEmpty()) items
    else withContext(Dispatchers.Default) { rankByVerdict(items, tags, proven, minResults) }

    /** 翻页用的阈值：首页已经够多确凿结果时（0）只留确凿的，否则（20）照样兜底全给 */
    private fun pagingMinResults(): Int =
        if (dropUnproven) 0 else TagResultPolicy.MIN_RESULTS

    /** 类型的中文名（只用于给用户看的提示文案） */
    private fun typeLabel(value: SourceType): String = when (value) {
        SourceType.NOVEL -> "小说"
        SourceType.COMIC -> "漫画"
        SourceType.VIDEO -> "视频"
    }

    /**
     * 本地索引结果的**成人源过滤**（v1.8.9.2）。
     *
     * 索引那批不过判定层 —— 它们靠自身标签命中，标题里通常没有标签词，拿标题去否
     * 会把整个本地索引否光。但分级 / 属性类标签是例外：一般向源的条目根本不可能
     * 属于这类题材。而索引里的标签有可能来自早期一次误判（某些站的分类页把整站
     * 目录挂在任意分类下），写进索引后会**自我强化** —— 每次搜索都作为「本地命中」
     * 直接合并进结果，绕过所有判定，用户看到的就是「下面还是一堆无关的」。
     * 这里按源挡一道，成人源缺失时不动（宁可少过滤，也不把索引清空）。
     */
    private fun dropNonAdultForExplicitTags(
        items: List<MediaItem>,
        tags: List<String>,
    ): List<MediaItem> {
        if (items.isEmpty()) return items
        if (tags.none(TagAlias::isExplicitAdultIntent)) return items
        val adultIds = adultSourceIds
        if (adultIds.isEmpty()) return items
        return items.filter { it.sourceId in adultIds }
    }

    /** 收尾提示：有源没回话时如实说明「可能不全」，别让用户以为这就是全部。 */
    private fun partialNote(): String? {
        if (_phase.value != SearchPhase.DONE_PARTIAL) return null
        val slow = _slowSourceCount.value
        return if (slow > 0) "$slow 个源超时或报错，结果可能不全（不代表没有）"
        else "部分源未正常返回，结果可能不全"
    }

    /**
     * 真正发请求。异常**交给调度层**折算终态 —— 这里不许再 catch 成 emptyList。
     *
     * 以前这里把超时和报错都吞成空列表，下游无从分辨「源说没有」和「源没说话」，
     * 于是「慢源」被当成「没结果」，结果越搜越少。现在超时抛出去，由调度层记 TIMEOUT。
     */
    private suspend fun executeRecall(task: RecallTask): List<MediaItem> {
        SlowSourceCache.get(SlowSourceCache.key(task.sourceId, task.term, task.page))
            ?.let { return it }
        val call: suspend () -> List<MediaItem> = {
            withContext(
                RequestBudget(
                    callTimeoutMs = SEARCH_REQUEST_TIMEOUT_MS,
                    queueWaitMs = VENERA_TAG_QUEUE_WAIT_MS,
                    // v1.8.9.2：召回属于后台批量工作。不这么标的话，几十个源的 search
                    // 会全部挤进 Venera 引擎的交互通道，用户点开详情页时 info 抢不到
                    // 通道，实测等到 30008ms 超时 —— 表现就是「点结果一直转圈」。
                    background = true,
                ),
            ) {
                contentRepository.explore(task.sourceId, task.url, task.page)
            }
        }
        // Venera 源必须先过窄额度：让引擎里始终留着位置给详情 / 正文这类交互请求
        return if (task.sourceId in veneraSourceIds) veneraSemaphore.withPermit { call() }
        else call()
    }

    /**
     * 入口是不是「只能按关键词搜」（源站没给这个标签真实分类页）。
     *
     * 判定层要用它区分「分类页证据」和「标题里出现了这个词」——
     * 后者不能当成「作品属于该标签」，但也不该被丢掉（见 [rankByVerdict]）。
     */
    private fun isSearchUrl(url: String): Boolean =
        TagEntryUrl.searchKeywordOf(url) != null ||
            TagEntryUrl.categoryOf(url)?.kind == "search"

    /** 入口 → 召回任务。主词（termPriority 小）先发，长尾后发。 */
    private fun tasksOf(entries: List<TagEntry>, tagIndex: Int, page: Int): List<RecallTask> =
        entries.map { entry ->
            RecallTask(
                tagIndex = tagIndex,
                sourceId = entry.sourceId,
                url = entry.url,
                term = entry.term,
                termPriority = entry.termPriority,
                kind = entry.kind,
                page = page,
            )
        }

    /**
     * 复合标签：**每个标签先取全量并集，再把各并集按标题求交**（v1.7.9.9 口径）。
     *
     * v1.8.9.1 只换了底下的调度层与取舍口径：
     * - 不再「有标签没有分类页就整批退回并集 + 标题硬过滤」，而是照常召回，
     *   由判定层逐词处理（分级词按 unknown 保留，题材词才按标题校验）；
     * - 求交仍然按归一化标题跨源进行（A 站有「无码」、B 站有「全彩」时同名作品要能对上）。
     */
    private suspend fun searchCompound(
        entriesPerTag: List<List<TagEntry>>,
        tags: List<String>,
        onPartial: suspend (List<MediaItem>) -> Unit,
    ): CompoundOutcome {
        if (entriesPerTag.isEmpty() || entriesPerTag.any { it.isEmpty() }) return CompoundOutcome.EMPTY
        val sources = entriesPerTag.flatMap { entries -> entries.map { it.sourceId } }.distinct()
        if (sources.isEmpty()) return CompoundOutcome.EMPTY

        // 只有「真实分类入口」才代表「作品属于这个标签」；关键词入口只是标题里出现了这个词
        val categoryEntries = entriesPerTag.map { list -> list.filter { it.kind == EntryKind.CATEGORY } }
        val withoutCategory = categoryEntries.indices.filter { categoryEntries[it].isEmpty() }

        if (withoutCategory.isNotEmpty()) {
            // 有标签在源里根本没有分类页 → 「组合筛选」在数据层不成立。
            // 如实展示各标签单独搜到的内容，不再用一刀切的标题过滤把它们砍掉，
            // 取舍交给判定层（分级词 unknown 保留 / 题材词按标题校验）。
            val result = recallEntries(entriesPerTag, page = 1, onItems = onPartial)
            val union = result.responses.flatMap { it.items }.distinctBy { "${it.sourceId}:${it.id}" }
            if (union.isEmpty()) return CompoundOutcome.EMPTY
            return CompoundOutcome(
                union,
                "「${withoutCategory.joinToString("」「") { tags[it] }}」在漫画源里没有真实分类页、" +
                    "只能按标题搜，无法与其它标签做组合筛选；下面是各标签单独搜到的内容" +
                    "（无码 / 全彩这类属性常不写进标题，搜不到不代表没有）",
                proven = emptyMap(),
            )
        }

        val strict = strictIntersection(categoryEntries, 1, onPartial)
        return if (strict.matched.isNotEmpty()) {
            CompoundOutcome(
                strict.matched,
                union = strict.unionPerTag,
                proven = provenOf(strict, tags),
            )
        } else {
            CompoundOutcome.EMPTY
        }
    }

    private data class StrictResult(
        val matched: List<MediaItem>,
        val unionPerTag: List<LinkedHashMap<String, MediaItem>>,
        /**
         * 每个大集的「同源 + 同标题」证明键集合（v1.8.4.1）。
         *
         * 求交本身是**跨源**按标题算的，但**打标**不能跟着跨源走：
         * B 站那部同名作品不是同一部，拿它当证据会给 A 站的作品贴错标签。
         */
        val provenKeysPerTag: List<Set<String>> = emptyList(),
    )

    /**
     * 严格求交（单页）：每个大集先取组内并集（组内 OR），再把各大集按作品身份键求交（组间 AND）。
     *
     * v1.8.8.0 起**边收边求交**：每回来一批就并入并集并按 [STREAM_EMIT_INTERVAL_MS]
     * 节流重算一次交集推给 UI —— 宽标签要等最慢的源才能算出完整交集，不流式就是干等。
     */
    private suspend fun strictIntersection(
        entriesPerTag: List<List<TagEntry>>,
        page: Int,
        onPartial: suspend (List<MediaItem>) -> Unit,
        carry: List<LinkedHashMap<String, MediaItem>>? = null,
    ): StrictResult {
        val unionPerTag = carry ?: MutableList(entriesPerTag.size) {
            LinkedHashMap<String, MediaItem>()
        }
        val provenKeys = MutableList(entriesPerTag.size) { mutableSetOf<String>() }
        val emitLock = Any()
        var lastEmitMs = 0L

        recallEntries(
            entriesPerTag,
            page,
            onBatch = { batch ->
                var snapshot: List<List<MediaItem>>? = null
                synchronized(emitLock) {
                    batch.responses.forEach { response ->
                        response.items.forEach { item ->
                            val key = TagQuery.identityKey(item.title)
                                .ifEmpty { "${item.sourceId}:${item.id}" }
                            unionPerTag[response.task.tagIndex].putIfAbsent(key, item)
                            TagProvenance.proofKey(response.task.sourceId, item.title)
                                ?.let { provenKeys[response.task.tagIndex].add(it) }
                        }
                    }
                    val now = android.os.SystemClock.uptimeMillis()
                    if (batch.responses.any { it.items.isNotEmpty() } &&
                        now - lastEmitMs >= STREAM_EMIT_INTERVAL_MS
                    ) {
                        lastEmitMs = now
                        // 锁内只做浅拷贝；求交很贵，挪到 Default 线程（本项目有过 ANR 前科）
                        snapshot = unionPerTag.map { ArrayList(it.values) }
                    }
                }
                snapshot?.let { snap ->
                    val matched = withContext(Dispatchers.Default) { TagQuery.intersect(snap) }
                    onPartial(matched)
                }
            },
        )
        val matched = TagQuery.intersect(unionPerTag.map { it.values.toList() })
        onPartial(matched)
        return StrictResult(matched, unionPerTag, provenKeys)
    }

    /** 成人源内容默认隐藏；用户明确选择成人/修饰标签时视为本次主动请求。 */
    private suspend fun filterAdults(items: List<MediaItem>, tags: List<String>): List<MediaItem> {
        if (items.isEmpty()) return items
        val adultIds = sourceRepository?.getSourcesByType(type)
            ?.filter { it.isAdult }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
        if (adultIds.isEmpty()) return items
        val hide = preferencesManager?.hideAdultFlow?.first() ?: true
        val explicitlyRequested = tags.any(TagAlias::isExplicitAdultIntent)
        return if (hide && !explicitlyRequested) items.filterNot { it.sourceId in adultIds }
        else items.sortedBy { it.sourceId in adultIds }
    }

    companion object {
        /** 分类页上下文打标的置信度（v1.8.1.9）：低于站方自述 100，高于文本猜的 60 */
        private const val CONTEXT_PAGE_CONFIDENCE = 90

        /**
         * Venera 源的并发上限（v1.8.9.2 从 10 收回 4）。
         *
         * 10 路的前提是「异步桥之后多个源是真并行」，但 JS 侧仍是**单线程**：
         * 一次返回 2.3MB JSON 的源在解析期间独占线程，10 路一起冲进来时详情页的
         * `info` 实测排队到 30009ms 直接超时。收到 4 路后单轮慢一些，但用户点开
         * 任何一部作品都能立刻拿到详情 —— 这是更该保的那个。
         */
        private const val VENERA_TAG_CONCURRENCY = 4

        /**
         * Venera 引擎的**排队预算**（v1.8.8.4，毫秒）。
         *
         * 引擎默认允许排队等 25 秒（`ENGINE_QUEUE_WAIT_MS`），但标签搜索单请求只给
         * 8 秒 —— 等到第 20 秒才轮到已经毫无意义，期间还白占着锁拖累后面的源。
         * 2 秒内拿不到锁就快速失败，让位给下一个。
         */
        private const val VENERA_TAG_QUEUE_WAIT_MS = 2_000L

        /** 首次搜索完成后后台预取几页（v1.8.1.2） */
        private const val PRELOAD_PAGES = 3

        /**
         * 连续多少页「整页没有任何新东西」才判定到底（v1.8.8.0，待办 #35）。
         *
         * 判据是**唯一条目数**（`sourceId:id`），不是列表行数 —— 同名作品被折叠时行数不涨，
         * 但多出一个播放源同样算「有新内容」。
         *
         * 设成 2 而不是 1：单页为空可能是某个源偶发超时，再问一页更稳。
         * 也不宜更大 —— 实测视频源对「关键词搜索」普遍忽略页码参数，
         * 第 2 页原样返回第 1 页那批 id，继续问只是让页脚一直转圈、用户等不到东西。
         */
        private const val REPEAT_PAGE_TOLERANCE = 2

        /**
         * 组合搜索流式上屏的最小间隔（v1.8.8.0，待办 #34）。
         *
         * 增量求交要遍历全部并集、每条都算标题身份键（正则 + 繁简），
         * 每来一个响应就算一次会把主线程占死。400ms 一次既能让用户「一边出东西」，
         * 又把重算次数压在个位数。
         */
        private const val STREAM_EMIT_INTERVAL_MS = 400L

        /**
         * 单个召回请求的超时（v1.8.1.1；v1.8.9.1 起由调度层统一计时）。
         *
         * 它同时是 [RequestBudget.callTimeoutMs]（交给 OkHttp 的 `callTimeout`，到点
         * **真中断连接**）和调度层 `withTimeout` 的时长。两者必须一致 ——
         * 调度层先到会把协程取消掉，callTimeout 就白设了。
         *
         * 到点只记 [RecallOutcome.TIMEOUT]（= 未知），**不再等同于「源里没有」**。
         */
        private const val SEARCH_REQUEST_TIMEOUT_MS = 8_000L

        /**
         * 召回批大小（v1.8.9.1）。
         *
         * 近义词按优先级排序后每批 4 个一起发，同时在飞的批次由滑动窗口控制
         * （窗口 = 总并发 / 批大小）。批太小调度开销大、批太大收敛早停不灵敏 ——
         * 4 是「一批回来就能判断有没有新东西」的量级。
         */
        private const val RECALL_BATCH_SIZE = 4

        /** 热门推荐标签上限（v1.6.1.0） */
        const val HOT_TAG_LIMIT = 12

        /**
         * 复合搜索每源翻页上限（v1.7.0.0；v1.8.0.0 起改为「按需翻页」）。
         *
         * 以前固定翻 2 页：够不够都只翻 2 页 —— 结果太少时用户毫无办法。
         * 现在改成双重条件：**交集够 [COMPOUND_ENOUGH_RESULTS] 条就停**（常见情况
         * 只请求 1 页，比过去更快），不够才继续往下翻，最多到本上限。
         * 上限从 2 提到 6：结果空/极少时多翻几页才有机会捞出跨源同作。
         */
        const val MAX_COMPOUND_PAGES = 6

        /**
         * 复合搜索「够了」的阈值（v1.8.0.0）。
         *
         * 一屏网格约 20~30 条，两屏量级即可满足浏览；再多用户也翻不到，
         * 换取的是少跑几轮网络请求。
         */
        const val COMPOUND_ENOUGH_RESULTS = 40
    }
}
