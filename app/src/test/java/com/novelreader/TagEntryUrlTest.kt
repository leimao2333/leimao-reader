package com.novelreader

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.novelreader.core.util.TagEntryUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签入口伪 URL 编解码测试（v1.7.0.0）。
 *
 * 分类入口要跨「落盘缓存 → 查询 → 源调用」三跳，参数里既可能是字符串
 * （包子漫画 `lianai`）也可能是数字（禁漫天堂 `0`），必须无损往返 ——
 * 数字退化成 `1.0` 会让源的分类接口直接查不到东西。
 */
class TagEntryUrlTest {

    @Test
    fun `搜索入口编解码`() {
        val url = TagEntryUrl.search("恋爱")
        assertEquals("search::恋爱", url)
        assertEquals("恋爱", TagEntryUrl.searchKeywordOf(url))
        assertNull(TagEntryUrl.searchKeywordOf("/category/cn"))
        assertNull(TagEntryUrl.searchKeywordOf("vcat::{\"c\":\"恋爱\"}"))
    }

    @Test
    fun `分类入口无损往返 - 字符串参数`() {
        val url = TagEntryUrl.category(
            name = "恋爱",
            param = "lianai",
            options = listOf(JsonPrimitive("all"), JsonPrimitive("all")),
        )
        val entry = TagEntryUrl.categoryOf(url)
        assertEquals("恋爱", entry?.name)
        assertEquals("lianai", entry?.param?.asString)
        assertEquals(listOf("all", "all"), entry?.options?.map { it?.asString })
        assertEquals("category", entry?.kind)
        assertNull(entry?.keyword)
    }

    @Test
    fun `分类入口无损往返 - 数字参数不变成小数`() {
        val url = TagEntryUrl.category(name = "最新A漫", param = 0, options = listOf(1, 2))
        val entry = TagEntryUrl.categoryOf(url)
        // 关键：0 不能变成 0.0 / "0.0"
        assertEquals("0", entry?.param?.asString)
        assertEquals(listOf("1", "2"), entry?.options?.map { it?.asString })
    }

    @Test
    fun `分类入口无损往返 - 对象参数`() {
        val param = JsonObject().apply { addProperty("id", 7) }
        val entry = TagEntryUrl.categoryOf(TagEntryUrl.category(name = "某分类", param = param))
        assertEquals(7, entry?.param?.asJsonObject?.get("id")?.asInt)
    }

    @Test
    fun `无参数与空过滤器也能往返`() {
        val entry = TagEntryUrl.categoryOf(TagEntryUrl.category(name = "最新", options = listOf(null)))
        assertEquals("最新", entry?.name)
        assertNull(entry?.param)
        assertEquals(1, entry?.options?.size)
        assertNull(entry?.options?.first())
    }

    @Test
    fun `search 类型的分类入口保留关键词与类型`() {
        val url = TagEntryUrl.category(
            name = "全彩",
            keyword = "全彩",
            kind = "search",
        )
        val entry = TagEntryUrl.categoryOf(url)
        assertEquals("search", entry?.kind)
        assertEquals("全彩", entry?.keyword)
    }

    @Test
    fun `不是分类入口或负载损坏一律返回 null`() {
        assertNull(TagEntryUrl.categoryOf("/category/cn"))
        assertNull(TagEntryUrl.categoryOf("search::恋爱"))
        assertNull(TagEntryUrl.categoryOf("vcat::这不是 json"))
        // 缺分类名 → 无效
        assertNull(TagEntryUrl.categoryOf("""vcat::{"p":"x"}"""))
        // 空对象也不能让调用方拿到「有入口」的假象
        assertNull(TagEntryUrl.categoryOf("vcat::{}"))
    }

    @Test
    fun `分类名里的引号不会破坏负载`() {
        val url = TagEntryUrl.category(name = """奇怪"名字\分类""", param = "p")
        assertTrue(url.startsWith(TagEntryUrl.CATEGORY_PREFIX))
        assertEquals("""奇怪"名字\分类""", TagEntryUrl.categoryOf(url)?.name)
    }
}
