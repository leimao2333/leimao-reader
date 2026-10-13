package com.novelreader.domain.model

/**
 * RSS 订阅源（领域模型，v1.4.0）。
 */
data class RssFeed(
    val feedId: Long,
    val url: String,
    val title: String,
    val siteUrl: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val groupName: String = "",
    /** 最近抓取时间（0 = 从未） */
    val lastFetchAt: Long = 0,
    /** 最近抓取失败原因（空 = 正常） */
    val lastError: String = "",
    val createdAt: Long = 0,
    /** 未读数 */
    val unreadCount: Int = 0,
)

/**
 * RSS 文章（领域模型，v1.4.0）。
 *
 * 冗余带上 [feedTitle] / [feedIcon]，便于「全部文章」流里直接展示来源。
 */
data class RssArticle(
    val articleId: Long,
    val feedId: Long,
    val title: String,
    val link: String = "",
    val author: String = "",
    val pubDate: Long = 0,
    val summary: String = "",
    val contentHtml: String = "",
    val imageUrl: String = "",
    val read: Boolean = false,
    val starred: Boolean = false,
    val feedTitle: String = "",
    val feedIcon: String = "",
)

/**
 * 订阅 / 刷新单个源的结果。
 *
 * 不做异常抛出：RSS 是「批量抓取」场景，一个源挂了不该中断其余源，
 * 因此失败以 [error] 形式返回，由 UI 汇总提示。
 */
data class RssFeedResult(
    val feedId: Long,
    val title: String,
    val url: String,
    /** 本次新增文章数 */
    val newArticles: Int = 0,
    /** 失败原因（空 = 成功） */
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}
