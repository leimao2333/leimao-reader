package com.novelreader

import com.novelreader.core.util.VideoCategoryPlan
import com.novelreader.core.util.VideoTypeMap
import com.novelreader.domain.model.CategorySource
import com.novelreader.domain.model.ExploreCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频分类合并纯逻辑单测（v1.6.7.0）。
 *
 * 两条核心不变式：
 * 1. **分类的身份是「分类名」，不是源内地址** —— 苹果 CMS 的地址是 `type_id`
 *    （1/2/9…），跨源必然撞号；身份用错就会出现「点 A 分类出 B 内容」
 *    「两个分类同时打勾」「标题显示错」。
 * 2. **类型必须归位且顺序固定** —— 各源把「动作片」当一级还是二级并不一致，
 *    直接按 `parent` 判类型会把 180 多个名字（含套图社 / 成人栏目 / 站内公告）
 *    全塞进「类型」下拉，而且首页分区会随源顺序乱跳。
 */
class VideoCategoryPlanTest {

    private fun cat(title: String, url: String, parent: String = "") =
        ExploreCategory(title = title, url = url, parent = parent)

    @Test
    fun `别名归位到规范类型且顺序恒定`() {
        // A 源叫「连续剧」，B 源叫「电视剧」—— 都该归到规范类型「电视剧」
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("B", "A"),
            catsBySource = mapOf(
                "A" to listOf(cat("连续剧", "2")),
                "B" to listOf(cat("电视剧", "1")),
            ),
        )
        assertEquals(listOf("电视剧"), plan.tree.keys.toList())
        // 别名不算二级分类
        assertEquals(emptyList<String>(), plan.tree["电视剧"])
        // 「电视剧」类型在各源的自身地址：B 用 1，A 用 2
        assertEquals(
            listOf(CategorySource("B", "1"), CategorySource("A", "2")),
            plan.typeUrls["电视剧"],
        )
    }

    @Test
    fun `类型顺序不受源顺序影响`() {
        // 反着给源，树键序仍然是 VideoTypeMap.ORDER 的顺序
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("B", "A"),
            catsBySource = mapOf(
                "A" to listOf(cat("电影", "1")),
                "B" to listOf(cat("短剧", "38"), cat("动漫", "4")),
            ),
        )
        assertEquals(listOf("电影", "动漫", "短剧"), plan.tree.keys.toList())
    }

    @Test
    fun `平铺源的二级分类被归位而不是当成一级类型`() {
        // 线上原型：多数源分类表是平的，「动作片」直接是一级
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A"),
            catsBySource = mapOf(
                "A" to listOf(cat("动作片", "9"), cat("喜剧片", "10")),
            ),
        )
        // 「动作片」不该成为一个类型，而是「电影」下的分类
        assertEquals(listOf("电影"), plan.tree.keys.toList())
        assertEquals(listOf("动作片", "喜剧片"), plan.tree["电影"])
    }

    @Test
    fun `表里没有的名字跟着父级归位`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A"),
            catsBySource = mapOf(
                "A" to listOf(cat("电影", "1"), cat("冷门新分类", "99", parent = "电影")),
            ),
        )
        assertEquals(listOf("电影"), plan.tree.keys.toList())
        assertEquals(listOf("冷门新分类"), plan.tree["电影"])
    }

    @Test
    fun `未归类名字不展示（套图社 成人栏目 站内公告）`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A"),
            catsBySource = mapOf(
                "A" to listOf(cat("爱蜜社", "7"), cat("成人笑话", "8"), cat("采集教程", "9")),
            ),
        )
        assertTrue(plan.tree.isEmpty())
        // 但地址表里仍然保留，详情页/其他入口按名查不会被误伤
        assertEquals(listOf(CategorySource("A", "7")), plan.urls["爱蜜社"])
    }

    @Test
    fun `不同分类撞同一地址也不会互相污染`() {
        // A 源里「预告片」=1；B 源里「福利」=1 —— 线上串味的原型
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A", "B"),
            catsBySource = mapOf(
                "A" to listOf(cat("预告片", "1")),
                "B" to listOf(cat("福利", "1")),
            ),
        )
        assertEquals(listOf("预告片"), plan.tree.keys.toList())
        assertEquals(listOf(CategorySource("A", "1")), plan.typeUrls["预告片"])
        assertEquals(listOf(CategorySource("B", "1")), plan.urls["福利"])
    }

    @Test
    fun `二级分类挂到父级名下`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A"),
            catsBySource = mapOf(
                "A" to listOf(
                    cat("电影", "2"),
                    cat("动作片", "9", parent = "电影"),
                    cat("喜剧片", "10", parent = "电影"),
                    cat("电视剧", "1"),
                    cat("欧美剧", "3", parent = "电视剧"),
                ),
            ),
        )
        assertEquals(listOf("电影", "电视剧"), plan.tree.keys.toList())
        assertEquals(listOf("动作片", "喜剧片"), plan.tree["电影"])
        assertEquals(listOf("欧美剧"), plan.tree["电视剧"])
    }

    @Test
    fun `父级没被单独返回时也能挂上`() {
        // 有些源只回二级（父级混在标题里），不能因此丢整棵子树
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A"),
            catsBySource = mapOf("A" to listOf(cat("动作片", "9", parent = "电影"))),
        )
        assertEquals(listOf("电影"), plan.tree.keys.toList())
        assertEquals(listOf("动作片"), plan.tree["电影"])
    }

    @Test
    fun `类型没有二级分类时是空列表且仍保留该类型`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A"),
            catsBySource = mapOf("A" to listOf(cat("短剧", "38"))),
        )
        assertTrue(plan.tree.containsKey("短剧"))
        assertEquals(emptyList<String>(), plan.tree["短剧"])
        assertEquals(listOf(CategorySource("A", "38")), plan.typeUrls["短剧"])
    }

    @Test
    fun `跨源二级分类按名合并保序且各留各的地址`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A", "B"),
            catsBySource = mapOf(
                "A" to listOf(cat("电影", "2"), cat("动作片", "9", parent = "电影")),
                "B" to listOf(
                    cat("电影", "5"),
                    cat("喜剧片", "10", parent = "电影"),
                    // A 已有的「动作片」不重复
                    cat("动作片", "19", parent = "电影"),
                ),
            ),
        )
        assertEquals(listOf("动作片", "喜剧片"), plan.tree["电影"])
        assertEquals(
            listOf(CategorySource("A", "9"), CategorySource("B", "19")),
            plan.urls["动作片"],
        )
    }

    @Test
    fun `类型的自身地址每源只取一条且优先规范名`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A", "B"),
            catsBySource = mapOf(
                // A 同时有「电影」和「电影片」—— 取规范名「电影」那条
                "A" to listOf(cat("电影片", "90"), cat("电影", "1")),
                // B 只有「4K电影」—— 退化取它
                "B" to listOf(cat("4K电影", "12")),
            ),
        )
        assertEquals(
            listOf(CategorySource("A", "1"), CategorySource("B", "12")),
            plan.typeUrls["电影"],
        )
        assertEquals(listOf(CategorySource("A", "90")), plan.urls["电影片"])
    }

    @Test
    fun `二级分类同义名归一后列表只留一项且地址合并`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A", "B"),
            catsBySource = mapOf(
                "A" to listOf(cat("电视剧", "1"), cat("港剧", "7", parent = "电视剧")),
                "B" to listOf(cat("电视剧", "2"), cat("香港剧", "8", parent = "电视剧")),
            ),
        )
        // 下拉里不该同时出现「港剧」和「香港剧」
        assertEquals(listOf("香港剧"), plan.tree["电视剧"])
        // 但两个源各自的地址都得留着，查询时逐源用自己的那条
        assertEquals(
            listOf(CategorySource("A", "7"), CategorySource("B", "8")),
            plan.urls["香港剧"],
        )
    }

    @Test
    fun `空名与前后空白被忽略`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A"),
            catsBySource = mapOf(
                "A" to listOf(cat("", "1"), cat("  ", "2"), cat(" 电影 ", "3")),
            ),
        )
        assertEquals(listOf("电影"), plan.tree.keys.toList())
        assertEquals(listOf(CategorySource("A", "3")), plan.urls["电影"])
    }

    // ===== 查询目标解析 =====

    private val plan = VideoCategoryPlan.build(
        sourceOrder = listOf("A", "B"),
        catsBySource = mapOf(
            "A" to listOf(cat("电影", "2"), cat("动作片", "9", parent = "电影")),
            "B" to listOf(cat("电影", "7"), cat("动作片", "19", parent = "电影")),
        ),
    )

    @Test
    fun `类型与分类都为空等于全部且逐源不带地址`() {
        assertEquals(
            listOf(CategorySource("A", ""), CategorySource("B", "")),
            VideoCategoryPlan.targetsOf(plan, "", "", listOf("A", "B")),
        )
    }

    @Test
    fun `只选类型时逐源取该类型自身的地址`() {
        assertEquals(
            listOf(CategorySource("A", "2"), CategorySource("B", "7")),
            VideoCategoryPlan.targetsOf(plan, "电影", "", listOf("A", "B")),
        )
    }

    @Test
    fun `选中二级分类时逐源取各自该分类的地址`() {
        assertEquals(
            listOf(CategorySource("A", "9"), CategorySource("B", "19")),
            VideoCategoryPlan.targetsOf(plan, "电影", "动作片", listOf("A", "B")),
        )
    }

    @Test
    fun `不认识该分类的源被跳过`() {
        // A 认识「动作片」，C 完全没这个分类
        assertEquals(
            listOf(CategorySource("A", "9")),
            VideoCategoryPlan.targetsOf(plan, "电影", "动作片", listOf("A", "C")),
        )
    }

    @Test
    fun `不可用的源即便有该分类也不请求`() {
        assertEquals(
            emptyList<CategorySource>(),
            VideoCategoryPlan.targetsOf(plan, "电影", "动作片", listOf("C", "D")),
        )
    }

    @Test
    fun `未知分类返回空目标而不是回退到全部`() {
        assertTrue(
            VideoCategoryPlan.targetsOf(plan, "电影", "不存在的分类", listOf("A", "B")).isEmpty(),
        )
    }

    @Test
    fun `空计划不崩`() {
        val empty = VideoCategoryPlan.build(emptyList(), emptyMap())
        assertTrue(empty.tree.isEmpty())
        assertTrue(empty.urls.isEmpty())
        assertTrue(empty.typeUrls.isEmpty())
        assertEquals(
            listOf(CategorySource("A", "")),
            VideoCategoryPlan.targetsOf(empty, "", "", listOf("A")),
        )
        assertTrue(VideoCategoryPlan.targetsOf(empty, "电影", "", listOf("A")).isEmpty())
    }

    @Test
    fun `归位表覆盖各源常见叫法`() {
        // 一级叫法（别名）
        assertEquals("电影", VideoTypeMap.typeOf("电影片"))
        assertEquals("电影", VideoTypeMap.typeOf("4K电影"))
        assertEquals("电视剧", VideoTypeMap.typeOf("连续剧"))
        assertEquals("电视剧", VideoTypeMap.typeOf("电视剧场"))
        assertEquals("综艺", VideoTypeMap.typeOf("综艺片"))
        assertEquals("动漫", VideoTypeMap.typeOf("动漫片"))
        assertEquals("短剧", VideoTypeMap.typeOf("微短剧"))
        assertEquals("纪录片", VideoTypeMap.typeOf("记录片"))
        // 平铺源当一级返回的二级分类
        assertEquals("电影", VideoTypeMap.typeOf("动作片"))
        assertEquals("电视剧", VideoTypeMap.typeOf("美国剧"))
        assertEquals("电视剧", VideoTypeMap.typeOf("港台剧"))
        assertEquals("动漫", VideoTypeMap.typeOf("里番动漫"))
        assertEquals("体育", VideoTypeMap.typeOf("篮球"))
        assertEquals("资讯", VideoTypeMap.typeOf("头条"))
        // 非影视栏目不该被归位（否则又会塞满「类型」下拉）
        assertEquals(null, VideoTypeMap.typeOf("爱蜜社"))
        assertEquals(null, VideoTypeMap.typeOf("采集教程"))
        assertEquals(null, VideoTypeMap.typeOf(""))
    }
}
