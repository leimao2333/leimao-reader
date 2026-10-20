package com.novelreader

import com.novelreader.core.util.VideoChannelCategories
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频道页「全部」分类的解析（v1.8.4.2）。
 *
 * 背景：视频板块的动漫频道默认停在某个子分类（国产动漫 / 日本动漫…），
 * 而且下拉里的「全部」选中后列表会被清空 —— 两个 bug 同一个根因：
 * 空串被当成「未选」，而不是「跨所有分类聚合」。
 *
 * 这里锁住四条不变式：
 * 1. 空分类 = 全部：各分类条目拍平（不是空列表）；
 * 2. 拍平后按「源 + 地址」去重，同一个源被多个分类命中只请求一次；
 * 3. 顺序跟 plan 走（分类正片优先、源成人沉底），不随 map 迭代乱掉；
 * 4. 具体分类只取自己，未知分类给空且**不回退到全部**。
 */
class VideoChannelCategoriesTest {

    private fun src(id: String) = SourceInfo(
        id = id,
        name = "源$id",
        type = SourceType.VIDEO,
        enabled = true,
    )

    /** 动漫频道线上原型的 plan：没有纯「动漫」分类，全是带修饰的子分类 */
    private val animePlan: Map<String, List<Pair<SourceInfo, String>>> = linkedMapOf(
        "国产动漫" to listOf(src("a") to "/list/1", src("b") to "/type/guochan"),
        "日本动漫" to listOf(src("a") to "/list/2"),
        "欧美动漫" to listOf(src("b") to "/type/oumei"),
    )

    @Test
    fun `全部等于各分类条目拍平而不是空列表`() {
        val entries = VideoChannelCategories.entriesOf(animePlan, "")
        // 以前这里是 emptyList()，所以「全部」选完就变空态
        assertEquals(4, entries.size)
        assertEquals(
            listOf("/list/1", "/type/guochan", "/list/2", "/type/oumei"),
            entries.map { it.second },
        )
    }

    @Test
    fun `空白分类也按全部处理`() {
        assertEquals(4, VideoChannelCategories.entriesOf(animePlan, "   ").size)
        assertEquals(4, VideoChannelCategories.entriesOf(animePlan, "\n").size)
    }

    @Test
    fun `全部按源加地址去重`() {
        // 同一个源的两个分类撞了同一个地址：只请求一次
        val plan = linkedMapOf(
            "国产动漫" to listOf(src("a") to "/list/1"),
            "日本动漫" to listOf(src("a") to "/list/1", src("a") to "/list/2"),
        )
        val entries = VideoChannelCategories.entriesOf(plan, "")
        assertEquals(listOf("/list/1", "/list/2"), entries.map { it.second })
    }

    @Test
    fun `不同源的同一个地址不算重复`() {
        // 苹果 CMS 的 type_id 跨源必然撞号（都是 /list/1），身份必须带源
        val plan = linkedMapOf(
            "国产动漫" to listOf(src("a") to "/list/1"),
            "日本动漫" to listOf(src("b") to "/list/1"),
        )
        assertEquals(2, VideoChannelCategories.entriesOf(plan, "").size)
    }

    @Test
    fun `具体分类只取自己的条目`() {
        val entries = VideoChannelCategories.entriesOf(animePlan, "日本动漫")
        assertEquals(listOf(src("a") to "/list/2"), entries)
    }

    @Test
    fun `未知分类给空且不回退到全部`() {
        assertTrue(VideoChannelCategories.entriesOf(animePlan, "不存在的分类").isEmpty())
    }

    @Test
    fun `空计划不崩`() {
        assertTrue(VideoChannelCategories.entriesOf(emptyMap(), "").isEmpty())
        assertTrue(VideoChannelCategories.entriesOf(emptyMap(), "动漫").isEmpty())
    }
}
