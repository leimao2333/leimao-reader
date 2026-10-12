package com.novelreader

import com.novelreader.domain.model.SourceFormat
import com.novelreader.source.loader.SourceValidator
import com.novelreader.source.venera.VeneraSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Venera 源导入链路回归测试（纯离线，不依赖 WebView）。
 *
 * 覆盖两个在真机/离线验证台上实测暴露过的问题：
 * 1. **数量**：`index.json` 里存在共用同一个 `key` 的条目（同一源的普通版与
 *    多账号版），id 若取 key 会互相覆盖 → 33 条只进 32 条。
 * 2. **识别**：Venera 源也是 `.js`，必须优先于自研 JS 源被识别，否则会被
 *    `validateJs` 判为「缺少必需函数」而导入失败。
 */
class VeneraImportTest {

    // ==================== id 约定（决定导入数量是否正确） ====================

    @Test
    fun `同 key 不同文件名必须得到不同 id`() {
        // 源集合 index.json 的真实形态：两条共用同一个 key
        val a = VeneraSourceId.fromFileName("alpha.js")
        val b = VeneraSourceId.fromFileName("alpha_multi_accounts.js")
        assertEquals("venera_alpha", a)
        assertEquals("venera_alpha_multi_accounts", b)
        assertNotEquals("key 重复的条目不能落到同一个 id，否则会少导入", a, b)
    }

    @Test
    fun `id 由文件名推导且恢复时幂等`() {
        // 导入
        assertEquals("venera_beta", VeneraSourceId.fromFileName("beta.js"))
        // 启动恢复时构造的是 "${id}.${format}"
        assertEquals("venera_beta", VeneraSourceId.fromFileName("venera_beta.venera"))
        // 不会二次加前缀
        assertEquals("venera_beta", VeneraSourceId.fromFileName("venera_beta"))
    }

    @Test
    fun `id 规则带目录与反斜杠也正确`() {
        assertEquals("venera_gamma", VeneraSourceId.fromFileName("https://x/y/gamma.js"))
        assertEquals("venera_gamma", VeneraSourceId.fromFileName("dir\\gamma.js"))
    }

    @Test
    fun `空文件名返回 null`() {
        assertNull(VeneraSourceId.fromFileName(null))
        assertNull(VeneraSourceId.fromFileName(""))
        assertNull(VeneraSourceId.fromFileName("   "))
    }

    /** 用真实 index.json 的 33 条清单跑一遍，确认 33 条能拿到 33 个互不相同的 id */
    @Test
    fun `真实 index_json 的33条文件名互不冲突`() {
        val files = listOf(
            "src01.js", "src01_multi_accounts.js", "src02.js", "src03.js", "src04.js",
            "src05.js", "src06.js", "src07.js", "src08.js", "src09.js",
            "src10.js", "src11.js", "src12.js", "src13.js", "src14.js",
            "src15.js", "src16.js", "src17.js", "src18.js", "src19.js",
            "src20.js", "src21.js", "src22.js", "src23.js", "src24.js",
            "src25.js", "src26.js", "src27.js", "src28.js", "src29.js",
            "src30.js", "src31.js", "src32.js",
        )
        val ids = files.map { VeneraSourceId.fromFileName(it)!! }
        assertEquals(33, ids.size)
        assertEquals("33 条清单必须得到 33 个不同 id（此前 key 冲突只进 32 条）", 33, ids.toSet().size)
    }

    // ==================== 格式识别 ====================

    private val veneraSource = """
        class Demo extends ComicSource {
            name = "示例源"
            key = "demo"
            version = "1.1.6"
            comic = {
                loadInfo: async (id) => { return new ComicDetails({ title: id }) },
                loadEp: async (comicId, epId) => { return { images: [] } }
            }
            search = { load: async (keyword, options, page) => { return { comics: [] } } }
        }
    """.trimIndent()

    private val veneraIndex = """
        [
          {"name":"示例源","fileName":"alpha.js","key":"alpha","version":"1.4.1"},
          {"name":"示例源(多账号)","fileName":"alpha_multi_accounts.js","key":"alpha","version":"1.4.1"}
        ]
    """.trimIndent()

    @Test
    fun `Venera 源被识别为 VENERA 而不是自研 JS`() {
        // 自研 JS 源要求 getMeta/search/chapterList/content，Venera 源没有这些，若误判会导入失败
        val r = SourceValidator.validate(veneraSource, "demo.js")
        assertTrue(r.message, r.valid)
        assertEquals(SourceFormat.VENERA, r.format)
        assertFalse(r.isVeneraIndex)
    }

    @Test
    fun `Venera 源索引被识别并标记 isVeneraIndex`() {
        val r = SourceValidator.validate(veneraIndex, "index.json")
        assertTrue(r.message, r.valid)
        assertEquals(SourceFormat.VENERA, r.format)
        assertTrue(r.isVeneraIndex)
        assertTrue("应报出条目数", r.message.contains("2"))
    }

    @Test
    fun `dot venera 扩展名也算 Venera 源`() {
        val r = SourceValidator.validate(veneraSource, "venera_demo.venera")
        assertEquals(SourceFormat.VENERA, r.format)
    }

    @Test
    fun `缺少 loadInfo 的 Venera 源被拒绝`() {
        val broken = "class X extends ComicSource { name = \"x\" }"
        val r = SourceValidator.validate(broken, "x.js")
        assertFalse(r.valid)
    }

    @Test
    fun `自研 JS 源不会被误判成 Venera`() {
        val js = """
            function getMeta() { return { name: "自研源" } }
            function search(keyword, page) { return [] }
            function chapterList(url) { return [] }
            function content(url) { return [] }
        """.trimIndent()
        val r = SourceValidator.validate(js, "custom.js")
        assertEquals(SourceFormat.JS, r.format)
        assertTrue(r.message, r.valid)
    }
}
