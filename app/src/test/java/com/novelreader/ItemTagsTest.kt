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
        title: String = "测试",
        desc: String? = null,
        tags: List<String> = emptyList(),
        extras: Map<String, String> = emptyMap(),
    ) = MediaItem(
        id = "1",
        title = title,
        type = type,
        desc = desc,
        tags = tags,
        extras = extras,
    )

    @Test
    fun `浏览上下文标签优先且保序`() {
        val out = ItemTags.of(
            item(SourceType.VIDEO, tags = listOf("动作片"), extras = mapOf("typeName" to "喜剧片")),
        )
        // 浏览上下文在前，extras 派生在后。
        // v1.8.4.0：影视分类名是复合词，先拆成「一级类型 + 具体属性」再归一 ——
        // 动作片 = 电影 + 动作、喜剧片 = 电影 + 搞笑（同一个类型标签只留一份）。
        assertEquals(listOf("电影", "动作", "搞笑"), out)
    }

    @Test
    fun `视频从 typeName 派生`() {
        val out = ItemTags.of(
            item(SourceType.VIDEO, extras = mapOf("typeName" to "科幻片")),
        )
        // v1.8.4.0：拆出类型 + 剥掉片后缀
        assertEquals(listOf("电影", "科幻"), out)
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
            listOf("电影", "动作", "搞笑"),
            ItemTags.of(item(SourceType.VIDEO, extras = mapOf("typeName" to "动作片/喜剧片"))),
        )
    }

    @Test
    fun `浏览上下文与 extras 同值去重`() {
        val out = ItemTags.of(
            item(SourceType.VIDEO, tags = listOf("动作片"), extras = mapOf("typeName" to "动作片")),
        )
        // 归一 + 拆分之后两者都是同一个键，类型标签也只出现一次
        assertEquals(listOf("电影", "动作"), out)
    }

    @Test
    fun `影视复合分类名拆成类型加属性两个标签`() {
        // v1.8.4.0：这是「输 A 搜 B」的另一半 —— 以前「里番动漫」只存成一个标签，
        // 搜「动漫」搜不到里番、搜「里番」也搜不到只标了「里番动漫」的源。
        assertEquals(
            listOf("动画", "里番"),
            ItemTags.of(item(SourceType.VIDEO, extras = mapOf("typeName" to "里番动漫"))),
        )
        // 地区后缀的拆成类型 + 地区
        assertEquals(
            listOf("动画", "国产"),
            ItemTags.of(item(SourceType.VIDEO, extras = mapOf("typeName" to "国产动漫"))),
        )
        // 整串就是地区别名：港剧 = 电视剧 + 香港
        assertEquals(
            listOf("电视剧", "香港"),
            ItemTags.of(item(SourceType.VIDEO, extras = mapOf("typeName" to "港剧"))),
        )
        // 兜底分类（其他片）只留一级类型，不制造「其他片」这种噪声标签
        assertEquals(
            listOf("电影"),
            ItemTags.of(item(SourceType.VIDEO, extras = mapOf("typeName" to "其他片"))),
        )
        // 源内别名统一成规范名：电影片 → 电影
        assertEquals(
            listOf("电影"),
            ItemTags.of(item(SourceType.VIDEO, extras = mapOf("typeName" to "电影片"))),
        )
    }

    @Test
    fun `小说漫画不拆复合分类名`() {
        // 拆分只对影视生效：小说的 kind 是作者 / 源自己写的分类，不该被类型词改写
        assertEquals(
            listOf("都市异能"),
            ItemTags.of(item(SourceType.NOVEL, extras = mapOf("kind" to "都市异能"))),
        )
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
        val many = (1..40).map { "标签$it" }
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

    @Test
    fun `小说题材从标题和简介补充`() {
        val out = ItemTags.of(
            item(
                SourceType.NOVEL,
                title = "末世囤货：我的安全屋",
                desc = "天灾降临后的末日生存与基建经营",
            ),
        )
        assertTrue("应识别小说细分题材：$out", out.any { it in setOf("末世囤货", "囤货", "天灾", "末日生存", "基建") })
    }

    @Test
    fun `同人作品自动附加通用原著和可识别原著名`() {
        val out = ItemTags.of(
            item(SourceType.COMIC, title = "原神同人：旅行者的日常"),
        )
        assertTrue("应自动添加同人标签：$out", out.contains("同人"))
        assertTrue("应自动添加原著标签：$out", out.contains("原著"))
        assertTrue("应识别原著作品：$out", out.contains("原著·原神"))
    }

    @Test
    fun `普通作品仅提到原著名时不误标为同人`() {
        val out = ItemTags.of(
            item(SourceType.NOVEL, title = "原神世界观设定解析"),
        )
        assertTrue("普通介绍不应误标为同人：$out", out.none { it == "同人" || it == "原著" || it.startsWith("原著·") })
    }

    @Test
    fun `源标签标记同人时可补识别的原著`() {
        val out = ItemTags.of(
            item(
                SourceType.NOVEL,
                title = "旅行者的故事",
                extras = mapOf(ItemTags.EXTRAS_TAGS to "二次创作"),
            ),
        )
        assertTrue(out.contains("同人"))
        assertTrue(out.contains("原著"))
    }

    @Test
    fun `标签数量达到上限时仍保留同人和原著`() {
        val many = (1..30).map { "标签$it" }
        val out = ItemTags.of(
            item(
                SourceType.COMIC,
                title = "原神同人：旅行者",
                tags = many,
            ),
        )
        assertEquals(ItemTags.MAX_TAGS, out.size)
        assertTrue(out.contains("同人"))
        assertTrue(out.contains("原著"))
        assertTrue(out.contains("原著·原神"))
    }
}
