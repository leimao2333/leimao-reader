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
     */
    suspend fun exploreCategories(): List<ExploreCategory> = emptyList()

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
 * 发现分类（v1.2.3.0）。
 */
data class ExploreCategory(
    val title: String,
    val url: String,
)