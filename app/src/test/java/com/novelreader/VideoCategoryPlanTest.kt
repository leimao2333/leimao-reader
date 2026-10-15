package com.novelreader

import com.novelreader.core.util.VideoCategoryPlan
import com.novelreader.core.util.VideoTypeMap
import com.novelreader.domain.model.CategorySource
import com.novelreader.domain.model.ExploreCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频分类合并纯逻辑单测（v1.6.7.0；v1.6.8.0 补「其他」兜底类型）。
 *
 * 三条核心不变式：
 * 1. **分类的身份是「分类名」，不是源内地址** —— 苹果 CMS 的地址是 `type_id`
 *    （1/2/9…），跨源必然撞号；身份用错就会出现「点 A 分类出 B 内容」
 *    「两个分类同时打勾」「标题显示错」。
 * 2. **类型必须归位且顺序固定** —— 各源把「动作片」当一级还是二级并不一致，
 *    直接按 `parent` 判类型会把 180 多个名字（含套图社 / 成人栏目 / 站内公告）
 *    全塞进「类型」下拉，而且首页分区会随源顺序乱跳。
 * 3. **归不进去的一个都不丢** —— 全部进「其他」兜底类型（v1.6.8.0），
 *    按覆盖源数倒序；合览时每源最多 5 条，单个分类精确选中不受限。
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
    fun `未归类名字全部进其他而不是被丢弃`() {
        // v1.6.8.0：用户明确要求「没包含的都加进去」。套图社 / 成人栏目 / 站内公告
        // 都收进「其他」，按覆盖源数倒序（跨源通用的排前面）
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A", "B"),
            catsBySource = mapOf(
                "A" to listOf(cat("爱蜜社", "7"), cat("成人笑话", "8"), cat("采集教程", "9")),
                "B" to listOf(cat("成人笑话", "18")),
            ),
        )
        assertEquals(listOf("其他"), plan.tree.keys.toList())
        // 「成人笑话」两个源都有排最前，其余按首次出现序
        assertEquals(listOf("成人笑话", "爱蜜社", "采集教程"), plan.tree["其他"])
        // 地址表按名合并、逐源各留各的
        assertEquals(
            listOf(CategorySource("A", "8"), CategorySource("B", "18")),
            plan.urls["成人笑话"],
        )
    }

    @Test
    fun `其他合览每源最多五条杂项地址`() {
        // 线上实测：101 个杂项名字只来自 13 个源，最多的单源占 37 条。
        // OkHttp 同 Host 并发 5，全打只会排队撞 10s 超时 → 每源截 5 条
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A", "B"),
            catsBySource = mapOf(
                "A" to (1..8).map { cat("杂项$it", "$it") },
                "B" to listOf(cat("爱蜜社", "7")),
            ),
        )
        // 合览：A 只取前 5 条（源的声明顺序），B 取 1 条
        assertEquals(6, plan.typeUrls["其他"]?.size)
        assertTrue(plan.typeUrls["其他"]!!.take(5).all { it.sourceId == "A" })
        assertEquals(CategorySource("B", "7"), plan.typeUrls["其他"]!!.last())
        // 但下拉里名字全都列得出来（A 的 8 个杂项 + B 的「爱蜜社」= 9），
        // 单个精确选中不受上限影响
        assertEquals(9, plan.tree["其他"]?.size)
        assertEquals(
            listOf(CategorySource("A", "8")),
            VideoCategoryPlan.targetsOf(plan, "其他", "杂项8", listOf("A", "B")),
        )
    }

    @Test
    fun `未归类父级及其子分类都进其他`() {
        // 「视频一区」这种站内结构父级也查不到归属，父子一起拍平进「其他」，
        // 各自成为可选中项（父级在苹果 CMS 里本身就是可查询地址）
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A"),
            catsBySource = mapOf(
                "A" to listOf(
                    cat("视频一区", "50"),
                    cat("日本有码", "51", parent = "视频一区"),
                    cat("中文字幕", "52", parent = "视频一区"),
                ),
            ),
        )
        assertEquals(listOf("视频一区", "日本有码", "中文字幕"), plan.tree["其他"])
        // 合览三条都在（未超每源 5 条上限）
        assertEquals(3, plan.typeUrls["其他"]?.size)
    }

    @Test
    fun `只选其他时逐源取杂项地址且不可用的源被跳过`() {
        val plan = VideoCategoryPlan.build(
            sourceOrder = listOf("A", "B"),
            catsBySource = mapOf(
                "A" to listOf(cat("爱蜜社", "7"), cat("秀人网", "8")),
                "B" to listOf(cat("福利", "9")),
            ),
        )
        assertEquals(
            listOf(CategorySource("A", "7"), CategorySource("A", "8"), CategorySource("B", "9")),
            VideoCategoryPlan.targetsOf(plan, "其他", "", listOf("A", "B")),
        )
        assertEquals(
            listOf(CategorySource("B", "9")),
            VideoCategoryPlan.targetsOf(plan, "其他", "", listOf("B", "C")),
        )
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
        // 「福利」归不进规范类型，进「其他」兜底，但不会被预告片吞掉
        assertEquals(listOf("预告片", "其他"), plan.tree.keys.toList())
        assertEquals(listOf("福利"), plan.tree["其他"])
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
        // 非影视栏目不该被归位到规范类型（进「其他」兜底，见 build 的测试）
        assertEquals(null, VideoTypeMap.typeOf("爱蜜社"))
        assertEquals(null, VideoTypeMap.typeOf("采集教程"))
        assertEquals(null, VideoTypeMap.typeOf(""))
        // 「其他」是兜底类型，恒排在 9 个规范类型之后
        assertEquals("其他", VideoTypeMap.ORDER.last())
        assertEquals(10, VideoTypeMap.ORDER.size)
    }
}
