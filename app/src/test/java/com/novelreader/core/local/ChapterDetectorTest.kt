package com.novelreader.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能目录识别回归测试（B9）。
 *
 * 覆盖四类场景：
 * - 中文序号（第X章 / 卷一 / 第X节 / 第X回 / 番外 / 楔子……）
 * - 英文序号（Chapter N / CHAPTER I / Part 1 / Prologue / Epilogue）
 * - 常见异体（正文 第X章 / chapter-1 / 001 标题 / 1. 标题）
 * - 误判防护与回退（正文里出现的「第三章」不算标题；无格式时按固定长度切分）
 */
class ChapterDetectorTest {

    // ------------------------------------------------------------------
    // 中文
    // ------------------------------------------------------------------

    @Test
    fun `阿拉伯数字章节 第1章 第2章 都能识别`() {
        val text = "第1章 开端\n正文一\n第2章 深入\n正文二"
        val marks = ChapterDetector.detect(text)
        assertEquals(2, marks.size)
        assertEquals("第1章 开端", marks[0].title)
        assertEquals("第2章 深入", marks[1].title)
    }

    @Test
    fun `中文数字章节 第一章 能识别`() {
        val marks = ChapterDetector.detect("第一章\n正文\n第二章\n正文")
        assertEquals(2, marks.size)
        assertEquals("第一章", marks[0].title)
    }

    @Test
    fun `大数字中文 第一百二十三章 能识别`() {
        val marks = ChapterDetector.detect("第一百二十三章\n正文\n第一百二十四章\n正文")
        assertEquals(2, marks.size)
        assertEquals("第一百二十三章", marks[0].title)
    }

    @Test
    fun `数字与标题之间有空格 第 1 章 也能识别`() {
        val marks = ChapterDetector.detect("第 1 章 开端\n正文\n第 2 章 深入")
        assertEquals(2, marks.size)
        assertEquals("第 1 章 开端", marks[0].title)
    }

    @Test
    fun `卷一 第X节 第X回 都能识别`() {
        val text = "卷一 风起\n正文\n第二节 山雨\n正文\n第三回 重逢\n正文"
        val marks = ChapterDetector.detect(text)
        assertEquals(3, marks.size)
        assertEquals("卷一 风起", marks[0].title)
        assertEquals("第二节 山雨", marks[1].title)
        assertEquals("第三回 重逢", marks[2].title)
    }

    @Test
    fun `番外 楔子 终章 都能识别`() {
        val text = "楔子\n正文\n正文\n番外 后日谈\n正文\n终章\n正文"
        val marks = ChapterDetector.detect(text)
        assertEquals(3, marks.size)
        assertEquals("楔子", marks[0].title)
        assertEquals("番外 后日谈", marks[1].title)
        assertEquals("终章", marks[2].title)
    }

    @Test
    fun `章节带副标题时整行都作为标题`() {
        val marks = ChapterDetector.detect("第一章 开端：新的旅程\n正文\n第二章 深入")
        assertEquals(2, marks.size)
        assertEquals("第一章 开端：新的旅程", marks[0].title)
    }

    // ------------------------------------------------------------------
    // 英文
    // ------------------------------------------------------------------

    @Test
    fun `英文 Chapter N 能识别`() {
        val marks = ChapterDetector.detect("Chapter 1\nbody\nChapter 2\nbody")
        assertEquals(2, marks.size)
        assertEquals("Chapter 1", marks[0].title)
    }

    @Test
    fun `大写 CHAPTER 罗马数字 能识别`() {
        val marks = ChapterDetector.detect("CHAPTER I\nbody\nCHAPTER II\nbody")
        assertEquals(2, marks.size)
        assertEquals("CHAPTER I", marks[0].title)
        assertEquals("CHAPTER II", marks[1].title)
    }

    @Test
    fun `Part 1 能识别`() {
        val marks = ChapterDetector.detect("Part 1 The Boy\nbody\nPart 2 The Forest\nbody")
        assertEquals(2, marks.size)
        assertEquals("Part 1 The Boy", marks[0].title)
    }

    @Test
    fun `Prologue 与 Epilogue 能识别`() {
        val marks = ChapterDetector.detect("Prologue\nbody\nEpilogue\nbody")
        assertEquals(2, marks.size)
        assertEquals("Prologue", marks[0].title)
        assertEquals("Epilogue", marks[1].title)
    }

    // ------------------------------------------------------------------
    // 异体
    // ------------------------------------------------------------------

    @Test
    fun `正文 第X章 前缀能识别`() {
        val marks = ChapterDetector.detect("正文 第一章\nbody\n正文 第二章\nbody")
        assertEquals(2, marks.size)
        assertEquals("正文 第一章", marks[0].title)
    }

    @Test
    fun `chapter-1 连字符写法能识别`() {
        val marks = ChapterDetector.detect("chapter-1\nbody\nchapter-2\nbody")
        assertEquals(2, marks.size)
        assertEquals("chapter-1", marks[0].title)
    }

    @Test
    fun `001 标题 前导零写法能识别`() {
        val marks = ChapterDetector.detect("001 开端\nbody\n002 深入\nbody")
        assertEquals(2, marks.size)
        assertEquals("001 开端", marks[0].title)
    }

    @Test
    fun `1 标题 带点分隔符能识别`() {
        val marks = ChapterDetector.detect("1. 开端\nbody\n2. 深入\nbody")
        assertEquals(2, marks.size)
        assertEquals("1. 开端", marks[0].title)
    }

    // ------------------------------------------------------------------
    // 误判防护
    // ------------------------------------------------------------------

    @Test
    fun `正文里出现的第三章不在行首时不识别为标题`() {
        val text = "他翻到了第三章，这一节写得很好。\n下一行也是正文。"
        assertTrue(ChapterDetector.detect(text).isEmpty())
    }

    @Test
    fun `数量词短语 3天后 不识别为标题`() {
        val text = "3 天后他离开了家。\n\n5 块钱是他全部的积蓄。"
        assertTrue(ChapterDetector.detect(text).isEmpty())
    }

    @Test
    fun `长段落不以标题形态出现时不识别`() {
        val text = "第一章 这是一段很长的正文，长到超过标题应该有的长度上限，" +
            "它只是恰好以第一章开头，所以不应该被当成一个章节标题来处理，否则整本书就碎了。\n" +
            "后面还有一行。\n"
        assertTrue(ChapterDetector.detect(text).isEmpty())
    }

    // ------------------------------------------------------------------
    // 回退与区间
    // ------------------------------------------------------------------

    @Test
    fun `完全没有章节格式时返回空 由调用方决定回退`() {
        val text = "这是一段没有任何章节格式的正文。\n又一段。\n".repeat(30)
        assertTrue(ChapterDetector.detect(text).isEmpty())
    }

    @Test
    fun `无章节格式时按固定长度切分回退`() {
        val text = "没有任何章节格式的正文内容。\n".repeat(40)
        val marks = ChapterDetector.detectWithFallback(text, chunkSize = 100)
        assertTrue(marks.size >= 2)
        assertEquals(0, marks.first().start)
        assertEquals(text.length, marks.last().end)
    }

    @Test
    fun `只有一个标题时也能识别 兼容旧实现`() {
        val marks = ChapterDetector.detect("楔子\n只有一章的书。")
        assertEquals(1, marks.size)
        assertEquals("楔子", marks[0].title)
    }

    @Test
    fun `章节区间指向标题行本身`() {
        val text = "第一章 开端\n正文内容\n第二章 深入\n正文内容"
        val marks = ChapterDetector.detect(text)
        assertEquals("第一章 开端", text.substring(marks[0].start, marks[0].end))
        assertEquals("第二章 深入", text.substring(marks[1].start, marks[1].end))
        assertTrue(text.substring(marks[0].end, marks[1].start).contains("正文内容"))
    }

    @Test
    fun `isChapterTitle 与识别口径一致`() {
        assertTrue(ChapterDetector.isChapterTitle("第一章 开端"))
        assertTrue(ChapterDetector.isChapterTitle("Chapter 3"))
        assertTrue(ChapterDetector.isChapterTitle("正文 第一章"))
        assertTrue(!ChapterDetector.isChapterTitle("他翻到了第三章"))
        assertTrue(!ChapterDetector.isChapterTitle("   "))
    }
}
