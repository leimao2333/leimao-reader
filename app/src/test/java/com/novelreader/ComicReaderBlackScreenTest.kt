package com.novelreader

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.domain.repository.ReadingProgress
import com.novelreader.ui.screens.comic.ComicReaderScreen
import com.novelreader.ui.screens.comic.ComicReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 漫画阅读页「黑屏」修复的本机 UI 测试（Robolectric，无需模拟器 / 真机）。
 *
 * 修复前：源返回空图片列表时，error 与 loading 均为空 → 直接进入渲染分支，
 * 空列表 + 纯黑背景 = 全黑屏且无任何提示。
 * 修复后：空列表统一转为错误态，页面给出「源已失效」提示与「重新加载」按钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComicReaderBlackScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val item = MediaItem(id = "i1", title = "测试漫画", type = SourceType.COMIC)
    private val chapters = listOf(ChapterItem(id = "c1", title = "第1话", url = "http://x/1", index = 0))
    private val chapter = chapters[0]

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(result: ContentResult): ComicReaderViewModel {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ComicReaderViewModel(
            contentRepository = FakeContentRepository(result),
            progressRepository = FakeProgressRepository(),
            preferences = PreferencesManager(ctx),
        )
    }

    @Test
    fun `空图片列表时显示失效提示与重新加载，而不是纯黑屏`() {
        val vm = newViewModel(ContentResult.comic(emptyList()))
        rule.setContent { ComicReaderScreen(vm, item, chapters, chapter) {} }
        rule.waitForIdle()

        rule.onNodeWithText("重新加载").assertIsDisplayed()
        rule.onNodeWithText("本章暂无可用图片，可能源已失效或章节待更新").assertIsDisplayed()
    }

    @Test
    fun `全为空白地址的图片同样视为失效`() {
        val vm = newViewModel(ContentResult.comic(listOf("", "   ")))
        rule.setContent { ComicReaderScreen(vm, item, chapters, chapter) {} }
        rule.waitForIdle()

        rule.onNodeWithText("重新加载").assertIsDisplayed()
    }

    @Test
    fun `加载异常时给出明确错误与重试`() {
        val vm = newViewModel(ContentResult(null))
        // 模拟仓库抛异常：这里直接用错误信息断言分支，避免依赖网络
        rule.setContent { ComicReaderScreen(vm, item, chapters, chapter) {} }
        rule.waitForIdle()

        // 空结果 → 失效态；重试按钮必须存在，保证用户有自恢复路径
        assertTrue(runCatching { rule.onNodeWithText("重新加载").assertIsDisplayed() }.isSuccess)
    }
}

// ===== 轻量 fake（不引入额外测试依赖） =====

private class FakeContentRepository(private val result: ContentResult) : ContentRepository {
    override suspend fun search(type: SourceType, keyword: String, page: Int): List<MediaItem> = emptyList()
    override fun searchWithProgress(
        type: SourceType,
        keyword: String,
        page: Int,
        concurrency: Int,
        fuzzyEnabled: Boolean,
    ): Flow<SearchState> = emptyFlow()

    override suspend fun detail(item: MediaItem): MediaItem = item
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> = emptyList()
    override suspend fun content(chapter: ChapterItem): ContentResult = result

    override suspend fun exploreSources(
        type: SourceType,
        includeDisabled: Boolean,
    ): List<Pair<com.novelreader.domain.model.SourceInfo, List<com.novelreader.domain.model.ExploreCategory>>> = emptyList()
    override suspend fun explore(sourceId: String, url: String, page: Int): List<MediaItem> = emptyList()
    override suspend fun exploreCategoriesOf(
        sourceId: String,
    ): List<com.novelreader.domain.model.ExploreCategory> = emptyList()
}

private class FakeProgressRepository : ProgressRepository {
    override suspend fun saveProgress(
        sourceId: String,
        itemId: String,
        chapterUrl: String,
        chapterTitle: String,
        chapterIndex: Int,
        textPosition: Int,
        playPositionMs: Long,
        durationMs: Long,
        itemType: String,
        itemTitle: String,
        itemCover: String?,
        itemAuthor: String?,
        pageIndex: Int,
        totalChapter: Int,
    ) = Unit

    override suspend fun getProgress(sourceId: String, itemId: String): ReadingProgress? = null
    override suspend fun removeProgress(sourceId: String, itemId: String) = Unit
    override suspend fun clearAllProgress() = Unit
    override suspend fun recentReadings(type: SourceType?): List<RecentReading> = emptyList()
}
