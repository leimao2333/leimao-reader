package com.novelreader

import com.novelreader.core.util.TagQuerySpec
import com.novelreader.core.util.TagTagger
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视打标**整条链路**单测（v1.8.4.0，待办 #11 / #12）。
 *
 * [VideoTagLexiconTest] 只测词表本身；这里测「词表 → [TagTagger] → 落库用的
 * (name, category, confidence)」这一段，也就是 `tag_instances` 表里真正会写进去的东西：
 *
 * 1. 采集站给的 `vod_area` / `vod_lang` / `vod_year` / `vod_actor` 有没有进标签层；
 * 2. 同名标签（`type_name` 的「动作片」与标题里的「动作」）会不会被合并成一条，
 *    且保留置信度更高的那条 —— 否则标签池里会出现「动作」两行、查询结果翻倍；
 * 3. 小说 / 漫画不会被影视字段污染（它们的 extras 里也可能塞 year / area）。
 */
class TagTaggerVideoTest {

    private fun video(
        title: String = "测试影片",
        typeName: String? = null,
        extras: Map<String, String> = emptyMap(),
    ) = MediaItem(
        id = "1",
        title = title,
        type = SourceType.VIDEO,
        extras = buildMap {
            typeName?.let { put("typeName", it) }
            putAll(extras)
        },
    )

    /** 标签名 → (大类, 置信度)，便于按名断言 */
    private fun tagMap(item: MediaItem): Map<String, Pair<String, Int>> =
        TagTagger.tag(item).associate { it.name to (it.category to it.confidence) }

    // ===== 结构化字段进标签层 =====

    @Test
    fun `港片的地区 语言 年代都进标签层`() {
        val tags = tagMap(
            video(
                title = "阿龙2025",
                typeName = "动作片",
                extras = mapOf(
                    "area" to "香港",
                    "lang" to "粤语",
                    "year" to "2025",
                ),
            )
        )
        assertEquals("题材" to 100, tags["动作"])
        assertEquals("地区" to 100, tags["香港"])
        assertEquals("语言" to 100, tags["粤语"])
        assertEquals("年代" to 100, tags["2025"])
    }

    @Test
    fun `主演最多两个 导演一个 且人名不改写`() {
        val tags = tagMap(
            video(
                extras = mapOf(
                    "actor" to "郑中基,傅舜盈,周秀娜,姜皓文",
                    "director" to "郑中基",
                ),
            )
        )
        assertTrue("郑中基" in tags)
        assertTrue("傅舜盈" in tags)
        assertTrue("周秀娜" !in tags)
        assertTrue("姜皓文" !in tags)
        // 演员与导演同名只留一条，不该出现两次
        assertEquals(1, TagTagger.tag(video(extras = mapOf("actor" to "郑中基", "director" to "郑中基")))
            .count { it.name == "郑中基" })
    }

    @Test
    fun `占位词不当标签`() {
        val tags = tagMap(video(extras = mapOf("actor" to "未知,佚名,暂无")))
        assertTrue(tags.isEmpty())
    }

    // ===== 同名合并 =====

    @Test
    fun `站方题材与标题题材同名时只留一条且取站方高分`() {
        val item = video(title = "动作喜剧", typeName = "动作片")
        val hit = TagTagger.tag(item).filter { it.name == "动作" }
        assertEquals(1, hit.size)
        assertEquals(100, hit.first().confidence)
        assertEquals(TagTagger.ORIGIN_STATION, hit.first().origin)
        // 标题里另一个词仍是文本管道，置信度低一档（喜剧 → 规范名「搞笑」）
        assertEquals(60, tagMap(item)["搞笑"]?.second)
    }

    @Test
    fun `片字后缀归到同一个题材键`() {
        // 「动作片」「喜剧片」这类片名后缀不该造出第二个标签
        val tags = tagMap(video(title = "无题", typeName = "喜剧片"))
        assertTrue("喜剧片" !in tags)
        assertTrue("搞笑" in tags)
    }

    // ===== 复合分类名拆标签（v1.8.4.0：和小说 / 漫画一样一部作品多个标签）=====

    @Test
    fun `里番动漫拆成动漫和里番两个标签`() {
        val tags = tagMap(video(title = "无题", typeName = "里番动漫"))
        // 类型维度：动画（「动漫」在词表里归一到「动画」，两个写法互通）
        assertEquals("题材" to 100, tags["动画"])
        // 具体维度：里番，且归到成人大类 —— 隐藏成人内容时要整类过滤
        assertEquals("成人" to 100, tags["里番"])
        assertEquals(2, tags.size)
    }

    @Test
    fun `港剧拆成电视剧加香港 且香港是地区不是题材`() {
        val tags = tagMap(video(title = "无题", typeName = "港剧"))
        assertEquals("题材" to 100, tags["电视剧"])
        // 关键：分类名拆出来的「香港」必须和 vod_area 派生的同类，
        // 否则 `地区:香港` 只查得到一半
        assertEquals("地区" to 100, tags["香港"])
    }

    @Test
    fun `分类名与 vod_area 都是香港时只留一条地区标签`() {
        val item = video(title = "无题", typeName = "香港剧", extras = mapOf("area" to "香港"))
        val hit = TagTagger.tag(item).filter { it.name == "香港" }
        assertEquals(1, hit.size)
        assertEquals(TagTagger.CATEGORY_REGION, hit.first().category)
    }

    @Test
    fun `兜底分类不产出噪声标签`() {
        val tags = tagMap(video(title = "无题", typeName = "其他片"))
        assertEquals(setOf("电影"), tags.keys)
    }

    // ===== 不越界 =====

    @Test
    fun `小说不会拿到影视专属标签`() {
        val novel = MediaItem(
            id = "2",
            title = "测试小说",
            type = SourceType.NOVEL,
            extras = mapOf("area" to "香港", "year" to "2025", "lang" to "粤语", "actor" to "张三"),
        )
        val tags = tagMap(novel)
        assertTrue(tags.keys.none { it == "香港" || it == "2025" || it == "粤语" || it == "张三" })
    }

    @Test
    fun `没有影视字段时标签为空`() {
        assertTrue(TagTagger.tag(video(title = "无题")).isEmpty())
    }

    // ===== 查询语法 =====

    @Test
    fun `新大类可以写进查询串`() {
        val clauses = TagQuerySpec.parse("地区:香港 年代:2025 主创:郑中基 lang:粤语")
        assertEquals(listOf("地区", "年代", "主创", "语言"), clauses.map { it.category })
        assertEquals(listOf("香港", "2025", "郑中基", "粤语"), clauses.map { it.name })
    }

    @Test
    fun `不认识的前缀整体当标签名`() {
        val clause = TagQuerySpec.parse("片名:阿龙").single()
        assertNull(clause.category)
        assertEquals("片名:阿龙", clause.name)
    }
}
