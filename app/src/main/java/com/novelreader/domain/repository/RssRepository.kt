package com.novelreader.domain.repository

import com.novelreader.domain.model.RssArticle
import com.novelreader.domain.model.RssFeed
import com.novelreader.domain.model.RssFeedResult
import kotlinx.coroutines.flow.Flow

/**
 * RSS 订阅仓库（v1.3.1.0）。
 *
 * 独立于 [ContentRepository]：订阅源是「地址 + 文章流」模型，
 * 没有搜索 / 详情 / 换源语义，因此单独一条链路，不与书源体系耦合。
 */
interface RssRepository {

    /** 全部订阅源（含未读数），按排序序号 */
    fun observeFeeds(): Flow<List<RssFeed>>

    /**
     * 文章列表。
     * @param feedId null = 所有源汇总（按时间倒序）
     */
    fun observeArticles(feedId: Long? = null): Flow<List<RssArticle>>

    /** 各源未读数：feedId -> 未读数 */
    fun observeUnreadCounts(): Flow<Map<Long, Int>>

    /** 未读总数 */
    fun observeUnreadTotal(): Flow<Int>

    suspend fun getArticle(articleId: Long): RssArticle?

    /** 单篇文章（响应式，已读/收藏状态变化会实时推送） */
    fun observeArticle(articleId: Long): Flow<RssArticle?>

    /** 某源文章快照（阅读页「上一篇 / 下一篇」用） */
    suspend fun articlesOfFeed(feedId: Long): List<RssArticle>

    /**
     * 添加订阅。
     *
     * - 地址是订阅源 → 直接入库
     * - 地址是站点首页 → 自动发现页面里的订阅地址
     * - 已订阅同一地址 → 原地刷新，不重复建条目
     */
    suspend fun addFeed(url: String): RssFeedResult

    /** 批量添加（并发抓取，逐条返回结果） */
    suspend fun addFeeds(urls: List<String>): List<RssFeedResult>

    /** 导入 OPML 订阅列表 */
    suspend fun addOpml(opml: String): List<RssFeedResult>

    /** 刷新单个源（增量入库，保留已读/收藏状态） */
    suspend fun refreshFeed(feedId: Long): RssFeedResult

    /** 刷新全部源（并发，最多 3 路） */
    suspend fun refreshAll(): List<RssFeedResult>

    /** 重命名订阅 */
    suspend fun renameFeed(feedId: Long, title: String)

    /** 删除订阅（连同其文章） */
    suspend fun deleteFeed(feedId: Long)

    /** 标记单篇已读 / 未读 */
    suspend fun markRead(articleId: Long, read: Boolean)

    /** 切换收藏 */
    suspend fun toggleStar(articleId: Long)

    /** 全部标记已读；传 feedId 则只标记该源 */
    suspend fun markAllRead(feedId: Long? = null)
}
