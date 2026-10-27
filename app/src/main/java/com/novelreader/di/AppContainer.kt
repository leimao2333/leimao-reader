package com.novelreader.di

import android.content.Context
import androidx.room.Room
import com.novelreader.core.download.DownloadManager
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.data.database.AppDatabase
import com.novelreader.data.repository.BookRepositoryImpl
import com.novelreader.data.repository.BookmarkRepositoryImpl
import com.novelreader.data.repository.ChapterCacheRepositoryImpl
import com.novelreader.data.repository.ContentRepositoryImpl
import com.novelreader.data.repository.ProgressRepositoryImpl
import com.novelreader.data.repository.SourceRepositoryImpl
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.BookmarkRepository
import com.novelreader.domain.repository.ChapterCacheRepository
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

    /**
     * 视频磁盘缓存预热（v1.7.7.0）。
     *
     * `SimpleCache` 要打开缓存目录与索引数据库，放主线程会拖慢冷启动，所以丢后台。
     * 没就绪时 [com.novelreader.core.media.VideoMediaCache.dataSourceFactory] 会退化为
     * 纯网络数据源 —— 不影响播放，只是第一次看没有缓存命中。
     */
    init {
        val ctx = context.applicationContext
        Thread({
            runCatching { com.novelreader.core.media.VideoMediaCache.init(ctx) }
        }, "video-media-cache-init").apply { isDaemon = true }.start()
    }

    // ===== 基础设施 =====
    private val appContext = context.applicationContext

    /** 应用上下文（供更新安装等需要 Context 的场景） */
    val context: Context get() = appContext

    /** 数据库 */
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "novel_reader.db",
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
            )
            .build()
    }

    /** 偏好设置 */
    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(appContext)
    }

    // ===== 源引擎 =====
    /** Venera 源运行时（隐藏 WebView，懒启动：只有真正用到 Venera 源时才创建 WebView） */
    val veneraEngine: com.novelreader.source.venera.VeneraEngine by lazy {
        com.novelreader.source.venera.VeneraEngine(appContext).also { engine ->
            // 已知失效配置的一次性修复（如包子漫画的默认域名），见 SourceRepairs 注释
            com.novelreader.source.venera.SourceRepairs.applyAll(engine)
        }
    }

    /** 影视首页 / 频道内容磁盘缓存（v1.5.7.0「加载一次之后固定」） */
    val videoHomeCache: com.novelreader.data.cache.VideoHomeCache by lazy {
        com.novelreader.data.cache.VideoHomeCache(
            java.io.File(appContext.filesDir, "video_home"),
        )
    }

    /** 视频预加载磁盘缓存（v1.6.0.0 落盘化，让缓存管理页能列出 / 清理） */
    val videoPrefetchCache: com.novelreader.data.cache.VideoPrefetchCache by lazy {
        com.novelreader.data.cache.VideoPrefetchCache(
            java.io.File(appContext.filesDir, "video_prefetch"),
        )
    }

    /** 标签磁盘缓存（v1.5.10.0，一个内容类型一个文件） */
    val tagStore: com.novelreader.data.cache.TagStore by lazy {
        com.novelreader.data.cache.TagStore(
            java.io.File(appContext.filesDir, "tags"),
        )
    }

    /** 本地标签索引（v1.8.1.2：遍历源 + 详情页题材，落到 local_tags 表） */
    val tagIndexRepository: com.novelreader.data.repository.TagIndexRepository by lazy {
        com.novelreader.data.repository.TagIndexRepository(
            contentRepository,
            sourceRepository,
            database.localTagDao(),
            database.tagInstanceDao(),
            appContext,
        )
    }

    /** 标签仓库（v1.5.10.0，自动收集各源发现分类为标签池） */
    val tagRepository: com.novelreader.data.repository.TagRepository by lazy {
        com.novelreader.data.repository.TagRepository(contentRepository, tagStore)
    }

    val sourceManager: SourceManager by lazy {
        SourceManager(veneraEngine)
    }

    // ===== 仓库 =====
    val sourceRepository: SourceRepository by lazy {
        SourceRepositoryImpl(sourceManager, database.sourceDao())
    }

    val contentRepository: ContentRepository by lazy {
        // v1.8.8.5：带上目录缓存 DAO —— 进书先读本地目录，不再每次都等网络
        ContentRepositoryImpl(
            sourceManager,
            localBookStore,
            preferencesManager,
            database.chapterListCacheDao(),
        )
    }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(database.bookDao(), tagIndexRepository)
    }

    val progressRepository: ProgressRepository by lazy {
        ProgressRepositoryImpl(database.progressDao(), tagIndexRepository)
    }

    /** 章节缓存仓库（单章缓存 + 缓存管理） */
    val chapterCacheRepository: ChapterCacheRepository by lazy {
        ChapterCacheRepositoryImpl(database.chapterCacheDao())
    }

    /** 书签仓库（v1.1.0.0） */
    val bookmarkRepository: BookmarkRepository by lazy {
        BookmarkRepositoryImpl(database.bookmarkDao())
    }

    /** RSS 订阅仓库（v1.3.1.0） */
    val rssRepository: com.novelreader.domain.repository.RssRepository by lazy {
        com.novelreader.data.repository.RssRepositoryImpl(database.rssDao())
    }

    /**
     * 订阅书架里的本地 PDF 存储（v1.8.6.0 待办 #29）。
     *
     * 文件落 `filesDir/rss_pdf/`，元数据走 `rss_pdfs` 表；
     * 导入 / 删除都由这里的 store 收口，RssScreen 不直接碰 DAO 与文件。
     */
    val rssPdfStore: com.novelreader.core.rss.RssPdfStore by lazy {
        com.novelreader.core.rss.RssPdfStore(appContext, database.rssPdfDao())
    }

    /** 下载管理器 */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(appContext)
    }

    /**
     * 论文 PDF 缓存（应用内阅读，v1.8.5.1）。
     *
     * 落在 `filesDir/pdf_cache/`，同一篇论文第二次打开直接命中；
     * 上限 300MB、按最久未打开淘汰（见 [com.novelreader.data.cache.PdfFileCache.prune]）。
     */
    val pdfCache: com.novelreader.data.cache.PdfFileCache by lazy {
        com.novelreader.data.cache.PdfFileCache(appContext)
    }

    /**
     * 书架条目清除器（v1.8.3.0 待办 #4/#5）。
     *
     * 删书时统一清书架行 + 章节缓存 + 阅读进度 + 漫画图片缓存，避免各处重复实现。
     */
    val shelfPurger: com.novelreader.core.bookshelf.ShelfPurger by lazy {
        com.novelreader.core.bookshelf.ShelfPurger(
            bookRepository,
            chapterCacheRepository,
            progressRepository,
            downloadManager,
        )
    }

    /** 本地 TXT 书库（v1.2.0.0 对标 legado 本地书） */
    val localBookStore: com.novelreader.core.local.LocalBookStore by lazy {
        com.novelreader.core.local.LocalBookStore(appContext)
    }

    /** 本地备份 / 恢复（v1.2.0.0 对标 legado 备份的本地版） */
    val backupManager: com.novelreader.core.backup.BackupManager by lazy {
        com.novelreader.core.backup.BackupManager(appContext, database, preferencesManager)
    }

    /**
     * 朗读控制器（v1.1.8.0 对标开源阅读听书）。
     *
     * 用 applicationContext 构造，避免持有 Activity；
     * 懒加载——只有真正进入阅读页发起朗读时才初始化 TTS 引擎。
     */
    val ttsController: com.novelreader.core.tts.TtsController by lazy {
        com.novelreader.core.tts.TtsController(appContext, localModelStore, preferencesManager)
    }

    /**
     * 本地模型（离线语音 / 字幕识别，v1.8.7.3）。
     *
     * 只负责下载与校验，不加载推理引擎——引擎由各自的使用方懒加载，
     * 避免「打开设置页就把几百兆模型读进内存」。
     */
    val localModelStore: com.novelreader.core.model.LocalModelStore by lazy {
        com.novelreader.core.model.LocalModelStore(appContext)
    }

    /** 离线语音识别（字幕）引擎（v1.8.7.3，懒加载） */
    val offlineAsrEngine: com.novelreader.core.asr.OfflineAsrEngine by lazy {
        com.novelreader.core.asr.OfflineAsrEngine(appContext, localModelStore)
    }

    /**
     * AI 字幕生成器（v1.8.7.3，待办 #10）。
     *
     * 与识别引擎共用同一个实例：模型只有一份句柄，重复 new 会白占几十兆内存。
     */
    val aiSubtitleGenerator: com.novelreader.core.asr.AiSubtitleGenerator by lazy {
        com.novelreader.core.asr.AiSubtitleGenerator(appContext, offlineAsrEngine)
    }
}
