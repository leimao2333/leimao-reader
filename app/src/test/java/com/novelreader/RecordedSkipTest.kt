package com.novelreader

import com.novelreader.core.util.RecordedSkip
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只看新」的过滤口径（v1.9.5.8）。
 *
 * 这几条锁的是**静默失效**的可能：键格式一变，过滤不会报错，只会「看起来打开了开关
 * 但什么都没跳过」——真机上很难判断是功能没生效，还是本来就全都看过了。
 */
class RecordedSkipTest {

    private fun item(id: String, source: String = "s1", title: String = "片$id") =
        MediaItem(id = id, title = title, type = SourceType.VIDEO, sourceId = source)

    @Test
    fun `键格式与索引口一致`() {
        assertEquals("s1|42", RecordedSkip.keyOf(item("42")))
    }

    @Test
    fun `关掉时原样返回同一个列表`() {
        val items = listOf(item("1"), item("2"))
        val out = RecordedSkip.visible(items, setOf("s1|1", "s1|2"), skip = false)
        assertSame(items, out)
    }

    @Test
    fun `打开时藏掉已收录的`() {
        val items = listOf(item("1"), item("2"), item("3"))
        val out = RecordedSkip.visible(items, setOf("s1|2"), skip = true)
        assertEquals(listOf("1", "3"), out.map { it.id })
    }

    @Test
    fun `同 id 不同源不算同一条`() {
        val items = listOf(item("7", source = "a"), item("7", source = "b"))
        val out = RecordedSkip.visible(items, setOf("a|7"), skip = true)
        assertEquals(listOf("b"), out.map { it.sourceId })
    }

    @Test
    fun `已收录集合为空时不做无谓过滤`() {
        val items = listOf(item("1"), item("2"))
        val out = RecordedSkip.visible(items, emptySet(), skip = true)
        assertSame(items, out)
    }

    @Test
    fun `freshCount 报出这一页还剩几条新的`() {
        val items = listOf(item("1"), item("2"), item("3"))
        assertEquals(2, RecordedSkip.freshCount(items, setOf("s1|1")))
        assertEquals(3, RecordedSkip.freshCount(items, emptySet()))
    }

    @Test
    fun `整页都收录过时 freshCount 为零`() {
        val items = listOf(item("1"), item("2"))
        assertEquals(0, RecordedSkip.freshCount(items, setOf("s1|1", "s1|2")))
    }

    @Test
    fun `脏数据不进键表`() {
        val items = listOf(
            item("1"),
            MediaItem(id = "", title = "空 id", type = SourceType.VIDEO, sourceId = "s1"),
            MediaItem(id = "9", title = "空源", type = SourceType.VIDEO, sourceId = ""),
        )
        val keys = RecordedSkip.keysOf(items)
        assertEquals(listOf("s1|1"), keys)
        assertTrue(keys.none { it.startsWith("s1|") && it.endsWith("|") })
    }
}
