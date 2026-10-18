package com.novelreader

import com.novelreader.core.util.TagQuery
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签查询纯逻辑测试（v1.5.10.0；v1.7.9.9 口径改为「每标签全量并集求交」）。
 *
 * 覆盖：查询串解析（多空格 / 全角空格 / 去重）、标题归一化、
 * 复合标签交集（含空集合短路、单标签透传、跨源同名合并）。
 */
class TagQueryTest {

    private fun item(title: String, sourceId: String = "src1", id: String = title) =
        MediaItem(id = id, title = title, type = SourceType.VIDEO, sourceId = sourceId)

    // ===== parse =====

    @Test
    fun `parse 单标签`() {
        assertEquals(listOf("玄幻"), TagQuery.parse("玄幻"))
    }

    @Test
    fun `parse 复合标签按空格拆分`() {
        assertEquals(listOf("玄幻", "完結"), TagQuery.parse("玄幻 完結"))
    }

    @Test
    fun `parse 多余空白与全角空格都算分隔符`() {
        assertEquals(listOf("a", "b", "c"), TagQuery.parse("  a 　 b   c　"))
    }

    @Test
    fun `parse 去重保序`() {
        assertEquals(listOf("玄幻", "都市"), TagQuery.parse("玄幻 都市 玄幻"))
    }

    @Test
    fun `parse 空串返回空列表`() {
        assertTrue(TagQuery.parse("").isEmpty())
        assertTrue(TagQuery.parse("   ").isEmpty())
    }

    // ===== normalizeTitle =====

    @Test
    fun `normalizeTitle 去空白与小写`() {
        assertEquals("鸣龙少年dvd版", TagQuery.normalizeTitle("鸣龙少年 DVD 版"))
    }

    // ===== intersect =====

    @Test
    fun `intersect 单标签原样返回`() {
        val list = listOf(item("A"), item("B"))
        assertEquals(list, TagQuery.intersect(listOf(list)))
    }

    @Test
    fun `intersect 两标签按标题交集`() {
        val tag1 = listOf(item("鸣龙少年"), item("炸薯条"), item("时差"))
        val tag2 = listOf(item("炸 薯 条"), item("鸣龙少年"), item("别的"))
        val result = TagQuery.intersect(listOf(tag1, tag2))
        // 归一化后「炸 薯 条」==「炸薯条」，按第一个标签的顺序输出
        assertEquals(listOf("鸣龙少年", "炸薯条"), result.map { it.title })
    }

    @Test
    fun `intersect 任一标签为空则交集为空`() {
        val tag1 = listOf(item("A"))
        val tag2 = emptyList<MediaItem>()
        assertTrue(TagQuery.intersect(listOf(tag1, tag2)).isEmpty())
    }

    @Test
    fun `intersect 三标签同时命中才保留`() {
        val t1 = listOf(item("A"), item("B"), item("C"))
        val t2 = listOf(item("A"), item("C"))
        val t3 = listOf(item("A"))
        assertEquals(listOf("A"), TagQuery.intersect(listOf(t1, t2, t3)).map { it.title })
    }

    @Test
    fun `intersect 交集结果保留第一个标签里的源信息`() {
        val t1 = listOf(item("A", sourceId = "s1", id = "a1"))
        val t2 = listOf(item("A", sourceId = "s2", id = "a2"))
        val result = TagQuery.intersect(listOf(t1, t2))
        assertEquals(1, result.size)
        assertEquals("s1", result[0].sourceId)
    }

    @Test
    fun `intersect 空输入返回空`() {
        assertTrue(TagQuery.intersect(emptyList()).isEmpty())
    }

    @Test
    fun `intersect 跨源并集按标题求交`() {
        // 「无码」的全部结果来自 s1，「全彩」的全部结果来自 s9 —— 两个标签没有公共源，
        // 但同名作品仍应被视为同一部（旧口径会因「无公共源」直接返回空）。
        val uncensoredUnion = listOf(
            item("被炉与彼女", sourceId = "s1", id = "a1"),
            item("夏日祭", sourceId = "s1", id = "a2"),
        )
        val fullColorUnion = listOf(
            item("被炉与彼女", sourceId = "s9", id = "b1"),
            item("别的作品", sourceId = "s9", id = "b2"),
        )
        val result = TagQuery.intersect(listOf(uncensoredUnion, fullColorUnion))
        assertEquals(listOf("被炉与彼女"), result.map { it.title })
        assertEquals("s1", result[0].sourceId)
    }

    @Test
    fun `intersect 三个标签的并集同时命中才保留`() {
        val uncensored = listOf(item("甲", "s1", "1"), item("乙", "s1", "2"))
        val fullColor = listOf(item("甲", "s2", "3"), item("乙", "s2", "4"))
        val completed = listOf(item("乙", "s3", "5"))
        assertEquals(
            listOf("乙"),
            TagQuery.intersect(listOf(uncensored, fullColor, completed)).map { it.title },
        )
    }

    @Test
    fun `intersect 跨源同名不同作会合并成一条`() {
        // 已知取舍：判据只有标题，同名不同作会合并（保留第一个标签里的那条）。
        val tagA = listOf(item("同名", "s1", "1"))
        val tagB = listOf(item("同名", "s2", "2"))
        val result = TagQuery.intersect(listOf(tagA, tagB))
        assertEquals(1, result.size)
        assertEquals("s1", result[0].sourceId)
    }

    // ===== 逻辑标签边界 =====

    @Test
    fun `parse 复合查询始终按空白拆分`() {
        assertEquals(listOf("玄幻", "都市"), TagQuery.parse("玄幻 都市"))
    }

    @Test
    fun `parse 不会把相邻标签合并成复合名称`() {
        assertEquals(
            listOf("全彩", "无码"),
            TagQuery.parse("全彩 无码"),
        )
    }

    @Test
    fun `parse 对编码空格及表单加号也保留标签边界`() {
        assertEquals(listOf("全彩", "无码"), TagQuery.parse("全彩%20无码"))
        assertEquals(
            listOf("%E5%85%A8%E5%BD%A9", "%E6%97%A0%E7%A0%81"),
            TagQuery.parse("%E5%85%A8%E5%BD%A9+%E6%97%A0%E7%A0%81"),
        )
        assertEquals(
            listOf("%E5%85%A8%E5%BD%A9", "%E6%97%A0%E7%A0%81"),
            TagQuery.parse("%E5%85%A8%E5%BD%A9%20%E6%97%A0%E7%A0%81"),
        )
    }

    @Test
    fun `parse 全角空格同样按分隔符处理`() {
        assertEquals(listOf("恋爱", "校园"), TagQuery.parse("恋爱　校园"))
    }

}
