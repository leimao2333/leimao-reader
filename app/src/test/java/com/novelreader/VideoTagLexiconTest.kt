package com.novelreader

import com.novelreader.core.util.TagTagger
import com.novelreader.core.util.VideoTagLexicon
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视打标词表单测（v1.8.4.0，待办 #12）。
 *
 * 盯住四件事：
 * 1. **地区 / 语言要归一** —— 「内地 / 大陆 / 国产」是同一个意思，不归并就互相搜不到；
 * 2. **占位词不能当标签** —— `vod_actor` 里常写「未知」「佚名」，进了标签池就是噪声；
 * 3. **主创要有上限** —— 演员表当标签会淹掉题材；
 * 4. **标题题材宁缺勿滥** —— 只认标题、只认歧义小的词。
 */
class VideoTagLexiconTest {

    private fun video(
        title: String = "测试影片",
        extras: Map<String, String> = emptyMap(),
    ) = MediaItem(
        id = "1",
        title = title,
        type = SourceType.VIDEO,
        extras = extras,
    )

    private fun names(item: MediaItem) = VideoTagLexicon.derive(item).map { it.name }

    // ===== 地区 =====

    @Test
    fun `内地 大陆 国产归一成同一个地区`() {
        listOf("内地", "大陆", "国产", "中国大陆").forEach { area ->
            assertEquals(area, listOf("国产"), names(video(extras = mapOf("area" to area))))
        }
    }

    @Test
    fun `地区别名归到规范名`() {
        assertEquals(listOf("香港"), names(video(extras = mapOf("area" to "港片"))))
        assertEquals(listOf("韩国"), names(video(extras = mapOf("area" to "韩剧"))))
        assertEquals(listOf("美国"), names(video(extras = mapOf("area" to "好莱坞"))))
    }

    @Test
    fun `认不出的地区不硬凑`() {
        assertTrue(names(video(extras = mapOf("area" to "火星"))).isEmpty())
    }

    // ===== 语言 / 年代 =====

    @Test
    fun `语言归一`() {
        assertEquals(listOf("国语"), names(video(extras = mapOf("lang" to "普通话"))))
        assertEquals(listOf("粤语"), names(video(extras = mapOf("lang" to "粵語"))))
    }

    @Test
    fun `年份只认四位且合理`() {
        assertEquals("2025", VideoTagLexicon.yearOf("2025"))
        assertNull(VideoTagLexicon.yearOf("25"))
        assertNull(VideoTagLexicon.yearOf("abcd"))
        assertNull(VideoTagLexicon.yearOf("9999"))
        assertNull(VideoTagLexicon.yearOf(null))
    }

    // ===== 主创 =====

    @Test
    fun `主演最多两个 导演一个`() {
        val tags = names(
            video(
                extras = mapOf(
                    "actor" to "张三,李四,王五",
                    "director" to "赵六,钱七",
                ),
            ),
        )
        assertEquals(listOf("张三", "李四", "赵六"), tags)
    }

    @Test
    fun `占位词不进标签`() {
        val tags = names(video(extras = mapOf("actor" to "未知,佚名,张三")))
        assertEquals(listOf("张三"), tags)
    }

    // ===== 标题题材 =====

    @Test
    fun `标题里认题材并按出现顺序`() {
        assertEquals(listOf("爱情", "喜剧"), VideoTagLexicon.genresFromTitle("爱情喜剧大片"))
    }

    @Test
    fun `标题题材最多三个`() {
        assertEquals(3, VideoTagLexicon.genresFromTitle("动作科幻悬疑恐怖").size)
    }

    @Test
    fun `没有题材词时返回空`() {
        assertTrue(VideoTagLexicon.genresFromTitle("阿龙2025").isEmpty())
    }

    // ===== 组合 =====

    @Test
    fun `一部港片的完整标签`() {
        val item = video(
            title = "动作片",
            extras = mapOf(
                "area" to "香港",
                "lang" to "粤语",
                "year" to "2025",
                "actor" to "张三",
                "typeName" to "动作片",
            ),
        )
        val derived = VideoTagLexicon.derive(item)
        val byName = derived.associateBy { it.name }
        assertEquals("香港", byName["香港"]?.name)
        assertEquals(TagTagger.CATEGORY_REGION, byName["香港"]?.category)
        assertEquals(TagTagger.CATEGORY_LANGUAGE, byName["粤语"]?.category)
        assertEquals(TagTagger.CATEGORY_ERA, byName["2025"]?.category)
        assertEquals(TagTagger.CATEGORY_CAST, byName["张三"]?.category)
        assertEquals(TagTagger.CATEGORY_GENRE, byName["动作"]?.category)
        // 标题题材是文本猜的，置信度必须低于站方字段
        assertTrue(byName.getValue("动作").confidence < byName.getValue("香港").confidence)
    }
}
