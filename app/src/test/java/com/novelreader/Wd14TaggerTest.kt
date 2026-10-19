package com.novelreader

import com.novelreader.core.util.TagAlias
import com.novelreader.core.visual.Wd14Labels
import com.novelreader.core.visual.Wd14Mapper
import com.novelreader.core.visual.Wd14TaggerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉打标纯逻辑测试（v1.8.1.4）。
 *
 * 覆盖：selected_tags.csv 解析（表头 / 引号 / 空行）、可用行筛选、
 * Danbooru 名 → 受控词表映射（登记过的才收，未登记的丢弃）。
 */
class Wd14TaggerTest {

    private val csv = """
        tag_id,name,category,count
        9999999,general,9,807858
        9999996,explicit,9,560281
        470575,1girl,0,4225150
        100000,monochrome,0,1200000
        100001,"yuri",0,900000
        100002,"comic, strip",0,300000
        200001,artist:foo,1,10
        300001,hakurei_reimu,3,80000
    """.trimIndent()

    @Test
    fun `csv 解析含引号与转义逗号`() {
        val labels = Wd14Labels.parse(csv)
        // "comic, strip" 是一个带引号的标签名，不能按逗号拆碎
        assertEquals(8, labels.size)
        assertEquals("comic, strip", labels.row(5).name)
        assertEquals(0, labels.row(5).category)
    }

    @Test
    fun `可用行只含 general 与 character`() {
        val labels = Wd14Labels.parse(csv)
        val usable = labels.usableIndices.map { labels.row(it).name }
        // rating(9) 与 artist(1) 不参与打标
        assertEquals(setOf("1girl", "monochrome", "yuri", "comic, strip", "hakurei_reimu"), usable.toSet())
        assertEquals(4, labels.usableIndices.count { !labels.isCharacter(it) })
        assertTrue(labels.isCharacter(labels.usableIndices.last()))
    }

    @Test
    fun `映射只收登记过的受控词`() {
        assertEquals("黑白", Wd14Mapper.toControlled("monochrome"))
        assertEquals("百合", Wd14Mapper.toControlled("yuri"))
        assertEquals("漫画", Wd14Mapper.toControlled("comic"))
        // danbooru 原始写法（下划线）也先转空格再匹配
        assertEquals("后宫", Wd14Mapper.toControlled("harem"))
    }

    @Test
    fun `未登记的 danbooru 词被丢弃`() {
        assertNull(Wd14Mapper.toControlled("1girl"))
        // long_hair 已登记（→ 长发）；greyscale 在 v1.8.1.9 补进「黑白」组（→ 黑白）；
        // 角色名（hakurei_reimu）仍不在受控词表
        assertEquals("长发", Wd14Mapper.toControlled("long_hair"))
        assertEquals("黑白", Wd14Mapper.toControlled("greyscale"))
        assertNull(Wd14Mapper.toControlled("hakurei_reimu"))
        assertNull(Wd14Mapper.toControlled("  "))
    }

    @Test
    fun `成人词映射后归成人压实`() {
        val controlled = Wd14Mapper.toControlled("uncensored")
        assertEquals("无码", controlled)
        // 与 TagTagger 的成人判定一致：映射结果直接可进「成人」大类
        assertTrue(TagAlias.isExplicitAdultIntent(controlled!!))
    }

    @Test
    fun `规格常量自洽`() {
        assertEquals(448, Wd14TaggerSpec.INPUT_SIZE)
        assertTrue(Wd14TaggerSpec.DEFAULT_MODEL_URL.startsWith("https://hf-mirror.com/"))
        assertTrue(Wd14TaggerSpec.DEFAULT_TAGS_URL.endsWith("selected_tags.csv"))
    }
}
