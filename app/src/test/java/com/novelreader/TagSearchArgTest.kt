package com.novelreader

import com.novelreader.core.util.TagSearchArg
import com.novelreader.domain.model.SourceType
import com.novelreader.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TagSearchArgTest {

    @Test
    fun `标签路由参数安全往返特殊字符`() {
        listOf(
            "奇幻",
            "全彩 无码",
            "50% 漫画",
            "%E5%85%A8%E5%BD%A9+tag",
            "a&b=c?x/y",
        ).forEach { tag ->
            val encoded = TagSearchArg.encode(tag)
            assertFalse(encoded.any { it in setOf('%', '+', '/', '=', '&', '?', ' ') })
            assertEquals(tag, TagSearchArg.decode(encoded))
        }
    }

    @Test
    fun `损坏的旧式百分号参数不导致解码闪退`() {
        assertEquals("%E0%A4%A", TagSearchArg.decode("%E0%A4%A"))
    }

    @Test
    fun `详情标签搜索路由参数不会被查询字符拆开`() {
        val tag = "作品 50% + 原著&特别"
        val route = Routes.tagSearch(SourceType.COMIC, tag)
        val argument = route.substringAfter("&tag=")

        assertEquals(tag, TagSearchArg.decode(argument))
    }
}
