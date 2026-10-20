package com.novelreader

import com.novelreader.core.util.ItemTags
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagTagger
import com.novelreader.core.util.VideoTagLexicon
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视分类名展开（v1.8.7.0，待办 #28）。
 *
 * 用例全部取自**线上采集站真实的 `vod_type_name`**（2026-10 实测 5 个源、300 条记录
 * 里的 22 个不同分类名），不是编出来的：
 *
 * | 源里的写法 | 该拆成 |
 * | --- | --- |
 * | `传媒-糖心传媒` | 传媒 + 糖心传媒 |
 * | `亚洲情色` | 亚洲 + 成人 |
 * | `日本无码` | 日本 + 无码 |
 * | `自拍偷拍` | 自拍 + 偷窥 |
 * | `三级伦理` | 伦理 |
 * | `少女萝莉` / `巨乳美乳` | 两个题材词 |
 * | `AV解说` / `重口色情` | 成人 + 属性 |
 *
 * 两条底线也在测：
 * 1. **片号不能被切** —— `SSNI-888` 里的连字符不是分类分隔符；
 * 2. **认不出就整串保留** —— `VR视角` 不许变成「VR」+「视角」这种半截标签。
 */
class VideoTagExpansionTest {

    private fun expand(raw: String) = VideoTagLexicon.splitCompoundTag(raw)

    private fun video(typeName: String? = null, extras: Map<String, String> = emptyMap()) =
        MediaItem(
            id = "1",
            title = "无题",
            type = SourceType.VIDEO,
            extras = buildMap {
                typeName?.let { put("typeName", it) }
                putAll(extras)
            },
        )

    private fun tagMap(item: MediaItem): Map<String, Pair<String, Int>> =
        TagTagger.tag(item).associate { it.name to (it.category to it.confidence) }

    // ===== 分隔符 =====

    @Test
    fun `栏目与厂牌按连字符拆开`() {
        // 「传媒-糖心传媒」= 栏目（传媒）+ 厂牌（糖心传媒）
        assertEquals(listOf("传媒", "糖心传媒"), expand("传媒-糖心传媒"))
    }

    @Test
    fun `片号里的连字符不当分隔符`() {
        // 纯数字段不切：否则 SSNI-888 会变成「SSNI」+「888」两个标签
        assertEquals(listOf("SSNI-888"), expand("SSNI-888"))
        assertEquals(listOf("FC2-123"), expand("FC2-123"))
    }

    // ===== 地区前缀 =====

    @Test
    fun `地区前缀拆成地区加属性`() {
        assertEquals(listOf("日本", "无码"), expand("日本无码"))
        assertEquals(listOf("国产", "传媒"), expand("国产传媒"))
        assertEquals(listOf("亚洲", "成人"), expand("亚洲情色"))
    }

    @Test
    fun `地区后面只剩修饰词时只留地区`() {
        assertEquals(listOf("国产"), expand("国产精品"))
    }

    // ===== 同义归一（单段）=====

    @Test
    fun `登记过的同义写法整串归一`() {
        assertEquals(listOf("伦理"), expand("三级伦理"))
        assertEquals(listOf("伦理"), expand("理论片"))
        assertEquals(listOf("制服"), expand("制服诱惑"))
        // 这一组的规范名就是组内首词 cosplay（与「角色扮演」互通）
        assertEquals(listOf("cosplay"), expand("cosplay"))
    }

    @Test
    fun `噪声后缀剥掉后剩下的才是标签`() {
        assertEquals(listOf("探花"), expand("探花系列"))
        assertEquals(listOf("抖阴"), expand("抖阴视频"))
    }

    // ===== 词典分词 =====

    @Test
    fun `两个题材词粘在一起要拆开`() {
        assertEquals(listOf("自拍", "偷窥"), expand("自拍偷拍"))
        assertEquals(listOf("少女", "萝莉"), expand("少女萝莉"))
        assertEquals(listOf("强奸", "乱伦"), expand("强奸乱伦"))
        assertEquals(listOf("网红", "主播"), expand("网红主播"))
        assertEquals(listOf("素人", "搭讪"), expand("素人搭讪"))
    }

    @Test
    fun `拆出来落在同一同义组时只留一个`() {
        // 巨乳 / 美乳 同一组，拆完归一后只剩「巨乳」
        assertEquals(listOf("巨乳"), expand("巨乳美乳"))
    }

    @Test
    fun `英文缩写也参与分词`() {
        assertEquals(listOf("成人", "解说"), expand("AV解说"))
        assertEquals(listOf("重口", "成人"), expand("重口色情"))
    }

    @Test
    fun `词表吃不下的整串保留 不硬拆`() {
        // 「视角」不在词表 → 整串保留，不产出「VR」这种半截标签
        assertEquals(listOf("VR视角"), expand("VR视角"))
        assertEquals(listOf("福利姬"), expand("福利姬"))
    }

    // ===== 与旧行为兼容（类型维度）=====

    @Test
    fun `类型维度照旧拆`() {
        assertEquals(listOf("电影", "动作"), expand("动作片"))
        assertEquals(listOf("动漫", "里番"), expand("里番动漫"))
        assertEquals(listOf("电视剧", "香港"), expand("港剧"))
        assertEquals(listOf("电影"), expand("其他片"))
        assertEquals(listOf("动漫"), expand("动画"))
    }

    // ===== 落库口径 =====

    @Test
    fun `一部片子因此拿到多个标签`() {
        val tags = tagMap(video(typeName = "亚洲情色"))
        // 地区归到「地区」大类，成人归到「成人」大类 —— 隐藏成人内容时能整类过滤
        assertEquals("地区" to 100, tags["亚洲"])
        assertEquals("成人" to 100, tags["成人"])
        assertEquals(2, tags.size)
    }

    @Test
    fun `vod_class 的多题材也进标签层`() {
        val tags = tagMap(video(typeName = "动作片", extras = mapOf(ItemTags.EXTRAS_CLASS to "剧情,悬疑")))
        assertEquals("题材" to 100, tags["动作"])
        assertEquals("题材" to 100, tags["剧情"])
        assertEquals("题材" to 100, tags["悬疑"])
        assertEquals("题材" to 100, tags["电影"])
    }

    @Test
    fun `vod_class 缺省时不影响原有标签`() {
        val tags = tagMap(video(typeName = "动作片"))
        assertEquals(setOf("电影", "动作"), tags.keys)
    }

    // ===== 查询侧同口径 =====

    @Test
    fun `查询侧能按规范名搜到源里的别名`() {
        // 源里写「理论片」「三级伦理」，用户搜「伦理」都要命中
        assertTrue("理论片" in TagAlias.aliasesOf("伦理"))
        assertTrue("三级伦理" in TagAlias.aliasesOf("伦理"))
        // 源里写「情色」，用户搜「成人」要命中（同一大类）
        assertTrue("情色" in TagAlias.aliasesOf("成人"))
        // 源里写「偷拍」，用户搜「偷窥」要命中
        assertTrue("偷拍" in TagAlias.aliasesOf("偷窥"))
    }
}
