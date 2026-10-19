package com.novelreader

import com.novelreader.core.util.HomeFeedPlan
import com.novelreader.domain.model.ExploreCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小说 / 漫画首页「推荐」分区纯逻辑单测（v1.7.1.0；v1.7.2.0 增加轮转选源）。
 *
 * 五条核心不变式：
 * 1. **跨源同名栏目合并**，身份是**栏目名**（栏目地址只存在 `urls` 里，逐源各用各的）；
 * 2. **推荐语义的栏目汇进第一排**（各源「推荐 / 热门 / 排行 / 精选…」合览），
 *    且**同一排里同一个源只占一个名额**，不随源数线性膨胀；
 * 3. **其余排按覆盖源数倒序** —— 跨源通用栏目（玄幻 / 都市）靠前，单源长尾沉底；
 * 4. **排数与每排源数都有上限**，否则一个热门栏目聚合 20+ 源、一排就打几十个请求；
 * 5. **挑源按「被用得最少」轮转**（v1.7.2.0）—— 热门栏目的前几名不再永远是同一批源，
 *    十来排下来覆盖面接近全部有分类的源（这正是「聚合的源太少」的解法）。
 */
class HomeFeedPlanTest {

    private fun cat(title: String, url: String) = ExploreCategory(title = title, url = url)

    @Test
    fun `跨源同名栏目合并且保持源优先级顺序`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2"),
            catsBySource = mapOf(
                "S1" to listOf(cat("玄幻", "/x1")),
                "S2" to listOf(cat("玄幻", "/x2")),
            ),
            maxRows = 8,
            maxSourcesPerRow = 8,
        )
        assertEquals(listOf("玄幻"), plan.rows.map { it.title })
        // 地址逐源各用各的，顺序跟随 sourceOrder
        assertEquals(listOf("S1" to "/x1", "S2" to "/x2"), plan.rows[0].targets.map { it.sourceId to it.url })
        assertEquals(listOf("玄幻"), plan.categories)
    }

    @Test
    fun `推荐语义栏目汇成第一排且同排同源只出一个`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2"),
            catsBySource = mapOf(
                "S1" to listOf(cat("推荐", "/r1"), cat("热门推荐", "/r2"), cat("完结榜", "/r3")),
                "S2" to listOf(cat("精选", "/r4")),
            ),
            maxRows = 8,
            maxSourcesPerRow = 8,
        )
        assertEquals(HomeFeedPlan.RECOMMEND_TITLE, plan.rows.first().title)
        // S1 有三个推荐语义栏目，但同一排里一个源只占一个名额（各取首条），
        // 这样 8 个名额就能覆盖 8 个源，而不是被两个源吃满
        assertEquals(
            listOf("S1" to "/r1", "S2" to "/r4"),
            plan.rows.first().targets.map { it.sourceId to it.url },
        )
        // 推荐语义的栏目不再重复出现在其余排
        assertTrue(plan.rows.drop(1).none { HomeFeedPlan.isRecommendLike(it.title) })
    }

    @Test
    fun `每排按用得最少的源轮转_排数够时能把源用满`() {
        // 6 个源都声明同样 3 个栏目，每排只取 2 个源 → 3 排正好把 6 个源各用一次
        // （注意一排对应一个栏目名，所以要 3 个栏目才会有 3 排）
        val sources = (1..6).map { "S$it" }
        val cats = listOf(cat("玄幻", "/x"), cat("都市", "/y"), cat("科幻", "/z"))
        val plan = HomeFeedPlan.build(
            sourceOrder = sources,
            catsBySource = sources.associateWith { cats },
            maxRows = 3,
            maxSourcesPerRow = 2,
        )
        // 覆盖最广的栏目也不再每排都吃同一批源
        assertEquals(listOf("S1", "S2"), plan.rows[0].targets.map { it.sourceId })
        assertEquals(listOf("S3", "S4"), plan.rows[1].targets.map { it.sourceId })
        assertEquals(listOf("S5", "S6"), plan.rows[2].targets.map { it.sourceId })
        assertEquals(sources, plan.rows.flatMap { r -> r.targets.map { it.sourceId } })
    }

    @Test
    fun `轮转会把没被用过的源插到靠前位置`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2", "S3", "S4"),
            catsBySource = mapOf(
                "S1" to listOf(cat("玄幻", "/a1"), cat("都市", "/b1")),
                "S2" to listOf(cat("玄幻", "/a2"), cat("都市", "/b2")),
                "S3" to listOf(cat("玄幻", "/a3")),
                "S4" to listOf(cat("都市", "/b4")),
            ),
            maxRows = 2,
            maxSourcesPerRow = 2,
        )
        // 玄幻、都市两排之后，没轮到的 S3 收进「更多源」兜底行（v1.7.5.0 保证全覆盖）
        assertEquals(listOf("玄幻", "都市", HomeFeedPlan.EXTRA_SOURCES_TITLE), plan.rows.map { it.title })
        // 玄幻排用掉 S1、S2；都市排里 S4 还没被用过 → 排到第一位，
        // 即使它在 sourceOrder 里排在最后
        assertEquals(listOf("S4", "S1"), plan.rows[1].targets.map { it.sourceId })
        // 兜底行收进两排都没轮到的 S3
        assertEquals(listOf("S3"), plan.rows.last().targets.map { it.sourceId })
    }

    @Test
    fun `没有推荐语义栏目时第一排就是覆盖最广的普通栏目`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2", "S3"),
            catsBySource = mapOf(
                "S1" to listOf(cat("玄幻", "/a"), cat("冷门栏目", "/b")),
                "S2" to listOf(cat("玄幻", "/c")),
                "S3" to listOf(cat("玄幻", "/d")),
            ),
            maxRows = 8,
            maxSourcesPerRow = 8,
        )
        assertEquals(listOf("玄幻", "冷门栏目"), plan.rows.map { it.title })
        assertEquals(3, plan.rows[0].targets.size)
    }

    @Test
    fun `排数与每排源数都受上限约束`() {
        val cats = (1..10).map { cat("栏目$it", "/c$it") }
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2", "S3"),
            catsBySource = mapOf(
                "S1" to cats,
                "S2" to cats,
                "S3" to cats,
            ),
            maxRows = 3,
            maxSourcesPerRow = 2,
        )
        assertEquals(3, plan.rows.size)
        assertTrue(plan.rows.all { it.targets.size <= 2 })
    }

    @Test
    fun `栏目下拉包含推荐项且不含空栏目`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1"),
            catsBySource = mapOf(
                "S1" to listOf(cat("推荐", "/r"), cat("  ", "/blank"), cat("玄幻", "/x")),
            ),
            maxRows = 8,
            maxSourcesPerRow = 8,
        )
        assertEquals(listOf(HomeFeedPlan.RECOMMEND_TITLE, "玄幻"), plan.categories)
    }

    @Test
    fun `targetsOf 对推荐项返回空_对具体栏目返回逐源地址`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2"),
            catsBySource = mapOf(
                "S1" to listOf(cat("推荐", "/r1"), cat("玄幻", "/x1")),
                "S2" to listOf(cat("玄幻", "/x2")),
            ),
            maxRows = 8,
            maxSourcesPerRow = 8,
        )
        // 「推荐」走横滑排，不做单栏目聚合
        assertTrue(HomeFeedPlan.targetsOf(plan, HomeFeedPlan.RECOMMEND_TITLE, 8).isEmpty())
        assertTrue(HomeFeedPlan.targetsOf(plan, "", 8).isEmpty())
        assertEquals(
            listOf("S1" to "/x1", "S2" to "/x2"),
            HomeFeedPlan.targetsOf(plan, "玄幻", 8).map { it.sourceId to it.url },
        )
        // 不认识的栏目名 → 空（而不是拿别的源地址硬问）
        assertTrue(HomeFeedPlan.targetsOf(plan, "不存在的栏目", 8).isEmpty())
    }

    @Test
    fun `识别推荐语义关键词`() {
        assertTrue(HomeFeedPlan.isRecommendLike("推荐"))
        assertTrue(HomeFeedPlan.isRecommendLike("热门小说"))
        assertTrue(HomeFeedPlan.isRecommendLike("排行榜"))
        assertTrue(HomeFeedPlan.isRecommendLike("TOP100"))
        assertTrue(!HomeFeedPlan.isRecommendLike("玄幻"))
        assertTrue(!HomeFeedPlan.isRecommendLike(""))
    }

    /**
     * 回归（v1.7.2.0 实测 bug）：排序必须按**覆盖源数**，不能按「条目数」。
     *
     * 真实数据：晋江文学一个源就把「月 度 榜」列了 15 遍（按分站各列一次），
     * 于是 `urls["月度榜"].size == 30` 而真实覆盖源只有 2 个；反过来「科幻」
     * 有 16 个源共用、条目数 29。按条目数排序的结果是首页头几排全被晋江的
     * 榜单页占满（那些页面取回 0 条内容），真正跨源共有的科幻 / 玄幻被挤到后面。
     */
    @Test
    fun `同一源重复声明的栏目按真实覆盖源数排序`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2", "S3"),
            catsBySource = mapOf(
                // S1 把「月度榜」重复声明 10 次（条目数 10），但只有 1 个源
                "S1" to List(10) { cat("月 度 榜", "/m$it") },
                "S2" to listOf(cat("科幻", "/s2")),
                "S3" to listOf(cat("科幻", "/s3")),
            ),
            maxRows = 8,
            maxSourcesPerRow = 8,
        )
        // 「科幻」覆盖 2 个源 > 「月度榜」覆盖 1 个源
        assertEquals(listOf("科幻", "月度榜"), plan.rows.map { it.title })
        // 月度榜只剩 S1 一条地址（重复声明被去掉）
        assertEquals(listOf("S1" to "/m0"), plan.rows[1].targets.map { it.sourceId to it.url })
    }

    /** 回归：legado 源爱写「月 度 榜」这种插空格的标题，去空白后必须与「月度榜」合并 */
    @Test
    fun `栏目标题去空白后跨源合并`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2"),
            catsBySource = mapOf(
                "S1" to listOf(cat("月 度 榜", "/a")),
                "S2" to listOf(cat("月度榜", "/b")),
            ),
            maxRows = 8,
            maxSourcesPerRow = 8,
        )
        assertEquals(listOf("月度榜"), plan.rows.map { it.title })
        assertEquals(2, plan.rows[0].targets.size)
        assertEquals(listOf("月度榜"), plan.categories)
    }

    @Test
    fun `空输入与非法上限返回空计划`() {
        assertTrue(HomeFeedPlan.build(emptyList(), emptyMap(), 8, 8).rows.isEmpty())
        assertTrue(
            HomeFeedPlan.build(listOf("S1"), mapOf("S1" to listOf(cat("玄幻", "/x"))), 0, 8).rows.isEmpty(),
        )
        assertTrue(
            HomeFeedPlan.build(listOf("S1"), mapOf("S1" to listOf(cat("玄幻", "/x"))), 8, 0).rows.isEmpty(),
        )
    }

    /**
     * 兜底行（v1.7.5.0）：普通栏目排被 [maxRows] 截断后，还留在长尾 / 零分类的源
     * 不能整源隐形，要在「更多源」兜底行里露面。
     *
     * 3 个源：S1 有热门栏目「玄幻」；S2 / S3 只有各自的独有名栏目。maxRows=1 只够排
     * 「玄幻」一行，S2 / S3 进兜底行，于是三个源全部覆盖。
     */
    @Test
    fun `长尾源收进更多源兜底行保证全覆盖`() {
        val plan = HomeFeedPlan.build(
            sourceOrder = listOf("S1", "S2", "S3"),
            catsBySource = mapOf(
                "S1" to listOf(cat("玄幻", "/x")),
                "S2" to listOf(cat("独家栏目A", "/a")),
                "S3" to listOf(cat("独家栏目B", "/b")),
            ),
            maxRows = 1,
            maxSourcesPerRow = 2,
        )
        // 没有推荐语义栏目，第一排是覆盖最广的热门栏目，最后多出一个兜底行
        assertEquals("玄幻", plan.rows.first().title)
        assertEquals(HomeFeedPlan.EXTRA_SOURCES_TITLE, plan.rows.last().title)
        // 三个源全部覆盖（S2 / S3 在兜底行）
        assertEquals(
            setOf("S1", "S2", "S3"),
            plan.rows.flatMap { r -> r.targets.map { it.sourceId } }.toSet(),
        )
        // 兜底行不污染栏目下拉
        assertTrue(plan.categories.none { it == HomeFeedPlan.EXTRA_SOURCES_TITLE })
    }

    /**
     * 多个兜底行时标题必须互不相同（v1.8.2.0）。
     *
     * leftover 超过一排容量后会拆成好几排，但它们以前全叫「更多源」；首页拿排标题当
     * LazyColumn 的 item key，撞名直接抛 `IllegalArgumentException` —— 用户滑到
     * 首页底部必闪退。这里锁住「编号递增且唯一」。
     */
    @Test
    fun `多个兜底行标题互不相同`() {
        // 8 个互不相同的独有名栏目、每排只带 2 个源，maxRows=1 只够排一个普通栏目，
        // 剩下的源全部进兜底行，于是必然产出多条「更多源」。
        val sources = (1..8).map { "S$it" }
        val plan = HomeFeedPlan.build(
            sourceOrder = sources,
            catsBySource = sources.associateWith { listOf(cat("独家$it", "/$it")) },
            maxRows = 1,
            maxSourcesPerRow = 2,
        )
        val extraTitles = plan.rows
            .map { it.title }
            .filter { it.startsWith(HomeFeedPlan.EXTRA_SOURCES_TITLE) }
        assertTrue("应产生多条兜底行，实际 $extraTitles", extraTitles.size >= 2)
        assertEquals(HomeFeedPlan.EXTRA_SOURCES_TITLE, extraTitles.first())
        assertEquals("更多源 2", extraTitles[1])
        // 唯一性才是 Compose key 不崩的关键
        assertEquals(
            "兜底行标题重复会导致首页滑到底闪退：$extraTitles",
            extraTitles.size,
            extraTitles.distinct().size,
        )
    }
}
