package com.novelreader

import com.novelreader.core.util.ItemTags
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目标签派生测试（v1.6.5.0）。
 *
 * 覆盖三类源的标签来源（浏览上下文 / 视频 typeName / legado kind）、
 * 多值字段拆分、状态词过滤、去重与限量。
 */
class ItemTagsTest {

    private fun item(
        type: SourceType,
        tags: List<String> = emptyList(),
        extras: Map<String, String> = emptyMap(),
    ) = MediaItem(
        id = "1",
        title = "测试",
        type = type,
        tags = tags,
        extras = extras,
    )

    @Test
    fun `浏览上下文标签优先且保序`() {
        val out = ItemTags.of(
            item(SourceType.VIDEO, tags = listOf("动作片"), extras = mapOf("typeName" to "喜剧片")),
        )
        // 浏览上下文在前，extras 派生在后
        assertEquals(listOf("动作片", "喜剧片"), out)
    }

    @Test
    fun `视频从 typeName 派生`() {
        val out = ItemTags.of(
            item(SourceType.VIDEO, extras = mapOf("typeName" to "科幻片")),
        )
        assertEquals(listOf("科幻片"), out)
    }

    @Test
    fun `小说从 kind 派生`() {
        val out = ItemTags.of(
            item(SourceType.NOVEL, extras = mapOf("kind" to "玄幻")),
        )
        assertEquals(listOf("玄幻"), out)
    }

    @Test
    fun `漫画从 kind 派生`() {
        val out = ItemTags.of(
            item(SourceType.COMIC, extras = mapOf("kind" to "少年漫")),
        )
        assertEquals(listOf("少年漫"), out)
    }

    @Test
    fun `漫画读取源自带题材标签`() {
        // v1.7.0.0：Venera 源响应里的 tags（如包子漫画的 type_names）也要展示
        val out = ItemTags.of(
            item(
                SourceType.COMIC,
                extras = mapOf(ItemTags.EXTRAS_TAGS to "恋爱,校园,日常"),
            ),
        )
        assertEquals(listOf("恋爱", "校园", "日常"), out)
    }

    @Test
    fun `源自带标签与 kind 并存且去重`() {
        val out = ItemTags.of(
            item(
                SourceType.COMIC,
                extras = mapOf(
                    "kind" to "热血",
                    ItemTags.EXTRAS_TAGS to "热血,冒险",
                ),
            ),
        )
        assertEquals(listOf("热血", "冒险"), out)
    }

    @Test
    fun `源自带标签里的状态词被过滤`() {
        val out = ItemTags.of(
            item(SourceType.COMIC, extras = mapOf(ItemTags.EXTRAS_TAGS to "已完结,奇幻")),
        )
        assertEquals(listOf("奇幻"), out)
    }

    @Test
    fun `kind 是状态词时过滤`() {
        // legado 的 kind 偶尔被源写成状态，不是分类
        assertEquals(
            emptyList<String>(),
            ItemTags.of(item(SourceType.NOVEL, extras = mapOf("kind" to "连载中"))),
        )
        assertEquals(
            emptyList<String>(),
            ItemTags.of(item(SourceType.NOVEL, extras = mapOf("kind" to "已完结"))),
        )
        assertEquals(
            emptyList<String>(),
            ItemTags.of(item(SourceType.NOVEL, extras = mapOf("kind" to "1"))),
        )
        // 状态词后面的真分类仍要保留（「连载中,玄幻」）
        assertEquals(
            listOf("玄幻"),
            ItemTags.of(item(SourceType.NOVEL, extras = mapOf("kind" to "连载中, 玄幻"))),
        )
    }

    @Test
    fun `多值字段按分隔符拆分`() {
        // v1.7.9.0：「东方玄幻」是「玄幻」的同义写法，展示时归一（不再并排出现两个）
        assertEquals(
            listOf("玄幻", "奇幻"),
            ItemTags.of(item(SourceType.NOVEL, extras = mapOf("kind" to "玄幻, 东方玄幻、奇幻"))),
        )
        assertEquals(
            listOf("动作片", "喜剧片"),
            ItemTags.of(item(SourceType.VIDEO, extras = mapOf("typeName" to "动作片/喜剧片"))),
        )
    }

    @Test
    fun `浏览上下文与 extras 同值去重`() {
        val out = ItemTags.of(
            item(SourceType.VIDEO, tags = listOf("动作片"), extras = mapOf("typeName" to "动作片")),
        )
        assertEquals(listOf("动作片"), out)
    }

    @Test
    fun `无任何来源返回空列表`() {
        assertEquals(emptyList<String>(), ItemTags.of(item(SourceType.VIDEO)))
        assertEquals(emptyList<String>(), ItemTags.of(item(SourceType.NOVEL)))
        assertEquals(emptyList<String>(), ItemTags.of(item(SourceType.COMIC)))
    }

    @Test
    fun `空白标签被忽略`() {
        val out = ItemTags.of(
            item(SourceType.VIDEO, tags = listOf("  ", ""), extras = mapOf("typeName" to "  ")),
        )
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun `超过上限按来源顺序截断`() {
        val many = (1..20).map { "标签$it" }
        val out = ItemTags.of(item(SourceType.VIDEO, tags = many))
        assertEquals(ItemTags.MAX_TAGS, out.size)
        assertEquals("标签1", out.first())
        assertEquals("标签${ItemTags.MAX_TAGS}", out.last())
    }

    // ===== 同义归一 + 标题补标签（v1.7.9.0）=====

    @Test
    fun `同义标签统一成规范名`() {
        // 源里「妖精」「elf」「エルフ」三种写法是同一个题材，展示时只留「精灵」
        val out = ItemTags.of(
            item(SourceType.COMIC, extras = mapOf(ItemTags.EXTRAS_TAGS to "妖精,elf,エルフ")),
        )
        assertEquals(listOf("精灵"), out)
    }

    @Test
    fun `语义相近但不同义的组不被归一`() {
        // 「恶魔/天使」「妹妹/姐姐」只是常一起出现，合并展示会误导
        val out = ItemTags.of(
            item(SourceType.COMIC, extras = mapOf(ItemTags.EXTRAS_TAGS to "恶魔,妹妹")),
        )
        assertTrue("恶魔不应变成天使：$out", out.contains("恶魔"))
        assertTrue("妹妹不应变成姐姐：$out", out.contains("妹妹"))
    }

    @Test
    fun `标题能补充题材标签`() {
        // 源只给一个分类时，标题里的题材词要能补上
        val item = MediaItem(
            id = "1",
            title = "转生成为史莱姆的那件事",
            type = SourceType.COMIC,
        )
        val out = ItemTags.of(item)
        assertTrue("应补出「转生」：$out", out.contains("转生"))
    }

    @Test
    fun `源自带标签优先于标题补充`() {
        val item = MediaItem(
            id = "1",
            title = "转生成为史莱姆的那件事",
            type = SourceType.COMIC,
            extras = mapOf(ItemTags.EXTRAS_TAGS to "转生"),
        )
        val out = ItemTags.of(item)
        // 源自带的排在最前，补充的不重复
        assertEquals("转生", out.first())
        assertEquals(1, out.count { it == "转生" })
    }

    @Test
    fun `视频不从简介抽标签`() {
        // 影视简介多是剧情复述，抽出来噪声大于收益
        val item = MediaItem(
            id = "1",
            title = "无题",
            type = SourceType.VIDEO,
            desc = "一个吸血鬼与恋爱故事",
        )
        assertTrue("视频不应从简介补标签：${ItemTags.of(item)}", ItemTags.of(item).isEmpty())
    }
}
