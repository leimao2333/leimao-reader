package com.novelreader

import com.novelreader.ui.screens.novel.ContentSearchChapter
import com.novelreader.ui.screens.novel.ContentSearchEngine
import com.novelreader.ui.screens.novel.ContentSearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文搜索纯逻辑单测：覆盖多章匹配、非重叠计数、上下文摘录的高亮偏移与结果上限。
 * 这些正是旧面板里最容易算错的部分，抽成纯函数后可直接在 JVM 上验证。
 */
class ContentSearchEngineTest {

    private fun chapter(index: Int, title: String, content: String) = ContentSearchChapter(
        listIndex = index,
        chapterIndex = index * 100,
        title = title,
        content = content,
    )

    @Test
    fun `findOccurrences 空关键词或空正文返回空列表`() {
        assertEquals(emptyList<Int>(), ContentSearchEngine.findOccurrences("abc", ""))
        assertEquals(emptyList<Int>(), ContentSearchEngine.findOccurrences("", "a"))
    }

    @Test
    fun `findOccurrences 返回非重叠命中且保持顺序`() {
        assertEquals(listOf(0, 8), ContentSearchEngine.findOccurrences("cat-dog-cat", "cat"))
        // 「aaa」里「aa」的两处出现互相重叠，只应算一次（与旧面板一致）。
        assertEquals(listOf(0), ContentSearchEngine.findOccurrences("aaa", "aa"))
    }

    @Test
    fun `buildExcerpt 中间命中时高亮区间精确指向关键词`() {
        val text = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val pos = text.indexOf("ABC")
        val ex = ContentSearchEngine.buildExcerpt(text, pos, 3, before = 5, after = 4)
        assertTrue(ex.text.startsWith("…"))
        assertTrue(ex.text.endsWith("…"))
        assertEquals("ABC", ex.text.substring(ex.matchStart, ex.matchEnd))
    }

    @Test
    fun `buildExcerpt 首尾命中时不加多余的省略号`() {
        val head = ContentSearchEngine.buildExcerpt("甲乙丙丁戊", 0, 1, before = 5, after = 2)
        assertFalse(head.text.startsWith("…"))
        assertEquals("甲", head.text.substring(head.matchStart, head.matchEnd))

        val tail = ContentSearchEngine.buildExcerpt("甲乙丙丁戊", 4, 1, before = 2, after = 5)
        assertFalse(tail.text.endsWith("…"))
        assertEquals("戊", tail.text.substring(tail.matchStart, tail.matchEnd))
    }

    @Test
    fun `buildExcerpt 换行替换后高亮下标仍然正确`() {
        val text = "前\n后关键词尾"
        val pos = text.indexOf("关键词")
        val ex = ContentSearchEngine.buildExcerpt(text, pos, 3, before = 10, after = 10)
        assertFalse(ex.text.contains("\n"))
        assertEquals("关键词", ex.text.substring(ex.matchStart, ex.matchEnd))
    }

    @Test
    fun `search 本章范围只返回当前章的结果`() {
        val chapters = listOf(
            chapter(0, "第一章", "苹果香蕉苹果"),
            chapter(1, "第二章", "苹果梨"),
        )
        val results = ContentSearchEngine.search(chapters, "苹果", ContentSearchScope.CURRENT_CHAPTER, 0)
        assertEquals(2, results.size)
        assertTrue(results.all { it.chapter.listIndex == 0 })
        assertEquals(listOf(0, 4), results.map { it.position })
    }

    @Test
    fun `search 全书范围按章节顺序返回结果`() {
        val chapters = listOf(
            chapter(0, "第一章", "苹果香蕉苹果"),
            chapter(1, "第二章", "苹果梨"),
        )
        val results = ContentSearchEngine.search(chapters, "苹果", ContentSearchScope.WHOLE_BOOK, 0)
        assertEquals(3, results.size)
        assertEquals(listOf(0, 0, 1), results.map { it.chapter.listIndex })
    }

    @Test
    fun `search 受结果上限约束且空关键词返回空`() {
        val chapters = listOf(chapter(0, "章", "ab".repeat(600)))
        val results = ContentSearchEngine.search(chapters, "ab", ContentSearchScope.WHOLE_BOOK, 0, maxResults = 10)
        assertEquals(10, results.size)
        assertEquals(
            emptyList<com.novelreader.ui.screens.novel.ContentSearchMatch>(),
            ContentSearchEngine.search(chapters, "  ", ContentSearchScope.WHOLE_BOOK, 0),
        )
    }
}
