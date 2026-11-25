package com.novelreader

import com.novelreader.source.moontv.MacCmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 采集源封面补全与接口结构兜底（v1.9.6.3）。
 *
 * 线上反馈「某个源整排没有图」的根因：`toItem` 只认 `vod_pic` 且要求 `http` 开头，
 * 相对路径 / 协议相对地址 / 其它字段名一律被丢掉。这里把四种情况都钉住。
 */
class MacCmsParserCoverTest {

    private val api = "https://cdn.example.com/api.php/provide/vod"

    private fun item(json: String, base: String? = api) =
        MacCmsParser.parseList(json, "sid", "测试源", base).firstOrNull()

    @Test
    fun `root relative cover is completed with site root`() {
        val it = item("""{"list":[{"vod_id":"1","vod_name":"片子","vod_pic":"/upload/1.jpg"}]}""")
        assertNotNull(it)
        assertEquals("https://cdn.example.com/upload/1.jpg", it!!.cover)
    }

    @Test
    fun `protocol relative cover borrows base scheme`() {
        val it = item("""{"list":[{"vod_id":"1","vod_name":"片子","vod_pic":"//img.example.com/x.jpg"}]}""")
        assertEquals("https://img.example.com/x.jpg", it!!.cover)
    }

    @Test
    fun `cover falls back to alternative field when vod_pic missing`() {
        val it = item(
            """{"list":[{"vod_id":"1","vod_name":"片子","vod_pic_slide":"https://img.example.com/s.jpg"}]}""",
        )
        assertEquals("https://img.example.com/s.jpg", it!!.cover)
    }

    @Test
    fun `empty cover stays null`() {
        val it = item("""{"list":[{"vod_id":"1","vod_name":"片子","vod_pic":""}]}""")
        assertNull(it!!.cover)
    }

    @Test
    fun `js undefined cover is filtered out`() {
        val it = item(
            """{"list":[{"vod_id":"1","vod_name":"片子",
               "vod_pic":"org.mozilla.javascript.Undefined@dc3369d"}]}""",
        )
        assertNull(it!!.cover)
    }

    @Test
    fun `list wrapped in data object is parsed`() {
        val it = item("""{"code":1,"data":{"list":[{"vod_id":"9","vod_name":"套壳源"}]}}""")
        assertNotNull(it)
        assertEquals("9", it!!.id)
    }

    @Test
    fun `categories are inferred from list when class is absent`() {
        val json = """{"list":[
            {"vod_id":"1","vod_name":"甲","type_id":"5","type_name":"动作片"},
            {"vod_id":"2","vod_name":"乙","type_id":"5","type_name":"动作片"},
            {"vod_id":"3","vod_name":"丙","type_id":"7","type_name":"喜剧片"}
        ]}"""
        val cats = MacCmsParser.parseCategoryTree(json)
        assertEquals(listOf("动作片", "喜剧片"), cats.map { it.title })
        assertEquals(listOf("5", "7"), cats.map { it.url })
    }
}
