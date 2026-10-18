package com.novelreader

import com.novelreader.core.util.TagAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签搜索的**请求量预算**（v1.8.1.1）。
 *
 * 背景：搜「无码 全彩」时一部内容要等好几分钟且多半空手而归。原因是搜索入口按
 * 「源 × 同义写法」逐条展开 ——「无码」组 27 个写法、「全彩」组 42 个，二十个源就是
 * 一千多个请求，再乘复合搜索的翻页。
 *
 * 这里锁死上限：单源单次最多展开 [TagAlias.MAX_SEARCH_TERMS_PER_SOURCE] 个写法，
 * 且用户输入的那个永远排第一（最贴近他要搜的词）。
 */
class TagSearchTermsBudgetTest {

    @Test
    fun `无码组发请求只展开两个写法`() {
        val terms = TagAlias.searchTermsForQuery("无码")
        assertEquals(TagAlias.MAX_SEARCH_TERMS_PER_SOURCE, terms.size)
        assertEquals("无码", terms.first())
    }

    @Test
    fun `全彩组发请求只展开两个写法`() {
        val terms = TagAlias.searchTermsForQuery("全彩")
        assertEquals(TagAlias.MAX_SEARCH_TERMS_PER_SOURCE, terms.size)
        assertEquals("全彩", terms.first())
    }

    @Test
    fun `展示用的完整写法列表不受限制`() {
        // 组展示要「全部内容」，所以展示口径必须仍是整组
        val all = TagAlias.searchTermsFor("全彩")
        assertTrue("全彩组应有几十个写法，实际 ${all.size}", all.size > 20)
        assertTrue(TagAlias.searchTermsFor("无码").size > 20)
    }

    @Test
    fun `用户输入别名时排在第一`() {
        val terms = TagAlias.searchTermsForQuery("無修正")
        assertEquals("無修正", terms.first())
        assertEquals(TagAlias.MAX_SEARCH_TERMS_PER_SOURCE, terms.size)
    }

    @Test
    fun `不在任何同义组的标签原样返回`() {
        assertEquals(listOf("本周推荐"), TagAlias.searchTermsForQuery("本周推荐"))
    }

    @Test
    fun `limit 可调且为 1 时只留用户输入`() {
        assertEquals(listOf("无码"), TagAlias.searchTermsForQuery("无码", limit = 1))
    }

    @Test
    fun `两个大集展开后的请求数远小于整组展开`() {
        val budget = TagAlias.searchTermsForQuery("无码").size +
            TagAlias.searchTermsForQuery("全彩").size
        val full = TagAlias.searchTermsFor("无码").size + TagAlias.searchTermsFor("全彩").size
        assertTrue("收缩后应至少少一个数量级：$budget vs $full", budget * 10 <= full)
    }
}
