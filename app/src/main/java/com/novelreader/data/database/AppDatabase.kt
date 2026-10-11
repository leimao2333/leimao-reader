package com.novelreader.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.novelreader.data.model.BookEntity
import com.novelreader.data.model.ProgressEntity
import com.novelreader.data.model.SourceEntity

/**
 * 应用数据库。
 *
 * 三张表：源（sources）、书架（books）、进度（progress）。
 */
@Database(
    entities = [
        SourceEntity::class,
        BookEntity::class,
        ProgressEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun bookDao(): BookDao
    abstract fun progressDao(): ProgressDao

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
    }
}
