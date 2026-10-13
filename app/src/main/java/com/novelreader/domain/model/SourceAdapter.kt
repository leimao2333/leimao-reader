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
     * 获取正文内容。
     */
    suspend fun content(chapter: ChapterItem): ContentResult

    /**
     * 有效性自检（可选实现）。用于导入源后测试是否可用。
     */
    suspend fun test(): Boolean = true

    // ===== 发现页（v1.2.3 对标 legado exploreUrl/ruleExplore） =====

    /**
     * 发现分类列表（可选实现）。源未配置 exploreUrl 时返回空列表。
     */
    suspend fun exploreCategories(): List<ExploreCategory> = emptyList()

    /**
     * 按发现分类地址拉取书籍列表（规则同搜索，用 ruleExplore）。
     * @param url 分类地址（可能含 {{page}} 等占位符）
     */
    suspend fun explore(url: String, page: Int = 1): List<MediaItem> = emptyList()
}

/**
 * 发现分类（v1.2.3）。
 */
data class ExploreCategory(
    val title: String,
    val url: String,
)
