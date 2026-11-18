package com.novelreader

import androidx.test.core.app.ApplicationProvider
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.repository.BookRepository
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.ui.screens.comic.restoredComicSearchSourceIds
import com.novelreader.ui.screens.novel.NovelSearchViewModel
import com.novelreader.ui.screens.novel.restoredNovelSearchSourceIds
import com.novelreader.ui.screens.tag.restoredTagSearchSourceIds
import com.novelreader.ui.screens.tag.tagItemsInSelectedSources
import com.novelreader.ui.screens.video.restoredVideoSearchSourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 搜索源记忆 + 标签搜索源过滤（v1.9.7.0）。
 *
 * 两个回归点：
 * 1. 持久化读回的源 id 必须和「当前可用源」取交集，且不能在源列表加载前拿空集合覆盖；
 * 2. 标签搜索的本地索引是跨源缓存，选源后必须在**合并之前**按 sourceIds 收窄，
 *    否则别的源的同名作品（连封面 / 详情链接）会被 SearchResultMerge 保留下来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSourceMemoryTest {

    /** ViewModel 的 viewModelScope 走 Dispatchers.Main，必须让它和 runTest 共用调度器，
     *  否则 advanceUntilIdle() 推不到 ViewModel 内部的 launch，断言时搜索还没发起。 */
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun prefs(): PreferencesManager =
        PreferencesManager(ApplicationProvider.getApplicationContext())

    private fun source(id: String) = SourceInfo(id = id, name = id, type = SourceType.NOVEL)

    private fun item(id: String, title: String, sourceId: String, cover: String? = null) = MediaItem(
        id = id,
        title = title,
        cover = cover,
        type = SourceType.NOVEL,
        sourceId = sourceId,
    )

    @Test
    fun `读回持久化源 id 时过滤掉已删除或停用的源`() {
        val remembered = setOf("srcA", "srcB", "srcGone")
        val available = setOf("srcA", "srcB")
        assertEquals(setOf("srcA", "srcB"), restoredNovelSearchSourceIds(remembered, available))
        assertEquals(setOf("srcA", "srcB"), restoredComicSearchSourceIds(remembered, available))
        assertEquals(setOf("srcA", "srcB"), restoredVideoSearchSourceIds(remembered, available))
        assertEquals(setOf("srcA", "srcB"), restoredTagSearchSourceIds(remembered, available))
        // 记忆里的源全被停用 → 回落成空集合（UI 口径 = 全部源），不能留一个搜不到的 id
        assertEquals(emptySet<String>(), restoredTagSearchSourceIds(setOf("srcGone"), available))
    }

    @Test
    fun `四个搜索页的选源偏好各自独立持久化`() = runBlocking {
        val p = prefs()
        p.setNovelSearchSelectedSourceIds(setOf("n1"))
        p.setComicSearchSelectedSourceIds(setOf("c1"))
        p.setVideoSearchSelectedSourceIds(setOf("v1"))
        p.setTagSearchSelectedSourceIds(setOf("t1"))
        assertEquals(setOf("n1"), p.novelSearchSelectedSourceIdsFlow.first())
        assertEquals(setOf("c1"), p.comicSearchSelectedSourceIdsFlow.first())
        assertEquals(setOf("v1"), p.videoSearchSelectedSourceIdsFlow.first())
        assertEquals(setOf("t1"), p.tagSearchSelectedSourceIdsFlow.first())
    }

    @Test
    fun `本地索引结果按选中源过滤 不混入其它源的同名作品`() {
        val indexed = listOf(
            item("1", "同一部", "srcA", cover = "cover-a"),
            item("2", "同一部", "srcB", cover = "cover-b"),
            item("3", "另一部", "srcA"),
        )
        val filtered = tagItemsInSelectedSources(indexed, setOf("srcA"))
        assertEquals(listOf(indexed[0], indexed[2]), filtered)
        assertTrue(filtered.all { it.sourceId == "srcA" })
        // 未选源 = 全部源，行为不变
        assertEquals(indexed, tagItemsInSelectedSources(indexed, emptySet()))
    }

    @Test
    fun `复合搜索并集同样按选中源过滤`() {
        // 复合搜索：各标签的并集里混着 srcB，结果层必须和本地索引走同一份过滤口径
        val union = listOf(
            item("10", "标签甲作品", "srcA"),
            item("11", "标签甲作品", "srcB"),
            item("12", "标签乙作品", "srcA"),
        )
        val filtered = tagItemsInSelectedSources(union, setOf("srcA"))
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.sourceId == "srcA" })
    }

    @Test
    fun `选源后搜索参数带 sourceIds`() = runBlocking {
        val content = RecordingContentRepository()
        val prefs = prefs()
        // 预热 DataStore 内存缓存：VM 的 search() 里要 first() 读偏好，不预热会卡在 IO 调度上
        prefs.searchConcurrencyFlow.first()
        prefs.fuzzySearchFlow.first()
        val vm = NovelSearchViewModel(
            contentRepository = content,
            preferencesManager = prefs,
            bookRepository = EmptyBookRepository(),
            sourceRepository = FakeSourceRepository(listOf(source("srcA"), source("srcB"))),
        )
        delay(200)
        vm.setSelectedSources(setOf("srcA"))
        delay(200)

        vm.search("关键词")
        delay(200)
        assertEquals(setOf("srcA"), vm.selectedSourceIds.value)
        assertEquals(1, content.calls.size)
        assertEquals(setOf("srcA"), content.calls[0].sourceIds)
    }

    @Test
    fun `分页 loadMore 仍带同一批 sourceIds`() = runBlocking {
        val content = RecordingContentRepository()
        val prefs = prefs()
        // 预热 DataStore 内存缓存：VM 的 search() 里要 first() 读偏好，不预热会卡在 IO 调度上
        prefs.searchConcurrencyFlow.first()
        prefs.fuzzySearchFlow.first()
        val vm = NovelSearchViewModel(
            contentRepository = content,
            preferencesManager = prefs,
            bookRepository = EmptyBookRepository(),
            sourceRepository = FakeSourceRepository(listOf(source("srcA"), source("srcB"))),
        )
        delay(200)
        vm.setSelectedSources(setOf("srcA"))
        delay(200)
        vm.search("关键词")
        delay(200)
        assertEquals(1, content.calls.size)
        assertTrue(vm.hasMore.value)

        vm.loadMore()
        delay(200)
        assertEquals(2, content.calls.size)
        assertEquals(2, content.calls[1].page)
        assertEquals(setOf("srcA"), content.calls[1].sourceIds)
    }

    @Test
    fun `源列表晚到时用户已选的源不会被空列表覆盖`() = runBlocking {
        val observed = MutableStateFlow<List<SourceInfo>>(emptyList())
        val vm = NovelSearchViewModel(
            contentRepository = RecordingContentRepository(),
            preferencesManager = prefs(),
            bookRepository = EmptyBookRepository(),
            // 首次 getSourcesByType 为空，真实列表稍后才从 observeSources 推来
            sourceRepository = FakeSourceRepository(emptyList(), observed),
        )
        delay(200)
        // 源列表还没到位时用户已经选好 srcA
        vm.setSelectedSources(setOf("srcA"))
        delay(200)

        observed.value = listOf(source("srcA"), source("srcB"))
        delay(200)
        // 迟到的源列表只能刷新下拉，不能把用户的选择冲掉
        assertEquals(setOf("srcA"), vm.selectedSourceIds.value)
    }

    @Test
    fun `改选源后立即按新范围重搜 不带旧源`() = runBlocking {
        val content = RecordingContentRepository()
        val prefs = prefs()
        // 预热 DataStore 内存缓存：VM 的 search() 里要 first() 读偏好，不预热会卡在 IO 调度上
        prefs.searchConcurrencyFlow.first()
        prefs.fuzzySearchFlow.first()
        val vm = NovelSearchViewModel(
            contentRepository = content,
            preferencesManager = prefs,
            bookRepository = EmptyBookRepository(),
            sourceRepository = FakeSourceRepository(listOf(source("srcA"), source("srcB"))),
        )
        delay(200)
        vm.setSelectedSources(setOf("srcA"))
        vm.onQueryChange("关键词")
        vm.search()
        delay(200)
        assertEquals(setOf("srcA"), content.calls.last().sourceIds)

        vm.setSelectedSources(setOf("srcB"))
        delay(200)
        assertEquals(setOf("srcB"), vm.selectedSourceIds.value)
        // 改选时已有查询词会立即重搜，参数必须是新源而不是旧源
        assertEquals(setOf("srcB"), content.calls.last().sourceIds)
    }
}

/** 记录每次 searchWithProgress 的分页与源范围，用来断言 sourceIds 没有在分支里丢掉。 */
private class RecordingContentRepository : ContentRepository {
    data class Call(val page: Int, val sourceIds: Set<String>?)
    val calls = mutableListOf<Call>()

    override suspend fun search(type: SourceType, keyword: String, page: Int): List<MediaItem> = emptyList()

    override fun searchWithProgress(
        type: SourceType,
        keyword: String,
        page: Int,
        concurrency: Int,
        fuzzyEnabled: Boolean,
        sourceIds: Set<String>?,
    ): Flow<SearchState> {
        calls += Call(page, sourceIds)
        return flowOf(
            SearchState(
                isSearching = false,
                hasSearched = true,
                results = listOf(
                    MediaItem(
                        id = "item-$page",
                        title = "结果$page",
                        type = type,
                        sourceId = sourceIds?.firstOrNull().orEmpty(),
                    )
                ),
            )
        )
    }

    override suspend fun detail(item: MediaItem): MediaItem = item
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> =
        listOf(ChapterItem(id = "c", title = "目录", url = "c"))
    override suspend fun content(chapter: ChapterItem): ContentResult = ContentResult()
    override suspend fun exploreSources(
        type: SourceType,
        includeDisabled: Boolean,
    ): List<Pair<SourceInfo, List<ExploreCategory>>> = emptyList()
    override suspend fun exploreCategoriesOf(sourceId: String): List<ExploreCategory> = emptyList()
    override suspend fun explore(sourceId: String, url: String, page: Int): List<MediaItem> = emptyList()
}

private class FakeSourceRepository(
    private val sources: List<SourceInfo>,
    private val observed: Flow<List<SourceInfo>> = emptyFlow(),
) : SourceRepository {
    override fun observeSources(): Flow<List<SourceInfo>> = observed
    override suspend fun getAllSources(): List<SourceInfo> = sources
    override suspend fun getSourcesByType(type: SourceType): List<SourceInfo> =
        sources.filter { it.type == type && it.enabled }
    override suspend fun awaitRestored() = Unit
    override suspend fun importSource(rawContent: String, fileName: String?): List<SourceInfo> = emptyList()
    override suspend fun previewImport(
        rawContent: String,
        fileName: String?,
        onProgress: ((done: Int, total: Int) -> Unit)?,
    ): List<SourceInfo> = emptyList()
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
    override suspend fun updateSource(sourceId: String, rawContent: String): SourceInfo = error("not used")
}

private class EmptyBookRepository : BookRepository {
    override fun observeBookshelf(): Flow<List<MediaItem>> = emptyFlow()
    override fun observeBookshelfByType(type: SourceType): Flow<List<MediaItem>> = emptyFlow()
    override suspend fun isCollected(item: MediaItem): Boolean = false
    override suspend fun addToBookshelf(item: MediaItem) = Unit
    override suspend fun removeFromBookshelf(item: MediaItem) = Unit
    override suspend fun removeFromBookshelfBatch(items: List<MediaItem>) = Unit
    override suspend fun setBookGroup(item: MediaItem, group: String) = Unit
    override suspend fun setBookGroupBatch(items: List<MediaItem>, group: String) = Unit
    override fun observeGroupNames(type: SourceType): Flow<List<String>> = emptyFlow()
    override suspend fun updateBookCover(item: MediaItem, coverUrl: String) = Unit
    override suspend fun renameGroup(type: SourceType, oldName: String, newName: String) = Unit
    override suspend fun deleteGroup(type: SourceType, name: String) = Unit
}
