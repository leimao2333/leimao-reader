package com.novelreader.data.repository

import com.novelreader.core.network.HttpClient
import com.novelreader.core.rss.CrossrefClient
import com.novelreader.core.rss.JournalCatalog
import com.novelreader.core.rss.JournalFeed
import com.novelreader.core.rss.JournalRef
import com.novelreader.core.rss.PaperMetaJson
import com.novelreader.core.rss.ParsedFeed
import com.novelreader.core.rss.RssParser
import com.novelreader.core.util.LogSanitizer
import com.novelreader.data.database.RssDao
import com.novelreader.data.model.RssArticleEntity
import com.novelreader.data.model.RssFeedEntity
import com.novelreader.domain.model.JournalCandidate
import com.novelreader.domain.model.RssArticle
import com.novelreader.domain.model.RssFeed
import com.novelreader.domain.model.RssFeedResult
import com.novelreader.domain.repository.RssRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * RSS 订阅仓库实现（v1.3.1.0）。
 *
 * 数据流：订阅地址 → 抓取（[HttpClient]）→ 解析（[RssParser]）→
 * 增量入库（guid 去重，保留已读/收藏）→ Room Flow 推给界面。
 *
 * 设计要点：
 * - **不抛异常，用 [RssFeedResult] 表达失败**：批量刷新时单个源挂掉不影响其余源；
 * - **并发限流 3 路**：订阅源常常几十上百个，全量并发容易被目标站当爬虫；
 * - **增量入库**：靠 (feedId, guid) 唯一索引 + IGNORE 插入，刷新不会把已读冲掉；
 * - **每源保留上限**：防止长期订阅把库撑爆（默认 300 篇 / 源）。
 */
class RssRepositoryImpl(
    private val dao: RssDao,
) : RssRepository {

    companion object {
        /** 每源保留文章上限 */
        private const val KEEP_PER_FEED = 300

        /** 界面一次最多展示的文章数 */
        private const val DISPLAY_LIMIT = 1000

        /** 并发抓取路数 */
        private const val CONCURRENCY = 3

        /** 单篇正文 HTML 上限（防止个别源塞整站页面进来） */
        private const val MAX_CONTENT_CHARS = 200_000

        private const val TAG = "RssRepository"

        /** 期刊源默认归入的分组名（用户可自行改分组 / 移出） */
        private const val JOURNAL_GROUP = "期刊"
    }

    // ==================== 读取 ====================

    override fun observeFeeds(): Flow<List<RssFeed>> =
        combine(dao.observeFeeds(), dao.observeUnreadCounts()) { feeds, unread ->
            val unreadMap = unread.associate { it.feedId to it.count }
            feeds.map { it.toDomain(unreadMap[it.feedId] ?: 0) }
        }

    override fun observeArticles(feedId: Long?): Flow<List<RssArticle>> {
        val articles = if (feedId == null) {
            dao.observeAllArticles(DISPLAY_LIMIT)
        } else {
            dao.observeArticlesOfFeed(feedId, DISPLAY_LIMIT)
        }
        return combine(articles, dao.observeFeeds()) { list, feeds ->
            val feedMap = feeds.associateBy { it.feedId }
            list.map { it.toDomain(feedMap[it.feedId]) }
        }
    }

    override fun observeUnreadCounts(): Flow<Map<Long, Int>> =
        dao.observeUnreadCounts().map { list -> list.associate { it.feedId to it.count } }

    override fun observeUnreadTotal(): Flow<Int> = dao.observeUnreadTotal()

    override suspend fun getArticle(articleId: Long): RssArticle? = withContext(Dispatchers.IO) {
        val article = dao.getArticle(articleId) ?: return@withContext null
        article.toDomain(dao.getFeed(article.feedId))
    }

    override suspend fun articlesOfFeed(feedId: Long): List<RssArticle> = withContext(Dispatchers.IO) {
        val feed = dao.getFeed(feedId)
        dao.listArticlesOfFeed(feedId, DISPLAY_LIMIT).map { it.toDomain(feed) }
    }

    override fun observeArticle(articleId: Long): Flow<RssArticle?> =
        combine(dao.observeArticle(articleId), dao.observeFeeds()) { article, feeds ->
            article?.toDomain(feeds.firstOrNull { it.feedId == article.feedId })
        }

    // ==================== 订阅 ====================

    override suspend fun addFeed(url: String): RssFeedResult {
        val clean = url.trim()
        if (clean.isEmpty()) return RssFeedResult(0, "", clean, error = "订阅地址为空")
        // 粘贴的是 OPML 整包时走批量导入
        if (RssParser.isOpml(clean)) {
            val results = addOpml(clean)
            return results.firstOrNull()
                ?: RssFeedResult(0, "", clean, error = "OPML 里没有可用订阅")
        }
        return ingest(clean)
    }

    override suspend fun addFeeds(urls: List<String>): List<RssFeedResult> {
        val clean = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return emptyList()
        return runLimited(clean) { ingest(it) }
    }

    override suspend fun addOpml(opml: String): List<RssFeedResult> {
        val outlines = runCatching { RssParser.parseOpml(opml) }.getOrDefault(emptyList())
        val urls = outlines.map { it.xmlUrl.trim() }.filter { it.isNotEmpty() }.distinct()
        if (urls.isEmpty()) return emptyList()
        return runLimited(urls) { ingest(it) }
    }

    /**
     * 抓取 + 入库一个地址。
     * 地址是站点首页时自动发现订阅链接（返回结果里的 url 为最终订阅地址）。
     */
    private suspend fun ingest(url: String): RssFeedResult {
        val fetched = try {
            fetch(url)
        } catch (e: Exception) {
            return RssFeedResult(0, "", url, error = friendlyError(e))
        }

        return try {
            val now = System.currentTimeMillis()
            val existing = dao.getFeedByUrl(fetched.url)
            // 期刊源自动归到「期刊」分组：一次订阅六本顶刊后，源 chips 行会被它们占满，
            // 分组筛选能把期刊和博客 / 资讯站分开看待（用户之后可以自己改分组）。
            val journalGroup = JournalFeed.isJournalFeed(fetched.url)
            val feedId = if (existing != null) {
                existing.feedId
            } else {
                dao.insertFeed(
                    RssFeedEntity(
                        url = fetched.url,
                        title = fetched.feed.title,
                        siteUrl = fetched.feed.siteUrl,
                        description = fetched.feed.description,
                        iconUrl = fetched.feed.iconUrl,
                        groupName = if (journalGroup) JOURNAL_GROUP else "",
                        lastFetchAt = now,
                        createdAt = now,
                    )
                )
            }
            val newCount = insertNewArticles(feedId, fetched.feed)
            // 用户自定义过标题时保留用户标题（title 非空且与源标题不同则视为已改名）
            val keepTitle = existing?.title?.takeIf { it.isNotBlank() && it != fetched.feed.title }
                ?: fetched.feed.title
            dao.updateFeedMeta(
                feedId = feedId,
                title = keepTitle,
                siteUrl = fetched.feed.siteUrl,
                description = fetched.feed.description,
                iconUrl = fetched.feed.iconUrl,
                fetchedAt = now,
            )
            if (journalGroup && existing != null && existing.groupName.isBlank()) {
                // 老库里已经订阅过的同址期刊源（升级前加的），补一个分组
                dao.updateGroup(feedId, JOURNAL_GROUP)
            }
            dao.trimFeed(feedId, KEEP_PER_FEED)
            RssFeedResult(
                feedId = feedId,
                title = keepTitle,
                url = fetched.url,
                newArticles = newCount,
                fetchedArticles = fetched.feed.articles.size,
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "订阅入库失败：${LogSanitizer.sanitize(e.message)}")
            RssFeedResult(0, fetched.feed.title, fetched.url, error = "入库失败：${e.javaClass.simpleName}")
        }
    }

    // ==================== 刷新 ====================

    override suspend fun refreshFeed(feedId: Long): RssFeedResult {
        val feed = withContext(Dispatchers.IO) { dao.getFeed(feedId) }
            ?: return RssFeedResult(feedId, "", "", error = "订阅不存在")
        return refreshOne(feed)
    }

    override suspend fun refreshAll(): List<RssFeedResult> {
        val feeds = withContext(Dispatchers.IO) { dao.getAllFeeds() }
        if (feeds.isEmpty()) return emptyList()
        return runLimited(feeds) { refreshOne(it) }
    }

    private suspend fun refreshOne(feed: RssFeedEntity): RssFeedResult {
        val now = System.currentTimeMillis()
        return try {
            val fetched = fetch(feed.url)
            val newCount = insertNewArticles(feed.feedId, fetched.feed)
            val keepTitle = feed.title.takeIf { it.isNotBlank() } ?: fetched.feed.title
            dao.updateFeedMeta(
                feedId = feed.feedId,
                title = keepTitle,
                siteUrl = fetched.feed.siteUrl.ifBlank { feed.siteUrl },
                description = fetched.feed.description.ifBlank { feed.description },
                iconUrl = fetched.feed.iconUrl.ifBlank { feed.iconUrl },
                fetchedAt = now,
            )
            dao.trimFeed(feed.feedId, KEEP_PER_FEED)
            RssFeedResult(
                feed.feedId,
                keepTitle,
                feed.url,
                newArticles = newCount,
                fetchedArticles = fetched.feed.articles.size,
            )
        } catch (e: Exception) {
            val reason = friendlyError(e)
            runCatching { dao.updateFetchResult(feed.feedId, now, reason) }
            android.util.Log.w(TAG, "刷新失败：${LogSanitizer.sanitize(e.message)}")
            RssFeedResult(feed.feedId, feed.title, feed.url, error = reason)
        }
    }

    // ==================== 管理 ====================

    override suspend fun renameFeed(feedId: Long, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        withContext(Dispatchers.IO) { dao.updateTitle(feedId, clean) }
    }

    override suspend fun setFeedGroup(feedId: Long, group: String) {
        withContext(Dispatchers.IO) { dao.updateGroup(feedId, group.trim()) }
    }

    override suspend fun setFeedGroupBatch(feedIds: List<Long>, group: String) {
        if (feedIds.isEmpty()) return
        withContext(Dispatchers.IO) { dao.updateGroupBatch(feedIds, group.trim()) }
    }

    override suspend fun groupNames(): List<String> = withContext(Dispatchers.IO) {
        runCatching { dao.getGroupNames() }.getOrDefault(emptyList())
    }

    override suspend fun countArticlesOfFeeds(feedIds: List<Long>): Int {
        if (feedIds.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            runCatching { dao.countArticlesOfFeeds(feedIds) }.getOrDefault(0)
        }
    }

    override suspend fun deleteFeed(feedId: Long) = withContext(Dispatchers.IO) {
        dao.deleteArticlesOfFeed(feedId)
        dao.deleteFeed(feedId)
    }

    override suspend fun deleteFeeds(feedIds: List<Long>) {
        if (feedIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            // 先清文章再删源：rss_articles 没有外键约束，顺序反了就会留孤儿数据
            feedIds.chunked(500).forEach { chunk ->
                dao.deleteArticlesOfFeeds(chunk)
                dao.deleteFeeds(chunk)
            }
        }
    }

    override suspend fun markRead(articleId: Long, read: Boolean) = withContext(Dispatchers.IO) {
        dao.setRead(articleId, read)
    }

    override suspend fun toggleStar(articleId: Long) = withContext(Dispatchers.IO) {
        val article = dao.getArticle(articleId) ?: return@withContext
        dao.setStarred(articleId, !article.starred)
    }

    override suspend fun markAllRead(feedId: Long?) = withContext(Dispatchers.IO) {
        if (feedId == null) dao.markAllRead() else dao.markFeedRead(feedId)
    }

    // ==================== 内部工具 ====================

    /**
     * 抓取地址并解析；非订阅源则尝试从页面里发现订阅链接后重试。
     *
     * **期刊源走完全不同的分支**（v1.8.5.0）：`crossref:issn:xxxx-xxxx` 不是真实
     * 地址，交给 [CrossrefClient] 拉该刊最新论文，再伪装成标准 [ParsedFeed]，
     * 于是订阅、增量入库、未读、分组、收藏、阅读页全部沿用现有 RSS 链路，
     * 一行新增数据通路都不用写。
     */
    private suspend fun fetch(url: String): FetchOutcome = withContext(Dispatchers.IO) {
        JournalFeed.issnOf(url)?.let { issn ->
            val works = CrossrefClient.recentWorks(issn)
            val journal = JournalCatalog.byIssn(issn)?.let { preset ->
                JournalRef(
                    issn = preset.issn,
                    issns = listOf(preset.issn),
                    title = preset.title,
                    publisher = preset.publisher,
                )
            } ?: JournalRef(issn, listOf(issn), issn)
            return@withContext FetchOutcome(url, JournalFeed.toParsedFeed(journal, works))
        }

        val raw = HttpClient.get(url)
        when {
            RssParser.isOpml(raw) -> throw IllegalArgumentException("这是 OPML 订阅列表，已按批量导入处理")
            RssParser.isFeed(raw) -> FetchOutcome(url, RssParser.parse(raw, url))
            else -> {
                val discovered = RssParser.discoverFeedUrl(raw, url)
                    ?: throw IllegalArgumentException("该地址不是订阅源，页面里也没找到订阅链接")
                val raw2 = HttpClient.get(discovered)
                FetchOutcome(discovered, RssParser.parse(raw2, discovered))
            }
        }
    }

    // ==================== 期刊订阅（v1.8.5.0） ====================

    override suspend fun searchJournals(query: String): List<JournalCandidate> {
        val q = query.trim()
        if (q.isEmpty()) {
            // 空检索 = 直接列出内置顶刊预设，进页面就能看见要点名的那几本
            return withContext(Dispatchers.IO) {
                JournalCatalog.PRESETS.map { it.toCandidate() }
            }
        }
        // 先按预设模糊匹配（中文简称也能命中），再补 Crossref 的检索结果
        val presets = JournalCatalog.find(q).map { it.toCandidate() }
        val remote = runCatching { CrossrefClient.searchJournals(q) }
            .getOrDefault(emptyList())
            .map { ref ->
                JournalCandidate(
                    issn = ref.issn,
                    issns = ref.issns,
                    title = ref.title,
                    shortName = JournalCatalog.matchPreset(ref)?.shortName.orEmpty(),
                    publisher = ref.publisher,
                    totalDois = ref.totalDois,
                    isPreset = JournalCatalog.matchPreset(ref) != null,
                )
            }
        // 预设永远排前面（用户点名的六本是「开箱可订」的重点），其余按 Crossref 收录量降序。
        // 不用 totalDois = Int.MAX_VALUE 把预设顶上去 —— 那个值会原样显示成「2147483647 篇」。
        return (presets + remote)
            .distinctBy { it.issn }
            .sortedWith(
                compareByDescending<JournalCandidate> { it.isPreset }
                    .thenByDescending { it.totalDois }
            )
    }

    private fun com.novelreader.core.rss.JournalPreset.toCandidate(): JournalCandidate =
        JournalCandidate(
            issn = issn,
            issns = listOf(issn),
            title = title,
            shortName = shortName,
            publisher = publisher,
            field = field,
            // 预设刊的收录量不在本地数据里，留 0 让界面不显示「N 篇」
            totalDois = 0,
            isPreset = true,
        )

    /**
     * 增量入库：只插 guid 没见过的新文章。
     * @return 实际新增条数
     */
    private suspend fun insertNewArticles(feedId: Long, feed: ParsedFeed): Int {
        if (feed.articles.isEmpty()) return 0
        val known = dao.guidsOfFeed(feedId).toHashSet()
        val fresh = feed.articles
            .distinctBy { it.guid }
            .filter { known.add(it.guid) }
        if (fresh.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val entities = fresh.map { article ->
            RssArticleEntity(
                feedId = feedId,
                guid = article.guid,
                title = article.title,
                link = article.link,
                author = article.author,
                pubDate = article.pubDate,
                summary = article.summary,
                contentHtml = article.contentHtml.take(MAX_CONTENT_CHARS),
                imageUrl = article.imageUrl,
                // 题录与 DOI 一起落库：阅读页 / 引用导出不再依赖重新抓一次源
                doi = article.paperMeta.doi,
                paperMetaJson = PaperMetaJson.toJson(article.paperMeta),
                read = false,
                starred = false,
                fetchedAt = now,
            )
        }
        dao.insertArticles(entities)
        return entities.size
    }

    /** 限流并发执行（保持传入顺序返回结果） */
    private suspend fun <T, R> runLimited(items: List<T>, block: suspend (T) -> R): List<R> =
        coroutineScope {
            val semaphore = Semaphore(CONCURRENCY)
            items.map { item ->
                async(Dispatchers.IO) { semaphore.withPermit { block(item) } }
            }.awaitAll()
        }

    private fun friendlyError(e: Exception): String = when (e) {
        is IllegalArgumentException -> e.message ?: "地址无效"
        else -> {
            val msg = e.message.orEmpty()
            when {
                msg.contains("timeout", ignoreCase = true) -> "请求超时"
                msg.contains("Unable to resolve host", ignoreCase = true) -> "域名无法解析"
                msg.contains("Failed to connect", ignoreCase = true) -> "无法连接"
                msg.contains("502") || msg.contains("503") -> "站点暂时不可用"
                else -> e.javaClass.simpleName
            }
        }
    }

    private data class FetchOutcome(val url: String, val feed: ParsedFeed)

    private fun RssFeedEntity.toDomain(unread: Int = 0): RssFeed = RssFeed(
        feedId = feedId,
        url = url,
        title = title.ifBlank { url },
        siteUrl = siteUrl,
        description = description,
        iconUrl = iconUrl,
        groupName = groupName,
        lastFetchAt = lastFetchAt,
        lastError = lastError,
        createdAt = createdAt,
        unreadCount = unread,
    )

    private fun RssArticleEntity.toDomain(feed: RssFeedEntity?): RssArticle = RssArticle(
        articleId = articleId,
        feedId = feedId,
        title = title,
        link = link,
        author = author,
        pubDate = pubDate,
        summary = summary,
        contentHtml = contentHtml,
        imageUrl = imageUrl,
        doi = doi.ifBlank { paperMetaJson.let { PaperMetaJson.fromJson(it).doi } },
        paperMeta = PaperMetaJson.fromJson(paperMetaJson),
        read = read,
        starred = starred,
        feedTitle = feed?.title.orEmpty(),
        feedIcon = feed?.iconUrl.orEmpty(),
    )
}
