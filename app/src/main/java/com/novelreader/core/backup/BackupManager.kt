package com.novelreader.core.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.data.database.AppDatabase
import com.novelreader.data.model.BookEntity
import com.novelreader.data.model.BookmarkEntity
import com.novelreader.data.model.ProgressEntity
import com.novelreader.data.model.RssArticleEntity
import com.novelreader.data.model.RssFeedEntity
import com.novelreader.data.model.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 本地备份 / 恢复（v1.2.0 对标 legado WebDav 备份的本地版）。
 *
 * 备份内容：书源、书架、阅读进度、书签、正文替换规则、RSS 订阅与文章（v1.4.0）。
 * 格式：单个 JSON 文件（SAF 导出/导入），版本号字段便于后续演进。
 *
 * 章节缓存不备份（体积大、可重建）；本地 TXT 书不备份（文件在应用私有目录，
 * 与书源分离，后续如需可扩展为打包导出）。
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val preferencesManager: PreferencesManager,
) {

    companion object {
        const val FORMAT_VERSION = 1
    }

    private val gson = Gson()

    /** 导出备份到 SAF Uri（CREATE_DOCUMENT），返回统计描述 */
    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) {
        val sources = database.sourceDao().getAll()
        // BookDao 无 getAll，取 observeAll 的首个值
        val books = database.bookDao().observeAll().first()
        val progress = database.progressDao().getAll()
        val bookmarks = database.bookmarkDao().getAll()
        val rssFeeds = database.rssDao().getAllFeeds()
        val rssArticles = database.rssDao().getAllArticles()
        val replaceRules = preferencesManager.novelReplaceRulesFlow.first()

        val root = JsonObject().apply {
            addProperty("app", "leimao-reader")
            addProperty("formatVersion", FORMAT_VERSION)
            addProperty("exportedAt", System.currentTimeMillis())
            add("sources", gson.toJsonTree(sources))
            add("books", gson.toJsonTree(books))
            add("progress", gson.toJsonTree(progress))
            add("bookmarks", gson.toJsonTree(bookmarks))
            add("rssFeeds", gson.toJsonTree(rssFeeds))
            add("rssArticles", gson.toJsonTree(rssArticles))
            addProperty("replaceRules", replaceRules)
        }

        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(gson.toJson(root).toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法写入所选文件")

        "已备份：书源 ${sources.size} · 书架 ${books.size} · 进度 ${progress.size} · " +
            "书签 ${bookmarks.size} · 订阅 ${rssFeeds.size} · 订阅文章 ${rssArticles.size}"
    }

    /** 从 SAF Uri（OPEN_DOCUMENT）恢复，返回统计描述 */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("无法读取所选文件")
        val root = JsonParser.parseString(text).asJsonObject
        if (root.get("app")?.asString != "leimao-reader") {
            throw IllegalArgumentException("不是磊毛阅读的备份文件")
        }

        var nSources = 0; var nBooks = 0; var nProgress = 0; var nBookmarks = 0
        var nFeeds = 0; var nArticles = 0
        // 订阅文章靠自增 feedId 关联，恢复时 feedId 会变，需要建立新旧映射
        val feedIdMap = mutableMapOf<Long, Long>()

        root.getAsJsonArray("sources")?.forEach { el ->
            val e = gson.fromJson(el, SourceEntity::class.java)
            if (e != null && e.id.isNotBlank()) { database.sourceDao().insert(e); nSources++ }
        }
        root.getAsJsonArray("books")?.forEach { el ->
            val e = gson.fromJson(el, BookEntity::class.java)
            if (e != null && e.itemId.isNotBlank()) { database.bookDao().insert(e); nBooks++ }
        }
        root.getAsJsonArray("progress")?.forEach { el ->
            val e = gson.fromJson(el, ProgressEntity::class.java)
            if (e != null && e.itemId.isNotBlank()) { database.progressDao().insert(e); nProgress++ }
        }
        root.getAsJsonArray("bookmarks")?.forEach { el ->
            val e = gson.fromJson(el, BookmarkEntity::class.java)
            if (e != null && e.itemId.isNotBlank()) { database.bookmarkDao().insert(e); nBookmarks++ }
        }
        root.getAsJsonArray("rssFeeds")?.forEach { el ->
            val e = gson.fromJson(el, RssFeedEntity::class.java)
            if (e != null && e.url.isNotBlank()) {
                val newId = database.rssDao().insertFeed(e.copy(feedId = 0))
                if (newId > 0) {
                    feedIdMap[e.feedId] = newId
                    nFeeds++
                }
            }
        }
        val restoredArticles = mutableListOf<RssArticleEntity>()
        root.getAsJsonArray("rssArticles")?.forEach { el ->
            val e = gson.fromJson(el, RssArticleEntity::class.java)
            if (e != null && e.guid.isNotBlank()) {
                val newFeedId = feedIdMap[e.feedId] ?: return@forEach
                restoredArticles.add(e.copy(articleId = 0, feedId = newFeedId))
            }
        }
        if (restoredArticles.isNotEmpty()) {
            database.rssDao().insertArticles(restoredArticles)
            nArticles = restoredArticles.size
        }
        root.get("replaceRules")?.asString?.let { rules ->
            if (rules.isNotBlank()) preferencesManager.setNovelReplaceRules(rules)
        }

        // 恢复的书源注册进 SourceManager（应用启动恢复逻辑的复用）
        "已恢复：书源 $nSources · 书架 $nBooks · 进度 $nProgress · 书签 $nBookmarks · " +
            "订阅 $nFeeds · 订阅文章 $nArticles（重启后生效全部书源）"
    }
}
