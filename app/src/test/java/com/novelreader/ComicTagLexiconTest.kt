package com.novelreader

import com.novelreader.core.util.ComicTagLexicon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从标题 / 简介抽取题材标签（v1.7.9.0）。
 *
 * 目的是解决「单部作品标签太少」：源只给 0~1 个标签时，靠标题与简介补。
 * 这里锁住三件事：**多语言命中**（中/英/日/韩）、**不抽元信息**、
 * **单字不误伤**（否则「龙珠」会被打上「龙」、「剑风传奇」被打上「剑」）。
 */
class ComicTagLexiconTest {

    @Test
    fun `中文标题能抽出题材`() {
        val tags = ComicTagLexicon.match("转生成为史莱姆的那件事")
        assertTrue("应命中「转生」：$tags", tags.contains("转生"))
    }

    @Test
    fun `英文标题能抽出题材并归一成中文`() {
        val tags = ComicTagLexicon.match("Vampire Romance at Midnight")
        assertTrue("应命中「吸血鬼」：$tags", tags.contains("吸血鬼"))
        assertTrue("应命中「恋爱」：$tags", tags.contains("恋爱"))
    }

    @Test
    fun `日文标题能抽出题材`() {
        // エルフ = elf = 精灵（同义组里已登记）
        val tags = ComicTagLexicon.match("エルフの花嫁は魔王を恐れない")
        assertTrue("应命中「精灵」：$tags", tags.contains("精灵"))
        assertTrue("应命中「魔王」或「结婚」类题材：$tags", tags.isNotEmpty())
    }

    @Test
    fun `韩文标题能抽出题材`() {
        val tags = ComicTagLexicon.match("마왕의 딸로 태어났다")
        assertTrue("应命中「魔王」：$tags", tags.contains("魔王"))
    }

    @Test
    fun `不抽元信息与状态词`() {
        // 「漫画」「连载中」这类每部作品都有，抽了等于噪音
        val tags = ComicTagLexicon.match("这是一部正在连载中的漫画作品")
        assertFalse("不应命中「漫画」：$tags", tags.contains("漫画"))
        assertFalse("不应命中「连载」：$tags", tags.contains("连载"))
        assertFalse("不应命中「连载中」：$tags", tags.contains("连载中"))
    }

    @Test
    fun `单字标签不会误伤`() {
        // 「龙」「剑」是单字词，直接参与包含匹配会把「龙珠」「剑风传奇」判成「龙」「剑」
        val tags = ComicTagLexicon.match("龙珠超 剑风传奇")
        assertFalse("不应命中单字「龙」：$tags", tags.contains("龙"))
        assertFalse("不应命中单字「剑」：$tags", tags.contains("剑"))
    }

    @Test
    fun `已有标签不会被重复补进来`() {
        val tags = ComicTagLexicon.match("转生与魔王", exclude = setOf("转生"))
        assertFalse("exclude 里的标签不应再出现：$tags", tags.contains("转生"))
        assertTrue(tags.contains("魔王"))
    }

    @Test
    fun `短英文词不做自由文本匹配`() {
        // gl / bl / sf / ol 这类双字符词在自由文本里误命中率极高
        val tags = ComicTagLexicon.match("follow the online signal")
        assertFalse("不应命中「百合」：$tags", tags.contains("百合"))
        assertFalse("不应命中「科幻」：$tags", tags.contains("科幻"))
    }

    @Test
    fun `空文本返回空且不超过上限`() {
        assertTrue(ComicTagLexicon.match("").isEmpty())
        val many = ComicTagLexicon.match(
            "恋爱 校园 战斗 冒险 奇幻 魔法 吸血鬼 丧尸 末日 机甲 运动 音乐",
            limit = 3,
        )
        assertTrue("应尊重 limit：$many", many.size <= 3)
    }
}
