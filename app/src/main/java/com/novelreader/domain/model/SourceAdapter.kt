package com.novelreader.domain.model

/**
 * 源适配器接口。
 *
 * 这是整个系统的核心抽象：上层（UI/ViewModel/Repository）只依赖此接口，
 * 不关心源底层是 JSON 还是 JS。新增源格式只需新增一个 Adapter 实现。
 *
 * 所有方法均为挂起函数，内部负责网络请求 / JS 执行 / 数据解析。
 */
interface SourceAdapter {

    /** 源元信息 */
    val info: SourceInfo

    /**
     * 搜索。
     * @param keyword 关键词
     * @param page 页码（从 1 开始）
     */
    suspend fun search(keyword: String, page: Int = 1): List<MediaItem>

    /**
     * 获取详情。
     * @param item 已获得的条目（或仅提供 detailUrl）
     */
    suspend fun detail(item: MediaItem): MediaItem

    /**
     * 获取目录 / 章节列表。
     */
    suspend fun chapterList(item: MediaItem): List<ChapterItem>

    /**
     * 获取**全部线路**的剧集（v1.3.6.0 播放页「换源」）。
     *
     * 默认实现把 [chapterList] 包装成单条线路 —— 小说 / 漫画与只提供单线路的源
     * 无需改动即可支持「换源」 Tab（Tab 里只有一条线路）。
     * 多线路源（苹果 CMS 的 `vod_play_from`）自行覆写，返回全部线路。
     *
     * 约定：**返回顺序即推荐顺序**，调用方默认使用第一条。
     */
    suspend fun chapterLines(item: MediaItem): List<ChapterLine> {
        val chapters = chapterList(item)
        return if (chapters.isEmpty()) {
            emptyList()
        } else {
            listOf(ChapterLine(ChapterLine.DEFAULT_NAME, chapters))
        }
    }

    /**
     * 获取正文内容。
     */
    suspend fun content(chapter: ChapterItem): ContentResult

    /**
     * 有效性自检（可选实现）。用于导入源后测试是否可用。
     */
    suspend fun test(): Boolean = true

    // ===== 发现页（v1.2.3.0 对标 legado exploreUrl/ruleExplore） =====

    /**
     * 发现分类列表（可选实现）。源未配置 exploreUrl 时返回空列表。
     *
     * 约定：只返回**一级分类**（[ExploreCategory.parent] 为空的那些）。
     */
    suspend fun exploreCategories(): List<ExploreCategory> = emptyList()

    /**
     * 发现分类的**廉价枚举**（v1.7.2.0，首页推荐跨源聚合用）。
     *
     * 语义与 [exploreCategories] 相同（一级分类名 + 该源内的分类地址），区别只在
     * **允许省略昂贵的运行时求值**：
     *
     * legado 源的分类本来就写在 `exploreUrl` 里，解析是纯本地字符串处理，零成本，
     * 默认实现直接复用 [exploreCategories]；而 Venera 源的 `category.parts` 要在
     * 隐藏 WebView 里真实评估（`dynamic` part 还会调用 loader 联网），25 个漫画源
     * 逐个跑就是几十秒的主线程派发，冷启动足以把首页拖到 ANR —— 这类源应当改用
     * 源码里的**静态分类表**（见 `VeneraSourceAdapter` 的覆写）。
     *
     * 调用方（首页推荐）需要的是「有哪些栏目、每个源各自的地址是什么」，
     * 静态表已足够；点进栏目后真正取内容时仍走源自己的正常链路。
     */
    suspend fun fastExploreCategories(): List<ExploreCategory> = exploreCategories()

    /**
     * 发现分类**完整层级树**（v1.6.7.0）。
     *
     * 部分源天然带父子分类（苹果 CMS 的 `class[].type_pid`：一级「电影 / 电视剧 /
     * 动漫 …」，二级「动作片 / 喜剧片 / 欧美剧 …」）。上层「类型 → 分类」二级下拉
     * 需要这层信息，所以单独开一个方法，避免污染 [exploreCategories] 的既有语义
     * （分区标题、标签池、频道筛选都只认一级分类）。
     *
     * 默认实现 = [exploreCategories]（无层级概念的源，全部条目都算一级）。
     */
    suspend fun exploreCategoryTree(): List<ExploreCategory> = exploreCategories()

    /**
     * 按发现分类地址拉取书籍列表（规则同搜索，用 ruleExplore）。
     * @param url 分类地址（可能含 {{page}} 等占位符）
     */
    suspend fun explore(url: String, page: Int = 1): List<MediaItem> = emptyList()

    /**
     * 带筛选的发现（v1.3.5.0 片库四维筛选）。
     *
     * 默认**忽略** [filters] 直接走 [explore]；支持筛选的源自行覆写
     * （如苹果 CMS 的 `area` / `year` / `order` 参数）。
     * [filters] 里值为空串的项一律不提交，等价于「不限」。
     */
    suspend fun exploreFilter(
        url: String,
        page: Int = 1,
        filters: Map<String, String> = emptyMap(),
    ): List<MediaItem> = explore(url, page)
}

/**
 * 发现分类（v1.2.3.0；v1.6.7.0 增加 [parent]）。
 *
 * **注意 [url] 是「源内地址」**：苹果 CMS 里就是该源的 `type_id`（`1` / `2` / `9` …），
 * 只在**同一个源内部**唯一。跨源聚合时**绝不能用它当身份**——不同源的同名分类地址不同、
 * 不同分类也可能撞同一个数字，必须用 [title]（分类名）作身份，把每个源各自的 [url]
 * 单独存起来（见 [CategorySource]）。
 */
data class ExploreCategory(
    val title: String,
    val url: String,
    /**
     * 父级分类名（`""` = 一级分类 / 无父级）。
     *
     * v1.6.7.0：视频苹果 CMS 源用它做「类型 → 分类」二级下拉。
     * 注意存的是**父级名字**而非父级 id —— 名字才是跨源稳定的。
     */
    val parent: String = "",
)

/**
 * 某个分类在**某一个源**里的地址（v1.6.7.0）。
 *
 * 分类的跨源身份是分类名；每个源对同一个分类名有各自的 [url]
 * （苹果 CMS 是 `type_id`）。聚合查询时逐源拿自己的 [url] 去请求，
 * 这样「点 A 分类却出来 B 分类内容」的问题才不会发生。
 */
data class CategorySource(
    val sourceId: String = "",
    val url: String = "",
)