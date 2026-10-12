package com.novelreader.domain.model

/**
 * 内容条目（搜索/发现/详情的结果项）。
 *
 * 小说、漫画、视频三类共用此模型，用 [type] 区分。
 */
data class MediaItem(
    /** 唯一 ID（源内） */
    val id: String,
    /** 标题 */
    val title: String,
    /** 封面图地址 */
    val cover: String? = null,
    /** 作者 / up主 / 出版社 */
    val author: String? = null,
    /** 简介 */
    val desc: String? = null,
    /** 内容类型 */
    val type: SourceType,
    /** 详情页地址（用于后续请求目录/正文） */
    val detailUrl: String = id,
    /** 所属源 ID */
    val sourceId: String = "",
    /** 所属源名称（用于搜索结果展示来源） */
    val sourceName: String = "",
    /** 其他扩展字段（预留冗余，各源可自定义） */
    val extras: Map<String, String> = emptyMap(),
    /** 收藏时间戳（书架条目带出，用于「按加入时间」排序；搜索结果为 0） */
    val addedAt: Long = 0,
    /** 书架分组名（书架条目带出，空 = 未分组，v1.3.0） */
    val groupName: String = "",
    /**
     * legado 变量表（`@put:{k:v}` 写入 / `@get:{k}` 读取）。
     *
     * 搜索时按「单条结果」独立求值并带出，供后续详情 / 目录 / 正文步骤使用。
     * 很多 JSON API 源依赖它：搜索规则里 `@put:{bid:$.comic_id}` 存下作品 ID，
     * 目录地址再用 `...&comic_id=@get:{bid}` 取回，否则目录会请求到错误地址而空白。
     */
    val variableMap: Map<String, String> = emptyMap(),
)

/**
 * 章节 / 目录项。
 *
 * 小说=章节，漫画=话，视频=集/选集。
 */
data class ChapterItem(
    /** 章节唯一 ID */
    val id: String,
    /** 章节标题 */
    val title: String,
    /** 章节内容地址 */
    val url: String,
    /** 排序序号 */
    val index: Int = 0,
    /** 所属源 ID（用于定位源适配器） */
    val sourceId: String = "",
)

/**
 * 正文内容结果。
 *
 * 根据类型，对应字段生效：
 * - NOVEL → [novelText]
 * - COMIC → [comicImages]
 * - VIDEO → [videoUrl]
 */
data class ContentResult(
    /** 小说正文 */
    val novelText: String? = null,
    /** 漫画图片地址列表 */
    val comicImages: List<String>? = null,
    /** 漫画图片加载所需的请求头（防盗链 Referer 等） */
    val comicHeaders: Map<String, String> = emptyMap(),
    /** 视频播放地址 */
    val videoUrl: String? = null,
    /** 视频播放所需的额外请求头（防盗链等） */
    val videoHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        fun novel(text: String) = ContentResult(novelText = text)
        fun comic(images: List<String>, headers: Map<String, String> = emptyMap()) =
            ContentResult(comicImages = images, comicHeaders = headers)
        fun video(url: String, headers: Map<String, String> = emptyMap()) =
            ContentResult(videoUrl = url, videoHeaders = headers)
    }
}

/**
 * 最近阅读条目（首页「最近阅读」列表用）。
 *
 * 由阅读进度 + 书架信息合并而来，用于快速回到上次阅读位置。
 */
data class RecentReading(
    /** 源 ID */
    val sourceId: String,
    /** 源内条目 ID */
    val itemId: String,
    /** 标题 */
    val title: String,
    /** 封面 */
    val cover: String? = null,
    /** 作者 */
    val author: String? = null,
    /** 内容类型 */
    val type: SourceType,
    /** 详情地址 */
    val detailUrl: String = itemId,
    /** 最后阅读的章节标题 */
    val chapterTitle: String,
    /** 章节索引 */
    val chapterIndex: Int = 0,
    /** 章节总数（用于计算已读百分比；0 表示未知） */
    val totalChapter: Int = 0,
    /** 更新时间戳 */
    val updatedAt: Long = 0L,
)
