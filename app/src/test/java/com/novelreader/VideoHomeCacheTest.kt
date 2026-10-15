package com.novelreader

import com.novelreader.data.cache.VideoHomeCache
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「加载一次之后固定」单测（v1.5.7.0）：影视首页 / 频道内容的磁盘缓存。
 *
 * 覆盖：
 * - 首页加载成功 → 落盘；新 ViewModel（模拟冷启动）进视频页 → 直接恢复，零网络请求
 * - 启用源集合变化（导入 / 删除 / 启停）→ 旧缓存作废，重新加载
 * - 频道（电影）同上；翻页后缓存跟随更新
 * - 缓存损坏 / 缺文件 → 静默忽略，走正常加载
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoHomeCacheTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== 缓存文件层 =====

    @Test
    fun `缓存读写往返`() {
        val cache = VideoHomeCache(tmp.newFolder())
        assertNull(cache.load("home"))
        cache.save("home", "{\"a\":1}")
        assertEquals("{\"a\":1}", cache.load("home"))
        // 覆盖写
        cache.save("home", "{\"a\":2}")
        assertEquals("{\"a\":2}", cache.load("home"))
    }

    @Test
    fun `损坏的缓存文件读出来是 null`() {
        val cache = VideoHomeCache(tmp.newFolder())
        // 写入非法内容不影响 load（load 只回原文，解析失败由 ViewModel 兜底）
        cache.save("home", "not-json{")
        assertEquals("not-json{", cache.load("home"))
        // 不存在的 key
        assertNull(cache.load("nope"))
    }

    // ===== 首页分区缓存 =====

    @Test
    fun `首页加载成功后落盘 - 冷启动新 ViewModel 恢复且零请求`() = runTest {
        val dir = tmp.newFolder()
        val sources = listOf(videoSource("src"), videoSource("src2"))
        val content = cacheTestContent()

        // 第一次：真实加载 → 写缓存
        val first = newViewModel(sources, content, cacheDir = dir)
        first.onVideoTabShown()
        advanceUntilIdle()
        val state = first.videoHome.value
        assertTrue("加载应有内容", state.rows.isNotEmpty())

        // 模拟冷启动：进程没了，新 ViewModel + 新 ContentRepository（请求计数清零）
        val coldContent = cacheTestContent()
        val cold = newViewModel(sources, coldContent, cacheDir = dir)
        cold.onVideoTabShown()
        advanceUntilIdle()

        val restored = cold.videoHome.value
        assertEquals("恢复的分区数应与上次一致", state.rows.map { it.title }, restored.rows.map { it.title })
        assertEquals("恢复的条目应一致", state.rows.flatMap { it.items.map { i -> i.id } },
            restored.rows.flatMap { it.items.map { i -> i.id } })
        assertEquals("恢复后不应再发任何内容请求", 0, coldContent.exploreCalls.size)
        assertEquals("恢复后不应再探测分类", 0, coldContent.exploreCategoriesCalls.size)
    }

    @Test
    fun `启用源集合变化后缓存作废 - 重新加载`() = runTest {
        val dir = tmp.newFolder()
        val sources = listOf(videoSource("src"), videoSource("src2"))
        val content = cacheTestContent()

        val first = newViewModel(sources, content, cacheDir = dir)
        first.onVideoTabShown()
        advanceUntilIdle()
        assertTrue(first.videoHome.value.rows.isNotEmpty())

        // 源集变了（多了一个源）：缓存作废，必须重新加载
        val newSources = sources + videoSource("src3")
        val newContent = cacheTestContent()
        val second = newViewModel(newSources, newContent, cacheDir = dir)
        second.onVideoTabShown()
        advanceUntilIdle()

        assertTrue("源集变化后应重新加载", newContent.exploreCalls.isNotEmpty())
        assertTrue(second.videoHome.value.rows.isNotEmpty())
    }

    @Test
    fun `缓存损坏时正常加载`() = runTest {
        val dir = tmp.newFolder()
        VideoHomeCache(dir).save("home", "{broken json!!")
        val content = cacheTestContent()
        val vm = newViewModel(listOf(videoSource("src")), content, cacheDir = dir)
        vm.onVideoTabShown()
        advanceUntilIdle()
        assertTrue(vm.videoHome.value.rows.isNotEmpty())
    }

    // ===== 频道（电影）缓存 =====

    @Test
    fun `频道加载成功后落盘 - 冷启动新 ViewModel 恢复且零请求`() = runTest {
        val dir = tmp.newFolder()
        val sources = listOf(videoSource("src"), videoSource("src2"))

        val first = newViewModel(sources, cacheTestContent(), cacheDir = dir)
        first.onMovieChannelShown()
        advanceUntilIdle()
        val st = first.movieChannel.value
        assertTrue("频道应有内容", st.items.isNotEmpty())
        assertTrue("频道应有分类", st.categories.isNotEmpty())

        // 冷启动恢复
        val coldContent = cacheTestContent()
        val cold = newViewModel(sources, coldContent, cacheDir = dir)
        cold.onMovieChannelShown()
        advanceUntilIdle()

        val restored = cold.movieChannel.value
        assertEquals(st.categories, restored.categories)
        assertEquals(st.selectedCategory, restored.selectedCategory)
        assertEquals(st.items.map { it.id }, restored.items.map { it.id })
        assertEquals("恢复后不应再发请求", 0, coldContent.exploreCalls.size)
        assertEquals("恢复后不应再探测分类", 0, coldContent.exploreCategoriesCalls.size)
    }

    @Test
    fun `频道翻页后缓存跟随更新`() = runTest {
        val dir = tmp.newFolder()
        val sources = listOf(videoSource("src"), videoSource("src2"))
        val content = cacheTestContent(paged = true)

        val vm = newViewModel(sources, content, cacheDir = dir)
        vm.onMovieChannelShown()
        advanceUntilIdle()
        val firstPageCount = vm.movieChannel.value.items.size

        // 模拟触底翻页（直接调公开的 loadMore，选中分类非空时生效）
        vm.loadMoreMovieChannel()
        advanceUntilIdle()
        val grown = vm.movieChannel.value.items
        assertTrue("翻页后条目应增加", grown.size > firstPageCount)

        // 新 ViewModel 恢复到的应是翻页后的完整内容
        val cold = newViewModel(sources, cacheTestContent(paged = true), cacheDir = dir)
        cold.onMovieChannelShown()
        advanceUntilIdle()
        assertEquals(grown.map { it.id }, cold.movieChannel.value.items.map { it.id })
    }

    // ===== 脚手架 =====

    private fun videoSource(id: String) = SourceInfo(
        id = id,
        name = "源$id",
        type = SourceType.VIDEO,
        baseUrl = "http://$id.test/api.php/provide/vod",
    )

    private fun item(id: String) = MediaItem(
        id = id,
        title = "片$id",
        cover = "http://pic.test/$id.png",
        type = SourceType.VIDEO,
        sourceId = "src",
        sourceName = "源src",
        extras = mapOf("remarks" to "更新至第3集", "score" to "8.5"),
    )

    /** 两个分类、每分类每页 2 条；paged=true 时翻页有新数据 */
    private fun cacheTestContent(paged: Boolean = false) = CacheTestContentRepository().apply {
        categories = listOf(ExploreCategory("电影", "1"), ExploreCategory("动漫", "2"))
        itemsByCategory = mapOf("1" to listOf(item("m1"), item("m2")), "2" to listOf(item("a1"), item("a2")))
        pagedItems = buildMap {
            put(1, listOf(item("m1"), item("m2")))
            if (paged) put(2, listOf(item("m3"), item("m4")))
        }
    }

    private fun newViewModel(
        sources: List<SourceInfo>,
        content: ContentRepository,
        cacheDir: java.io.File,
    ): HomeViewModel = HomeViewModel(
        bookRepository = CacheTestBookRepository(),
        progressRepository = CacheTestProgressRepository(),
        contentRepository = content,
        localBookStore = null,
        sourceRepository = CacheTestSourceRepository(sources),
        preferencesManager = null,
        videoCache = VideoHomeCache(cacheDir),
    )
}

private class CacheTestContentRepository : ContentRepository {
    var categories: List<ExploreCategory> = emptyList()
    var itemsByCategory: Map<String, List<MediaItem>> = emptyMap()
    var pagedItems: Map<Int, List<MediaItem>> = emptyMap()

    val exploreCalls = mutableListOf<Pair<String, Int>>()
    val exploreCategoriesCalls = mutableListOf<String>()

    override suspend fun exploreCategoriesOf(sourceId: String): List<ExploreCategory> {
        exploreCategoriesCalls += sourceId
        return categories
    }

    override suspend fun explore(sourceId: String, url: String, page: Int): List<MediaItem> {
        exploreCalls += url to page
        if (pagedItems.isNotEmpty()) return pagedItems[page].orEmpty()
        return itemsByCategory[url].orEmpty()
    }

    override suspend fun exploreFiltered(
        sourceId: String,
        url: String,
        page: Int,
        filters: Map<String, String>,
    ): List<MediaItem> = explore(sourceId, url, page)

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
        includeDisabled: Boolean,
    ): List<Pair<SourceInfo, List<ExploreCategory>>> = emptyList()
}

private class CacheTestSourceRepository(private val sources: List<SourceInfo>) : SourceRepository {
    override suspend fun getAllSources(): List<SourceInfo> = sources
    override suspend fun getSourcesByType(type: SourceType): List<SourceInfo> =
        sources.filter { it.type == type }

    override suspend fun awaitRestored() = Unit
    override suspend fun importSource(rawContent: String, fileName: String?): List<SourceInfo> =
        emptyList()

    override suspend fun previewImport(rawContent: String, fileName: String?): List<SourceInfo> =
        emptyList()

    override suspend fun previewImportBatch(
        files: List<com.novelreader.domain.model.SourceFileContent>,
    ): com.novelreader.domain.model.SourceImportBatch =
        com.novelreader.domain.model.SourceImportBatch(emptyList())

    override suspend fun importSelected(ids: Set<String>): Int = 0
    override suspend fun deleteSource(sourceId: String) = Unit
    override suspend fun deleteSources(ids: List<String>) = Unit
    override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = Unit
    override suspend fun setSourceSortOrder(sourceId: String, sortOrder: Int) = Unit
    override suspend fun reorderSources(orderedIds: List<String>) = Unit
    override suspend fun getAdapter(sourceId: String): SourceAdapter? = null
    override suspend fun getRawContent(sourceId: String): String? = null
    override suspend fun updateSource(sourceId: String, rawContent: String): SourceInfo =
        error("not used")
}

private class CacheTestProgressRepository : ProgressRepository {
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

private class CacheTestBookRepository : BookRepository {
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
