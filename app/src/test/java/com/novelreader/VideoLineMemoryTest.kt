package com.novelreader

import com.novelreader.core.util.VideoLineMemory
import com.novelreader.core.util.VideoLineMemory.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「记住上次用的源（线路）」纯逻辑单测（v1.6.9.0）。
 *
 * 三条不变式：
 * 1. **身份是线路名** —— 源的 `vod_play_url` 追加一条线路就会让下标整体错位，
 *    靠下标找会在下次把用户指到别人身上；
 * 2. **落盘数据最坏只能丢一条记忆** —— 损坏 / 截断 / 空串一律当没有，
 *    绝不能因为读记忆失败打断播放；
 * 3. **记忆不能无限增长** —— 过期条目丢掉、超出上限按最近看的保留。
 */
class VideoLineMemoryTest {

    private val now = 1_700_000_000_000L

    private fun entry(name: String, index: Int, updatedAt: Long = now) =
        Entry(lineName = name, lineIndex = index, updatedAt = updatedAt)

    // ===== key =====

    @Test
    fun `不同条目与不同源各自独立`() {
        val a = VideoLineMemory.key("srcA", "1001")
        val b = VideoLineMemory.key("srcA", "1002")
        val c = VideoLineMemory.key("srcB", "1001")
        assertEquals(3, setOf(a, b, c).size)
    }

    @Test
    fun `key 不因 sourceId 内含分隔符而串味`() {
        // 「a|b」+「c」与「a」+「b|c」如果用「|」拼就会撞成同一个 key
        assertTrue(
            VideoLineMemory.key("a|b", "c") != VideoLineMemory.key("a", "b|c"),
        )
    }

    // ===== 按名字解析下标 =====

    @Test
    fun `线路顺序变了也能按名字找回`() {
        // 上次用的是「量子资源」（当时下标 0），源调整顺序后它跑到了 2
        val index = VideoLineMemory.resolveIndex(
            entry("量子资源", 0),
            listOf("kk线路", "秒播线路", "量子资源"),
        )
        assertEquals(2, index)
    }

    @Test
    fun `名字匹配不上时退回下标`() {
        // 源把线路改名了，但顺序没动 → 下标还能用
        assertEquals(
            1,
            VideoLineMemory.resolveIndex(entry("旧线路名", 1), listOf("A线路", "B线路")),
        )
    }

    @Test
    fun `下标越界且名字不匹配时返回 null`() {
        // 源只剩一条线路了，旧记忆指向 5
        assertNull(VideoLineMemory.resolveIndex(entry("已消失的线路", 5), listOf("A线路")))
    }

    @Test
    fun `没有记忆或线路表为空时返回 null`() {
        assertNull(VideoLineMemory.resolveIndex(null, listOf("A")))
        assertNull(VideoLineMemory.resolveIndex(entry("A", 0), emptyList()))
    }

    // ===== 序列化往返 =====

    @Test
    fun `写入后序列化再读回内容一致`() {
        val key = VideoLineMemory.key("srcA", "1001")
        val map = VideoLineMemory.put(emptyMap(), key, " 量子资源 ", 2, now)
        assertEquals("名字去掉首尾空白", "量子资源", map[key]?.lineName)

        val restored = VideoLineMemory.decode(VideoLineMemory.encode(map))
        assertEquals(map, restored)
        assertEquals(2, restored[key]?.lineIndex)
        assertEquals(now, restored[key]?.updatedAt)
    }

    @Test
    fun `再次写入同一部片子会覆盖而不是追加`() {
        val key = VideoLineMemory.key("srcA", "1001")
        var map = VideoLineMemory.put(emptyMap(), key, "线路一", 0, now)
        map = VideoLineMemory.put(map, key, "线路二", 3, now + 1000)
        assertEquals(1, map.size)
        assertEquals("线路二", map[key]?.lineName)
    }

    @Test
    fun `损坏的 json 当作没有记忆`() {
        assertTrue(VideoLineMemory.decode("{ 这不是 json").isEmpty())
        assertTrue(VideoLineMemory.decode("").isEmpty())
        assertTrue(VideoLineMemory.decode(null).isEmpty())
        // 旧版结构（object 而不是 array）也不能崩
        assertTrue(VideoLineMemory.decode("""{"k1":{"lineName":"x","lineIndex":0}}""").isEmpty())
    }

    @Test
    fun `字段缺失的条目被跳过而不是崩溃`() {
        // 人为构造缺 lineName 的条目：能解出来但名字为空，不该抛异常
        val restored = VideoLineMemory.decode("""[{"key":"k1","lineIndex":1,"updatedAt":5}]""")
        assertEquals(1, restored.size)
        assertEquals("", restored["k1"]?.lineName)
        assertEquals(1, restored["k1"]?.lineIndex)
    }

    @Test
    fun `没有 key 的条目被丢弃`() {
        assertTrue(VideoLineMemory.decode("""[{"lineIndex":1,"updatedAt":5}]""").isEmpty())
        assertTrue(VideoLineMemory.decode("""[{"key":"","lineIndex":1}]""").isEmpty())
    }

    // ===== 裁剪 =====

    @Test
    fun `过期条目被丢掉`() {
        val key = VideoLineMemory.key("srcA", "1")
        val stale = mapOf(key to entry("线路", 0, updatedAt = now - VideoLineMemory.MAX_AGE_MS - 1))
        assertTrue(VideoLineMemory.prune(stale, now).isEmpty())
    }

    @Test
    fun `刚好在有效期内的条目保留`() {
        val key = VideoLineMemory.key("srcA", "1")
        val fresh = mapOf(key to entry("线路", 0, updatedAt = now - VideoLineMemory.MAX_AGE_MS + 1))
        assertEquals(1, VideoLineMemory.prune(fresh, now).size)
    }

    @Test
    fun `超出上限时保留最近看的那几条`() {
        var map = emptyMap<String, Entry>()
        repeat(5) { i ->
            map = VideoLineMemory.put(
                map, VideoLineMemory.key("src", "$i"), "线路$i", 0,
                now - (5 - i) * 1000L,
            )
        }
        val pruned = VideoLineMemory.prune(map, now, maxEntries = 2)
        assertEquals(2, pruned.size)
        assertTrue("下标 4 是最新的", pruned.containsKey(VideoLineMemory.key("src", "4")))
        assertTrue("下标 3 次新", pruned.containsKey(VideoLineMemory.key("src", "3")))
        assertTrue("更早的应被丢掉", !pruned.containsKey(VideoLineMemory.key("src", "2")))
    }
}
