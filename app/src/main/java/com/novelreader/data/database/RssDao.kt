package com.novelreader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novelreader.data.model.RssArticleEntity
import com.novelreader.data.model.RssFeedEntity
import kotlinx.coroutines.flow.Flow

/**
 * 某源的未读数（聚合查询结果）。
 */
data class FeedUnreadCount(
    val feedId: Long,
    val count: Int,
)

/**
 * RSS 订阅 DAO（v1.3.1.0）。
 */
@Dao
interface RssDao {

    // ==================== 订阅源 ====================

    @Query("SELECT * FROM rss_feeds ORDER BY sortOrder ASC, feedId ASC")
    fun observeFeeds(): Flow<List<RssFeedEntity>>

    @Query("SELECT * FROM rss_feeds ORDER BY sortOrder ASC, feedId ASC")
    suspend fun getAllFeeds(): List<RssFeedEntity>

    @Query("SELECT * FROM rss_feeds WHERE feedId = :feedId LIMIT 1")
    suspend fun getFeed(feedId: Long): RssFeedEntity?

    @Query("SELECT * FROM rss_feeds WHERE url = :url LIMIT 1")
    suspend fun getFeedByUrl(url: String): RssFeedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeed(feed: RssFeedEntity): Long

    /** 重命名订阅（用户自定义标题优先，后续刷新不再覆盖） */
    @Query("UPDATE rss_feeds SET title = :title WHERE feedId = :feedId")
    suspend fun updateTitle(feedId: Long, title: String)

    @Query("UPDATE rss_feeds SET groupName = :group WHERE feedId = :feedId")
    suspend fun updateGroup(feedId: Long, group: String)

    /** 抓取成功后回写元信息（标题仅在为空时由调用方决定是否覆盖） */
    @Query(
        """
        UPDATE rss_feeds SET
            title = :title, siteUrl = :siteUrl, description = :description,
            iconUrl = :iconUrl, lastFetchAt = :fetchedAt, lastError = ''
        WHERE feedId = :feedId
        """
    )
    suspend fun updateFeedMeta(
        feedId: Long,
        title: String,
        siteUrl: String,
        description: String,
        iconUrl: String,
        fetchedAt: Long,
    )

    /** 抓取失败：只记失败原因与时间，不影响已有内容 */
    @Query("UPDATE rss_feeds SET lastFetchAt = :fetchedAt, lastError = :error WHERE feedId = :feedId")
    suspend fun updateFetchResult(feedId: Long, fetchedAt: Long, error: String)

    @Query("DELETE FROM rss_feeds WHERE feedId = :feedId")
    suspend fun deleteFeed(feedId: Long)

    @Query("SELECT COUNT(*) FROM rss_feeds")
    suspend fun countFeeds(): Int

    // ==================== 文章 ====================

    @Query("SELECT * FROM rss_articles ORDER BY pubDate DESC, articleId DESC LIMIT :limit")
    fun observeAllArticles(limit: Int): Flow<List<RssArticleEntity>>

    @Query(
        """
        SELECT * FROM rss_articles WHERE feedId = :feedId
        ORDER BY pubDate DESC, articleId DESC LIMIT :limit
        """
    )
    fun observeArticlesOfFeed(feedId: Long, limit: Int): Flow<List<RssArticleEntity>>

    @Query(
        """
        SELECT * FROM rss_articles WHERE feedId = :feedId
        ORDER BY pubDate DESC, articleId DESC LIMIT :limit
        """
    )
    suspend fun listArticlesOfFeed(feedId: Long, limit: Int): List<RssArticleEntity>

    @Query("SELECT * FROM rss_articles WHERE articleId = :articleId LIMIT 1")
    suspend fun getArticle(articleId: Long): RssArticleEntity?

    @Query("SELECT * FROM rss_articles WHERE articleId = :articleId LIMIT 1")
    fun observeArticle(articleId: Long): Flow<RssArticleEntity?>

    /** 该源已有文章的 guid（用于增量入库） */
    @Query("SELECT guid FROM rss_articles WHERE feedId = :feedId")
    suspend fun guidsOfFeed(feedId: Long): List<String>

    /** 仅插入新文章：guid 冲突时忽略，保住已读 / 收藏状态 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<RssArticleEntity>)

    @Query("SELECT feedId, COUNT(*) AS count FROM rss_articles WHERE read = 0 GROUP BY feedId")
    fun observeUnreadCounts(): Flow<List<FeedUnreadCount>>

    @Query("SELECT COUNT(*) FROM rss_articles WHERE read = 0")
    fun observeUnreadTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM rss_articles WHERE read = 0")
    suspend fun countUnread(): Int

    @Query("UPDATE rss_articles SET read = :read WHERE articleId = :articleId")
    suspend fun setRead(articleId: Long, read: Boolean)

    @Query("UPDATE rss_articles SET starred = :starred WHERE articleId = :articleId")
    suspend fun setStarred(articleId: Long, starred: Boolean)

    @Query("UPDATE rss_articles SET read = 1 WHERE feedId = :feedId")
    suspend fun markFeedRead(feedId: Long)

    @Query("UPDATE rss_articles SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM rss_articles WHERE feedId = :feedId")
    suspend fun deleteArticlesOfFeed(feedId: Long)

    @Query("DELETE FROM rss_articles")
    suspend fun deleteAllArticles()

    /** 全量导出（备份用） */
    @Query("SELECT * FROM rss_articles")
    suspend fun getAllArticles(): List<RssArticleEntity>

    /**
     * 每源只保留最近 [keep] 篇（按时间倒序），其余删除。
     * 防止长期订阅把数据库撑爆。
     */
    @Query(
        """
        DELETE FROM rss_articles WHERE feedId = :feedId AND articleId NOT IN (
            SELECT articleId FROM rss_articles WHERE feedId = :feedId
            ORDER BY pubDate DESC, articleId DESC LIMIT :keep
        )
        """
    )
    suspend fun trimFeed(feedId: Long, keep: Int)
}
