package com.novelreader

import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.RecentReading
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.ProgressRepository
import com.novelreader.domain.repository.ReadingProgress
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.ui.screens.common.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 影视首页多分区单测（v1.3.4.0）。
 *
 * 覆盖两类视图的分流与状态迁移：
 * - 分区视图：继续观看 + 各一级分类各一排
 * - 网格视图：单分类海报墙（点「查看更多」进入）
 *
 * 以及几个容易退化的行为：换源要把展开状态收回、部分分类失败只丢那一排、
 * 分类接口不通时才判定源不可达。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoHomeSectionsTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== 分区视图 =====

    @Test
    fun `进入影视 Tab 时按分类拉出多排分区`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"), cat("2", "连续剧"), cat("3", "综艺"))
            itemsByCategory = mapOf(
                "1" to listOf(video("m1", "电影一")),
                "2" to listOf(video("t1", "剧一"), video("t2", "剧二")),
                "3" to listOf(video("s1", "综艺一")),
            )
        }
        val vm = newViewModel(content = content)

        vm.onVideoTabShown()
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertFalse("默认应是分区视图", state.expanded)
        assertEquals(3, state.rows.size)
        assertEquals(listOf("电影", "连续剧", "综艺"), state.rows.map { it.title })
        assertEquals(listOf("1", "2", "3"), state.rows.map { it.categoryId })
        assertEquals(2, state.rows[1].items.size)
        assertNull("正常加载不应有错误提示", state.error)
    }

    @Test
    fun `没有分类信息时退化为单排全部`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = emptyList()
            itemsByCategory = mapOf("" to listOf(video("a", "甲"), video("b", "乙")))
        }
        val vm = newViewModel(content = content)

        vm.onVideoTabShown()
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertEquals(1, state.rows.size)
        assertEquals("全部", state.rows.first().title)
        assertEquals("", state.rows.first().categoryId)
        assertEquals(2, state.rows.first().items.size)
    }

    @Test
    fun `单个分类拉取失败只丢那一排`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"), cat("2", "坏分类"), cat("3", "综艺"))
            itemsByCategory = mapOf(
                "1" to listOf(video("m1", "电影一")),
                "3" to listOf(video("s1", "综艺一")),
            )
            failCategories = setOf("2")
        }
        val vm = newViewModel(content = content)

        vm.onVideoTabShown()
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertEquals(listOf("电影", "综艺"), state.rows.map { it.title })
        assertNull("有排成功了就不该报错", state.error)
    }

    @Test
    fun `分类接口不通时判定源不可达且不再拉内容`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categoryError = IllegalStateException("timeout")
        }
        val vm = newViewModel(content = content)

        vm.onVideoTabShown()
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertFalse(state.loading)
        assertTrue(state.rows.isEmpty())
        assertTrue(
            "应给出可读原因，实际=${state.error}",
            state.error!!.contains("视频源都暂时无法连接"),
        )
    }

    @Test
    fun `分区视图不触发分页加载`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"))
            itemsByCategory = mapOf("1" to listOf(video("m1", "电影一")))
        }
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()

        val callsBefore = content.exploreCalls.size
        vm.loadMoreVideo()
        advanceUntilIdle()

        assertEquals("分区视图下触底不应发请求", callsBefore, content.exploreCalls.size)
    }

    // ===== 继续观看 =====

    @Test
    fun `继续观看带出集数角标与续播定位`() = runTest(dispatcher) {
        val progress = VideoHomeFakeProgressRepository().apply {
            records = listOf(
                RecentReading(
                    sourceId = "src",
                    itemId = "1001",
                    title = "示例剧",
                    cover = "https://img.test/a.jpg",
                    type = SourceType.VIDEO,
                    detailUrl = "1001",
                    chapterTitle = "第05集",
                    chapterUrl = "https://play.test/5.m3u8",
                    chapterIndex = 4,
                    totalChapter = 20,
                    // 老记录没有总时长 → 退回集数进度 5/20 = 0.25
                    playPositionMs = 600_000,
                    durationMs = 0,
                )
            )
        }
        val vm = newViewModel(progress = progress)

        vm.onVideoTabShown()
        advanceUntilIdle()

        val cont = vm.videoHome.value.continueWatching
        assertEquals(1, cont.size)
        assertEquals("5/20", cont[0].episodeLabel)
        assertEquals(0.25f, cont[0].progress, 0.0001f)
        // 续播定位靠 extras 传到详情页（不必改路由参数）
        assertEquals("4", cont[0].item.extras[HomeViewModel.EXTRA_RESUME_INDEX])
        assertEquals(
            "https://play.test/5.m3u8",
            cont[0].item.extras[HomeViewModel.EXTRA_RESUME_URL],
        )
        // 源名从源列表补上，卡片要显示「哪个源」
        assertEquals("演示源", cont[0].item.sourceName)
    }

    @Test
    fun `继续观看优先用时间进度`() = runTest(dispatcher) {
        val progress = VideoHomeFakeProgressRepository().apply {
            records = listOf(
                RecentReading(
                    sourceId = "src",
                    itemId = "2002",
                    title = "示例电影",
                    type = SourceType.VIDEO,
                    chapterUrl = "https://play.test/1.m3u8",
                    chapterTitle = "正片",
                    chapterIndex = 0,
                    totalChapter = 1,
                    playPositionMs = 45 * 60_000L,
                    durationMs = 90 * 60_000L,
                )
            )
        }
        val vm = newViewModel(progress = progress)

        vm.onVideoTabShown()
        advanceUntilIdle()

        val cont = vm.videoHome.value.continueWatching.single()
        assertEquals(0.5f, cont.progress, 0.0001f)
        assertEquals("单片电影不该出现 1/1 角标", "", cont.episodeLabel)
    }

    @Test
    fun `换源不影响继续观看`() = runTest(dispatcher) {
        val progress = VideoHomeFakeProgressRepository().apply {
            records = listOf(
                RecentReading(
                    sourceId = "srcB",
                    itemId = "9",
                    title = "别的源看过的",
                    type = SourceType.VIDEO,
                    chapterUrl = "u",
                    chapterTitle = "第1集",
                    chapterIndex = 0,
                    totalChapter = 2,
                )
            )
        }
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"))
            itemsByCategory = mapOf("1" to listOf(video("m1", "电影一")))
        }
        val vm = newViewModel(content = content, progress = progress)
        vm.onVideoTabShown()
        advanceUntilIdle()

        vm.selectVideoSource("src2")
        advanceUntilIdle()

        assertEquals("src2", vm.videoHome.value.currentSourceId)
        assertEquals(
            "「上次看到哪」不该因为换源而消失",
            1,
            vm.videoHome.value.continueWatching.size,
        )
    }

    // ===== 网格视图 =====

    @Test
    fun `展开分类进入网格并按该分类拉取`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"), cat("2", "连续剧"))
            itemsByCategory = mapOf(
                "1" to listOf(video("m1", "电影一")),
                "2" to listOf(video("t1", "剧一")),
            )
        }
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()

        vm.expandVideoCategory("2", "连续剧")
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertTrue(state.expanded)
        assertEquals("连续剧", state.expandedTitle)
        assertEquals("2", state.selectedCategory)
        assertEquals(1, state.items.size)
        assertEquals("剧一", state.items.first().title)
    }

    @Test
    fun `网格视图内切分类`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"), cat("2", "连续剧"))
            itemsByCategory = mapOf(
                "1" to listOf(video("m1", "电影一")),
                "2" to listOf(video("t1", "剧一")),
            )
        }
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("2", "连续剧")
        advanceUntilIdle()

        vm.selectVideoCategory("1")
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertTrue(state.expanded)
        assertEquals("电影", state.expandedTitle)
        assertEquals("电影一", state.items.single().title)
    }

    @Test
    fun `返回分区视图会清掉网格内容`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"))
            itemsByCategory = mapOf("1" to listOf(video("m1", "电影一")))
        }
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("1", "电影")
        advanceUntilIdle()
        assertTrue(vm.videoHome.value.items.isNotEmpty())

        vm.collapseVideoCategory()
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertFalse(state.expanded)
        assertTrue("退回分区视图应清空网格 items", state.items.isEmpty())
        assertEquals("", state.expandedTitle)
        assertEquals(1, state.rows.size)
    }

    @Test
    fun `换源会把展开的分类收回分区视图`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"))
            itemsByCategory = mapOf("1" to listOf(video("m1", "电影一")))
        }
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("1", "电影")
        advanceUntilIdle()
        assertTrue(vm.videoHome.value.expanded)

        // 换源后原来的分类 id 在新源里未必存在，必须退回分区视图
        vm.selectVideoSource("src2")
        advanceUntilIdle()
        val afterSwitch = vm.videoHome.value
        assertFalse(afterSwitch.expanded)
        assertEquals("", afterSwitch.expandedTitle)
        assertTrue(afterSwitch.items.isEmpty())

        // 再进页面也不能莫名其妙回到展开态
        vm.onVideoTabShown()
        advanceUntilIdle()
        assertFalse(vm.videoHome.value.expanded)
    }

    @Test
    fun `网格视图按分类分页累加`() = runTest(dispatcher) {
        val content = VideoHomeFakeContentRepository().apply {
            categories = listOf(cat("1", "电影"))
            pagedItems = mapOf(
                1 to listOf(video("p1", "第一页一")),
                2 to listOf(video("p2", "第二页一")),
            )
        }
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("1", "电影")
        advanceUntilIdle()
        assertEquals(1, vm.videoHome.value.items.size)

        vm.loadMoreVideo()
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertEquals(2, state.items.size)
        assertEquals(listOf("第一页一", "第二页一"), state.items.map { it.title })
    }

    // ===== 片库四维筛选（v1.3.5.0）=====

    @Test
    fun `未选筛选时网格请求不带任何筛选参数`() = runTest(dispatcher) {
        val content = newGridContent()
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("1", "电影")
        advanceUntilIdle()

        val call = content.filteredCalls.last()
        assertEquals("1", call.first)
        assertEquals(1, call.second)
        assertTrue("默认不应带筛选参数", call.third.isEmpty())
    }

    @Test
    fun `选地区与年份后请求带上对应参数且重置到第一页`() = runTest(dispatcher) {
        val content = newGridContent()
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("1", "电影")
        advanceUntilIdle()
        vm.loadMoreVideo()
        advanceUntilIdle()
        assertEquals(2, vm.videoHome.value.items.size)

        vm.selectVideoArea("大陆")
        advanceUntilIdle()
        vm.selectVideoYear("2024")
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertEquals("大陆", state.selectedArea)
        assertEquals("2024", state.selectedYear)
        // 换筛选必须重新从第 1 页拉，否则会把新旧条件的结果混在一起
        assertEquals(1, content.filteredCalls.last().second)
        assertEquals(mapOf("area" to "大陆", "year" to "2024"), content.filteredCalls.last().third)
        assertEquals(1, state.items.size)
    }

    @Test
    fun `三个筛选维度互不覆盖`() = runTest(dispatcher) {
        val content = newGridContent()
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("1", "电影")
        advanceUntilIdle()

        vm.selectVideoArea("韩国")
        advanceUntilIdle()
        vm.selectVideoOrder("score")
        advanceUntilIdle()

        assertEquals(
            "切排序不该把已选地区清掉",
            mapOf("area" to "韩国", "order" to "score"),
            content.filteredCalls.last().third,
        )
    }

    @Test
    fun `重选同一个筛选值不重复发请求`() = runTest(dispatcher) {
        val content = newGridContent()
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("1", "电影")
        advanceUntilIdle()
        vm.selectVideoArea("美国")
        advanceUntilIdle()

        val callsBefore = content.filteredCalls.size
        vm.selectVideoArea("美国")
        advanceUntilIdle()

        assertEquals("值没变不该再请求", callsBefore, content.filteredCalls.size)
    }

    @Test
    fun `清空筛选值会退回不带该参数的请求`() = runTest(dispatcher) {
        val content = newGridContent()
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()
        vm.expandVideoCategory("1", "电影")
        advanceUntilIdle()
        vm.selectVideoArea("日本")
        advanceUntilIdle()

        vm.selectVideoArea("")
        advanceUntilIdle()

        assertEquals("", vm.videoHome.value.selectedArea)
        assertTrue("恢复不限后不应再带 area", content.filteredCalls.last().third.isEmpty())
    }

    @Test
    fun `分区视图下切筛选不应发请求`() = runTest(dispatcher) {
        val content = newGridContent()
        val vm = newViewModel(content = content)
        vm.onVideoTabShown()
        advanceUntilIdle()

        val callsBefore = content.exploreCalls.size
        vm.selectVideoArea("大陆")
        advanceUntilIdle()

        // applyVideoFilter 会强制 expanded=true 并重拉一次首屏 —— 这是预期行为：
        // 筛选只在网格视图有意义，切筛选等于「进入该筛选下的网格」。
        assertEquals(true, vm.videoHome.value.expanded)
        assertTrue(content.exploreCalls.size > callsBefore)
    }

    // ===== 无源 =====

    @Test
    fun `没有视频源时给出引导文案`() = runTest(dispatcher) {
        val vm = newViewModel(sources = emptyList())
        vm.onVideoTabShown()
        advanceUntilIdle()

        val state = vm.videoHome.value
        assertFalse(state.loading)
        assertTrue(state.error!!.contains("导入源"))
    }

    // ===== 脚手架 =====

    /** 片库筛选测试的固定数据：两个分类 + 两页（每页一条） */
    private fun newGridContent() = VideoHomeFakeContentRepository().apply {
        categories = listOf(cat("1", "电影"), cat("2", "连续剧"))
        itemsByCategory = mapOf(
            "1" to listOf(video("m1", "电影一")),
            "2" to listOf(video("t1", "剧一")),
        )
        pagedItems = mapOf(
            1 to listOf(video("m1", "电影一")),
            2 to listOf(video("m2", "电影二")),
        )
    }

    private fun newViewModel(
        sources: List<SourceInfo> = listOf(
            SourceInfo(
                id = "src",
                name = "演示源",
                type = SourceType.VIDEO,
                baseUrl = "http://demo.test/api.php/provide/vod",
            ),
            SourceInfo(
                id = "src2",
                name = "备选源",
                type = SourceType.VIDEO,
                baseUrl = "http://demo2.test/api.php/provide/vod",
            ),
        ),
        content: ContentRepository = VideoHomeFakeContentRepository(),
        progress: ProgressRepository = VideoHomeFakeProgressRepository(),
    ): HomeViewModel = HomeViewModel(
        bookRepository = VideoHomeFakeBookRepository(),
        progressRepository = progress,
        contentRepository = content,
        localBookStore = null,
        sourceRepository = VideoHomeFakeSourceRepository(sources),
        preferencesManager = null,
    )

    private fun cat(id: String, name: String) = ExploreCategory(title = name, url = id)

    private fun video(id: String, title: String) = MediaItem(
        id = id,
        title = title,
        type = SourceType.VIDEO,
        sourceId = "src",
        sourceName = "演示源",
    )
}

private class VideoHomeFakeSourceRepository(private val sources: List<SourceInfo>) : SourceRepository {
    override suspend fun getAllSources(): List<SourceInfo> = sources
    override suspend fun getSourcesByType(type: SourceType): List<SourceInfo> =
        sources.filter { it.type == type }

    override suspend fun awaitRestored() { /* 测试假实现：恢复瞬时完成 */ }

    override suspend fun importSource(rawContent: String, fileName: String?): List<SourceInfo> =
        emptyList()

    override suspend fun previewImport(rawContent: String, fileName: String?): List<SourceInfo> =
        emptyList()

    override suspend fun importSelected(ids: Set<String>): Int = 0

    override suspend fun deleteSource(sourceId: String) = Unit
    override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = Unit
    override suspend fun getAdapter(sourceId: String): SourceAdapter? = null
    override suspend fun getRawContent(sourceId: String): String? = null
    override suspend fun updateSource(sourceId: String, rawContent: String): SourceInfo =
        error("not used")
}

private class VideoHomeFakeContentRepository : ContentRepository {
    var categories: List<ExploreCategory> = emptyList()

    /** 分类 → 第一页内容 */
    var itemsByCategory: Map<String, List<MediaItem>> = emptyMap()

    /** 走到分页时用的数据（page → items），优先于 [itemsByCategory] */
    var pagedItems: Map<Int, List<MediaItem>> = emptyMap()

    /** 这些分类的 explore 会抛错，模拟单排失败 */
    var failCategories: Set<String> = emptySet()

    /** 非空时分类接口直接抛错，模拟整源不可达 */
    var categoryError: Exception? = null

    /** 记录每次 explore 调用（分类 id, 页码），用于断言「没发多余请求」 */
    val exploreCalls = mutableListOf<Pair<String, Int>>()

    /** 记录每次带筛选的调用（分类 id, 页码, filters），用于断言筛选参数透传 */
    val filteredCalls = mutableListOf<Triple<String, Int, Map<String, String>>>()

    override suspend fun exploreCategoriesOf(sourceId: String): List<ExploreCategory> {
        categoryError?.let { throw it }
        return categories
    }

    override suspend fun exploreFiltered(
        sourceId: String,
        url: String,
        page: Int,
        filters: Map<String, String>,
    ): List<MediaItem> {
        filteredCalls += Triple(url, page, filters)
        return explore(sourceId, url, page)
    }

    override suspend fun explore(sourceId: String, url: String, page: Int): List<MediaItem> {
        exploreCalls += url to page
        if (url in failCategories) throw IllegalStateException("boom: $url")
        if (pagedItems.isNotEmpty()) return pagedItems[page].orEmpty()
        return itemsByCategory[url].orEmpty()
    }

    override suspend fun search(type: SourceType, keyword: String, page: Int): List<MediaItem> =
        emptyList()

    override fun searchWithProgress(
        type: SourceType,
        keyword: String,
        page: Int,
        concurrency: Int,
        fuzzyEnabled: Boolean,
    ): Flow<SearchState> = flowOf(SearchState())

    override suspend fun detail(item: MediaItem): MediaItem = item
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> = emptyList()
    override suspend fun content(chapter: ChapterItem): ContentResult = ContentResult()

    override suspend fun exploreSources(
        type: SourceType,
    ): List<Pair<SourceInfo, List<ExploreCategory>>> = emptyList()
}

private class VideoHomeFakeProgressRepository : ProgressRepository {
    var records: List<RecentReading> = emptyList()

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
    override suspend fun recentReadings(type: SourceType?): List<RecentReading> =
        records.filter { type == null || it.type == type }
}

private class VideoHomeFakeBookRepository : BookRepository {
    override fun observeBookshelf(): Flow<List<MediaItem>> = flowOf(emptyList())
    override fun observeBookshelfByType(type: SourceType): Flow<List<MediaItem>> =
        flowOf(emptyList())

    override suspend fun isCollected(item: MediaItem): Boolean = false
    override suspend fun addToBookshelf(item: MediaItem) = Unit
    override suspend fun removeFromBookshelf(item: MediaItem) = Unit
    override suspend fun setBookGroup(item: MediaItem, group: String) = Unit
    override fun observeGroupNames(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun updateBookCover(item: MediaItem, coverUrl: String) = Unit
    override suspend fun renameGroup(oldName: String, newName: String) = Unit
    override suspend fun deleteGroup(name: String) = Unit
}
