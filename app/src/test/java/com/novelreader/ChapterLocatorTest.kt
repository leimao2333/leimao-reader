package com.novelreader

import com.novelreader.domain.model.ChapterItem
import com.novelreader.source.search.ChapterLocator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 换源章节定位回归测试。
 *
 * 覆盖「换源形同虚设」的三种典型场景：
 * - 标题完全一致 → 精确定位
 * - 标题风格变化（前缀 / 噪声）→ 归一化 / 包含匹配
 * - 标题对不上 → 按阅读比例落点，而不是一律回第一章
 */
class ChapterLocatorTest {

    private fun chapters(titles: List<String>) = titles.mapIndexed { i, t ->
        ChapterItem(id = "$i", title = t, url = "u$i", index = i)
    }

    @Test
    fun `标题完全一致时精确定位`() {
        val target = chapters(listOf("第一章 开端", "第二章 深入", "第三章 终局"))
        val idx = ChapterLocator.locate(target, "第二章 深入", currentPosition = 1, oldTotal = 3)
        assertEquals(1, idx)
    }

    @Test
    fun `源改写章节前缀时仍能匹配`() {
        // 目标源给章节标题加了「正文」前缀，完全相等匹配失败，应走包含匹配
        val target = chapters(listOf("正文 第一章 开端", "正文 第二章 深入", "正文 第三章 终局"))
        val idx = ChapterLocator.locate(target, "第二章 深入", currentPosition = 1, oldTotal = 3)
        assertEquals(1, idx)
    }

    @Test
    fun `标题带空白噪声时归一化匹配`() {
        val target = chapters(listOf("第一章 开端", "第 二 章 深 入", "第三章 终局"))
        val idx = ChapterLocator.locate(target, "第二章深入", currentPosition = 1, oldTotal = 3)
        assertEquals(1, idx)
    }

    @Test
    fun `标题完全对不上时按阅读比例映射而非回第一章`() {
        // 旧目录 100 章，读到第 60 章（下标 59）；新目录 200 章
        // 期望落在约 60% 处（约 120 章），而不是 0
        val target = chapters((1..200).map { "卷${it}节" })
        val idx = ChapterLocator.locate(target, "完全不同的标题", currentPosition = 59, oldTotal = 100)
        assertEquals(120, idx)
    }

    @Test
    fun `读到末章时比例映射落在目标末尾`() {
        val target = chapters((1..50).map { "第${it}章" })
        val idx = ChapterLocator.locate(target, "对不上", currentPosition = 99, oldTotal = 100)
        assertEquals(50 - 1, idx)
    }

    @Test
    fun `目标目录为空时返回 0 不越界`() {
        assertEquals(0, ChapterLocator.locate(emptyList(), "任意", 5, 10))
    }

    @Test
    fun `位置未知且标题对不上时回落首章`() {
        val target = chapters(listOf("A", "B", "C"))
        assertEquals(0, ChapterLocator.locate(target, "对不上", currentPosition = -1, oldTotal = 0))
    }
}
