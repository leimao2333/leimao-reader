package com.novelreader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.novelreader.data.database.AppDatabase
import com.novelreader.data.model.ProgressEntity
import com.novelreader.data.repository.ProgressRepositoryImpl
import com.novelreader.domain.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「最近观看 / 最近阅读」按类型过滤的回归测试。
 *
 * 背景（v1.3.4.0 实测发现的 bug）：`progress.itemType` 由保存路径写成
 * `SourceType.name`（**大写** `VIDEO`），而 `books.type` 与历史迁移里的数据是
 * **小写** `video`。`recentReadings` 的 SQL 用 `= :type` 直接等值比较，
 * 于是「继续观看」永远取不到数据（小说侧同理）。
 *
 * 这里用真实 Room 内存库跑真正的 SQL，覆盖：
 * 1. 旧数据（大写）能被查到；
 * 2. 新保存的数据能被查到，且落库统一为小写；
 * 3. 类型过滤不会串类型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressRecentReadingsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ProgressRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ProgressRepositoryImpl(db.progressDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 直接落库，模拟旧版本写下的**大写** itemType */
    private fun insertRaw(itemId: String, itemType: String) = runBlocking {
        db.progressDao().insert(
            ProgressEntity(
                sourceId = "s",
                itemId = itemId,
                itemType = itemType,
                itemTitle = "影片$itemId",
                chapterUrl = "http://x/$itemId",
                chapterTitle = "第1集",
                chapterIndex = 0,
                totalChapter = 24,
                playPositionMs = 12_000,
                durationMs = 60_000,
            )
        )
    }

    @Test
    fun `旧数据的大写 itemType 也能被按类型查到`() = runBlocking {
        insertRaw("a", "VIDEO")
        val rows = repo.recentReadings(SourceType.VIDEO)
        assertEquals("大写历史数据必须能命中", 1, rows.size)
        assertEquals("a", rows[0].itemId)
    }

    @Test
    fun `小写历史数据同样能命中`() = runBlocking {
        insertRaw("b", "video")
        assertEquals(1, repo.recentReadings(SourceType.VIDEO).size)
    }

    @Test
    fun `保存进度后落库统一小写且能被查到`() = runBlocking {
        repo.saveProgress(
            sourceId = "s",
            itemId = "c",
            chapterUrl = "http://x/c",
            chapterTitle = "第3集",
            chapterIndex = 2,
            playPositionMs = 30_000,
            durationMs = 60_000,
            itemType = SourceType.VIDEO.name,
            itemTitle = "影片c",
            itemCover = null,
            itemAuthor = null,
            totalChapter = 24,
        )
        val stored = db.progressDao().get("s", "c")
        assertEquals("落库应统一为小写", "video", stored?.itemType)
        assertEquals(1, repo.recentReadings(SourceType.VIDEO).size)
        // 不带类型过滤时也能拿到
        assertEquals(1, repo.recentReadings(null).size)
    }

    @Test
    fun `类型过滤不会串类型`() = runBlocking {
        insertRaw("v", "VIDEO")
        insertRaw("n", "NOVEL")
        val videos = repo.recentReadings(SourceType.VIDEO)
        assertEquals(1, videos.size)
        assertTrue("VIDEO 查询不应混入小说", videos.all { it.itemId == "v" })
        assertEquals(2, repo.recentReadings(null).size)
    }

    @Test
    fun `续播所需字段完整带出`() = runBlocking {
        insertRaw("d", "VIDEO")
        val row = repo.recentReadings(SourceType.VIDEO).single()
        assertEquals("http://x/d", row.chapterUrl)
        assertEquals(12_000L, row.playPositionMs)
        assertEquals(60_000L, row.durationMs)
        assertEquals(24, row.totalChapter)
        assertEquals(SourceType.VIDEO, row.type)
    }

    @Test
    fun `清空全部进度后列表为空`() = runBlocking {
        insertRaw("v", "VIDEO")
        insertRaw("n", "NOVEL")
        assertEquals(2, repo.recentReadings(null).size)
        repo.clearAllProgress()
        assertEquals(0, repo.recentReadings(null).size)
    }

    @Test
    fun `删除单条进度只影响该条`() = runBlocking {
        insertRaw("a", "VIDEO")
        insertRaw("b", "NOVEL")
        repo.removeProgress("s", "a")
        val rest = repo.recentReadings(null)
        assertEquals(1, rest.size)
        assertEquals("b", rest[0].itemId)
    }
}
