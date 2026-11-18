package com.novelreader.core.follow

import android.content.Context
import com.google.gson.JsonParser
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 一次追更检查里单本作品的更新（D30） */
data class FollowUpdate(
    val itemTitle: String,
    val newCount: Int,
    val latestChapter: String,
)

/**
 * 追更检查器（D30）。
 *
 * 没有引入 WorkManager：为了不给 APK 加一个只为「每 6/12/24 小时跑一次」的依赖，
 * 这里用进程内循环（[runLoop]）在应用存活期间定时检查，配合「打开应用时立即检查一次」。
 * 进程被系统杀掉后不会自唤醒 —— 这是明确的取舍，UI 文案写的是「大致间隔」。
 *
 * 基线存在 `filesDir/follow_baseline.json`：`sourceId|itemId -> 章节数 + 最新章节`。
 * 不入 Room 是刻意的：追更基线是可重建的派生数据，为它写一次数据库迁移不划算。
 */
class FollowUpdateChecker(
    private val context: Context,
    private val bookRepository: BookRepository,
    private val contentRepository: ContentRepository,
    private val preferences: PreferencesManager,
) {

    companion object {
        /** 单次检查最多看多少本：几十本书 × 每本一次目录请求，跑太久没有意义 */
        private const val MAX_BOOKS_PER_RUN = 24

        /** 同时探测几本：源站点普遍受不了高并发，3 路已经够快 */
        private const val CONCURRENCY = 3

        /** 单本目录请求超时（Venera 源慢，30s 起步） */
        private const val CHAPTER_TIMEOUT_MS = 40_000L

        /** 启动后的首次检查延迟：避开首页源探测高峰，别和「首页几十个源」抢带宽 */
        private const val FIRST_RUN_DELAY_MS = 90_000L

        private const val BASELINE_FILE = "follow_baseline.json"
    }

    private val baselineFile: File get() = File(context.filesDir, BASELINE_FILE)

    /**
     * 跑一轮检查。
     *
     * @return 有更新的作品（首次见到某本书只记基线、不通知，避免「刚导入就报更新 N 话」）
     */
    suspend fun runOnce(): List<FollowUpdate> = withContext(Dispatchers.IO) {
        val books = runCatching { bookRepository.observeBookshelf().first() }
            .getOrDefault(emptyList())
            // 本地漫画 / 本地书没有「更新」概念，直接跳过
            .filter { it.type == SourceType.NOVEL || it.type == SourceType.COMIC }
            .filterNot { it.sourceId.startsWith("local_") }
            .take(MAX_BOOKS_PER_RUN)

        if (books.isEmpty()) return@withContext emptyList()

        val baseline = loadBaseline().toMutableMap()
        val updates = mutableListOf<FollowUpdate>()
        val semaphore = Semaphore(CONCURRENCY)

        books.forEach { item ->
            val chapters = runCatching {
                withTimeoutOrNull(CHAPTER_TIMEOUT_MS) {
                    semaphore.withPermit { contentRepository.chapterList(item) }
                }
            }.getOrNull().orEmpty()

            if (chapters.isEmpty()) return@forEach

            val key = baselineKey(item)
            val latest = chapters.lastOrNull()?.title.orEmpty()
            val count = chapters.size
            val previous = baseline[key]

            when {
                // 第一次见到：只记基线
                previous == null -> baseline[key] = ChapterBaseline(count, latest)
                // 章节变多：算更新
                count > previous.count -> {
                    updates.add(FollowUpdate(item.title, count - previous.count, latest))
                    baseline[key] = ChapterBaseline(count, latest)
                }
                // 章节变少（源重整目录）或最新章节换了：更新基线但不报「更新」
                count != previous.count || latest != previous.latestChapter ->
                    baseline[key] = ChapterBaseline(count, latest)
            }
        }

        saveBaseline(baseline)
        updates
    }

    /**
     * 进程内定时循环：设置变更（开关 / 间隔）会立刻重启循环。
     *
     * 用 `combine + collectLatest`：用户把间隔从 24 小时改成 6 小时，不需要等当前
     * 那次 24 小时 delay 跑完才生效。
     */
    suspend fun runLoop() {
        combine(
            preferences.followEnabledFlow,
            preferences.followIntervalHoursFlow,
        ) { enabled, hours -> enabled to hours }
            .collectLatest { (enabled, hours) ->
                if (!enabled) return@collectLatest
                delay(FIRST_RUN_DELAY_MS)
                runCatching { FollowNotifier.notify(context, runOnce()) }
                while (true) {
                    delay(hours.coerceIn(1, 168) * 3_600_000L)
                    runCatching { FollowNotifier.notify(context, runOnce()) }
                }
            }
    }

    // ===== 基线读写 =====

    private data class ChapterBaseline(val count: Int, val latestChapter: String)

    private fun baselineKey(item: MediaItem): String = "${item.sourceId}|${item.id}"

    private fun loadBaseline(): Map<String, ChapterBaseline> = runCatching {
        val json = JsonParser.parseString(baselineFile.readText()).asJsonObject
        json.entrySet().associate { (key, value) ->
            val obj = value.asJsonObject
            key to ChapterBaseline(
                count = obj.get("count")?.asInt ?: 0,
                latestChapter = obj.get("latest")?.asString.orEmpty(),
            )
        }
    }.getOrDefault(emptyMap())

    private fun saveBaseline(map: Map<String, ChapterBaseline>) {
        runCatching {
            val obj = com.google.gson.JsonObject()
            map.forEach { (key, value) ->
                val o = com.google.gson.JsonObject()
                o.addProperty("count", value.count)
                o.addProperty("latest", value.latestChapter)
                obj.add(key, o)
            }
            baselineFile.writeText(obj.toString())
        }
    }
}
