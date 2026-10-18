package com.novelreader

import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagQuery
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复合标签搜索的**语义**测试（v1.8.0.0）。
 *
 * 用户口径：输入 `A B` 时，`A` 与 `B` 各是一个**大集**（标签 + 它的全部转译写法）。
 * - 组内：作品命中该组**任意一个**写法即可（并集）；
 * - 组间：**每一组都要命中**才留下（交集）。
 *
 * 也就是说结果里的每一部作品，至少带两个标签，且这两个标签分别来自两个大集。
 * 这里把「每组先取并集，再求交」这条链路按真实分层测一遍。
 */
class TagCompoundSemanticsTest {

    private fun item(title: String, sourceId: String = "src1", id: String = title) =
        MediaItem(id = id, title = title, type = SourceType.COMIC, sourceId = sourceId)

    /** 模拟「一个标签先取全量并集」：多源 / 多译名结果合到一起 */
    private fun unionOf(vararg lists: List<MediaItem>): List<MediaItem> =
        lists.flatMap { it }.distinctBy { TagQuery.normalizeTitle(it.title) }

    // ===== 组内取并 =====

    @Test
    fun `组内任意一个译名命中就算该组满足`() {
        // A 组（无码组）：三个译名分别在不同源上有结果
        val groupA = unionOf(
            listOf(item("Alpha", sourceId = "s1")),   // 无码
            listOf(item("Beta", sourceId = "s2")),    // 無修正
            listOf(item("Gamma", sourceId = "s3")),   // uncensored
        )
        // B 组（巨乳组）
        val groupB = unionOf(
            listOf(item("Beta", sourceId = "s4"), item("Delta", sourceId = "s4")),
        )

        val hit = TagQuery.intersect(listOf(groupA, groupB))

        // Beta 只是因为 A 组里「無修正」那一路命中、B 组里「巨乳」那一路命中
        assertEquals(listOf("Beta"), hit.map { it.title })
    }

    // ===== 组间取交 =====

    @Test
    fun `只命中一个组的作品被排除`() {
        val groupA = unionOf(listOf(item("只有A"), item("两组都有")))
        val groupB = unionOf(listOf(item("只有B"), item("两组都有")))

        val hit = TagQuery.intersect(listOf(groupA, groupB))

        assertEquals(listOf("两组都有"), hit.map { it.title })
    }

    @Test
    fun `三个大集要求每个都命中`() {
        val a = unionOf(listOf(item("全集")))
        val b = unionOf(listOf(item("全集"), item("只有AB")))
        val c = unionOf(listOf(item("全集"), item("只有AC")))

        val hit = TagQuery.intersect(listOf(a, b, c))

        assertEquals(listOf("全集"), hit.map { it.title })
    }

    @Test
    fun `任一大集为空则交集为空`() {
        val a = unionOf(listOf(item("X")))
        assertTrue(TagQuery.intersect(listOf(a, emptyList())).isEmpty())
    }

    // ===== 作品身份：跨源标题写法差异 =====

    @Test
    fun `繁体标题与简体标题视为同一部作品`() {
        val groupA = unionOf(listOf(item("無碼作品")))
        val groupB = unionOf(listOf(item("无码作品")))

        val hit = TagQuery.intersect(listOf(groupA, groupB))

        assertEquals(1, hit.size)
        assertEquals("無碼作品", hit.first().title)
    }

    @Test
    fun `标题带作者与状态括号后缀仍能对上`() {
        val groupA = unionOf(listOf(item("[某作者] 无码作品 (全彩) 【完结】")))
        val groupB = unionOf(listOf(item("无码作品")))

        val hit = TagQuery.intersect(listOf(groupA, groupB))

        assertEquals(1, hit.size)
    }

    @Test
    fun `分隔符与标点差异不影响匹配`() {
        val groupA = unionOf(listOf(item("无码作品-第01卷")))
        val groupB = unionOf(listOf(item("无码作品 第01卷")))

        assertEquals(1, TagQuery.intersect(listOf(groupA, groupB)).size)
    }

    @Test
    fun `完全不同的标题不会被误合并`() {
        val groupA = unionOf(listOf(item("甲作品"), item("乙作品")))
        val groupB = unionOf(listOf(item("丙作品"), item("丁作品")))

        assertTrue(TagQuery.intersect(listOf(groupA, groupB)).isEmpty())
    }

    @Test
    fun `数字不同不是同一部作品`() {
        val groupA = unionOf(listOf(item("斗罗大陆 第1卷")))
        val groupB = unionOf(listOf(item("斗罗大陆 第2卷")))

        assertTrue(TagQuery.intersect(listOf(groupA, groupB)).isEmpty())
    }

    // ===== 身份键（结果去重） =====

    @Test
    fun `identityKey 把繁简与装饰后缀收敛成同一个键`() {
        val a = TagQuery.identityKey("無碼作品 (全彩)")
        val b = TagQuery.identityKey("无码作品")
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun `单标签保持原顺序原样返回`() {
        val only = listOf(item("B"), item("A"), item("C"))
        assertEquals(only, TagQuery.intersect(listOf(only)))
    }

    // ===== 大集本身：同义组覆盖 =====

    @Test
    fun `体型类标签已互为译名`() {
        // aliasesOf 返回的是归一化后的写法（去空白 + 小写），所以英文是 bigbreasts
        val group = TagAlias.aliasesOf("巨乳")
        assertTrue("巨乳组应包含爆乳", group.any { it.contains("爆乳") })
        assertTrue("巨乳组应包含英文写法", group.any { it.contains("bigbreasts") })
    }

    @Test
    fun `无码组跨简繁与英文`() {
        val group = TagAlias.aliasesOf("无码")
        assertTrue(group.any { it.contains("無修正") })
        assertTrue(group.any { it.contains("uncensored") })
    }

    @Test
    fun `同义标签在复合搜索里合并成一个条件`() {
        // 「巨乳 爆乳」是同一个大集，合并后只剩一个条件（不是两个集求交）
        val deduped = TagAlias.dedupeAliases(listOf("巨乳", "爆乳"))
        assertEquals(1, deduped.size)
    }

    @Test
    fun `不同大集的标签不会被合并`() {
        val deduped = TagAlias.dedupeAliases(listOf("巨乳", "无码"))
        assertEquals(2, deduped.size)
    }
}
