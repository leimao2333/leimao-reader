package com.novelreader

import com.novelreader.core.search.TagScanMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 翻页记忆（v1.9.6.0）：书签的键口径、读写、作废与落盘。
 *
 * 这些点错了都会变成「下次还是从头翻」这种看起来像没做的现象 ——
 * 尤其是键口径：`无码 全彩` 与 `全彩 无码` 必须是同一份书签。
 */
class TagScanMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `key ignores tag order and blanks`() {
        assertEquals(
            TagScanMemory.keyOf("VIDEO", listOf("全彩", "无码")),
            TagScanMemory.keyOf("VIDEO", listOf(" 无码 ", "全彩", "")),
        )
        // 类型不同要分开算：同一个标签名在小说 / 视频里是两个世界
        assertNotNull(TagScanMemory.keyOf("NOVEL", listOf("无码")))
        assertEquals(false, TagScanMemory.keyOf("NOVEL", listOf("无码")) ==
            TagScanMemory.keyOf("VIDEO", listOf("无码")))
    }

    @Test
    fun `remember then read back`() {
        TagScanMemory.init(tmp.newFolder("a"))
        val key = TagScanMemory.keyOf("VIDEO", listOf("里番组"))
        assertEquals(1, TagScanMemory.nextPageOf(key))

        TagScanMemory.remember(key, nextPage = 38, scannedPages = 37)
        assertEquals(38, TagScanMemory.nextPageOf(key))
        assertEquals(37, TagScanMemory.peek(key)?.scannedPages)
    }

    @Test
    fun `page one is not worth a bookmark`() {
        TagScanMemory.init(tmp.newFolder("b"))
        val key = TagScanMemory.keyOf("VIDEO", listOf("里番组"))
        TagScanMemory.remember(key, nextPage = 1)
        // 第 1 页等于「没翻过」，存下来只会让下次多走一次 clear
        assertNull(TagScanMemory.peek(key))
    }

    @Test
    fun `clear drops the bookmark`() {
        TagScanMemory.init(tmp.newFolder("c"))
        val key = TagScanMemory.keyOf("COMIC", listOf("全彩"))
        TagScanMemory.remember(key, nextPage = 12)
        assertEquals(12, TagScanMemory.nextPageOf(key))

        TagScanMemory.clear(key)
        assertEquals(1, TagScanMemory.nextPageOf(key))
    }

    @Test
    fun `bookmark survives restart`() {
        val dir = tmp.newFolder("d")
        TagScanMemory.init(dir)
        val key = TagScanMemory.keyOf("VIDEO", listOf("里番组", "无码"))
        TagScanMemory.remember(key, nextPage = 57, scannedPages = 56)

        // 模拟杀进程后重新启动：重新 init 应该从文件读回来
        TagScanMemory.init(dir)
        assertEquals(57, TagScanMemory.nextPageOf(key))
        assertEquals(56, TagScanMemory.peek(key)?.scannedPages)
    }
}
