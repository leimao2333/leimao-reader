package com.novelreader

import com.novelreader.core.util.VideoFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 片库筛选纯逻辑单测（v1.3.5.0）。
 *
 * 重点在三条容易写错的规则：
 * 1. 年份必须**按当前年动态生成**（写死列表过几年就选不到最新年份）；
 * 2.「全部」统一映射为空串，空值不进请求参数（否则会变成 `area=` 这种脏参数）；
 * 3. 下拉按钮在未筛选时显示**维度名**而非「全部」，否则四个按钮全是「全部」看不出能点什么。
 */
class VideoFiltersTest {

    // ===== 地区 =====

    @Test
    fun `地区首个选项是不限且值为空串`() {
        val areas = VideoFilters.areas()
        assertEquals(VideoFilters.ALL, areas.first())
        assertEquals("", areas.first().value)
        assertEquals(VideoFilters.ALL_LABEL, areas.first().label)
    }

    @Test
    fun `地区含常见产地且不含重复项`() {
        val areas = VideoFilters.areas().drop(1)
        assertTrue(areas.any { it.value == "大陆" })
        assertTrue(areas.any { it.value == "美国" })
        assertTrue(areas.any { it.value == "日本" })
        assertEquals("选项不应重复", areas.size, areas.map { it.value }.toSet().size)
    }

    // ===== 年份 =====

    @Test
    fun `年份从当前年倒序生成且数量为窗口大小`() {
        val years = VideoFilters.years(2026)
        assertEquals(VideoFilters.YEAR_WINDOW + 1, years.size) // 含「全部」
        assertEquals("", years.first().value)
        assertEquals("2026", years[1].value)
        assertEquals("2026", years[1].label)
        assertEquals("2016", years.last().value)
        assertEquals(VideoFilters.YEAR_WINDOW, years.size - 1)
    }

    @Test
    fun `年份全部为四位数字且不重复`() {
        val values = VideoFilters.years(2026).drop(1).map { it.value }
        assertEquals(values.size, values.toSet().size)
        values.forEach { assertTrue("年份应是 4 位数字：$it", Regex("""^\d{4}$""").matches(it)) }
    }

    /** 跨年场景：2027 年打开时列表里必须有 2027（这是「动态生成」的存在意义） */
    @Test
    fun `次年打开时列表首位是次年`() {
        assertEquals("2027", VideoFilters.years(2027)[1].value)
        assertFalse(VideoFilters.years(2027).any { it.value == "2028" })
    }

    // ===== 排序 =====

    @Test
    fun `排序使用苹果 CMS 约定的参数值`() {
        val orders = VideoFilters.orders()
        assertEquals("", orders.first().value)
        assertEquals(
            listOf("time", "hits", "score"),
            orders.drop(1).map { it.value },
        )
        assertEquals(listOf("最新", "最热", "评分"), orders.drop(1).map { it.label })
    }

    // ===== 参数组装 =====

    @Test
    fun `全不限时不产生任何参数`() {
        assertTrue(VideoFilters.toFilterMap("", "", "").isEmpty())
    }

    @Test
    fun `只组装非空维度`() {
        assertEquals(mapOf("area" to "大陆"), VideoFilters.toFilterMap("大陆", "", ""))
        assertEquals(mapOf("year" to "2024"), VideoFilters.toFilterMap("", "2024", ""))
        assertEquals(mapOf("order" to "score"), VideoFilters.toFilterMap("", "", "score"))
    }

    @Test
    fun `多个维度同时组装`() {
        val map = VideoFilters.toFilterMap("美国", "2023", "hits")
        assertEquals(3, map.size)
        assertEquals("美国", map["area"])
        assertEquals("2023", map["year"])
        assertEquals("hits", map["order"])
    }

    /** 空白串也必须被当作「不限」，否则会拼出 `area=%20` 这类脏参数 */
    @Test
    fun `空白值被视为不限`() {
        assertTrue(VideoFilters.toFilterMap("  ", "\t", "").isEmpty())
    }

    // ===== 按钮文案 =====

    @Test
    fun `未筛选时显示维度名`() {
        assertEquals("地区", VideoFilters.labelOf(VideoFilters.areas(), "", "地区"))
        assertEquals("年份", VideoFilters.labelOf(VideoFilters.years(2026), "", "年份"))
        assertEquals("排序", VideoFilters.labelOf(VideoFilters.orders(), "", "排序"))
    }

    @Test
    fun `已筛选时显示所选值的中文标签`() {
        assertEquals("大陆", VideoFilters.labelOf(VideoFilters.areas(), "大陆", "地区"))
        // 排序提交值英文、显示中文
        assertEquals("评分", VideoFilters.labelOf(VideoFilters.orders(), "score", "排序"))
    }

    @Test
    fun `遇到不在候选表里的值原样显示`() {
        assertEquals("内地", VideoFilters.labelOf(VideoFilters.areas(), "内地", "地区"))
    }
}
