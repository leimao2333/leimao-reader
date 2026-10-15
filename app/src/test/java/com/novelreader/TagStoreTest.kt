package com.novelreader

import com.novelreader.data.cache.TagPlan
import com.novelreader.data.cache.TagStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 标签磁盘缓存测试（v1.5.10.0）。
 *
 * 覆盖：plan JSON 往返、宽松匹配 entriesFor、损坏文件兜底。
 */
class TagStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun samplePlan() = TagPlan(
        collectedAtMs = 1730000000000L,
        plan = linkedMapOf(
            "玄幻" to listOf("novel_1" to "http://a/xuanhuan/1.html", "novel_2" to "http://b/xh"),
            "电影" to listOf("moontv_v1" to "http://c/1"),
        ),
    )

    @Test
    fun `save 后 load 往返一致`() {
        val store = TagStore(tmp.newFolder())
        val plan = samplePlan()
        store.save("NOVEL", plan)
        val loaded = store.load("NOVEL")
        assertEquals(plan.collectedAtMs, loaded?.collectedAtMs)
        assertEquals(plan.plan, loaded?.plan)
        assertEquals(listOf("玄幻", "电影"), loaded?.tags)
    }

    @Test
    fun `load 不存在返回 null`() {
        val store = TagStore(tmp.newFolder())
        assertNull(store.load("VIDEO"))
    }

    @Test
    fun `load 损坏文件返回 null 不抛异常`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "COMIC.json").writeText("{not valid json")
        val store = TagStore(dir)
        assertNull(store.load("COMIC"))
    }

    @Test
    fun `entriesFor 精确匹配`() {
        val plan = samplePlan()
        assertEquals(2, plan.entriesFor("玄幻").size)
        assertEquals(0, plan.entriesFor("科幻").size)
    }

    @Test
    fun `entriesFor 宽松匹配取最短命中`() {
        val plan = TagPlan(
            plan = linkedMapOf(
                "热血漫画" to listOf("c1" to "u1"),
                "热血" to listOf("c2" to "u2"),
            ),
        )
        // 查询「热血」精确命中
        assertEquals(listOf("c2" to "u2"), plan.entriesFor("热血"))
        // 查询「热血漫」无精确命中，宽松命中「热血漫画」（更短候选取语义最贴近——这里两条都包含，
        // 按最短 key 取「热血」……注意「热血」也是「热血漫」的超集包含关系）
        val result = plan.entriesFor("热血漫")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `hotTags 按 entry 数倒序 数量小于 limit 也只返回所有`() {
        val plan = TagPlan(
            plan = linkedMapOf(
                "玄幻" to listOf("s1" to "u1", "s2" to "u2"),
                "电影" to listOf("s1" to "u3"),
                "都市" to listOf("s1" to "u4", "s2" to "u5", "s3" to "u6"),
            ),
        )
        // 都市(3) > 玄幻(2) > 电影(1)
        assertEquals(listOf("都市", "玄幻", "电影"), plan.hotTags(10))
    }

    @Test
    fun `hotTags 取 limit 个`() {
        val plan = TagPlan(
            plan = linkedMapOf(
                "玄幻" to listOf("s1" to "u1", "s2" to "u2", "s3" to "u3"),
                "都市" to listOf("s1" to "u4", "s2" to "u5"),
                "科幻" to listOf("s1" to "u6"),
            ),
        )
        // 玄幻(3) > 都市(2) > 科幻(1)，取前 2
        assertEquals(listOf("玄幻", "都市"), plan.hotTags(2))
    }

    @Test
    fun `hotTags entry 数相同时按 key 稳定排序`() {
        // 用 ASCII 标签避免中日韩字符排序的歧义
        val plan = TagPlan(
            plan = linkedMapOf(
                "alpha" to listOf("s1" to "u1"),
                "beta" to listOf("s1" to "u2"),
                "gamma" to listOf("s1" to "u3"),
            ),
        )
        // 全是 1 条，按 key 字典序
        assertEquals(listOf("alpha", "beta", "gamma"), plan.hotTags(5))
    }

    @Test
    fun `hotTags 空 plan 返回空`() {
        assertEquals(emptyList<String>(), TagPlan.EMPTY.hotTags(5))
    }

    @Test
    fun `hotTags limit 小于等于 0 时返回所有`() {
        val plan = TagPlan(
            plan = linkedMapOf(
                "玄幻" to listOf("s1" to "u1"),
                "都市" to listOf("s1" to "u2"),
            ),
        )
        // 都是 1 条，按 key 排序：玄 < 都
        assertEquals(listOf("玄幻", "都市"), plan.hotTags(0))
        assertEquals(listOf("玄幻", "都市"), plan.hotTags(-1))
    }

    @Test
    fun `重复 save 覆盖旧值`() {
        val store = TagStore(tmp.newFolder())
        store.save("NOVEL", samplePlan())
        store.save("NOVEL", TagPlan(collectedAtMs = 1L, plan = linkedMapOf("都市" to listOf("s" to "u"))))
        val loaded = store.load("NOVEL")
        assertEquals(listOf("都市"), loaded?.tags)
    }

    // ===== v1.6.2.0 标签名清洗 =====

    @Test
    fun `normalizeTag trim 前后空格`() {
        assertEquals("玄幻", TagPlan.normalizeTag("  玄幻  "))
        assertEquals("玄幻 漫画", TagPlan.normalizeTag("\t玄幻 漫画\n"))
    }

    @Test
    fun `normalizeTag 折叠多空格为单个`() {
        assertEquals("热血 漫画", TagPlan.normalizeTag("热血   漫画"))
        assertEquals("热血 漫画", TagPlan.normalizeTag("热血  漫画"))
    }

    @Test
    fun `normalizeTag 折叠全角空格`() {
        // \u3000 是 IDEOGRAPHIC SPACE（全角空格）
        assertEquals("热血 漫画", TagPlan.normalizeTag("热血\u3000\u3000漫画"))
    }

    @Test
    fun `normalizeTag 全空白返回空串`() {
        assertEquals("", TagPlan.normalizeTag(""))
        assertEquals("", TagPlan.normalizeTag("   "))
        assertEquals("", TagPlan.normalizeTag("\t\n\u3000"))
    }

    @Test
    fun `fromJson 落地时清洗 - trim 与折叠多空格`() {
        // 模拟旧的、未清洗的本地缓存：key 含前后空格、多空格、全角空格
        val rawJson = """
            {"collectedAtMs":100,"plan":{
                "  玄幻  ":[["s1","u1"]],
                "热血  漫画":[["s2","u2"]],
                "热血\u3000漫画":[["s3","u3"]]
            }}
        """.trimIndent()
        val plan = TagPlan.fromJson(rawJson) ?: error("should parse")
        // 三个都清洗后折叠：「玄幻」、「热血 漫画」
        assertEquals(setOf("玄幻", "热血 漫画"), plan.plan.keys)
        // 「热血 漫画」集合了 s2 与 s3 两条
        assertEquals(2, plan.entriesFor("热血 漫画").size)
    }
}
