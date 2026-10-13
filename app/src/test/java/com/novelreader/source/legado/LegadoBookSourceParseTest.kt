package com.novelreader.source.legado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0.0 「部分小说源不加载」修复回归测试。
 *
 * 根因 1：真实书源里 `header` / 部分字段偶有写成 JSON **对象**（而非字符串）的情况，
 * 旧 `str()` 取值抛异常 → parseOne 失败 → 整个源在列表解析时被静默丢弃。
 * 根因 2：解析失败的条目完全静默，用户无从得知有多少源没导进来。
 */
class LegadoBookSourceParseTest {

    @Test
    fun `header 为 JSON 对象时源不被丢弃`() {
        val raw = """
            [{
                "bookSourceName": "测试源A",
                "bookSourceUrl": "https://a.example.com",
                "bookSourceType": 0,
                "searchUrl": "https://a.example.com/search?q={{key}}",
                "header": {"User-Agent": "Mozilla/5.0", "Referer": "https://a.example.com"},
                "ruleSearch": {"bookList": "class.item", "name": "tag.h3@text"}
            }]
        """.trimIndent()

        val pairs = LegadoBookSource.parseListWithRaw(raw)
        assertEquals(1, pairs.size)
        val src = pairs[0].first
        assertEquals("测试源A", src.name)
        // header 对象序列化后 parseHeaders 仍可解析回 Map
        val headers = src.parseHeaders()
        assertEquals("Mozilla/5.0", headers["User-Agent"])
        assertEquals("https://a.example.com", headers["Referer"])
    }

    @Test
    fun `searchUrl 为对象时不抛异常且其余字段正常`() {
        val raw = """
            [{
                "bookSourceName": "测试源B",
                "bookSourceUrl": "https://b.example.com",
                "bookSourceType": 0,
                "searchUrl": {"mapper": "x"},
                "ruleSearch": {}
            }]
        """.trimIndent()

        val pairs = LegadoBookSource.parseListWithRaw(raw)
        assertEquals(1, pairs.size)
        assertEquals("测试源B", pairs[0].first.name)
    }

    @Test
    fun `列表内坏条目被跳过并计数`() {
        // 第 2 条缺 bookSourceName → 跳过；第 3 条 bookSourceType 为对象 → 旧实现会炸，现应容错
        val raw = """
            [
                {"bookSourceName": "好源1", "bookSourceUrl": "https://1.example.com", "bookSourceType": 0},
                {"bookSourceUrl": "https://bad.example.com", "bookSourceType": 0},
                {"bookSourceName": "好源2", "bookSourceUrl": "https://2.example.com", "bookSourceType": {"weird": true}}
            ]
        """.trimIndent()

        val detailed = LegadoBookSource.parseListWithRawDetailed(raw)
        assertEquals(2, detailed.pairs.size)
        assertEquals(1, detailed.skipped)
        assertEquals(listOf("好源1", "好源2"), detailed.pairs.map { it.first.name })
    }

    @Test
    fun `数字型字段可正常读取`() {
        val raw = """
            [{"bookSourceName": "数字源", "bookSourceUrl": "https://n.example.com", "bookSourceType": 3}]
        """.trimIndent()
        val src = LegadoBookSource.parseListWithRaw(raw)[0].first
        assertTrue(src.isVideo)
        assertEquals(3, src.type)
    }
}
