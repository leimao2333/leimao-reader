package com.novelreader

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.novelreader.source.venera.VeneraTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Venera 源 `tags` 归一化测试（v1.7.0.0）。
 *
 * 背景：`tags` 在**列表响应**里是数组，在**详情响应**里几乎都是对象
 * （`ComicDetails.tags = {分组名: [标签…]}`）。旧实现按数组解析，
 * Gson 遇到 JsonObject 会抛 `IllegalStateException: Not a JSON Array`，
 * 把整条搜索 / 详情链路带崩 —— 漫画源的标签栏与详情一起消失。
 */
class VeneraTagsTest {

    @Test
    fun `数组形态 - 保序去重`() {
        val arr = JsonArray().apply {
            add("玄幻"); add("热血"); add("玄幻"); add("  ")
        }
        assertEquals(listOf("玄幻", "热血"), VeneraTags.parse(arr))
    }

    @Test
    fun `对象形态 - 摊平各分组并丢掉作者分组`() {
        // 与 kavita.js / baozi.js 的 ComicDetails 同形
        val obj = JsonParser.parseString(
            """{"作者":["张三"],"类型":["热血","格斗"],"标签":["本地","验收"]}"""
        )
        assertEquals(
            listOf("热血", "格斗", "本地", "验收"),
            VeneraTags.parse(obj),
        )
    }

    @Test
    fun `对象形态 - 分组值是单个字符串也吃得下`() {
        val obj = JsonParser.parseString("""{"标签":"都市","提示":"请登录"}""")
        assertEquals(listOf("都市"), VeneraTags.parse(obj))
    }

    @Test
    fun `对象形态 - 标签元素是对象时取 name`() {
        val obj = JsonParser.parseString("""{"manga_dex":[{"name":"Action"},{"title":"Comedy"}]}""")
        assertEquals(listOf("Action", "Comedy"), VeneraTags.parse(obj))
    }

    @Test
    fun `空值与异常形态 - 一律空列表不抛异常`() {
        assertTrue(VeneraTags.parse(null).isEmpty())
        assertTrue(VeneraTags.parse(JsonNull.INSTANCE).isEmpty())
        assertTrue(VeneraTags.parse(JsonObject()).isEmpty())
        assertTrue(VeneraTags.parse(JsonPrimitive(1)).isEmpty())
    }

    @Test
    fun `对象里全是元信息分组 - 返回空而不是把作者当标签`() {
        val obj = JsonParser.parseString("""{"作者":["张三"],"authors":["Li Si"]}""")
        assertTrue(VeneraTags.parse(obj).isEmpty())
    }
}
