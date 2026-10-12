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
 * 1. **数量**：`index.json` 里存在 key 重复的条目（`copy_manga` 与
 *    `copy_manga_multi_accounts`），id 若取 key 会互相覆盖 → 33 条只进 32 条。
 * 2. **识别**：Venera 源也是 `.js`，必须优先于自研 JS 源被识别，否则会被
 *    `validateJs` 判为「缺少必需函数」而导入失败。
 */
class VeneraImportTest {

    // ==================== id 约定（决定导入数量是否正确） ====================

    @Test
    fun `同 key 不同文件名必须得到不同 id`() {
        // venera-configs/index.json 的真实形态：两条 key 都是 copy_manga
        val a = VeneraSourceId.fromFileName("copy_manga.js")
        val b = VeneraSourceId.fromFileName("copy_manga_multi_accounts.js")
        assertEquals("venera_copy_manga", a)
        assertEquals("venera_copy_manga_multi_accounts", b)
        assertNotEquals("key 重复的条目不能落到同一个 id，否则会少导入", a, b)
    }

    @Test
    fun `id 由文件名推导且恢复时幂等`() {
        // 导入
        assertEquals("venera_baozi", VeneraSourceId.fromFileName("baozi.js"))
        // 启动恢复时构造的是 "${id}.${format}"
        assertEquals("venera_baozi", VeneraSourceId.fromFileName("venera_baozi.venera"))
        // 不会二次加前缀
        assertEquals("venera_baozi", VeneraSourceId.fromFileName("venera_baozi"))
    }

    @Test
    fun `id 规则带目录与反斜杠也正确`() {
        assertEquals("venera_goda", VeneraSourceId.fromFileName("https://x/y/goda.js"))
        assertEquals("venera_goda", VeneraSourceId.fromFileName("dir\\goda.js"))
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
            "copy_manga.js", "copy_manga_multi_accounts.js", "komiic.js", "baozi.js", "picacg.js",
            "nhentai.js", "wnacg.js", "ehentai.js", "jm.js", "manga_dex.js", "ikmmh.js",
            "shonen_jump_plus.js", "hitomi.js", "comick.js", "ykmh.js", "zaimanhua.js",
            "manhuagui.js", "manwaba.js", "lanraragi.js", "komga.js", "comic_walker.js",
            "mh1234.js", "ccc.js", "goda.js", "mh18.js", "mxs.js", "manhuaren.js", "hcomic.js",
            "jcomic.js", "hot_manga.js", "kavita.js", "happy.js", "mycomic.js",
        )
        val ids = files.map { VeneraSourceId.fromFileName(it)!! }
        assertEquals(33, ids.size)
        assertEquals("33 条清单必须得到 33 个不同 id（此前 key 冲突只进 32 条）", 33, ids.toSet().size)
    }

    // ==================== 格式识别 ====================

    private val veneraSource = """
        class Baozi extends ComicSource {
            name = "包子漫画"
            key = "baozi"
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
          {"name":"拷贝漫画","fileName":"copy_manga.js","key":"copy_manga","version":"1.4.1"},
          {"name":"拷贝漫画(多账号)","fileName":"copy_manga_multi_accounts.js","key":"copy_manga","version":"1.4.1"}
        ]
    """.trimIndent()

    @Test
    fun `Venera 源被识别为 VENERA 而不是自研 JS`() {
        // 自研 JS 源要求 getMeta/search/chapterList/content，Venera 源没有这些，若误判会导入失败
        val r = SourceValidator.validate(veneraSource, "baozi.js")
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
        val r = SourceValidator.validate(veneraSource, "venera_baozi.venera")
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
