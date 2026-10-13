package com.novelreader.domain.repository

import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceInfo
import kotlinx.coroutines.flow.Flow

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

    /** 删除一个源 */
    suspend fun deleteSource(sourceId: String)

    /** 启用 / 禁用源 */
    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean)

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
     */
    fun searchWithProgress(
        type: com.novelreader.domain.model.SourceType,
        keyword: String,
        page: Int = 1,
        concurrency: Int = 6,
        fuzzyEnabled: Boolean = true,
    ): Flow<SearchState>

    /** 获取详情 */
    suspend fun detail(item: MediaItem): MediaItem

    /** 获取目录 */
    suspend fun chapterList(item: MediaItem): List<ChapterItem>

    /** 获取正文 */
    suspend fun content(chapter: ChapterItem): ContentResult

    // ===== 发现页（v1.2.3.0 对标 legado exploreUrl/ruleExplore） =====

    /** 有发现分类的已启用源列表（分类惰性加载：仅解析源 JSON，不发请求） */
    suspend fun exploreSources(type: com.novelreader.domain.model.SourceType): List<Pair<SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>>

    /** 按发现分类拉取书籍列表 */
    suspend fun explore(sourceId: String, url: String, page: Int): List<MediaItem>
}
