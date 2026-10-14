package com.novelreader

import com.novelreader.domain.model.ChapterItem
import com.novelreader.ui.screens.comic.ComicChapterVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 漫画目录分卷（C4）纯逻辑测试。
 */
class ComicChapterVolumeTest {

    private fun ch(i: Int, title: String) = ChapterItem(id = "$i", title = title, url = "u$i", index = i)

    @Test
    fun `解析阿拉伯数字卷号`() {
        assertEquals(1, ComicChapterVolume.volumeOf("第1卷 第5话"))
        assertEquals(12, ComicChapterVolume.volumeOf("第12卷"))
        assertEquals(3, ComicChapterVolume.volumeOf("Vol.3 Ch.2"))
    }

    @Test
    fun `解析中文数字卷号`() {
        assertEquals(1, ComicChapterVolume.volumeOf("第一卷"))
        assertEquals(2, ComicChapterVolume.volumeOf("第二卷 第3话"))
        assertEquals(12, ComicChapterVolume.volumeOf("第十二卷"))
    }

    @Test
    fun `解析全角数字卷号`() {
        assertEquals(3, ComicChapterVolume.volumeOf("第３卷"))
    }

    @Test
    fun `无卷号返回负一`() {
        assertEquals(-1, ComicChapterVolume.volumeOf("第5话"))
        assertEquals(-1, ComicChapterVolume.volumeOf("序章"))
    }

    @Test
    fun `扁平目录不视为分卷`() {
        val chapters = listOf(ch(0, "第1话"), ch(1, "第2话"), ch(2, "第3话"))
        assertFalse(ComicChapterVolume.isVolumeStructured(chapters))
        // 退回单组
        val groups = ComicChapterVolume.groupByVolume(chapters)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].second.size)
    }

    @Test
    fun `分卷目录按卷分组且保持顺序`() {
        val chapters = listOf(
            ch(0, "第1卷 第1话"),
            ch(1, "第1卷 第2话"),
            ch(2, "第2卷 第1话"),
            ch(3, "第2卷 第2话"),
            ch(4, "第2卷 第3话"),
        )
        assertTrue(ComicChapterVolume.isVolumeStructured(chapters))
        val groups = ComicChapterVolume.groupByVolume(chapters)
        assertEquals(2, groups.size)
        assertEquals("第 1 卷", groups[0].first)
        assertEquals(2, groups[0].second.size)
        assertEquals("第 2 卷", groups[1].first)
        assertEquals(3, groups[1].second.size)
        // 卷内顺序保持
        assertEquals("第1卷 第1话", groups[0].second[0].title)
        assertEquals("第2卷 第3话", groups[1].second[2].title)
    }

    @Test
    fun `只有少量卷号不视为结构化分卷`() {
        // 仅 1/4 的章节带卷号，应退回扁平
        val chapters = listOf(
            ch(0, "第1卷 第1话"),
            ch(1, "第1话"),
            ch(2, "第2话"),
            ch(3, "第3话"),
        )
        assertFalse(ComicChapterVolume.isVolumeStructured(chapters))
    }
}
