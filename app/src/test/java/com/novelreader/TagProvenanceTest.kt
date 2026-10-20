package com.novelreader

import com.novelreader.core.util.TagProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打标证据单测（v1.8.4.1）。
 *
 * 盯住的是「哪些检索结果配得上被贴上搜索的那个标签」—— 这是标签层最容易脏的地方：
 * 一旦错标，之后按标签搜索会被索引命中，错一次就一直在错。四条底线：
 *
 * 1. **关键词命中不算证据**：源站的包含匹配把《全面接触》算进「全彩」；
 * 2. **跨源同名不算证据**：A 站《夏日》是「无码」，不能给 B 站同名作品也贴一个；
 * 3. **同源同标题才算**：这是唯一能确定「同一部作品被源站归进该分类」的依据；
 * 4. 标题写法差异（繁简 / 标点 / 空格）不影响判定。
 */
class TagProvenanceTest {

    private fun hits(vararg pairs: Pair<String, Set<String>>) = pairs.toList()

    @Test
    fun `同源同标题证明成立`() {
        val key = TagProvenance.proofKey("src-a", "夏日")!!
        assertEquals(
            listOf("无码"),
            TagProvenance.provenTags("src-a", "夏日", hits("无码" to setOf(key))),
        )
    }

    @Test
    fun `跨源同名不算证据`() {
        // A 站的作品进了「无码」分类页，B 站有同名作品 —— 那是两部作品，不能顺手贴标签
        val keyA = TagProvenance.proofKey("src-a", "夏日")!!
        assertEquals(
            emptyList<String>(),
            TagProvenance.provenTags("src-b", "夏日", hits("无码" to setOf(keyA))),
        )
    }

    @Test
    fun `标题写法差异不影响判定`() {
        // 源站列表页与分类页的标题可能差一个空格 / 全半角 / 繁简
        val key = TagProvenance.proofKey("src-a", "夏日 大作戰！")!!
        assertEquals(
            listOf("全彩"),
            TagProvenance.provenTags("src-a", "夏日大作戰", hits("全彩" to setOf(key))),
        )
    }

    @Test
    fun `只返回证明成立的那些标签`() {
        val key = TagProvenance.proofKey("src-a", "夏日")!!
        val proven = TagProvenance.provenTags(
            "src-a", "夏日",
            hits(
                "全彩" to setOf(key),
                "无码" to setOf(TagProvenance.proofKey("src-b", "夏日")!!),
                "中文" to emptySet(),
            ),
        )
        assertEquals(listOf("全彩"), proven)
    }

    @Test
    fun `没有源归属的结果无法证明`() {
        // 关键词入口抓回来的条目偶尔缺 sourceId，这种结果不能当任何标签的证据
        assertNull(TagProvenance.proofKey("", "夏日"))
        assertNull(TagProvenance.proofKey("   ", "夏日"))
    }

    @Test
    fun `按证据分组 没证据的单独一组`() {
        val items = listOf("a:1", "a:2", "b:1")
        val proven = mapOf("a:1" to setOf("全彩"), "b:1" to setOf("全彩", "无码"))
        val groups = TagProvenance.groupByProof(items, proven) { it }
        fun tagsOf(id: String) = groups.entries.first { id in it.value }.key
        assertEquals(listOf("全彩"), tagsOf("a:1"))
        assertEquals(listOf("全彩", "无码"), tagsOf("b:1"))
        assertTrue("没证据的作品要单独成组：$groups", tagsOf("a:2").isEmpty())
    }
}
