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
}
