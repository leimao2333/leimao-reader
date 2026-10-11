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
    version = 3,
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
    }
}
