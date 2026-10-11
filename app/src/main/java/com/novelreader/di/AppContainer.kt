package com.novelreader.di

import android.content.Context
import androidx.room.Room
import com.novelreader.core.download.DownloadManager
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.data.database.AppDatabase
import com.novelreader.data.repository.BookRepositoryImpl
import com.novelreader.data.repository.ContentRepositoryImpl
import com.novelreader.data.repository.ProgressRepositoryImpl
import com.novelreader.data.repository.SourceRepositoryImpl
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.source.manager.SourceManager

/**
 * 依赖注入容器（简易手动 DI）。
 *
 * 统一管理数据库、仓库、源管理器等实例的创建与持有。
 * 在 [com.novelreader.NovelReaderApp] 中初始化，通过 Application 暴露。
 *
 * 设计：不引入 Hilt/Koin，用轻量手动 DI 保持简单可控；
 * 后续如需自动注入，可平滑迁移（容器已隔离构造逻辑）。
 */
class AppContainer(context: Context) {

    // ===== 基础设施 =====
    private val appContext = context.applicationContext

    /** 数据库 */
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "novel_reader.db",
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    /** 偏好设置 */
    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(appContext)
    }

    // ===== 源引擎 =====
    val sourceManager: SourceManager by lazy {
        SourceManager()
    }

    // ===== 仓库 =====
    val sourceRepository: SourceRepository by lazy {
        SourceRepositoryImpl(sourceManager, database.sourceDao())
    }

    val contentRepository: ContentRepository by lazy {
        ContentRepositoryImpl(sourceManager)
    }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(database.bookDao())
    }

    val progressRepository: ProgressRepository by lazy {
        ProgressRepositoryImpl(database.progressDao())
    }

    /** 下载管理器 */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(appContext)
    }
}
