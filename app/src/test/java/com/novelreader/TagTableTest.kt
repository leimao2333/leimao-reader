package com.novelreader

import com.novelreader.core.util.TagTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端打标表纯逻辑测试（v1.8.1.5）。
 *
 * 覆盖：Gson 往返解析、坏输入容错、合并口径（同名标签取更高置信度、同分保既有）。
 */
class TagTableTest {

    private val json = """
        {
          "version": 1,
          "generatedAt": 1730000000000,
          "source": "pc",
          "items": [{
            "sourceId": "s1", "itemId": "i1", "itemType": "COMIC",
            "title": "被炉与彼女", "cover": "https://x/c.jpg", "detailUrl": "https://x/1",
            "sourceName": "GoDa漫画", "author": "某人",
            "tags": [
              {"name": "全彩", "category": "题材", "confidence": 72, "origin": "视觉"},
              {"name": "百合", "category": "题材", "confidence": 88, "origin": "站方"}
            ]
          }]
        }
    """.trimIndent()

    @Test
    fun `解析与序列化往返`() {
        val payload = TagTable.parse(json)!!
        assertEquals(1, payload.items.size)
        val item = payload.items.first()
        assertEquals("s1", item.sourceId)
        assertEquals(2, item.tags.size)
        assertEquals("视觉", item.tags[0].origin)
        // 再序列化仍可解析
        val roundTrip = TagTable.parse(TagTable.toJson(payload))!!
        assertEquals(item.tags.map { it.name }, roundTrip.items.first().tags.map { it.name })
    }

    @Test
    fun `坏输入返回 null`() {
        assertNull(TagTable.parse(""))
        assertNull(TagTable.parse("not json"))
        assertNull(TagTable.parse("""{"broken": true}"""))
    }

    @Test
    fun `无既有标签直接采纳`() {
        assertEquals(72, TagTable.mergeEntry(null, entry(72)).confidence)
    }

    @Test
    fun `incoming 更高则覆盖`() {
        val merged = TagTable.mergeEntry(entry(60, origin = "文本"), entry(72, origin = "视觉"))
        assertEquals(72, merged.confidence)
        assertEquals("视觉", merged.origin)
    }

    @Test
    fun `incoming 更低或同分保留既有`() {
        assertEquals(100, TagTable.mergeEntry(entry(100, origin = "站方"), entry(72)).confidence)
        assertEquals("站方", TagTable.mergeEntry(entry(100, origin = "站方"), entry(100)).origin)
    }

    private fun entry(confidence: Int, origin: String = "视觉") =
        TagTable.Entry(name = "全彩", category = "题材", confidence = confidence, origin = origin)

    @Test
    fun `流式解析与整表解析结果一致`() {
        val payload = TagTable.parse(json)!!
        val streamed = mutableListOf<TagTable.Item>()
        var meta = Triple(0, 0L, "")
        val count = kotlinx.coroutines.runBlocking {
            TagTable.parseItems(
                reader = json.reader(),
                onMeta = { v, g, s -> meta = Triple(v, g, s) },
                onItem = { streamed += it },
            )
        }
        assertEquals(payload.items.size, count)
        assertEquals(payload.items.size, streamed.size)
        // 逐字段核对第一条
        val a = payload.items.first()
        val b = streamed.first()
        assertEquals(a.sourceId, b.sourceId)
        assertEquals(a.itemId, b.itemId)
        assertEquals(a.itemType, b.itemType)
        assertEquals(a.title, b.title)
        assertEquals(a.tags.map { it.name }, b.tags.map { it.name })
        assertEquals(a.tags.map { it.confidence }, b.tags.map { it.confidence })
        // 元数据
        assertEquals(payload.version, meta.first)
        assertEquals(payload.generatedAt, meta.second)
        assertEquals(payload.source, meta.third)
    }

    @Test
    fun `流式解析坏输入抛异常`() {
        kotlinx.coroutines.runBlocking {
            var threw = false
            try {
                TagTable.parseItems("not json".reader(), { _, _, _ -> }, {})
            } catch (e: Exception) {
                threw = true
            }
            assertTrue(threw)
        }
    }
}
