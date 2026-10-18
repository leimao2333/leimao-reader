package com.novelreader

import com.novelreader.core.util.ComicGenres
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.data.cache.TagPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 漫画题材词表 + 标签伪 URL 协议 + 噪声标签过滤（v1.6.6.0）。
 *
 * 背景：漫画源普遍没有发现分类能力（25 个 Venera 源协议层就没有分类浏览，
 * legado 漫画源也大多没配 `exploreUrl`），标签池只有几十个。这里给漫画源
 * 绑「按题材搜索」入口来补足，下面覆盖该链路的纯逻辑部分。
 */
class ComicGenresTest {

    // ===== TagEntryUrl =====

    @Test
    fun `search 入口可往返解析`() {
        val url = TagEntryUrl.search("热血")
        assertEquals("search::热血", url)
        assertEquals("热血", TagEntryUrl.searchKeywordOf(url))
    }

    @Test
    fun `search 构造时 trim 关键词`() {
        assertEquals("search::恋爱", TagEntryUrl.search("  恋爱  "))
    }

    @Test
    fun `普通发现分类地址不被当成搜索入口`() {
        assertNull(TagEntryUrl.searchKeywordOf("/category/xuanhuan/1.html"))
        assertNull(TagEntryUrl.searchKeywordOf("全部小说::/web/all/1"))
        assertNull(TagEntryUrl.searchKeywordOf(""))
    }

    @Test
    fun `search 前缀但关键词为空视为非搜索入口`() {
        assertNull(TagEntryUrl.searchKeywordOf("search::"))
        assertNull(TagEntryUrl.searchKeywordOf("search::   "))
    }

    // ===== ComicGenres =====

    @Test
    fun `题材词表无重复且数量符合预期`() {
        // v1.7.0.0：32 → 66（补上主流漫画站同样高频的题材 / 受众词）
        // v1.7.6.0：66 → 182（按受众 / 都市情感 / 玄幻修真 / 科幻末世 /
        //   武侠古风 / 悬疑猎奇 拆出细分标签，让标签页能按细粒度找书）
        // v1.7.6.3：182 → 394（补精灵 / 兽人 / 魔王 / 勇者 / 魔法少女 等奇幻、
        //   异世界、角色属性、娱乐圈、无限流、刑侦、生活经营等词）
        // v1.7.10.0：394 → 479 个去重词（补充关系、场景和细分类型）
        assertEquals(479, ComicGenres.ALL.size)
        assertEquals(ComicGenres.ALL.size, ComicGenres.ALL.distinct().size)
        assertTrue(ComicGenres.ALL.all { it.isNotBlank() })
    }

    @Test
    fun `新增题材词包含精灵等奇幻与角色词`() {
        listOf(
            "精灵", "兽人", "魔族", "魔王", "勇者", "骑士", "魔法少女",
            "恶役千金", "吸血鬼", "狼人", "异世界", "转生", "克苏鲁",
            "萝莉", "御姐", "女仆", "巫女", "千金", "娱乐圈", "无限流",
        ).forEach { assertTrue("题材词应包含「$it」", it in ComicGenres.ALL) }
    }

    @Test
    fun `题材词不会被噪声过滤误杀`() {
        // 题材词必须都能进标签池，否则绑了也白绑
        assertTrue(ComicGenres.ALL.none { TagPlan.isNoiseTag(it) })
    }

    @Test
    fun `每题材绑定的源数上限为正`() {
        assertTrue(ComicGenres.MAX_SOURCES_PER_GENRE in 1..16)
    }

    // ===== TagPlan.isNoiseTag =====

    @Test
    fun `入口词与状态词被判定为噪声`() {
        val noise = listOf(
            "全部", "全部漫画", "最近更新", "最新上架", "排行榜", "排行",
            "热门", "推荐", "精选", "分类", "首页", "连载", "连载中",
            "已完结", "完结", "完结漫画",
        )
        noise.forEach { assertTrue("应判为噪声：$it", TagPlan.isNoiseTag(it)) }
    }

    @Test
    fun `噪声判定先做清洗与大小写归一`() {
        assertTrue(TagPlan.isNoiseTag("  全部  "))
        assertTrue(TagPlan.isNoiseTag("全部\u3000漫画"))
        assertTrue(TagPlan.isNoiseTag("排行榜  "))
    }

    @Test
    fun `真题材词不被误杀`() {
        val keep = listOf("热血", "都市", "玄幻", "科幻", "电影", "动作片", "恋爱漫画", "热血连载漫画")
        keep.forEach { assertFalse("不该判为噪声：$it", TagPlan.isNoiseTag(it)) }
    }

    @Test
    fun `空串不是噪声 - 由调用方按空值跳过`() {
        assertFalse(TagPlan.isNoiseTag(""))
        assertFalse(TagPlan.isNoiseTag("   "))
    }

    // ===== 与 fromJson 的联动：旧缓存里的噪声标签会被丢掉 =====

    @Test
    fun `fromJson 丢弃旧缓存里的噪声标签`() {
        val raw = """
            {"collectedAtMs":100,"plan":{
                "热血":[["s1","search::热血"]],
                "全部":[["s1","/all"]],
                "连载中":[["s1","/serial"]],
                "排行榜":[["s1","/rank"]]
            }}
        """.trimIndent()
        val plan = TagPlan.fromJson(raw) ?: error("should parse")
        assertEquals(setOf("热血"), plan.plan.keys)
    }

    @Test
    fun `fromJson 保留 search 伪入口地址`() {
        val raw = """{"collectedAtMs":100,"plan":{"热血":[["s1","search::热血"]]}}"""
        val plan = TagPlan.fromJson(raw) ?: error("should parse")
        assertEquals(listOf("s1" to "search::热血"), plan.entriesFor("热血"))
    }
}
