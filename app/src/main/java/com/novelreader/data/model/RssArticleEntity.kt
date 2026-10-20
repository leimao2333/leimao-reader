package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RSS 文章实体（Room，v1.3.1.0 新增）。
 *
 * 去重键为「源 + guid」：同一篇文章重复抓取时命中唯一索引，
 * 插入用 IGNORE 策略，因此已读 / 收藏状态不会被刷新覆盖。
 */
@Entity(
    tableName = "rss_articles",
    indices = [Index(value = ["feedId", "guid"], unique = true)],
)
data class RssArticleEntity(
    @PrimaryKey(autoGenerate = true)
    val articleId: Long = 0,
    /** 所属订阅源 */
    val feedId: Long,
    /** 去重标识（guid / id / 链接 / 标题+时间） */
    val guid: String,
    val title: String = "",
    /** 原文链接 */
    val link: String = "",
    val author: String = "",
    /** 发布时间（毫秒，0 = 未知） */
    val pubDate: Long = 0,
    /** 纯文本摘要（列表页展示） */
    val summary: String = "",
    /** 正文 HTML（阅读页渲染） */
    val contentHtml: String = "",
    /** 首图（列表缩略图） */
    val imageUrl: String = "",
    /**
     * 论文 DOI（v1.8.5.0；非期刊文章为空）。
     *
     * 单独存一列而不是只塞进 [paperMetaJson]：按 DOI 去重、按 DOI 查 Crossref
     * 都要能直接查，不必反序列化 JSON。
     */
    val doi: String = "",
    /**
     * 论文题录 JSON（v1.8.5.0；`PaperMeta` 序列化，空 = 非期刊文章）。
     *
     * 用 JSON 而不是拆成十几列：题录字段将来还会加（基金、引用数、ORCID…），
     * 拆列每加一个字段就要一次迁移，代价远大于收益。
     */
    val paperMetaJson: String = "",
    /** 已读标记 */
    val read: Boolean = false,
    /** 收藏标记 */
    val starred: Boolean = false,
    /** 抓取入库时间 */
    val fetchedAt: Long = System.currentTimeMillis(),
)
