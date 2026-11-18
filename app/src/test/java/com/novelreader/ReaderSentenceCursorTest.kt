package com.novelreader

import com.novelreader.core.reader.ReaderSentenceCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A12 朗读逐句跟随高亮 —— 正文切句游标单测。
 *
 * 覆盖：中文标点、英文标点、连续标点、无标点长文本、空文本、引号内标点、
 * 英文缩写误切（Mr. / e.g. / 小数 / 链接内点）、换行边界、空白忽略、分号切句，
 * 以及 [ReaderSentenceCursor.locate] 区间与 [ReaderSentenceCursor.sentences] 的一致性。
 */
class ReaderSentenceCursorTest {

    private fun assertSentences(text: String, expected: List<String>) {
        assertEquals(expected, ReaderSentenceCursor.sentences(text))
    }

    @Test
    fun `中文标点切句并保留句末标点`() {
        assertSentences(
            "今天天气很好。我们出去玩吧！你说呢？",
            listOf("今天天气很好。", "我们出去玩吧！", "你说呢？"),
        )
    }

    @Test
    fun `英文标点切句`() {
        assertSentences(
            "Hello world. This is a test! Good?",
            listOf("Hello world.", "This is a test!", "Good?"),
        )
    }

    @Test
    fun `连续标点并入同一句`() {
        assertSentences(
            "真的吗？！太好了……",
            listOf("真的吗？！", "太好了……"),
        )
    }

    @Test
    fun `无标点长文本整体成一句`() {
        val long = "这是一段没有任何标点符号的很长很长很长的文本"
        assertSentences(long, listOf(long))
    }

    @Test
    fun `空文本返回空列表`() {
        assertSentences("", emptyList())
    }

    @Test
    fun `引号内标点不把句子劈开`() {
        assertSentences(
            "他说：“你好。”",
            listOf("他说：“你好。”"),
        )
    }

    @Test
    fun `英文缩写Mr不误切`() {
        assertSentences(
            "Mr. Smith went home. Then he slept.",
            listOf("Mr. Smith went home.", "Then he slept."),
        )
    }

    @Test
    fun `拉丁缩写eg不误切`() {
        assertSentences(
            "I like fruits, e.g. apples. Very much.",
            listOf("I like fruits, e.g. apples.", "Very much."),
        )
    }

    @Test
    fun `小数不误切`() {
        assertSentences(
            "Pi is 3.14 and e is 2.71.",
            listOf("Pi is 3.14 and e is 2.71."),
        )
    }

    @Test
    fun `链接内点不误切`() {
        assertSentences(
            "访问 www.example.com 看看。",
            listOf("访问 www.example.com 看看。"),
        )
    }

    @Test
    fun `换行是句子边界且不跨段`() {
        assertSentences(
            "第一行没有标点\n第二行有句号。",
            listOf("第一行没有标点", "第二行有句号。"),
        )
    }

    @Test
    fun `句首句尾空白被忽略`() {
        assertSentences(
            "  你好。  \n\n  再见！  ",
            listOf("你好。", "再见！"),
        )
    }

    @Test
    fun `中文分号也切句`() {
        assertSentences(
            "甲；乙。",
            listOf("甲；", "乙。"),
        )
    }

    @Test
    fun `locate区间与sentences下标一致`() {
        val text = "今天很好。明天更好！"
        val sentences = ReaderSentenceCursor.sentences(text)
        sentences.forEachIndexed { index, s ->
            val range = ReaderSentenceCursor.locate(text, index)!!
            assertEquals(
                "第 $index 句的区间应还原出原句",
                s,
                text.substring(range.start, range.end),
            )
        }
    }

    @Test
    fun `locate越界返回null`() {
        assertNull(ReaderSentenceCursor.locate("只有一句。", 5))
        assertNull(ReaderSentenceCursor.locate("", 0))
    }
}
