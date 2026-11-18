package com.novelreader

import com.novelreader.core.util.BookConflictDetector
import com.novelreader.core.util.BookEntry
import com.novelreader.core.util.ConflictReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C9 书籍冲突检测 / 去重 单测。 */
class BookConflictDetectorTest {

    private fun entry(
        id: String,
        title: String,
        author: String? = null,
        sourceId: String = "src",
        sourceName: String? = null,
        wordCount: Int? = null,
        coverUrl: String? = null,
        chapterCount: Int? = null,
        updatedAt: Long? = null,
    ) = BookEntry(
        id = id,
        title = title,
        author = author,
        sourceId = sourceId,
        sourceName = sourceName,
        wordCount = wordCount,
        coverUrl = coverUrl,
        chapterCount = chapterCount,
        updatedAt = updatedAt,
    )

    // -----------------------------------------------------------------
    // 归一化
    // -----------------------------------------------------------------

    @Test
    fun `书名归一化去掉空格与首尾空白`() {
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle(" 斗破苍穹 "))
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle("斗 破 苍 穹"))
    }

    @Test
    fun `书名归一化全角转半角并转小写`() {
        assertEquals("dogman", BookConflictDetector.normalizeTitle("ＤＯＧ　ＭＡＮ"))
        assertEquals("harrypotter", BookConflictDetector.normalizeTitle("Harry Potter"))
        assertEquals("harrypotter", BookConflictDetector.normalizeTitle("HARRY POTTER"))
    }

    @Test
    fun `书名归一化去掉书名号与标点`() {
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle("《斗破苍穹》"))
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle("【斗破苍穹】"))
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle("斗破苍穹！"))
    }

    @Test
    fun `书名归一化去掉括号内的卷次后缀`() {
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle("斗破苍穹（1）"))
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle("斗破苍穹（第2卷）"))
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle("斗破苍穹 第2卷"))
        assertEquals("斗破苍穹", BookConflictDetector.normalizeTitle("斗破苍穹（上）"))
    }

    @Test
    fun `书名归一化忽略繁简差异`() {
        assertEquals(
            BookConflictDetector.normalizeTitle("无码"),
            BookConflictDetector.normalizeTitle("無碼"),
        )
        assertEquals("无码", BookConflictDetector.normalizeTitle("無碼"))
    }

    @Test
    fun `作者清洗去掉角色与出品后缀`() {
        assertEquals("萧炎", BookConflictDetector.normalizeAuthor("萧炎（著）"))
        assertEquals("萧炎", BookConflictDetector.normalizeAuthor("萧炎 著"))
        assertEquals("萧炎", BookConflictDetector.normalizeAuthor("萧炎出品"))
        assertEquals("萧炎", BookConflictDetector.normalizeAuthor("作者：萧炎"))
    }

    @Test
    fun `作者清洗识别不出作者时返回空`() {
        assertNull(BookConflictDetector.normalizeAuthor(null))
        assertNull(BookConflictDetector.normalizeAuthor("   "))
        assertNull(BookConflictDetector.normalizeAuthor("[作者]"))
        // 占位作者等同于「没有作者」
        assertNull(BookConflictDetector.normalizeAuthor("佚名"))
    }

    @Test
    fun `多作者顺序不同也算同一作者`() {
        assertEquals(
            BookConflictDetector.normalizeAuthor("张三/李四"),
            BookConflictDetector.normalizeAuthor("李四,张三"),
        )
    }

    // -----------------------------------------------------------------
    // 判定
    // -----------------------------------------------------------------

    @Test
    fun `完全同名同作者判为强匹配`() {
        val a = entry("1", "斗破苍穹", "萧炎")
        val b = entry("2", "《斗破苍穹》", "萧炎（著）")
        val verdict = BookConflictDetector.evaluate(a, b)
        assertEquals(1.0f, verdict.score, 0.0001f)
        assertEquals(ConflictReason.TITLE_AND_AUTHOR_IDENTICAL, verdict.reason)
        assertEquals(1.0f, BookConflictDetector.similarity(a, b), 0.0001f)
    }

    @Test
    fun `同名不同作者不判为重复`() {
        val a = entry("1", "斗破苍穹", "萧炎")
        val b = entry("2", "斗破苍穹", "药老")
        assertEquals(0f, BookConflictDetector.similarity(a, b), 0.0001f)
        assertEquals(
            ConflictReason.AUTHOR_CONFLICT,
            BookConflictDetector.evaluate(a, b).reason,
        )
        assertTrue(BookConflictDetector.detect(listOf(a, b)).isEmpty())
    }

    @Test
    fun `书名相似但作者不同不判为重复`() {
        val a = entry("1", "斗破苍穹", "萧炎")
        val b = entry("2", "斗破苍穹前传", "药老")
        assertEquals(0f, BookConflictDetector.similarity(a, b), 0.0001f)
        assertTrue(BookConflictDetector.detect(listOf(a, b)).isEmpty())
    }

    @Test
    fun `作者为空且书名相同仍匹配但置信度更低`() {
        val withAuthor = entry("1", "斗破苍穹", "萧炎")
        val withoutAuthor = entry("2", "斗破苍穹", null)
        val score = BookConflictDetector.similarity(withAuthor, withoutAuthor)
        assertEquals(BookConflictDetector.SCORE_AUTHOR_MISSING, score, 0.0001f)
        assertTrue("缺作者时置信度应低于完全一致", score < 1.0f)
        // 但仍应被默认阈值判为重复
        assertEquals(1, BookConflictDetector.detect(listOf(withAuthor, withoutAuthor)).size)
    }

    @Test
    fun `一方书名是另一方子串且作者相同判为匹配`() {
        val a = entry("1", "斗破苍穹", "萧炎")
        val b = entry("2", "斗破苍穹前传", "萧炎")
        val score = BookConflictDetector.similarity(a, b)
        assertTrue("子串关系应判为相似，实际=$score", score >= 0.9f)
        val groups = BookConflictDetector.detect(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].members.size)
    }

    @Test
    fun `编辑距离仅一字之差且作者相同判为匹配`() {
        val a = entry("1", "斗破苍穹", "萧炎")
        val b = entry("2", "斗破苍茫", "萧炎")
        assertTrue(BookConflictDetector.similarity(a, b) >= 0.9f)
    }

    @Test
    fun `完全不同的两本书不匹配`() {
        val a = entry("1", "三体", "刘慈欣")
        val b = entry("2", "鬼吹灯", "天下霸唱")
        assertEquals(0f, BookConflictDetector.similarity(a, b), 0.0001f)
        assertTrue(BookConflictDetector.detect(listOf(a, b)).isEmpty())
    }

    @Test
    fun `短标题不因字符重合被误判`() {
        // 两字标题差一个字，证据不足以判定为同一本书
        val a = entry("1", "三体", "刘慈欣")
        val b = entry("2", "三生", "刘慈欣")
        assertTrue(BookConflictDetector.similarity(a, b) < 0.9f)
    }

    // -----------------------------------------------------------------
    // 分组
    // -----------------------------------------------------------------

    @Test
    fun `多条同书条目聚成一个冲突组`() {
        val entries = listOf(
            entry("1", "斗破苍穹", "萧炎", sourceId = "s1"),
            entry("2", "斗破苍穹", "萧炎", sourceId = "s2"),
            entry("3", "《斗破苍穹》", "萧炎（著）", sourceId = "s3"),
            entry("4", "鬼吹灯", "天下霸唱", sourceId = "s1"),
        )
        val groups = BookConflictDetector.detect(entries)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].members.size)
        assertTrue(groups[0].members.none { it.id == "4" })
        assertEquals(ConflictReason.TITLE_AND_AUTHOR_IDENTICAL, groups[0].reasonCode)
    }

    @Test
    fun `单条不成组`() {
        assertTrue(BookConflictDetector.detect(listOf(entry("1", "斗破苍穹", "萧炎"))).isEmpty())
    }

    @Test
    fun `同名不同作者不会因缺作者条目被连成一组`() {
        val entries = listOf(
            entry("1", "斗破苍穹", null),
            entry("2", "斗破苍穹", "萧炎"),
            entry("3", "斗破苍穹", "药老"),
        )
        val groups = BookConflictDetector.detect(entries)
        // 最多只能凑出一组（缺作者的那条只能落到其中一边）
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].members.size)
        val ids = groups[0].members.map { it.id }.toSet()
        assertFalse("不同作者的两条不能同组", ids.containsAll(setOf("2", "3")))
    }

    @Test
    fun `相同id的重复条目只保留信息最全的一条`() {
        val entries = listOf(
            entry("dup", "斗破苍穹", "萧炎", chapterCount = 100, wordCount = 1000, updatedAt = 1),
            entry("dup", "斗破苍穹", "萧炎", chapterCount = 200, wordCount = 2000, coverUrl = "http://c"),
            entry("other", "斗破苍穹", "萧炎", chapterCount = 50),
        )
        val groups = BookConflictDetector.detect(entries)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].members.size)
        assertEquals(1, groups[0].members.count { it.id == "dup" })
        // 章节最多的那条应被推荐保留
        assertEquals("dup", groups[0].recommendedId)
    }

    // -----------------------------------------------------------------
    // 保留建议
    // -----------------------------------------------------------------

    @Test
    fun `保留建议优先章节数多的`() {
        val entries = listOf(
            entry("1", "斗破苍穹", "萧炎", chapterCount = 100),
            entry("2", "斗破苍穹", "萧炎", chapterCount = 300),
        )
        val group = BookConflictDetector.detect(entries).single()
        assertEquals("2", group.recommendedId)
        assertEquals(listOf("2", "1"), group.rankedIds)
    }

    @Test
    fun `保留建议优先有封面的`() {
        val entries = listOf(
            entry("1", "斗破苍穹", "萧炎", chapterCount = 100),
            entry("2", "斗破苍穹", "萧炎", chapterCount = 100, coverUrl = "http://cover"),
        )
        val group = BookConflictDetector.detect(entries).single()
        assertEquals("2", group.recommendedId)
    }

    @Test
    fun `保留建议优先更新时间新的`() {
        val entries = listOf(
            entry("1", "斗破苍穹", "萧炎", updatedAt = 100),
            entry("2", "斗破苍穹", "萧炎", updatedAt = 200),
        )
        val group = BookConflictDetector.detect(entries).single()
        assertEquals("2", group.recommendedId)
    }

    @Test
    fun `来源权重影响保留顺序`() {
        val entries = listOf(
            entry("1", "斗破苍穹", "萧炎", sourceId = "weak"),
            entry("2", "斗破苍穹", "萧炎", sourceId = "strong"),
        )
        val group = BookConflictDetector.detect(entries, sourceWeights = mapOf("strong" to 10)).single()
        assertEquals("2", group.recommendedId)
        assertNotNull(BookConflictDetector.recommend(entries))
    }

    // -----------------------------------------------------------------
    // 确定性 / 阈值 / 边界
    // -----------------------------------------------------------------

    @Test
    fun `多次调用结果完全一致`() {
        val entries = listOf(
            entry("1", "斗破苍穹", "萧炎", sourceId = "s1"),
            entry("2", "斗破苍穹", "萧炎", sourceId = "s2"),
            entry("3", "鬼吹灯", "天下霸唱", sourceId = "s1"),
        )
        val first = BookConflictDetector.detect(entries)
        val second = BookConflictDetector.detect(entries)
        assertEquals(first, second)
        assertEquals(first.map { it.recommendedId }, second.map { it.recommendedId })
        assertEquals(first.map { it.rankedIds }, second.map { it.rankedIds })
    }

    @Test
    fun `输入顺序不同不影响分组结果`() {
        val entries = listOf(
            entry("1", "斗破苍穹", "萧炎", sourceId = "s1"),
            entry("2", "斗破苍穹", "萧炎", sourceId = "s2"),
            entry("3", "鬼吹灯", "天下霸唱", sourceId = "s1"),
        )
        val straight = BookConflictDetector.detect(entries)
        val shuffled = BookConflictDetector.detect(entries.reversed())
        assertEquals(
            straight.map { it.members.map { m -> m.id }.toSet() },
            shuffled.map { it.members.map { m -> m.id }.toSet() },
        )
        assertEquals(straight.map { it.recommendedId }, shuffled.map { it.recommendedId })
    }

    @Test
    fun `阈值生效`() {
        val entries = listOf(
            entry("1", "斗破苍穹前传", "萧炎"),
            entry("2", "斗破苍穹", "萧炎"),
        )
        // 相似度在 0.9 与 0.99 之间
        assertEquals(1, BookConflictDetector.detect(entries, threshold = 0.9f).size)
        assertTrue(BookConflictDetector.detect(entries, threshold = 0.99f).isEmpty())
        // 阈值提高后，缺作者的同名书也会掉出分组
        val missingAuthor = listOf(
            entry("1", "斗破苍穹", "萧炎"),
            entry("2", "斗破苍穹", null),
        )
        assertEquals(1, BookConflictDetector.detect(missingAuthor, threshold = 0.9f).size)
        assertTrue(BookConflictDetector.detect(missingAuthor, threshold = 0.95f).isEmpty())
    }

    @Test
    fun `空列表与空标题的边界`() {
        assertTrue(BookConflictDetector.detect(emptyList()).isEmpty())
        assertTrue(
            BookConflictDetector.detect(
                listOf(entry("1", "", "萧炎"), entry("2", "", "萧炎")),
            ).isEmpty(),
        )
        assertEquals(
            ConflictReason.TITLE_EMPTY,
            BookConflictDetector.evaluate(entry("1", "", "萧炎"), entry("2", "", "萧炎")).reason,
        )
    }
}
