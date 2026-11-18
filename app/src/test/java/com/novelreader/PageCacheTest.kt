package com.novelreader

import com.novelreader.core.reader.PageCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 分页 LRU 缓存单测（A16）：命中 / 淘汰 / 容量边界 / key 失效 / 并发 / 清空。 */
class PageCacheTest {

    private fun key(
        bookId: String = "book",
        chapterIndex: Int = 1,
        contentHash: Int = 100,
        fontSize: Float = 16f,
    ) = PageCache.keyOf(
        bookId = bookId,
        chapterIndex = chapterIndex,
        contentHash = contentHash,
        fontSize = fontSize,
    )

    @Test
    fun missThenHit() {
        val cache = PageCache(2)
        val k = key()
        assertNull(cache.get(k))
        cache.put(k, listOf("第一页", "第二页"))
        assertEquals(listOf("第一页", "第二页"), cache.get(k))
    }

    @Test
    fun putSameKeyOverwritesValue() {
        val cache = PageCache(2)
        val k = key()
        cache.put(k, listOf("旧"))
        cache.put(k, listOf("新"))
        assertEquals(listOf("新"), cache.get(k))
        assertEquals(1, cache.size())
    }

    @Test
    fun lruEvictsLeastRecentlyUsed() {
        val cache = PageCache(2)
        val a = key(chapterIndex = 1)
        val b = key(chapterIndex = 2)
        val c = key(chapterIndex = 3)
        cache.put(a, listOf("A"))
        cache.put(b, listOf("B"))
        // 访问 A，使 B 变成最久未用
        assertNotNull(cache.get(a))
        cache.put(c, listOf("C"))
        assertNotNull(cache.get(a))
        assertNull(cache.get(b))
        assertNotNull(cache.get(c))
        assertEquals(2, cache.size())
    }

    @Test
    fun capacityOneKeepsOnlyLatest() {
        val cache = PageCache(1)
        cache.put(key(chapterIndex = 1), listOf("A"))
        cache.put(key(chapterIndex = 2), listOf("B"))
        assertEquals(1, cache.size())
        assertNull(cache.get(key(chapterIndex = 1)))
        assertEquals(listOf("B"), cache.get(key(chapterIndex = 2)))
    }

    @Test
    fun capacityZeroDisablesCaching() {
        val cache = PageCache(0)
        val k = key()
        cache.put(k, listOf("不该被缓存"))
        assertNull(cache.get(k))
        assertFalse(cache.contains(k))
        assertEquals(0, cache.size())
        assertEquals(0, cache.maxSize())
    }

    @Test
    fun parameterChangeProducesDifferentKeyAndMissesCache() {
        val cache = PageCache(4)
        cache.put(key(fontSize = 16f), listOf("16 号字排版"))
        // 仅字号变化也必须视为不同 key，绝不返回旧布局
        assertEquals(listOf("16 号字排版"), cache.get(key(fontSize = 16f)))
        assertNull(cache.get(key(fontSize = 18f)))
    }

    @Test
    fun everyKeyFieldAffectsKeyIdentity() {
        val base = PageCache.keyOf(
            bookId = "b", chapterIndex = 1, contentHash = 2, titleHash = 7,
            widthPx = 1080, heightPx = 1920f, fontSize = 16f,
            lineHeightMultiplier = 1.5f, paragraphSpacingPx = 8f, titleSpacerPx = 10f,
            fontFamily = "serif", bold = true, letterSpacing = 0f,
            indent = true, hideChapterTitle = false,
        )
        assertNotEquals(base, base.copy(bookId = "other"))
        assertNotEquals(base, base.copy(chapterIndex = 2))
        assertNotEquals(base, base.copy(contentHash = 3))
        assertNotEquals(base, base.copy(titleHash = 8))
        assertNotEquals(base, base.copy(widthPx = 720))
        assertNotEquals(base, base.copy(heightPx = 1080f))
        assertNotEquals(base, base.copy(fontSize = 18f))
        assertNotEquals(base, base.copy(lineHeightMultiplier = 2f))
        assertNotEquals(base, base.copy(paragraphSpacingPx = 12f))
        assertNotEquals(base, base.copy(titleSpacerPx = 20f))
        assertNotEquals(base, base.copy(fontFamily = "sans"))
        assertNotEquals(base, base.copy(bold = false))
        assertNotEquals(base, base.copy(letterSpacing = 1f))
        assertNotEquals(base, base.copy(indent = false))
        assertNotEquals(base, base.copy(hideChapterTitle = true))
    }

    @Test
    fun keyOfFactoryMatchesDataClassConstructor() {
        assertEquals(
            PageCache.Key(bookId = "b", chapterIndex = 2, fontSize = 20f),
            PageCache.keyOf(bookId = "b", chapterIndex = 2, fontSize = 20f),
        )
    }

    @Test
    fun clearRemovesEverything() {
        val cache = PageCache(4)
        cache.put(key(chapterIndex = 1), listOf("A"))
        cache.put(key(chapterIndex = 2), listOf("B"))
        assertEquals(2, cache.size())
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get(key(chapterIndex = 1)))
    }

    @Test
    fun containsDoesNotCreateEntryAndReflectsPresence() {
        val cache = PageCache(2)
        val k = key()
        assertFalse(cache.contains(k))
        cache.put(k, listOf("X"))
        assertTrue(cache.contains(k))
        assertEquals(1, cache.size())
    }

    @Test
    fun concurrentReadWriteIsSafe() {
        val cache = PageCache(8)
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        try {
            repeat(8) { threadId ->
                pool.execute {
                    try {
                        repeat(200) { i ->
                            val k = key(chapterIndex = i % 16, fontSize = 14f + (i % 4))
                            cache.put(k, listOf("t$threadId-$i"))
                            cache.get(k)
                            cache.contains(k)
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertTrue("并发读写不应抛异常: $errors", errors.isEmpty())
        assertTrue("条目数不应超过容量", cache.size() <= 8)
    }
}
