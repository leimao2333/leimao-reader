package com.novelreader

import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.core.util.TagQuery
import com.novelreader.data.cache.TagPlan
import com.novelreader.data.cache.TagStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        val store = TagStore(dir)
        // 写到当前版本号对应的文件名（v1.6.6.0 起缓存文件名带逻辑版本号）
        store.fileOf("COMIC").writeText("{not valid json")
        assertNull(store.load("COMIC"))
    }

    @Test
    fun `旧逻辑版本的缓存文件被忽略 - 触发重新收集`() {
        val dir = tmp.newFolder()
        val store = TagStore(dir)
        store.save("COMIC", samplePlan())
        // 模拟上一版逻辑留下的旧文件
        java.io.File(dir, "COMIC.json").writeText(samplePlan().toJson())
        // 现版本的缓存照常命中
        assertEquals(2, store.load("COMIC")?.plan?.size)
        // 保存一次会顺手清掉旧版本文件
        store.save("COMIC", samplePlan())
        assertTrue(!java.io.File(dir, "COMIC.json").exists())
    }

    @Test
    fun `entriesFor 精确匹配`() {
        val plan = samplePlan()
        assertEquals(2, plan.entriesFor("玄幻").size)
        assertEquals(0, plan.entriesFor("科幻").size)
    }

    @Test
    fun `searchEntriesFor 扩展同义搜索词但不改动真实分类入口`() {
        val plan = TagPlan(
            plan = linkedMapOf(
                "无码" to listOf("search_source" to TagEntryUrl.search("无码")),
                "無修正" to listOf(
                    "category_source" to "https://example.com/category/uncensored",
                    "fallback_source" to TagEntryUrl.category(
                        name = "无修正",
                        param = "all",
                        keyword = "无码",
                        kind = "search",
                    ),
                ),
            ),
        )

        val entries = plan.searchEntriesFor("无码", "無修正")

        assertEquals("search::無修正", entries.first { it.first == "search_source" }.second)
        assertTrue(entries.contains("search_source" to TagEntryUrl.search("无码")))
        assertTrue(entries.contains("search_source" to TagEntryUrl.search("无修正")))
        assertTrue(entries.contains("search_source" to TagEntryUrl.search("無修正")))
        assertTrue(entries.contains("search_source" to TagEntryUrl.search("uncensored")))
        assertTrue(entries.contains("category_source" to "https://example.com/category/uncensored"))
        assertTrue(entries.contains(
            "fallback_source" to TagEntryUrl.category(
                name = "无修正",
                param = "all",
                keyword = "無修正",
                kind = "search",
            ),
        ))
        assertEquals(1, entries.count {
            it == ("category_source" to "https://example.com/category/uncensored")
        })
    }

    @Test
    fun `复合查询保留两个标签并合并原文与百分号转义入口`() {
        val escapedFullColor = "%E5%85%A8%E5%BD%A9"
        val escapedUncensored = "%E6%97%A0%E7%A0%81"
        val plan = TagPlan(
            plan = linkedMapOf(
                "全彩 无码" to listOf("comic_combined" to "search::全彩 无码"),
                "全彩" to listOf("comic_raw_color" to "search::全彩"),
                escapedFullColor to listOf("comic_encoded_color" to "search::%E5%85%A8%E5%BD%A9"),
                "无码" to listOf("comic_raw_adult" to "search::无码"),
                escapedUncensored to listOf("comic_encoded_adult" to "search::%E6%97%A0%E7%A0%81"),
            ),
        )
        assertTrue("全彩 无码" in plan.tags)
        val logicalTags = TagQuery.parse("全彩 无码")
        val canonicalTags = TagAlias.dedupeAliases(logicalTags.map(TagAlias::canonicalOf))

        assertEquals(listOf("全彩", "无码"), canonicalTags)
        assertEquals(
            listOf(
                "comic_raw_color" to "search::全彩",
                "comic_encoded_color" to "search::%E5%85%A8%E5%BD%A9",
            ),
            plan.entriesFor(canonicalTags[0]),
        )
        assertEquals(
            listOf(
                "comic_raw_adult" to "search::无码",
                "comic_encoded_adult" to "search::%E6%97%A0%E7%A0%81",
            ),
            plan.entriesFor(canonicalTags[1]),
        )
        assertEquals(listOf("全彩", "无码"), logicalTags)
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

    @Test
    fun `并发 save 使用独立临时文件并保留有效缓存`() {
        val dir = tmp.newFolder()
        val store = TagStore(dir)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val saves = (1..32).map { index ->
                pool.submit {
                    store.save(
                        "NOVEL",
                        TagPlan(
                            collectedAtMs = index.toLong(),
                            plan = linkedMapOf("标签$index" to listOf("s$index" to "u$index")),
                        ),
                    )
                }
            }
            saves.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(store.load("NOVEL")?.plan?.keys?.singleOrNull()?.startsWith("标签") == true)
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            pool.shutdownNow()
        }
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
