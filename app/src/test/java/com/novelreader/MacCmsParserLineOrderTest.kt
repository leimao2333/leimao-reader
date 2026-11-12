package com.novelreader

import com.novelreader.source.moontv.MacCmsParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 线路默认选择（v1.9.5.1）：集数优先，同集数时播放地址带媒体后缀的线路优先。
 */
class MacCmsParserLineOrderTest {

    @Test
    fun `same episode count prefers m3u8 line over html page`() {
        val lines = MacCmsParser.parseEpisodeLines(
            playFrom = "线路A\$\$\$线路B",
            playUrl = "正片\$https://play.example.com/play/abc\$\$\$" +
                "正片\$https://play.example.com/play/abc/index.m3u8",
        )
        assertEquals(2, lines.size)
        assertEquals("线路B", lines.first().name)
        assertEquals(
            "https://play.example.com/play/abc/index.m3u8",
            lines.first().episodes.first().url,
        )
    }

    @Test
    fun `more episodes still wins over media suffix`() {
        val lines = MacCmsParser.parseEpisodeLines(
            playFrom = "长线路\$\$\$短线路",
            playUrl = "第1集\$https://a.com/1#第2集\$https://a.com/2#第3集\$https://a.com/3\$\$\$" +
                "正片\$https://a.com/only.m3u8",
        )
        assertEquals("长线路", lines.first().name)
        assertEquals(3, lines.first().episodes.size)
    }

    @Test
    fun `no media suffix keeps original stable order`() {
        val lines = MacCmsParser.parseEpisodeLines(
            playFrom = "A\$\$\$B",
            playUrl = "正片\$https://a.com/x\$\$\$正片\$https://b.com/y",
        )
        assertEquals(listOf("A", "B"), lines.map { it.name })
    }
}
