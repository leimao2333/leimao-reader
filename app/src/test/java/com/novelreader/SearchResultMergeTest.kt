package com.novelreader

import com.novelreader.core.util.SearchResultMerge
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索结果合并去重（v1.8.4.1）。
 *
 * 起因是一次真崩溃：标签搜索把「本地索引里的旧标题」和「网络刚返回的新标题」当成
 * 两条不同作品留在列表里，而列表 key 是 `sourceId:id` —— 同一个 key 出现两次，
 * Compose 直接抛 `Key ... was already used`，页面崩掉。
 */
class SearchResultMergeTest {

    private fun item(
        sourceId: String,
        id: String,
        title: String,
        sourceName: String = "测试源",
    ) = MediaItem(
        id = id,
        title = title,
        type = SourceType.NOVEL,
        sourceId = sourceId,
        sourceName = sourceName,
        detailUrl = id,
    )

    @Test
    fun `同一条作品标题不同也只留一条`() {
        val local = item("s1", "1", "玄幻：从混沌体开始")
        val network = item("s1", "1", "玄幻：从混沌体开始（校对版）")
        val merged = SearchResultMerge.merge(listOf(local), listOf(network))
        assertEquals(1, merged.size)
        // 后到的（网络）覆盖先到的（索引里的旧标题）
        assertEquals("玄幻：从混沌体开始（校对版）", merged.first().title)
    }

    @Test
    fun `跨源同名作品合并成一条`() {
        val merged = SearchResultMerge.merge(
            listOf(item("s1", "1", "遮天")),
            listOf(item("s2", "9", "遮天")),
        )
        assertEquals(1, merged.size)
        assertEquals("s1", merged.first().sourceId)
    }

    @Test
    fun `不同作品各自保留且顺序不变`() {
        val merged = SearchResultMerge.merge(
            listOf(item("s1", "1", "遮天"), item("s1", "2", "完美世界")),
            listOf(item("s2", "9", "吞噬星空"), item("s1", "1", "遮天")),
        )
        assertEquals(listOf("遮天", "完美世界", "吞噬星空"), merged.map { it.title })
    }

    @Test
    fun `列表 key 在合并后唯一`() {
        // 列表用 sourceId:id 当 key，重复就会崩
        val merged = SearchResultMerge.merge(
            listOf(item("s1", "1", "A"), item("s1", "2", "B")),
            listOf(item("s1", "1", "A2"), item("s1", "2", "B2")),
        )
        val keys = merged.map { SearchResultMerge.keyOf(it) }
        assertEquals(keys.size, keys.distinct().size)
    }

    // ===== 增量累加器（翻页用）=====

    @Test
    fun `累加器增量追加与一次性合并结果一致`() {
        val page1 = listOf(item("s1", "1", "遮天"), item("s1", "2", "完美世界"))
        val page2 = listOf(item("s1", "2", "完美世界（新）"), item("s2", "9", "遮天"))
        val acc = SearchResultMerge.Accumulator()
        acc.reset(page1)
        val incremental = acc.addAll(page2)
        val oneShot = SearchResultMerge.merge(page1, page2)
        assertEquals(oneShot.map { it.title }, incremental.map { it.title })
        assertEquals(oneShot.map { SearchResultMerge.keyOf(it) }, incremental.map { SearchResultMerge.keyOf(it) })
    }

    @Test
    fun `累加器同 id 更新内容但位置不变`() {
        val acc = SearchResultMerge.Accumulator()
        acc.reset(listOf(item("s1", "1", "A"), item("s1", "2", "B")))
        val out = acc.addAll(listOf(item("s1", "1", "A 修订版")))
        assertEquals(listOf("A 修订版", "B"), out.map { it.title })
        assertEquals(2, out.size)
    }

    @Test
    fun `累加器跨源同名只留一条`() {
        val acc = SearchResultMerge.Accumulator()
        acc.reset(listOf(item("s1", "1", "遮天")))
        val out = acc.addAll(listOf(item("s2", "9", "遮天")))
        assertEquals(1, out.size)
        assertEquals("s1", out.first().sourceId)
    }
}
