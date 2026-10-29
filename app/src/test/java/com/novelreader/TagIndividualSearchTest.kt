package com.novelreader

import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.core.util.TagSuggestions
import com.novelreader.core.util.TagVerdicts
import com.novelreader.data.cache.TagPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分组内单标签搜索（v1.9.2.0）。
 *
 * 需求口径：
 * 1. **分组保留** —— 整组搜索写成「组首词 + 组」（「全彩组」），计数与覆盖不变；
 * 2. **每个标签都能单独搜** —— 普通写法（含组首词「全彩」、组内成员「彩绘」）
 *    只搜它自己，不再被并进整组；
 * 3. 标签池里没有的词可以自动新建（[com.novelreader.data.repository.TagRepository.ensureTags]）。
 *
 * 这里只测纯逻辑（词表口径 / 标签池入口 / 提示词 / 判定），
 * 自动新建与同步收录依赖仓库与网络，放在实现处靠注释与真机验证。
 */
class TagIndividualSearchTest {

    @Test
    fun `全彩是组首词 彩绘是组内成员`() {
        assertTrue(TagAlias.isGroupHead("全彩"))
        assertFalse(TagAlias.isGroupMember("全彩"))

        assertTrue(TagAlias.isGroupMember("彩绘"))
        assertFalse(TagAlias.isGroupHead("彩绘"))
        assertEquals("全彩", TagAlias.groupHeadOf("彩绘"))

        // 繁简 / 大小写同样按归一化口径判定
        assertTrue(TagAlias.isGroupMember("彩繪"))
        assertTrue(TagAlias.isGroupMember("Full Colored"))
        assertTrue(TagAlias.isGroupHead("全彩"))
    }

    @Test
    fun `不在任何组里的标签两种口径都只有自己`() {
        assertFalse(TagAlias.isGroupHead("本周推荐"))
        assertFalse(TagAlias.isGroupMember("本周推荐"))
        assertEquals(setOf("本周推荐"), TagAlias.searchAliasesOf("本周推荐"))
        assertEquals(listOf("本周推荐"), TagAlias.searchTermsOf("本周推荐"))
    }

    @Test
    fun `整组写法是组首词加组 普通写法只搜自己`() {
        // v1.9.2.0：整组搜索写成「首词 + 组」
        assertTrue(TagAlias.isGroupQuery("全彩组"))
        assertEquals("全彩", TagAlias.groupQueryHeadOf("全彩组"))
        // 用成员 + 组也认（免得「彩绘组」被当成新标签自动新建）
        assertEquals("全彩", TagAlias.groupQueryHeadOf("彩绘组"))
        assertFalse(TagAlias.isGroupQuery("全彩"))
        assertFalse(TagAlias.isGroupQuery("彩绘"))

        // 整组写法：整组全部写法（分组搜索、分组覆盖不变）
        val groupAliases = TagAlias.searchAliasesOf("全彩组")
        assertTrue(groupAliases.contains("彩绘"))
        assertTrue(groupAliases.contains("fullcolor"))
        assertTrue(groupAliases.size > 5)

        // 普通写法（含组首词本身）：只有它自己
        assertEquals(setOf("全彩"), TagAlias.searchAliasesOf("全彩"))
        assertEquals(setOf("彩绘"), TagAlias.searchAliasesOf("彩绘"))
        assertEquals(listOf("全彩"), TagAlias.searchTermsOf("全彩"))
        assertEquals(listOf("彩绘"), TagAlias.searchTermsOf("彩绘"))
        // 按源分配写法的口径一致
        assertEquals(listOf("彩绘"), TagAlias.searchTermsOfSource("彩绘", sourceOrdinal = 3))
        assertTrue(TagAlias.searchTermsOfSource("全彩组", sourceOrdinal = 3).size > 1)
    }

    @Test
    fun `整组写法落到组首词上 不产生新标签`() {
        // 池子 / 索引 / 打标里没有「全彩组」这个标签，一律按首词走
        assertEquals("全彩", TagAlias.canonicalOf("全彩组"))
        assertEquals("全彩", TagAlias.planTagOf("全彩组"))
        assertEquals("全彩", TagAlias.planTagOf("彩绘组"))
        assertEquals("彩绘", TagAlias.planTagOf("彩绘"))
        // 成人口径也要认（隐藏成人内容时整类过滤）
        assertTrue(TagAlias.isExplicitAdultIntent("无码组"))
    }

    @Test
    fun `展示名保留成员原词 不并回组首词`() {
        assertEquals("全彩", TagAlias.displayNameOf("全彩"))
        // 「彩绘」如果显示成「全彩」，用户会以为系统又把它并回整组了
        assertEquals("彩绘", TagAlias.displayNameOf("彩绘"))
        assertEquals("彩繪", TagAlias.displayNameOf("彩繪"))
    }

    @Test
    fun `组内成员列表包含首词 便于界面铺开`() {
        val members = TagAlias.membersOfGroup("全彩")
        assertTrue(members.first() == "全彩")
        assertTrue(members.contains("彩绘"))
        assertTrue(members.contains("上色"))
        assertTrue(TagAlias.membersOfGroup("本周推荐").isEmpty())
    }

    @Test
    fun `复合搜索按条件去重 整组与普通写法是两个条件`() {
        // 整组写法与它组里的任意写法是两个条件 → 都保留（求交）
        assertEquals(
            listOf("全彩组", "彩绘"),
            TagAlias.dedupeConditions(listOf("全彩组", "彩绘")),
        )
        assertEquals(
            listOf("全彩", "彩绘"),
            TagAlias.dedupeConditions(listOf("全彩", "彩绘")),
        )
        // 同一个整组条件的两种写法 → 只留一个
        assertEquals(
            listOf("全彩组"),
            TagAlias.dedupeConditions(listOf("全彩组", "彩绘组")),
        )
        // 两个不同的成员也各自独立
        assertEquals(
            listOf("彩绘", "上色"),
            TagAlias.dedupeConditions(listOf("彩绘", "上色")),
        )
    }

    @Test
    fun `标签池保留组内成员 且成员只取自己的入口`() {
        val plan = TagPlan(
            plan = linkedMapOf(
                "全彩" to listOf(
                    "s1" to "https://a/full-color",
                    "s2" to "https://b/quancai",
                ),
                "彩绘" to listOf("s1" to "https://a/caihui"),
            ),
        )

        // 组首词：整组入口（含成员那一份，分组覆盖不变）
        assertEquals(3, plan.entriesFor("全彩").size)
        // 成员：只有自己的入口，不借用整组的分类页
        assertEquals(listOf("s1" to "https://a/caihui"), plan.entriesFor("彩绘", exact = true))
        // 标签列表里两者都在（成员没有被并回组首词）
        assertEquals(listOf("全彩", "彩绘"), plan.tags)
    }

    @Test
    fun `单标签入口生成关键词搜索地址`() {
        val plan = TagPlan(
            plan = linkedMapOf(
                "彩绘" to listOf("s1" to TagEntryUrl.search("彩绘")),
            ),
        )
        val entries = plan.searchEntriesFor("彩绘", "彩绘", exact = true)
        assertEquals(listOf("s1" to "search::彩绘"), entries)
    }

    @Test
    fun `自动新建的标签随标签池落盘 重新收集也不丢`() {
        val plan = TagPlan(
            plan = linkedMapOf("彩绘" to listOf("s1" to TagEntryUrl.search("彩绘"))),
            customTags = setOf("彩绘"),
        )
        val loaded = TagPlan.fromJson(plan.toJson())
        assertEquals(setOf("彩绘"), loaded?.customTags)
        assertEquals(listOf("彩绘"), loaded?.tags)
    }

    @Test
    fun `旧缓存没有 customTags 字段也能读`() {
        val raw = """{"collectedAtMs":100,"plan":{"玄幻":[["s1","u1"]]}}"""
        val plan = TagPlan.fromJson(raw)
        assertEquals(listOf("玄幻"), plan?.tags)
        assertTrue(plan?.customTags?.isEmpty() == true)
    }

    @Test
    fun `已选整组时提示词仍给组内成员`() {
        val pool = listOf("全彩", "彩绘", "彩色", "全彩本", "全彩组")
        // 输入「全彩组 彩」：正在输入第二段
        val hits = TagSuggestions.of(pool, "全彩组 彩")
        assertTrue("组内成员必须能被提示到，否则用户不知道可以单搜", hits.contains("彩绘"))
        // 已经写进查询的整组写法不再重复提示
        assertFalse(hits.contains("全彩组"))
    }

    @Test
    fun `打完首词仍提示整组写法`() {
        val pool = listOf("全彩", "彩绘", "全彩组")
        // 「全彩」已成立，但「全彩组」是同一组的另一个入口，必须还能提示出来
        val hits = TagSuggestions.of(pool, "全彩")
        assertTrue(hits.contains("全彩组"))
        assertFalse(hits.contains("全彩"))
    }

    @Test
    fun `判定层对普通写法只认它自己 整组写法认整组`() {
        // 搜「彩绘」时，标题写着「全彩」的作品不能算命中（否则精确性又被抹平）
        assertFalse(
            TagVerdicts.contextOf("彩绘")
                .matchesNormalized(TagVerdicts.normalizeTitle("全彩 百合 短篇")),
        )
        assertTrue(
            TagVerdicts.contextOf("彩绘")
                .matchesNormalized(TagVerdicts.normalizeTitle("彩绘 百合 短篇")),
        )
        // 组首词本身也是单标签搜索：标题只写「彩绘」不算命中「全彩」
        assertFalse(
            TagVerdicts.contextOf("全彩")
                .matchesNormalized(TagVerdicts.normalizeTitle("彩绘 百合 短篇")),
        )
        // 整组写法仍然认整组写法
        assertTrue(
            TagVerdicts.contextOf("全彩组")
                .matchesNormalized(TagVerdicts.normalizeTitle("彩绘 百合 短篇")),
        )
    }

    @Test
    fun `索引名口径 整组写法落首词 成员保留原词`() {
        // 整组写法不是真实标签 → 落回首词
        assertEquals("全彩", TagAlias.indexNameOf("全彩组"))
        // 成员必须保留原词，否则它自己的收录数永远是 0（chip 上没有数字）
        assertEquals("彩绘", TagAlias.indexNameOf("彩绘"))
        assertEquals("カラー版", TagAlias.indexNameOf("カラー版"))
        // 首词本身也是原词
        assertEquals("全彩", TagAlias.indexNameOf("全彩"))
    }

    @Test
    fun `搜索上下文的标签按原词落库`() {
        // 搜「カラー版」时，索引里要记 カラー版，而不是并成 全彩
        val context = com.novelreader.core.util.TagTagger.searchContext(listOf("カラー版"))
        assertEquals(listOf("カラー版"), context.map { it.first })
        // 整组写法仍然落回首词
        assertEquals(
            listOf("全彩"),
            com.novelreader.core.util.TagTagger.searchContext(listOf("全彩组")).map { it.first },
        )
    }

    @Test
    fun `打标时成员上下文不会被并回组首词`() {
        val item = com.novelreader.domain.model.MediaItem(
            id = "i1",
            title = "某作品",
            type = com.novelreader.domain.model.SourceType.COMIC,
            sourceId = "s1",
        )
        val tags = com.novelreader.core.util.TagTagger.tag(
            item,
            context = listOf(
                com.novelreader.core.util.TagTagger.ContextTag("彩绘", 90),
            ),
        )
        assertTrue("成员写法要独立成一条标签行", tags.any { it.name == "彩绘" })
        assertFalse("不能再被并成组首词", tags.any { it.name == "全彩" })
    }

    @Test
    fun `索引侧把组内成员补成独立标签行 证据强度沿用规范名`() {
        val tagged = listOf(
            com.novelreader.core.util.TagTagger.Tag(
                name = "全彩",
                category = "题材",
                confidence = 100,
                origin = "站方",
            ),
            com.novelreader.core.util.TagTagger.Tag(
                name = "百合",
                category = "题材",
                confidence = 100,
                origin = "站方",
            ),
        )
        // 站方原始标签里写着「彩绘」「彩色」——都属于「全彩」组
        val members = com.novelreader.core.util.TagTagger.memberTags(
            tagged = tagged,
            rawNames = listOf("彩绘", "彩色", "百合", "连载中"),
        )

        assertEquals(listOf("彩绘", "彩色"), members.map { it.name })
        // 大类 / 置信度 / 来源与「全彩」那一条完全一致，只是多了一个可精确检索的键
        members.forEach { tag ->
            assertEquals("题材", tag.category)
            assertEquals(100, tag.confidence)
            assertEquals("站方", tag.origin)
        }
    }

    @Test
    fun `没有对应规范名的原始写法不补行`() {
        val tagged = listOf(
            com.novelreader.core.util.TagTagger.Tag("百合", "题材", 100, "站方"),
        )
        // 「黑白」是另一组的组首词（不是成员）；「彩绘」是成员，但它对应的规范名
        // 「全彩」这一条不在 tagged 里（模拟漏打的极端情况）→ 两者都不补
        val members = com.novelreader.core.util.TagTagger.memberTags(
            tagged = tagged,
            rawNames = listOf("黑白", "彩绘"),
        )
        assertTrue(members.isEmpty())
    }
}
