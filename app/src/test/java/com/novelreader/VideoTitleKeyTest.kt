package com.novelreader

import com.novelreader.source.search.FuzzyMatch
import com.novelreader.source.search.VideoTitleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视片名归一化测试（v1.3.7.0）。
 *
 * 对应真实问题：同一部片在不同采集站的名字带「来源侧后缀」（年份 / 画质 / 版本），
 * 不剥离就导致聚合搜索折叠不起来 —— 搜「流浪地球3」会看到四条同名结果。
 */
class VideoTitleKeyTest {

    // ===== 应被归一到同一个 key =====

    @Test
    fun `年份括号后缀被剥离`() {
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3(2026)"))
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3（2026）"))
    }

    @Test
    fun `方括号与书名号方括号后缀被剥离`() {
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3[国语]"))
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3【HD】"))
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3【粤语版】"))
    }

    @Test
    fun `尾部画质版本词被剥离`() {
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3 高清完整版"))
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3 HD"))
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3 中字"))
    }

    @Test
    fun `多轮后缀可一次剥净`() {
        // 「流浪地球3(2026)【hd】」需要剥两轮
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3(2026)【HD】"))
        assertEquals("流浪地球3", VideoTitleKey.of("流浪地球3 高清完整版【国语】"))
    }

    @Test
    fun `空白与全角差异被归一`() {
        assertEquals(VideoTitleKey.of("流浪地球3"), VideoTitleKey.of("流浪地球３"))
        assertEquals(VideoTitleKey.of("流浪地球3"), VideoTitleKey.of(" 流浪 地球3 "))
    }

    @Test
    fun `四种常见写法全都收敛到同一 key`() {
        val keys = setOf(
            VideoTitleKey.of("流浪地球3"),
            VideoTitleKey.of("流浪地球3(2026)"),
            VideoTitleKey.of("流浪地球3【HD】"),
            VideoTitleKey.of("流浪地球3 高清完整版"),
        )
        assertEquals("四种写法应归一为同一个 key", 1, keys.size)
    }

    // ===== 绝不能被剥掉的内容标识 =====

    @Test
    fun `季数不会被剥离`() {
        // 不同季是不同作品，合并会误导用户
        assertNotEquals(VideoTitleKey.of("庆余年第一季"), VideoTitleKey.of("庆余年第二季"))
        assertEquals("庆余年第二季", VideoTitleKey.of("庆余年第二季"))
        // 归一化会去空白 + 转小写
        assertEquals("runningman第2季", VideoTitleKey.of("Running Man 第2季"))
    }

    @Test
    fun `裸数字结尾不会被剥离`() {
        assertEquals("唐人街探案3", VideoTitleKey.of("唐人街探案3"))
        assertEquals("速度与激情10", VideoTitleKey.of("速度与激情10"))
        assertNotEquals(VideoTitleKey.of("唐人街探案3"), VideoTitleKey.of("唐人街探案2"))
    }

    @Test
    fun `词表中缀出现在片名中间时不剥离`() {
        // 「高清」在中间 —— 只剥尾部，避免「高清时代」变成「时代」
        assertEquals("高清时代", VideoTitleKey.of("高清时代"))
        assertEquals("完整版攻略", VideoTitleKey.of("完整版攻略"))
    }

    // ===== 边界 =====

    @Test
    fun `纯括号片名退回归一化结果而非空串`() {
        // 否则所有「纯括号片名」都会被归一成空 key 而错误合并成一组
        val k = VideoTitleKey.of("【预告】")
        assertTrue("纯括号片名不能归一成空串", k.isNotEmpty())
        assertEquals(FuzzyMatch.normalize("【预告】"), k)
    }

    @Test
    fun `纯词缀片名不会被剥空`() {
        assertTrue(VideoTitleKey.of("高清").isNotEmpty())
        assertTrue(VideoTitleKey.of("完整版").isNotEmpty())
    }

    @Test
    fun `空串与纯空白不抛异常`() {
        assertEquals("", VideoTitleKey.of(""))
        assertEquals("", VideoTitleKey.of("   "))
    }

    @Test
    fun `普通无后缀片名原样保留`() {
        assertEquals("三大队", VideoTitleKey.of("三大队"))
        assertEquals("繁花", VideoTitleKey.of("繁花"))
    }
}
