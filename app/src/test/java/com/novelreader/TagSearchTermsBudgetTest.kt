package com.novelreader

import com.novelreader.core.util.TagAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签搜索的**请求量预算**（v1.8.1.1；v1.8.7.1 改挑选口径）。
 *
 * 背景：搜「无码 全彩」时一部内容要等好几分钟且多半空手而归。原因是搜索入口按
 * 「源 × 同义写法」逐条展开 ——「无码」组 27 个写法、「全彩」组 42 个，二十个源就是
 * 一千多个请求，再乘复合搜索的翻页。
 *
 * 这里锁死上限：单源单次最多展开 [TagAlias.MAX_SEARCH_TERMS_PER_SOURCE] 个写法，
 * 且用户输入的那个永远排第一（最贴近他要搜的词）。
 *
 * v1.8.7.1 补的是**质量**：原来按组内声明顺序取前两个，永远拿到简繁一对
 * （`无码` + `無碼`），日文 / 英文写法永远轮不到，用户看到的就是
 * 「说好的同义词组合搜索，实际只搜了两个长得几乎一样的词」。
 */
class TagSearchTermsBudgetTest {

    @Test
    fun `无码组发请求的写法数受上限约束`() {
        val terms = TagAlias.searchTermsForQuery("无码")
        assertEquals(TagAlias.MAX_SEARCH_TERMS_PER_SOURCE, terms.size)
        assertEquals("无码", terms.first())
    }

    @Test
    fun `无码组覆盖简繁主写法与一个外文写法`() {
        val terms = TagAlias.searchTermsForQuery("无码")
        // 中文：用户输入本身 + 简繁互补的另一个主写法
        assertEquals("无码", terms.first())
        assertTrue("应保留繁体主写法，实际 $terms", "無碼" in terms)
        // 外文：优先拉丁（英文站最多），其次日文
        assertTrue(
            "应展开一个外文写法，实际 $terms",
            terms.any { t -> t.all { it.code < 128 } && t.length >= 3 } ||
                terms.any { t -> t.any { it in '\u3040'..'\u30FF' } },
        )
    }

    @Test
    fun `输入繁体时简体主写法不会丢`() {
        // 用户输「無修正」，第二个位置必须给「无码」—— 否则另一半中文源整个不参与
        val terms = TagAlias.searchTermsForQuery("無修正")
        assertEquals("無修正", terms.first())
        assertTrue("应保留简体主写法，实际 $terms", "无码" in terms)
    }

    @Test
    fun `全彩组发请求的写法数受上限约束`() {
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
