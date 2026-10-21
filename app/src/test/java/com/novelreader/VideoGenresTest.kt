package com.novelreader

import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagTagger
import com.novelreader.core.util.VideoGenres
import com.novelreader.core.util.VideoTagLexicon
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视题材 / 属性词表整合（v1.8.7.1）。
 *
 * 背景：视频的标签池此前**完全**由采集站的分类名决定（`TagRepository.attachSearchTags`
 * 对 VIDEO 直接 return），站方没建「无码」分类，标签池里就永远没有这个词，
 * 用户单搜它只会得到「未找到标签」；而且同义组只在池里恰好存在另一种写法时才生效，
 * 等于「无码 → 無修正 / uncensored」这条链在视频侧从来没跑通过。
 *
 * 这里锁死四件事：
 * 1. 词表本身干净（无重复、不含漫画专有载体词）；
 * 2. 属性词（无码 / 有码 / 里番 / 成人）确实在表里，且是不限源的宽标签；
 * 3. 分类名分词认得新并入的题材词；
 * 4. 视频标题里的属性词能进详情页标签栏。
 */
class VideoGenresTest {

    @Test
    fun `词表没有重复项`() {
        val dup = VideoGenres.ALL.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("词表存在重复项：$dup", dup.isEmpty())
    }

    @Test
    fun `词表包含影视属性词`() {
        // 这四个是用户明确要能单搜 / 点选的核心属性，缺一个就等于「标签池里没有」
        listOf("无码", "有码", "里番", "成人").forEach {
            assertTrue("词表应包含「$it」", it in VideoGenres.ALL)
        }
    }

    @Test
    fun `词表包含通用题材并沿用漫画口径`() {
        // 「结合漫画词表」的落点：这些词在漫画 / 视频里是同一个标签，不该各叫一套
        listOf("动作", "悬疑", "犯罪", "末世", "赛博朋克", "校园", "治愈").forEach {
            assertTrue("词表应包含通用题材「$it」", it in VideoGenres.ALL)
        }
    }

    @Test
    fun `词表不收漫画小说专有的载体词`() {
        // 真人影视搜「韩漫」「四格」「颜艺」只会零结果，或把动画区内容混进真人片
        listOf("韩漫", "国漫", "日漫", "四格", "颜艺", "轻小说").forEach {
            assertTrue("词表不应包含载体词「$it」", it !in VideoGenres.ALL)
        }
    }

    @Test
    fun `宽标签都在词表内`() {
        // WIDE_SEARCH_TAGS 会覆盖全部可搜索源，写错一个词就是全源白跑一轮
        VideoGenres.WIDE_SEARCH_TAGS.forEach {
            assertTrue("宽标签「$it」必须在主词表里", it in VideoGenres.ALL)
        }
    }

    @Test
    fun `属性词都能归一到自己的规范名`() {
        // attachSearchTags 会对每个词做 canonicalOf，归一后变了名字就会绑到别的标签上
        listOf("无码", "有码", "里番", "成人", "中字", "自拍").forEach {
            assertEquals(it, TagAlias.canonicalOf(it))
        }
    }

    @Test
    fun `分类名分词认得并入的题材词`() {
        // 分词词表现在读 VideoGenres.ALL，「赛博朋克」这种长词也要求全串覆盖才切
        assertEquals(listOf("赛博朋克", "动作"), VideoTagLexicon.splitCompoundTag("赛博朋克动作"))
        // 分词结果还会过一遍同义归一：「末世」的规范名是组首词「末日」
        assertEquals(listOf("末日", "丧尸"), VideoTagLexicon.splitCompoundTag("末世丧尸"))
        // 角色属性词只在分词表里（不进标签池），粘在一起也要能拆开
        assertEquals(listOf("少女", "萝莉"), VideoTagLexicon.splitCompoundTag("少女萝莉"))
    }

    @Test
    fun `地区前缀仍优先于分词`() {
        // 日本无码 → 日本 + 无码（地区维度不能因为词表变大被吞掉）
        assertEquals(listOf("日本", "无码"), VideoTagLexicon.splitCompoundTag("日本无码"))
    }

    @Test
    fun `视频标题里的属性词进标签层`() {
        val item = MediaItem(
            id = "1",
            title = "无码 中字 动作片",
            type = SourceType.VIDEO,
            extras = mapOf("typeName" to "动作片"),
        )
        val tags = TagTagger.tag(item).associate { it.name to it }
        assertTrue("应抽到「无码」：${tags.keys}", tags.containsKey("无码"))
        assertTrue("应抽到「中字」：${tags.keys}", tags.containsKey("中字"))
        // 标题抽的是「文本」管道，置信度必须低于站方分类 —— 否则详情页会把
        // 标题里猜出来的词当成源站声明，压掉真正的站方题材
        assertEquals(TagTagger.ORIGIN_TEXT, tags["无码"]?.origin)
        assertEquals(60, tags["无码"]?.confidence)
        // 站方 typeName 拆出来的「动作」仍取站方分
        assertEquals(TagTagger.ORIGIN_STATION, tags["动作"]?.origin)
    }

    @Test
    fun `视频仍不从简介抽题材`() {
        // 采集站简介多是剧情复述，只抽标题（v1.8.7.1 保持这条边界）
        val item = MediaItem(
            id = "1",
            title = "无题",
            type = SourceType.VIDEO,
            desc = "一个吸血鬼与恋爱故事",
        )
        assertTrue(
            "视频不应从简介补标签：${TagTagger.tag(item)}",
            TagTagger.tag(item).isEmpty(),
        )
    }
}
