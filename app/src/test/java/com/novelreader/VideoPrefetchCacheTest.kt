package com.novelreader

import com.novelreader.data.cache.VideoPrefetchCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 视频预加载磁盘缓存单测（v1.6.0.0）。
 *
 * 覆盖：
 * - Entry 序列化往返（headers 顺序无关）
 * - listAll 枚举全部条目（含损坏文件跳过）
 * - clear 清空全部
 * - 含分隔符 / 特殊字符的 key 会被 sanitize 为合法文件名
 */
class VideoPrefetchCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `Entry 序列化往返`() {
        val cache = VideoPrefetchCache(tmp.newFolder())
        val key = "src1__item1__0__ep-1"
        val entry = VideoPrefetchCache.Entry(
            videoUrl = "http://cdn.test/ep-1.m3u8",
            headers = mapOf("Referer" to "http://src1.test", "User-Agent" to "NovelReader/1.0"),
        )
        cache.save(key, entry)
        val loaded = cache.load(key)
        assertNotNull(loaded)
        assertEquals(entry.videoUrl, loaded!!.videoUrl)
        assertEquals(entry.headers, loaded.headers)
    }

    @Test
    fun `不存在的 key 返回 null`() {
        val cache = VideoPrefetchCache(tmp.newFolder())
        assertNull(cache.load("nope"))
    }

    @Test
    fun `覆盖写同一 key 不会出现两个文件`() {
        val dir = tmp.newFolder("cache")
        val cache = VideoPrefetchCache(dir)
        val key = "src1__item1__0__ep-1"
        cache.save(key, VideoPrefetchCache.Entry("http://a.test", emptyMap()))
        cache.save(key, VideoPrefetchCache.Entry("http://b.test", mapOf("X" to "1")))
        // 只能有一个 .json 文件 + 之前的 .tmp 已被清理
        val files = dir.listFiles()!!.filter { it.isFile && it.name.endsWith(".json") }
        assertEquals(1, files.size)
        val loaded = cache.load(key)
        assertEquals("http://b.test", loaded!!.videoUrl)
        assertEquals(mapOf("X" to "1"), loaded.headers)
    }

    @Test
    fun `listAll 枚举全部 - 损坏文件跳过`() {
        val dir = tmp.newFolder("cache")
        val cache = VideoPrefetchCache(dir)
        cache.save("k1", VideoPrefetchCache.Entry("http://a.test", emptyMap()))
        cache.save("k2", VideoPrefetchCache.Entry("http://b.test", mapOf("X" to "1")))
        // 注入一个损坏文件
        java.io.File(dir, "v1__k3_broken.json").writeText("not-json{")

        val all = cache.listAll()
        assertEquals(2, all.size)
        assertEquals("http://a.test", all["k1"]!!.videoUrl)
        assertEquals("http://b.test", all["k2"]!!.videoUrl)
    }

    @Test
    fun `clear 清空全部 - 后续 load 返回 null`() {
        val cache = VideoPrefetchCache(tmp.newFolder())
        cache.save("k1", VideoPrefetchCache.Entry("http://a.test", emptyMap()))
        cache.save("k2", VideoPrefetchCache.Entry("http://b.test", emptyMap()))
        assertEquals(2, cache.count())
        cache.clear()
        assertEquals(0, cache.count())
        assertNull(cache.load("k1"))
        assertNull(cache.load("k2"))
    }

    @Test
    fun `totalSize 与 count 反映当前文件数`() {
        val cache = VideoPrefetchCache(tmp.newFolder())
        assertEquals(0L, cache.totalSize())
        assertEquals(0, cache.count())
        cache.save("k1", VideoPrefetchCache.Entry("http://a.test/1234567", emptyMap()))
        cache.save("k2", VideoPrefetchCache.Entry("http://b.test/89", emptyMap()))
        // 至少有一个 >0 的非零 size
        assertTrue(cache.totalSize() > 0L)
        assertEquals(2, cache.count())
    }

    @Test
    fun `含分隔符的 key 会被 sanitize 成合法文件名 - 文件不会污染 cache 目录`() {
        val dir = tmp.newFolder("cache")
        val cache = VideoPrefetchCache(dir)
        // 含 / 与 : 的 key（合法的 sourceId / itemId 经过 sanitize 后只能出现 _ 与合法字符）
        val raw = "srcA/B__item:C__0__ep-1"
        cache.save(raw, VideoPrefetchCache.Entry("http://a.test", emptyMap()))
        // 目录里应当有且仅有一个 .json 文件，没有 .tmp 残留
        val files = dir.listFiles()!!.filter { it.isFile }
        assertEquals(1, files.size)
        assertTrue("文件应以 .json 结尾", files[0].name.endsWith(".json"))
        assertEquals("http://a.test", cache.load(raw)!!.videoUrl)
    }

    @Test
    fun `极长的 key 会被截断避免文件系统报错`() {
        val cache = VideoPrefetchCache(tmp.newFolder())
        val longKey = "x".repeat(500)
        cache.save(longKey, VideoPrefetchCache.Entry("http://a.test", emptyMap()))
        // load 应当能拿到（filename 走 sanitize 后被截断，但 save / load 用同一 key，文件名一致）
        val loaded = cache.load(longKey)
        assertNotNull("极长 key 也应能正确 load", loaded)
        assertEquals("http://a.test", loaded!!.videoUrl)
    }
}
