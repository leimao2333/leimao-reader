package com.novelreader.domain.repository

import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceInfo
import kotlinx.coroutines.flow.Flow

/**
 * 一轮「各源发现分类枚举」的结果。
 *
 * 除了 `源 → 该源的分类表`，还带上**这轮枚举实际覆盖了多少个源**（[enumerated]）——
 * 冷启动时源是从数据库异步恢复的，推荐页若在恢复完成前就触发枚举，只会看到当时
 * 已注册的零星几个源，聚合结果退化成「聚合 1 个源」（v1.7.2.0 实测：缓存里 10 排
 * 内容全部来自同一个源，且被落盘缓存固化成常态）。调用方拿这个数字跟当前已启用
 * 源数一比就知道这轮结果可不可信、能不能落盘。
 */
data class SourceEnumeration(
    /** 源 → 该源的发现分类（无分类的源不出现在这里） */
    val items: List<Pair<SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>> = emptyList(),
    /**
     * 本轮**实际参与枚举**的源数（已启用源数，受 `limit` 截断）。
     *
     * 注意是「进过枚举循环」的源数，不等于「有分类的源数」——
     * 大多数源本来就没有 `exploreUrl`，[items] 比它小是正常的。
     */
    val enumerated: Int = 0,
)

/**
 * 源仓库接口（Domain 层定义，Data 层实现）。
 *
 * 负责源的存储、加载、解析后的缓存，以及对外提供统一的查询入口。
 */
interface SourceRepository {

    /** 获取所有已导入的源 */
    suspend fun getAllSources(): List<SourceInfo>

    /** 按类型获取源 */
    suspend fun getSourcesByType(type: com.novelreader.domain.model.SourceType): List<SourceInfo>

    /** 导入一个源（JSON / JS / legado 原始内容），返回导入的所有源 */
    suspend fun importSource(rawContent: String, fileName: String? = null): List<SourceInfo>

    /**
     * 解析源（不注册），返回解析出的候选源元信息（v1.4.7.0「可选添加」用）。
     *
     * 当一个内容展开成多个源时（legado 列表 / MoonTV 配置），先调用本方法拿到候选列表，
     * 供用户在 UI 勾选要添加哪些，再通过 [importSelected] 批量注册勾选的源。
     */
    suspend fun previewImport(rawContent: String, fileName: String? = null): List<SourceInfo>

    /**
     * 批量解析多个源文件（聚合包导入用，v1.5.6.0）。
     *
     * 与 [previewImport] 的区别：候选缓存**只在开头清空一次**，各文件结果累积，
     * 用户可在一份勾选列表里跨文件挑选；连调 [previewImport] 只有最后一个文件的
     * 候选能注册（每次都 `clear()`）。
     *
     * @param files 已下载好的各子包（文件名 + 内容）
     * @return 合并后的候选列表 + 失败/跳过统计
     */
    suspend fun previewImportBatch(files: List<com.novelreader.domain.model.SourceFileContent>):
        com.novelreader.domain.model.SourceImportBatch

    /**
     * 批量注册用户勾选的源（v1.4.7.0「可选添加」）。
     *
     * @param selected 勾选源的 [SourceInfo.id] 集合（id 来自 [previewImport] 返回结果）
     * @return 实际注册的源数量
     */
    suspend fun importSelected(ids: Set<String>): Int

    /**
     * 等待启动时的源恢复完成（v1.4.8.0 冷启动竞态防护）。
     *
     * 恢复已完成时立即返回；未完成则挂起直到恢复结束（无论成败都放行）。
     * 首页/搜索等在启动早期读取源列表的入口应先调用本方法，避免拿到空源列表。
     */
    suspend fun awaitRestored()

    /** 删除一个源 */
    suspend fun deleteSource(sourceId: String)

    /**
     * 批量删除多个源（单次写库，只触发一次 invalidation，v1.5.5.2）。
     *
     * 供「一键删除失效源」使用：循环 [deleteSource] 会触发多次 Room invalidation，
     * 与 observeAll() Flow 重新查询并发导致 Cursor 读失效闪退。
     */
    suspend fun deleteSources(ids: List<String>)

    /** 启用 / 禁用源 */
    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean)

    /**
     * 合并重复源（v1.6.6.0）。
     *
     * 同一站点被重复导入时会出现「同名 + 同站」的重复条目（聚合源包里常见，
     * `baseUrl` 往往只差一个结尾斜杠）。按「同类型 + 同名 + 归一化 baseUrl」
     * 分组，每组保留一条（启用优先、规则更完整者优先，见
     * [com.novelreader.core.util.SourceDedupe]），其余删除。
     *
     * baseUrl 为空的源（Venera / 内置）不参与——只靠名字判重会误伤
     * （多个站点不同但都叫「笔趣阁」的源必须各自保留）。
     *
     * @return 被合并（删除）的源数量
     */
    suspend fun dedupeDuplicateSources(): Int = 0

    /**
     * 预览可合并的重复源数量（只读，不写库；源管理页按钮文案用）。
     */
    suspend fun countDuplicateSources(): Int = 0

    /**
     * 设置单个源的手动排序序号（v1.5.8.0）。
     */
    suspend fun setSourceSortOrder(sourceId: String, sortOrder: Int)

    /**
     * 按给定 id 顺序批量重写手动排序序号（v1.5.8.0）。
     *
     * 传入列表的顺序即用户手动排好的顺序，内部按 `1..n` 依次赋序号。
     * 不在列表里的源序号清 0（回退自动排序）。
     */
    suspend fun reorderSources(orderedIds: List<String>)

    /** 获取源对应的适配器（供后续调用 search/detail 等）；源不存在时返回 null */
    suspend fun getAdapter(sourceId: String): com.novelreader.domain.model.SourceAdapter?

    /**
     * 获取源的原始规则内容（用于编辑）。
     * @return 原始文本；源不存在或未保存原始内容时返回 null
     */
    suspend fun getRawContent(sourceId: String): String?

    /**
     * 更新源的规则内容（覆盖原始内容并重新解析注册）。
     *
     * 编辑保存后，源适配器会按新规则重建，立即生效。
     * @return 更新后的 [SourceInfo]；解析失败时抛异常（保留原源不变）
     */
    suspend fun updateSource(sourceId: String, rawContent: String): SourceInfo
}

/**
 * 内容仓库接口。
 *
 * 聚合多个源，提供跨源的内容查询能力（搜索、详情、目录、正文）。
 */
interface ContentRepository {

    /** 在指定类型的多个源中搜索 */
    suspend fun search(
        type: com.novelreader.domain.model.SourceType,
        keyword: String,
        page: Int = 1,
    ): List<MediaItem>

    /**
     * 带进度的跨源搜索。
     *
     * 每搜完一个源就 emit 一次增量 [SearchState]（结果累加 + 进度更新），
     * 供 UI 边搜边展示进度、源名和实时结果。
     *
     * @param concurrency 并行搜索线程数（同时搜索的源数量）
     * @param fuzzyEnabled 是否启用模糊匹配排序/过滤；false 时保持源返回原序
     * @param sourceIds **只搜这些源**（null = 全部已启用源）。「按源搜索」用（v1.8.1.1）
     */
    fun searchWithProgress(
        type: com.novelreader.domain.model.SourceType,
        keyword: String,
        page: Int = 1,
        concurrency: Int = 6,
        fuzzyEnabled: Boolean = true,
        sourceIds: Set<String>? = null,
    ): Flow<SearchState>

    /** 获取详情 */
    suspend fun detail(item: MediaItem): MediaItem

    /** 获取目录 */
    suspend fun chapterList(item: MediaItem): List<ChapterItem>

    /** 获取正文 */
    suspend fun content(chapter: ChapterItem): ContentResult

    /**
     * 图片加载失败后的**备用地址**（v1.7.1.0）。
     *
     * Venera 源可用 `onImageLoad → onLoadFailed` 声明多镜像回退（禁漫天堂内置 5 个
     * 备用域名）。此前该能力完全没接：Coil 按原地址加载失败就只能显示「图片加载失败」，
     * 第一个域名被墙 == 整章都看不了。默认实现返回 null（不支持的源无影响）。
     *
     * @return (新地址, 该地址需要的请求头)；没有更多回退时返回 null
     */
    suspend fun fallbackImage(sourceId: String, url: String): Pair<String, Map<String, String>>? = null

    /**
     * 获取**全部线路**的剧集（v1.3.6.0 播放页「换源」）。
     *
     * 默认实现把 [chapterList] 包成单条线路，单线路源无需改动。
     */
    suspend fun chapterLines(item: MediaItem): List<com.novelreader.domain.model.ChapterLine> {
        val chapters = chapterList(item)
        return if (chapters.isEmpty()) {
            emptyList()
        } else {
            listOf(
                com.novelreader.domain.model.ChapterLine(
                    com.novelreader.domain.model.ChapterLine.DEFAULT_NAME,
                    chapters,
                )
            )
        }
    }

    // ===== 发现页（v1.2.3.0 对标 legado exploreUrl/ruleExplore） =====

    /**
     * 有发现分类的源列表（分类惰性加载：仅解析源 JSON，不发请求）。
     *
     * @param includeDisabled true = 包含已禁用的源（v1.6.5.0 标签池用：
     *   标签收集要涵盖**每一个源**的小说 / 漫画 / 视频，用户临时禁用的源
     *   其分类仍是有效标签；发现页等场景保持默认 false）。
     */
    suspend fun exploreSources(
        type: com.novelreader.domain.model.SourceType,
        includeDisabled: Boolean = false,
    ): List<Pair<SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>>

    /**
     * 同 [exploreSources]，但**只枚举前 `limit` 个源**（v1.7.1.0）。
     *
     * 首页「推荐」只需要十来行横滑，没必要把全部源都问一遍 —— 漫画源里 Venera 占大半，
     * 取分类要在隐藏 WebView 里真实评估源声明的分类表，36 个源逐个跑要几十秒，
     * 且都是主线程派发的活（引擎的 `evaluateJavascript` 必须走主线程），
     * 冷启动时容易把界面拖到 ANR。
     *
     * 默认实现退化为「枚举完再截断」：测试假实现无需跟着改。
     */
    suspend fun exploreSourcesLimited(
        type: com.novelreader.domain.model.SourceType,
        limit: Int,
        includeDisabled: Boolean = false,
    ): List<Pair<SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>> =
        if (limit <= 0) exploreSources(type, includeDisabled)
        else exploreSources(type, includeDisabled).take(limit)

    /**
     * 首页推荐专用的**容错枚举**（v1.7.2.0）。
     *
     * 与 [exploreSourcesLimited] 的区别是「慢源不丢」：
     * - 每个源的取分类有**独立超时**，不是整批共用一个超时；
     * - 运行时评估超时 / 报错的源，用源码里的**静态分类表**兜底
     *   （见 [com.novelreader.domain.model.SourceAdapter.fastExploreCategories]），
     *   于是 Venera 源不会因为一次 WebView 评估慢就整源消失；
     * - 整轮有全局截止，到点用已有结果收工。
     *
     * 返回值比 [exploreSources] 多带一个 [SourceEnumeration.enumerated]，
     * 用来识别「源还没恢复完就开始枚举」的冷启动竞态（见 [SourceEnumeration]）。
     * 默认实现退化为 [exploreSourcesLimited]，测试假实现无需跟着改。
     */
    suspend fun exploreSourcesFast(
        type: com.novelreader.domain.model.SourceType,
        limit: Int = 0,
        includeDisabled: Boolean = false,
    ): SourceEnumeration {
        val items = exploreSourcesLimited(type, limit, includeDisabled)
        return SourceEnumeration(items = items, enumerated = items.size)
    }

    /** 按发现分类拉取书籍列表 */
    suspend fun explore(sourceId: String, url: String, page: Int): List<MediaItem>

    /**
     * 按发现分类 + 筛选条件拉取列表（v1.3.5.0 片库四维筛选）。
     *
     * [filters] 目前由影视模块使用，键为苹果 CMS 参数名（`area` / `year` / `order`），
     * 值为空串的项不提交。默认空 map = 等价于 [explore]。
     */
    suspend fun exploreFiltered(
        sourceId: String,
        url: String,
        page: Int,
        filters: Map<String, String> = emptyMap(),
    ): List<MediaItem> = explore(sourceId, url, page)

    /**
     * 列表页封面补全（v1.8.2.0）。
     *
     * 有些源的 `ruleExplore` / `ruleSearch` 压根没写 `coverUrl`（实测 138 个小说源里
     * 36 个如此，企鹅阅读、52PO小说、海马读书…），于是一整排卡片全是「书名首字」色块；
     * 但这些源的 `ruleBookInfo` **是有封面的**。此前只靠 [com.novelreader.core.util.CoverMemory]
     * 在「用户点过一次详情」之后才补得上，首次进首页看到的仍是占位块。
     *
     * 这里主动出击：对 [items] 里**没有封面**的条目逐条去取详情，取到后写进
     * CoverMemory 并回填进返回列表。调用方应当**后台**执行（不阻塞上屏）。
     *
     * @param limit 单次最多补几条（0 = 不限）。列表可能上百条，必须设上限：
     *   每条都要打一次详情页请求，补 100 条等于把首屏变成一次全量抓取。
     * @return 补过封面的新列表；一条都没补到时原样返回。
     */
    suspend fun backfillCovers(items: List<MediaItem>, limit: Int = 0): List<MediaItem> = items

    /**
     * 取**单个源**的发现分类（v1.3.3.0 影视首页按当前源加载分类）。
     *
     * 与 [exploreSources] 不同：只查一个源，避免影视首页在源较多时
     * 逐个请求全部分类导致长时间等待。
     */
    suspend fun exploreCategoriesOf(sourceId: String): List<com.novelreader.domain.model.ExploreCategory>

    /**
     * 取**单个源**的发现分类**完整层级树**（v1.6.7.0）。
     *
     * 与 [exploreCategoriesOf] 的区别只有一个：带不带二级分类。
     * 影视首页的「类型 → 分类」二级下拉需要树；分区标题 / 标签池 / 频道筛选
     * 仍然只要一级（用 [exploreCategoriesOf]）。
     *
     * 默认实现退回 [exploreCategoriesOf]：测试假实现无需跟着改。
     */
    suspend fun exploreCategoryTreeOf(sourceId: String): List<com.novelreader.domain.model.ExploreCategory> =
        exploreCategoriesOf(sourceId)

    /**
     * 该类型下适合「按题材搜索」的源 id（v1.6.6.0 漫画标签补题材词用）。
     *
     * 返回顺序即推荐顺序：**中文名源优先**（题材词是中文，这类源命中率高）、
     * 其次已启用的源；同档内保持管理器原有顺序（手动排序 / 名称序）。
     *
     * @param limit 最多返回几个；<= 0 表示不限（调用方按等待时长自行裁剪。
     *   [com.novelreader.core.util.ComicGenres.MAX_SOURCES_PER_GENRE] 就是为此设的）。
     *
     * 默认实现返回空列表：测试假实现无需跟着改（同 [exploreFiltered] 的套路）。
     */
    suspend fun searchableSourceIds(
        type: com.novelreader.domain.model.SourceType,
        limit: Int = 0,
    ): List<String> = emptyList()
}
