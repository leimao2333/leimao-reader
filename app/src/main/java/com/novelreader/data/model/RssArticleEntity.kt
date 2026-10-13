package com.novelreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RSS 文章实体（Room，v1.4.0 新增）。
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
    /** 已读标记 */
    val read: Boolean = false,
    /** 收藏标记 */
    val starred: Boolean = false,
    /** 抓取入库时间 */
    val fetchedAt: Long = System.currentTimeMillis(),
)
