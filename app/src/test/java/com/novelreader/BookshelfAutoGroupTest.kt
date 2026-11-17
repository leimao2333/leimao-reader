package com.novelreader

import com.novelreader.core.util.AutoGroupInput
import com.novelreader.core.util.BookshelfAutoGroup
import com.novelreader.core.util.ShelfCompanionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C6 书架智能伴生分组 单测。 */
class BookshelfAutoGroupTest {

    @Test
    fun `按进度与完成状态归类`() {
        val inputs = listOf(
            AutoGroupInput(1L, hasProgress = true, finished = false),  // 在读
            AutoGroupInput(2L, hasProgress = false, finished = false), // 未读
            AutoGroupInput(3L, hasProgress = true, finished = true),   // 已读完
            AutoGroupInput(4L, hasProgress = false, finished = true),  // 读完优先于未读
        )
        val groups = BookshelfAutoGroup.classify(inputs)

        assertEquals(listOf(1L), groups[ShelfCompanionGroup.READING])
        assertEquals(listOf(2L), groups[ShelfCompanionGroup.UNREAD])
        // finished=true 时必须进 FINISHED，哪怕 hasProgress=false
        assertEquals(listOf(3L, 4L), groups[ShelfCompanionGroup.FINISHED])
    }

    @Test
    fun `三个分组恒存在，空组返回空列表`() {
        val groups = BookshelfAutoGroup.classify(emptyList())
        assertTrue(groups.containsKey(ShelfCompanionGroup.READING))
        assertTrue(groups.containsKey(ShelfCompanionGroup.UNREAD))
        assertTrue(groups.containsKey(ShelfCompanionGroup.FINISHED))
        groups.values.forEach { assertTrue(it.isEmpty()) }
    }

    @Test
    fun `组内保持传入顺序`() {
        val inputs = listOf(
            AutoGroupInput(9L, true, false),
            AutoGroupInput(2L, true, false),
            AutoGroupInput(5L, true, false),
        )
        assertEquals(listOf(9L, 2L, 5L), BookshelfAutoGroup.classify(inputs)[ShelfCompanionGroup.READING])
    }

    @Test
    fun `完成判定按 99 百分比`() {
        // 100 章，读到第 99 章（index 98）= 99% → 算读完
        assertTrue(BookshelfAutoGroup.approxFinished(chapterIndex = 98, totalChapter = 100))
        // 读到第 98 章（index 97）= 98% → 未读完
        assertFalse(BookshelfAutoGroup.approxFinished(chapterIndex = 97, totalChapter = 100))
    }

    @Test
    fun `总章数unknown时视为未读完`() {
        assertFalse(BookshelfAutoGroup.approxFinished(0, 0))
        assertFalse(BookshelfAutoGroup.approxFinished(5, -1))
    }

    @Test
    fun `单章书读到第一章即算读完`() {
        assertTrue(BookshelfAutoGroup.approxFinished(chapterIndex = 0, totalChapter = 1))
    }

    @Test
    fun `归类结果覆盖所有输入且不重复`() {
        val inputs = (1L..10L).map {
            AutoGroupInput(it, hasProgress = it % 2 == 0L, finished = it % 5 == 0L)
        }
        val all = BookshelfAutoGroup.classify(inputs).values.flatten()
        assertEquals("每本书必须恰好出现在一个分组里", 10, all.size)
        assertEquals(10, all.toSet().size)
    }
}
