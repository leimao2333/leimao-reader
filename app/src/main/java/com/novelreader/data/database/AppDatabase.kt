package com.novelreader.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.novelreader.data.model.BookEntity
import com.novelreader.data.model.BookmarkEntity
import com.novelreader.data.model.ChapterCacheEntity
import com.novelreader.data.model.ProgressEntity
import com.novelreader.data.model.SourceEntity

/**
 * 应用数据库。
 *
 * 五张表：源（sources）、书架（books）、进度（progress）、章节缓存（chapter_cache）、书签（bookmarks）。
 */
@Database(
    entities = [
        SourceEntity::class,
        BookEntity::class,
        ProgressEntity::class,
        ChapterCacheEntity::class,
        BookmarkEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun bookDao(): BookDao
    abstract fun progressDao(): ProgressDao
    abstract fun chapterCacheDao(): ChapterCacheDao
    abstract fun bookmarkDao(): BookmarkDao

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
         * v7 -> v8：新增 bookmarks 表（阅读书签，v1.1.0）。
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
    }
}
