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
    /** 其他扩展字段（预留冗余，各源可自定义） */
    val extras: Map<String, String> = emptyMap(),
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
    /** 视频播放地址 */
    val videoUrl: String? = null,
    /** 视频播放所需的额外请求头（防盗链等） */
    val videoHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        fun novel(text: String) = ContentResult(novelText = text)
        fun comic(images: List<String>) = ContentResult(comicImages = images)
        fun video(url: String, headers: Map<String, String> = emptyMap()) =
            ContentResult(videoUrl = url, videoHeaders = headers)
    }
}
