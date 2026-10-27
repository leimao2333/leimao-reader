package com.novelreader.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.novelreader.data.model.BookEntity
import com.novelreader.data.model.LocalTagEntity
import com.novelreader.data.model.TagInstanceEntity
import com.novelreader.data.model.BookmarkEntity
import com.novelreader.data.model.ChapterCacheEntity
import com.novelreader.data.model.ChapterListCacheEntity
import com.novelreader.data.model.ProgressEntity
import com.novelreader.data.model.RssArticleEntity
import com.novelreader.data.model.RssFeedEntity
import com.novelreader.data.model.RssPdfEntity
import com.novelreader.data.model.SourceEntity

/**
 * 应用数据库。
 *
 * 八张表：源（sources）、书架（books）、进度（progress）、章节缓存（chapter_cache）、
 * 书签（bookmarks）、RSS 订阅（rss_feeds）、RSS 文章（rss_articles）、
 * 订阅书架里的本地 PDF（rss_pdfs，v1.8.6.0 待办 #29）。
 */
@Database(
    entities = [
        SourceEntity::class,
        BookEntity::class,
        ProgressEntity::class,
        ChapterCacheEntity::class,
        BookmarkEntity::class,
        RssFeedEntity::class,
        RssArticleEntity::class,
        RssPdfEntity::class,
        LocalTagEntity::class,
        TagInstanceEntity::class,
        ChapterListCacheEntity::class,
    ],
    version = 18,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun bookDao(): BookDao
    abstract fun progressDao(): ProgressDao
    abstract fun chapterCacheDao(): ChapterCacheDao
    abstract fun chapterListCacheDao(): ChapterListCacheDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun rssDao(): RssDao
    abstract fun rssPdfDao(): RssPdfDao
    abstract fun localTagDao(): LocalTagDao
    abstract fun tagInstanceDao(): TagInstanceDao

    companion object {
        /** v1 -> v2：sources 表新增 category 列 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 -> v3：progress 表去重 + 建立 sourceId+itemId 唯一索引 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 去重：删除同一 sourceId+itemId 的旧记录，仅保留 progressId 最大（最新）的一条
                db.execSQL(
                    """
                    DELETE FROM progress
                    WHERE progressId NOT IN (
                        SELECT MAX(progressId) FROM progress GROUP BY sourceId, itemId
                    )
                    """.trimIndent()
                )
                // 建立联合唯一索引，防止后续再产生重复进度
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_progress_sourceId_itemId " +
                        "ON progress (sourceId, itemId)"
                )
            }
        }

        /** v3 -> v4：progress 表新增 itemType / itemTitle 列（最近阅读按类型过滤 + 修复标题） */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN itemType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE progress ADD COLUMN itemTitle TEXT NOT NULL DEFAULT ''")
                // 存量数据回填：优先从 books 表补 type/title
                db.execSQL(
                    """
                    UPDATE progress SET
                        itemType = IFNULL((SELECT b.type FROM books b
                            WHERE b.sourceId = progress.sourceId AND b.itemId = progress.itemId), ''),
                        itemTitle = IFNULL((SELECT b.title FROM books b
                            WHERE b.sourceId = progress.sourceId AND b.itemId = progress.itemId), '')
                    """.trimIndent()
                )
                // books 表里查不到的旧记录（书未收藏）：旧版 UI 对未知类型统一按小说展示，
                // 这里保持同一行为，避免老记录从最近阅读里"消失"
                db.execSQL("UPDATE progress SET itemType = 'novel' WHERE itemType = ''")
            }
        }

        /**
         * v4 -> v5：progress 表新增 itemCover / itemAuthor / pageIndex 列。
         *
         * - itemCover / itemAuthor：进度冗余存封面与作者，书不在书架时最近阅读也能正确展示（#12）
         * - pageIndex：翻页模式记住页码，退出重进恢复到对应页（#32）
         * 存量数据从 books 表回填封面与作者。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN itemCover TEXT")
                db.execSQL("ALTER TABLE progress ADD COLUMN itemAuthor TEXT")
                db.execSQL("ALTER TABLE progress ADD COLUMN pageIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE progress SET
                        itemCover = IFNULL(itemCover, (SELECT b.cover FROM books b
                            WHERE b.sourceId = progress.sourceId AND b.itemId = progress.itemId)),
                        itemAuthor = IFNULL(itemAuthor, (SELECT b.author FROM books b
                            WHERE b.sourceId = progress.sourceId AND b.itemId = progress.itemId))
                    """.trimIndent()
                )
            }
        }

        /**
         * v5 -> v6：progress 表新增 totalChapter 列（章节总数，用于最近阅读显示"已读 xx%"，#13）。
         * 存量数据无从得知总章节数，默认 0（前端按未知处理，不显示百分比）。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN totalChapter INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 -> v7：新增 chapter_cache 表（单章缓存 + 缓存管理，#30）。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chapter_cache (
                        cacheId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        itemTitle TEXT NOT NULL DEFAULT '',
                        itemType TEXT NOT NULL DEFAULT '',
                        chapterIndex INTEGER NOT NULL,
                        chapterTitle TEXT NOT NULL DEFAULT '',
                        chapterUrl TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL DEFAULT '',
                        size INTEGER NOT NULL DEFAULT 0,
                        cachedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_chapter_cache_sourceId_itemId_chapterIndex " +
                        "ON chapter_cache (sourceId, itemId, chapterIndex)"
                )
            }
        }

        /**
         * v7 -> v8：新增 bookmarks 表（阅读书签，v1.1.0.0）。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        bookmarkId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        itemTitle TEXT NOT NULL DEFAULT '',
                        itemType TEXT NOT NULL DEFAULT '',
                        chapterIndex INTEGER NOT NULL DEFAULT 0,
                        chapterUrl TEXT NOT NULL DEFAULT '',
                        chapterTitle TEXT NOT NULL DEFAULT '',
                        textPosition INTEGER NOT NULL DEFAULT 0,
                        snippet TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bookmarks_sourceId_itemId " +
                        "ON bookmarks (sourceId, itemId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_sourceId_itemId_chapterUrl_textPosition " +
                        "ON bookmarks (sourceId, itemId, chapterUrl, textPosition)"
                )
            }
        }

        /**
         * v8 -> v9：books 表新增 groupName 列（书架自定义分组，v1.3.0.0）。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v9 -> v10：books 表新增 requestOptions 列（详情地址随带的请求配置）。
         *
         * 漫画 App 接口源（如某些 App 接口源）把接口签名（`m-request-id` / `access-token`）写在
         * 地址的 `,{...}` 后缀里。该配置原先只存在于内存中的 MediaItem，收藏进书架后丢失，
         * 导致从书架重进详情时请求无签名 → 目录空白。存量数据无从回填（旧地址已剥离后缀），
         * 默认空串，用户重新收藏或重新搜索即可补上。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN requestOptions TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v10 -> v11：新增 RSS 订阅两张表（v1.3.1.0 RSS 订阅模块）。
         *
         * - rss_feeds：订阅源（地址唯一）
         * - rss_articles：文章（源 + guid 唯一，重复抓取只插新条目，已读/收藏状态不被覆盖）
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rss_feeds (
                        feedId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        siteUrl TEXT NOT NULL,
                        description TEXT NOT NULL,
                        iconUrl TEXT NOT NULL,
                        groupName TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        lastFetchAt INTEGER NOT NULL,
                        lastError TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_rss_feeds_url ON rss_feeds (url)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rss_articles (
                        articleId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        feedId INTEGER NOT NULL,
                        guid TEXT NOT NULL,
                        title TEXT NOT NULL,
                        link TEXT NOT NULL,
                        author TEXT NOT NULL,
                        pubDate INTEGER NOT NULL,
                        summary TEXT NOT NULL,
                        contentHtml TEXT NOT NULL,
                        imageUrl TEXT NOT NULL,
                        read INTEGER NOT NULL,
                        starred INTEGER NOT NULL,
                        fetchedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_rss_articles_feedId_guid " +
                        "ON rss_articles (feedId, guid)"
                )
            }
        }

        /**
         * v11 -> v12：progress 表新增 durationMs 列（视频总时长，v1.3.4.0「继续观看」）。
         *
         * 「继续观看」卡片要画时间进度条（`playPositionMs / durationMs`），
         * 而此前只存了进度、没存总时长，分母缺失只能退回集数比例。
         * 存量数据无从回填（旧记录没采过时长），默认 0 = 未知，
         * 前端按 0 处理即回退为「集数进度」，不影响展示。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v12 -> v13：sources 表新增 sortOrder 列（手动排序序号，v1.5.8.0）。
         *
         * 默认 0 = 未手动排序，前端按自动排序回退；>0 时按数值升序排列。
         * 存量数据无从得知手动顺序，默认 0 即可。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v13 -> v14：新增本地标签索引表（v1.8.1.2）。
         *
         * 漫画源大多没有分类页，「按标签复合筛选」只能靠本地索引：遍历各源列表 + 逐部拉详情，
         * 把源站自己声明的题材（legado `kind` / Venera `tags`）落地到这张表。
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_tags` (
                      `sourceId` TEXT NOT NULL,
                      `itemId` TEXT NOT NULL,
                      `itemType` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `cover` TEXT,
                      `detailUrl` TEXT NOT NULL,
                      `sourceName` TEXT NOT NULL,
                      `author` TEXT,
                      `tags` TEXT NOT NULL,
                      `tagsLower` TEXT NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`sourceId`, `itemId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v14 -> v15：标签实例表（v1.8.1.2「中间标签层」）。
         *
         * 一部作品的标签从「拼成一个字符串」改成**每个标签一行**，
         * 这样嵌套式 AND 查询（同一部作品内必须同时存在这些标签）才表达得精确。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tag_instances` (
                      `sourceId` TEXT NOT NULL,
                      `itemId` TEXT NOT NULL,
                      `itemType` TEXT NOT NULL,
                      `tagName` TEXT NOT NULL,
                      `category` TEXT NOT NULL,
                      `confidence` INTEGER NOT NULL,
                      `origin` TEXT NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`sourceId`, `itemId`, `tagName`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_instances_itemType ON tag_instances (itemType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_instances_tagName ON tag_instances (tagName)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tag_instances_itemType_tagName " +
                        "ON tag_instances (itemType, tagName)",
                )
            }
        }

        /**
         * v15 -> v16：RSS 文章表新增论文题录（v1.8.5.0）。
         *
         * `doi` 单独成列是为了「按 DOI 去重 / 按 DOI 查 Crossref」不用反序列化；
         * `paperMetaJson` 存整份 `PaperMeta`（期刊 / 卷 / 期 / 页 / 年 / 作者 / 类型），
         * 将来加字段（基金、ORCID…）不必再迁移。
         *
         * 存量数据默认空串 = 非期刊文章，阅读页不渲染题录卡，行为与旧版一致。
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rss_articles ADD COLUMN doi TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE rss_articles ADD COLUMN paperMetaJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rss_articles_doi ON rss_articles (doi)")
            }
        }

        /**
         * v16 -> v17：订阅书架新增本地 PDF 表（v1.8.6.0 待办 #29）。
         *
         * 新建表而非复用 `books`：PDF 是「导入即读」的静态文档，不参与
         * 章节缓存 / 阅读进度 / 分组那一套书架逻辑。
         * 文件本体在 `filesDir/rss_pdf/`，删除时由上层同步清理。
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `rss_pdfs` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `name` TEXT NOT NULL,
                      `fileName` TEXT NOT NULL,
                      `size` INTEGER NOT NULL,
                      `addedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v17 -> v18：新增目录缓存表（v1.8.8.5）。
         *
         * 只缓存正文（chapter_cache）不够：目录本身每次进书都要重新请求，
         * 几百上千章的表要一两秒，慢源好几秒 —— 这是「点一下没反应」的主因。
         * 一本书一行、整份目录存 JSON（目录是整体使用的，拆行只会变成 N 次查询）。
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chapter_list_cache` (
                      `sourceId` TEXT NOT NULL,
                      `itemId` TEXT NOT NULL,
                      `json` TEXT NOT NULL,
                      `count` INTEGER NOT NULL,
                      `cachedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`sourceId`, `itemId`)
                    )
                    """.trimIndent(),
                )
            }
        }

    }
}
