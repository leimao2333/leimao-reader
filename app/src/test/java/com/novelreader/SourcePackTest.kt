package com.novelreader

import com.novelreader.domain.model.SourceFormat
import com.novelreader.source.loader.SourcePack
import com.novelreader.source.loader.SourceValidator
import com.novelreader.source.venera.VeneraIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聚合源包清单（leimaoPack）解析测试（v1.5.6.0）。
 *
 * 纯逻辑、离线：覆盖「一条链接导入全部源」的识别与地址解析，
 * 以及**不误伤**既有格式（legado / MoonTV / Venera 索引）的回归。
 */
class SourcePackTest {

    private val manifestJson = """
        {
          "leimaoPack": 1,
          "name": "磊毛阅读聚合源",
          "version": "1.0",
          "files": ["legado-all.json", "video-all.json", "venera/index.json"]
        }
    """.trimIndent()

    // ==================== 识别 ====================

    @Test
    fun `合法清单能解析出全部子包`() {
        assertTrue(SourcePack.looksLikeManifest(manifestJson))
        val m = SourcePack.parse(manifestJson)!!
        assertEquals(1, m.version)
        assertEquals("磊毛阅读聚合源", m.name)
        assertEquals(
            listOf("legado-all.json", "video-all.json", "venera/index.json"),
            m.files,
        )
    }

    @Test
    fun `缺少 files 或魔数不算清单`() {
        assertNull(SourcePack.parse("""{"leimaoPack":1,"files":[]}"""))
        assertNull(SourcePack.parse("""{"name":"x","files":["a.json"]}"""))
        assertFalse(SourcePack.looksLikeManifest("""{"name":"x","files":["a.json"]}"""))
    }

    @Test
    fun `合法清单经 SourceValidator 校验带 isSourcePack 标记`() {
        val r = SourceValidator.validate(manifestJson, "all.json")
        assertTrue(r.message, r.valid)
        assertTrue("必须标记为聚合包清单", r.isSourcePack)
        // 清单不是源，不应被当成 legado / Venera
        assertFalse(r.isLegadoList)
        assertFalse(r.isVeneraIndex)
    }

    @Test
    fun `既有格式不再被误判为清单`() {
        val legadoArray = """[{"bookSourceName":"甲","bookSourceUrl":"https://a.com"}]"""
        val legadoSingle = """{"bookSourceName":"甲","bookSourceUrl":"https://a.com"}"""
        val moonTv = """{"api_site":{"a.com":{"name":"甲","api":"https://a.com/api.php/provide/vod"}}}"""
        val veneraIndex = """[{"name":"甲","fileName":"a.js","key":"a","version":"1"}]"""
        val jsSource = """function getMeta(){return{}} function search(){} function chapterList(){} function content(){}"""

        listOf(legadoArray, legadoSingle, moonTv, veneraIndex, jsSource).forEach { content ->
            assertFalse("不应被识别为聚合包清单：$content", SourcePack.looksLikeManifest(content))
            assertNull(SourcePack.parse(content))
        }

        // 各自的识别结果保持不变
        assertTrue(SourceValidator.validate(legadoArray).isLegadoList)
        assertEquals(SourceFormat.LEGADO, SourceValidator.validate(legadoSingle).format)
        assertTrue(SourceValidator.validate(moonTv).isMoonTvConfig)
        assertTrue(SourceValidator.validate(veneraIndex).isVeneraIndex)
    }

    // ==================== 地址解析 ====================

    @Test
    fun `相对路径按清单目录解析`() {
        val base = "https://raw.githubusercontent.com/leimao2333/leimao-sources-dist/main/all.json"
        assertEquals(
            "https://raw.githubusercontent.com/leimao2333/leimao-sources-dist/main/legado-all.json",
            SourcePack.resolveUrl(base, "legado-all.json"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/leimao2333/leimao-sources-dist/main/venera/index.json",
            SourcePack.resolveUrl(base, "venera/index.json"),
        )
    }

    @Test
    fun `清单带查询串或片段时仍解析到正确目录`() {
        val base = "https://example.com/sources/all.json?raw=1#top"
        assertEquals(
            "https://example.com/sources/a.json",
            SourcePack.resolveUrl(base, "a.json"),
        )
        assertEquals(
            "https://example.com/sources/js/b.js",
            SourcePack.resolveUrl(base, "./js/b.js"),
        )
    }

    @Test
    fun `绝对地址原样使用且无需清单地址`() {
        assertEquals(
            "https://cdn.example.com/a.json",
            SourcePack.resolveUrl(null, "https://cdn.example.com/a.json"),
        )
        assertEquals(
            "http://cdn.example.com/a.json",
            SourcePack.resolveUrl("https://other.com/all.json", "http://cdn.example.com/a.json"),
        )
    }

    @Test
    fun `上一级路径可折叠、无法解析时返回 null`() {
        assertEquals(
            "https://example.com/a.json",
            SourcePack.resolveUrl("https://example.com/dir/all.json", "../a.json"),
        )
        // 相对路径 + 没有清单地址 → 无法解析（文件方式导入的场景）
        assertNull(SourcePack.resolveUrl(null, "a.json"))
        assertNull(SourcePack.resolveUrl("", "a.json"))
        assertNull(SourcePack.resolveUrl("https://example.com/all.json", "  "))
    }

    // ==================== Venera 索引解析（抽到 VeneraIndex 后不回归） ====================

    @Test
    fun `Venera 索引解析与候选 id 推导`() {
        val index = """
            [{"name":"甲","fileName":"one.js","key":"one","version":"1.0"},
             {"name":"乙","fileName":"one_multi.js","key":"one","version":"1.1"}]
        """.trimIndent()
        val entries = VeneraIndex.parse(index)!!
        assertEquals(2, entries.size)
        // key 相同的两条必须落到不同 id（否则会少导入）
        assertEquals("venera_one", VeneraIndex.toSourceInfo(entries[0]).id)
        assertEquals("venera_one_multi", VeneraIndex.toSourceInfo(entries[1]).id)
        assertEquals("甲", entries[0].name)

        // 非索引内容返回 null（legado 数组首元素没有 fileName/key）
        assertNull(VeneraIndex.parse("""[{"bookSourceName":"甲"}]"""))
        assertNull(VeneraIndex.parse("""{"a":1}"""))
    }
}
