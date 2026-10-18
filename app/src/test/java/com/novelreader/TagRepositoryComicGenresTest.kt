package com.novelreader

import com.novelreader.core.util.ComicGenres
import com.novelreader.core.util.NovelGenres
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.data.cache.TagStore
import com.novelreader.data.repository.TagRepository
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SearchState
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 漫画标签池集成测试（v1.6.6.0）。
 *
 * 覆盖 [TagRepository.collect] → [TagStore] 落盘这条链路：漫画源既有的发现分类
 * 与新增的「按题材搜索」入口是否都进了标签池，噪声标签是否被滤掉。
 * 这不是纯函数测试——素材直接用模拟器实测的漫画源清单形状。
 */
class TagRepositoryComicGenresTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 只实现标签收集用得到的方法；其余给最小实现。
     *
     * [VeneraStyleSources] 模拟「没有发现分类能力」的源（Venera 那 25 个）：
     * 它们不出现在 [exploreSources] 的结果里，但出现在 [searchableSourceIds] 里。
     */
    private class FakeContent(
        private val withCategories: List<Pair<SourceInfo, List<ExploreCategory>>>,
        private val searchable: List<String>,
    ) : ContentRepository {

        override suspend fun exploreSources(
            type: SourceType,
            includeDisabled: Boolean,
        ): List<Pair<SourceInfo, List<ExploreCategory>>> = withCategories

        override suspend fun searchableSourceIds(type: SourceType, limit: Int): List<String> =
            if (limit > 0) searchable.take(limit) else searchable

        override suspend fun search(type: SourceType, keyword: String, page: Int): List<MediaItem> =
            emptyList()

        override fun searchWithProgress(
            type: SourceType,
            keyword: String,
            page: Int,
            concurrency: Int,
            fuzzyEnabled: Boolean,
            sourceIds: Set<String>?,
        ): Flow<SearchState> = flowOf(SearchState())

        override suspend fun detail(item: MediaItem): MediaItem = item
        override suspend fun chapterList(item: MediaItem): List<ChapterItem> = emptyList()
        override suspend fun content(chapter: ChapterItem): ContentResult = ContentResult()
        override suspend fun explore(sourceId: String, url: String, page: Int): List<MediaItem> =
            emptyList()

        override suspend fun exploreCategoriesOf(sourceId: String): List<ExploreCategory> =
            emptyList()
    }

    private fun info(
        id: String,
        name: String,
        type: SourceType = SourceType.COMIC,
    ) = SourceInfo(
        id = id,
        name = name,
        type = type,
        version = "1",
        author = "",
        desc = "",
        baseUrl = "",
        format = com.novelreader.domain.model.SourceFormat.LEGADO,
    )

    private fun repo(
        withCategories: List<Pair<SourceInfo, List<ExploreCategory>>>,
        searchable: List<String>,
    ): TagRepository = TagRepository(
        contentRepository = FakeContent(withCategories, searchable),
        store = TagStore(tmp.newFolder()),
    )

    @Test
    fun `漫画标签池同时含源分类与题材词搜索入口`() = runBlocking {
        val r = repo(
            withCategories = listOf(
                info("legado_1", "包子漫画") to listOf(
                    ExploreCategory("国产漫画", "/category/cn"),
                    // 噪声：入口词与状态词都不该进池
                    ExploreCategory("全部", "/category/all"),
                    ExploreCategory("连载中", "/category/serial"),
                ),
            ),
            searchable = listOf("venera_baozi", "legado_2"),
        )

        val plan = r.refresh(SourceType.COMIC)

        // 源分类保留
        assertEquals(listOf("legado_1" to "/category/cn"), plan.entriesFor("国产漫画"))
        // 噪声被过滤
        assertTrue(plan.entriesFor("全部").isEmpty())
        assertTrue(plan.entriesFor("连载中").isEmpty())
        // 题材词全部进池，且绑的是「按题材搜索」入口
        ComicGenres.ALL.forEach { genre ->
            val entries = plan.entriesFor(genre)
            assertTrue("题材 $genre 应进标签池", entries.isNotEmpty())
            assertTrue(
                "题材 $genre 应绑搜索入口",
                entries.all { TagEntryUrl.searchKeywordOf(it.second) == TagAlias.canonicalOf(genre) },
            )
        }
        // 没有分类能力的源（Venera 风格）也能参与题材词
        val hot = plan.entriesFor("热血")
        assertEquals(listOf("venera_baozi", "legado_2"), hot.map { it.first })
    }

    @Test
    fun `题材词绑定源数受上限约束`() = runBlocking {
        val many = (1..40).map { "venera_$it" }
        val r = repo(withCategories = emptyList(), searchable = many)
        val plan = r.refresh(SourceType.COMIC)
        assertEquals(
            ComicGenres.MAX_SOURCES_PER_GENRE,
            plan.entriesFor("热血").size,
        )
    }

    @Test
    fun `源已有同名分类时不重复绑搜索入口 - 其他题材词仍会补`() = runBlocking {
        val r = repo(
            withCategories = listOf(
                info("legado_1", "铅笔漫画") to listOf(ExploreCategory("热血", "/list/31")),
            ),
            // 同一个源既给了分类，又在可搜索源里；另一个源没有任何分类
            searchable = listOf("legado_1", "legado_2"),
        )
        val plan = r.refresh(SourceType.COMIC)
        val hot = plan.entriesFor("热血")
        // 「热血」：legado_1 只留一条「真分类」入口；legado_2 补搜索入口
        assertEquals(1, hot.count { it.first == "legado_1" })
        assertEquals("/list/31", hot.first { it.first == "legado_1" }.second)
        assertEquals(listOf("legado_2"), hot.filter { it.first != "legado_1" }.map { it.first })
        assertEquals(
            "热血",
            TagEntryUrl.searchKeywordOf(hot.first { it.first == "legado_2" }.second),
        )

        // v1.7.6.3：有分类能力的源也能拿到**其他**题材词（每个漫画都有更多标签）
        val elf = plan.entriesFor("精灵")
        assertTrue(
            "legado_1 应通过搜索入口拿到「精灵」",
            elf.any { it.first == "legado_1" && TagEntryUrl.searchKeywordOf(it.second) == "精灵" },
        )
    }

    @Test
    fun `小说标签池补充细分题材和同人原著检索`() = runBlocking {
        val r = TagRepository(
            contentRepository = FakeContent(
                withCategories = listOf(
                    info("novel_1", "测试小说源", SourceType.NOVEL) to emptyList(),
                ),
                searchable = listOf("novel_1", "novel_2"),
            ),
            store = TagStore(tmp.newFolder()),
        )
        val plan = r.refresh(SourceType.NOVEL)

        NovelGenres.ALL.forEach { genre ->
            assertTrue("小说题材 $genre 应进入标签池", plan.entriesFor(genre).isNotEmpty())
        }
        assertEquals(
            listOf("novel_1", "novel_2"),
            plan.entriesFor("同人").map { it.first },
        )
        val canon = plan.entriesFor("原著·原神")
        assertEquals(listOf("novel_1", "novel_2"), canon.map { it.first })
        assertTrue(canon.all { TagEntryUrl.searchKeywordOf(it.second) == "原神" })
    }

    @Test
    fun `全源轮转让每个漫画源都分到题材词`() = runBlocking {
        val many = (1..12).map { "venera_$it" }
        val r = repo(withCategories = emptyList(), searchable = many)
        val plan = r.refresh(SourceType.COMIC)
        val covered = plan.tags.flatMap { plan.entriesFor(it) }.map { it.first }.toSet()
        assertEquals(many.toSet(), covered)
    }

    @Test
    fun `无码和全彩标签覆盖全部可搜索漫画源`() = runBlocking {
        val many = (1..40).map { "comic_$it" }
        val r = repo(withCategories = emptyList(), searchable = many)
        val plan = r.refresh(SourceType.COMIC)

        listOf("无码", "全彩").forEach { tag ->
            val entries = plan.entriesFor(tag)
            assertEquals("$tag 应覆盖全部源", many.toSet(), entries.map { it.first }.toSet())
            assertTrue(entries.all { TagEntryUrl.searchKeywordOf(it.second) == tag })
        }
    }

    @Test
    fun `全彩标签合并不同语言的分类入口`() = runBlocking {
        val r = repo(
            withCategories = listOf(
                info("comic_cn", "中文源") to listOf(ExploreCategory("全彩", "/full-color")),
                info("comic_en", "英文源") to listOf(ExploreCategory("Official Colored", "/colored")),
            ),
            searchable = emptyList(),
        )
        val plan = r.refresh(SourceType.COMIC)

        assertEquals(1, plan.tags.count { it == "全彩" })
        assertEquals(
            setOf("comic_cn", "comic_en"),
            plan.entriesFor("全彩").map { it.first }.toSet(),
        )
    }

    @Test
    fun `小说类型使用小说题材而非漫画题材词`() = runBlocking {
        val novelInfo = info("novel_1", "中文书城").copy(type = SourceType.NOVEL)
        val r = repo(
            withCategories = listOf(novelInfo to listOf(ExploreCategory("都市", "/dushi"))),
            searchable = listOf("novel_1"),
        )
        val plan = r.refresh(SourceType.NOVEL)
        assertTrue(plan.tags.contains("都市"))
        assertTrue(NovelGenres.ALL.all { plan.tags.contains(TagAlias.canonicalOf(it)) })
        assertFalse(plan.tags.contains("热血"))
    }

    @Test
    fun `没有可搜索源时只保留源分类 - 不抛异常`() = runBlocking {
        val r = repo(
            withCategories = listOf(info("l1", "某漫画") to listOf(ExploreCategory("科幻", "/s"))),
            searchable = emptyList(),
        )
        val plan = r.refresh(SourceType.COMIC)
        assertEquals(listOf("科幻"), plan.tags)
    }
}
