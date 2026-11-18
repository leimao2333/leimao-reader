package com.novelreader

import com.novelreader.core.util.ChangeSourceDeduper
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 换源搜索结果去重 / 复用（B11）纯逻辑测试。 */
class ChangeSourceDeduperTest {

    private fun cand(
        sourceId: String,
        id: String,
        title: String,
        author: String? = null,
        url: String = "https://example.com/book/$id",
    ) = ChangeSourceDeduper.Candidate(
        sourceId = sourceId, id = id, title = title, author = author, detailUrl = url,
    )

    // ===== 身份键 =====

    @Test
    fun `规范化 URL 抹平协议与结尾斜杠差异`() {
        val a = cand("s1", "1", "A", url = "https://example.com/book/1/")
        val b = cand("s1", "2", "A", url = "http://example.com/book/1")
        assertEquals(
            ChangeSourceDeduper.identityKey(a, ChangeSourceDeduper.KeyMode.NORMALIZED_URL),
            ChangeSourceDeduper.identityKey(b, ChangeSourceDeduper.KeyMode.NORMALIZED_URL),
        )
    }

    @Test
    fun `书名作者键抹平繁简与全半角`() {
        // 用转换器自身的输出构造对照，避免依赖具体词表收录了哪些字：
        // b 的书名就是 a 转简体后的结果，两者经归一化后必然得到同一身份键。
        val traditional = "這學生的問題"
        val simplified = com.novelreader.core.text.ChineseConverter.toSimplified(traditional)
        val a = cand("s1", "1", traditional, author = "天蚕土豆")
        val b = cand("s1", "9", simplified, author = "天蚕土豆")
        assertEquals(
            ChangeSourceDeduper.identityKey(a, ChangeSourceDeduper.KeyMode.TITLE_AUTHOR),
            ChangeSourceDeduper.identityKey(b, ChangeSourceDeduper.KeyMode.TITLE_AUTHOR),
        )
    }

    @Test
    fun `空 sourceId 不参与去重`() {
        assertNull(ChangeSourceDeduper.identityKey(cand("", "1", "A"), ChangeSourceDeduper.KeyMode.NORMALIZED_URL))
    }

    // ===== 去重：核心是「同源折叠、跨源各留一条」=====

    @Test
    fun `同源同书按 URL 折叠成一条`() {
        val base = listOf(cand("s1", "1", "A", url = "https://example.com/book/1"))
        val incoming = listOf(cand("s1", "88", "A", url = "https://example.com/book/1/"))
        val out = ChangeSourceDeduper.dedupe(base, incoming)
        assertEquals(1, out.size)
    }

    @Test
    fun `跨源同一本书各保留一条（换源语义不能被破坏）`() {
        val base = listOf(cand("s1", "1", "遮天", author = "辰东"))
        val incoming = listOf(
            cand("s2", "9", "遮天", author = "辰东"),
            cand("s3", "7", "遮天", author = "辰东"),
        )
        val out = ChangeSourceDeduper.dedupe(base, incoming, ChangeSourceDeduper.KeyMode.TITLE_AUTHOR)
        assertEquals(3, out.size)
        assertEquals(setOf("s1", "s2", "s3"), out.map { it.sourceId }.toSet())
    }

    @Test
    fun `同名不同作者按书名作者键不误合并`() {
        val base = listOf(cand("s1", "1", "遮天", author = "辰东"))
        val incoming = listOf(cand("s1", "2", "遮天", author = "其它作者"))
        val out = ChangeSourceDeduper.dedupe(base, incoming, ChangeSourceDeduper.KeyMode.TITLE_AUTHOR)
        assertEquals(2, out.size)
    }

    @Test
    fun `后到内容覆盖先到但位置不变`() {
        val base = listOf(cand("s1", "1", "A"), cand("s1", "2", "B"))
        val incoming = listOf(cand("s1", "1", "A-新版"))
        val out = ChangeSourceDeduper.dedupe(base, incoming)
        assertEquals(listOf("A-新版", "B"), out.map { it.title })
    }

    // ===== 复用缓存 =====

    @Test
    fun `复用缓存命中与空结果已搜标记`() {
        val cache = ChangeSourceDeduper.ReuseCache()
        assertFalse(cache.searched("k1"))
        cache.put("k1", listOf(cand("s1", "1", "A")))
        assertTrue(cache.searched("k1"))
        assertNotNull(cache.get("k1"))

        // 空结果：searched=true 但 get=null（不占内存）
        cache.put("k2", emptyList())
        assertTrue(cache.searched("k2"))
        assertNull(cache.get("k2"))

        cache.markEmpty("k1")
        assertTrue(cache.isEmpty("k1"))
        cache.clear()
        assertEquals(0, cache.size)
        assertFalse(cache.searched("k1"))
    }

    @Test
    fun `Candidate_of 能从 MediaItem 搬运`() {
        val item = MediaItem(
            id = "1", title = "A", author = "X", type = SourceType.NOVEL,
            detailUrl = "https://example.com/1", sourceId = "s1",
        )
        val c = ChangeSourceDeduper.Candidate.of(item)
        assertEquals("s1", c.sourceId)
        assertEquals("A", c.title)
        assertEquals("https://example.com/1", c.detailUrl)
    }
}
