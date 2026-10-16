package com.novelreader.core.util

import com.novelreader.domain.model.CategorySource
import com.novelreader.domain.model.ExploreCategory

/**
 * 小说 / 漫画首页「推荐」分区的**纯逻辑**（v1.7.1.0）。
 *
 * 与 [VideoCategoryPlan] 同思路（跨源把分类合并成一份视图），但**不做类型归位** ——
 * 小说 / 漫画的分类没有影视那套「电影 / 电视剧 / 综艺」的一级类型概念，
 * 各源的分类名（玄幻 / 都市 / 言情…）本身就是可展示的一行，直接按名字跨源合并即可。
 *
 * 产出的 [Plan.rows] 就是首页「推荐」页自上而下的若干横滑排：
 * - 第一排固定是**「推荐」**（把各源带推荐语义的分类汇在一起，见 [isRecommendLike]）；
 * - 后面每排是一个分类名，按**覆盖源数倒序**排列 —— 跨源通用的名字（玄幻 / 都市）
 *   排前面，只有一个源才有的长尾（本站独家栏目）沉底；
 * - 每排的目标数有上限（`maxSourcesPerRow`），避免一个热门分类聚合 30 个源、
 *   一排就打出几十个请求；**每排挑源按「被用得最少」轮转**，于是十来排下来
 *   能把内容摊到尽量多的源上，而不是每排都只吃同一批源。
 */
object HomeFeedPlan {

    /** 第一排的标题（推荐语义分类的合览排） */
    const val RECOMMEND_TITLE = "推荐"

    /** 兜底行的标题：普通栏目排排满后，还没挤进去的源（长尾 / 零分类源）收在这里 */
    const val EXTRA_SOURCES_TITLE = "更多源"

    /** 带这些词（不区分大小写）的分类名视为「推荐语义」，汇入第一排 */
    private val RECOMMEND_WORDS = listOf(
        "推荐", "热门", "排行", "精选", "榜单", "人气", "最新", "新书", "完结", "top",
    )

    /** 首页一排：标题 + 该排要聚合的 `(源, 该源该分类的源内地址)` */
    data class Row(
        val title: String,
        val targets: List<CategorySource>,
    )

    /**
     * @param rows 首页要展示的横滑排（已按顺序、已限量）
     * @param categories 全部栏目名（首项固定 [RECOMMEND_TITLE]，其余按覆盖源数倒序）——
     *   首页「栏目」下拉用，等同原「发现」页的栏目列表
     * @param urls 栏目名 → 各源该栏目的源内地址（选中具体栏目时据此聚合）
     */
    data class Plan(
        val rows: List<Row> = emptyList(),
        val categories: List<String> = emptyList(),
        val urls: Map<String, List<CategorySource>> = emptyMap(),
    )

    /** 分类名是否带「推荐 / 热门 / 排行」语义 */
    fun isRecommendLike(title: String): Boolean {
        val t = title.lowercase()
        return RECOMMEND_WORDS.any { t.contains(it) }
    }

    /**
     * 合并各源的分类，产出首页「推荐」页的横滑排。
     *
     * **每排选源用「用得最少优先」的轮转**（v1.7.2.0）：此前每排都取 `urls[name]` 的
     * 前 N 个，而覆盖源数最多的那几个分类（玄幻 / 都市…）前几名永远是同一批源，
     * 于是「聚合 N 个源」里 N 迟迟上不去、内容也全来自少数几个源。现在按「该源已被
     * 用过几次」升序挑源，第二排自然会去用第一排没用过的源，十来排下来覆盖面接近
     * 全部有分类的源。并列时按 [sourceOrder]（源管理器顺序）定先后，输出稳定。
     *
     * @param sourceOrder 存活 / 可用源 id，**顺序即优先级**（决定同一分类名下各源的先后）
     * @param catsBySource 各源的分类（来自 `exploreSources`）
     * @param maxRows 最多产出几排（含「推荐」排）
     * @param maxSourcesPerRow 每排最多聚合几个源（同一排里同一个源只出一次）
     */
    fun build(
        sourceOrder: List<String>,
        catsBySource: Map<String, List<ExploreCategory>>,
        maxRows: Int,
        maxSourcesPerRow: Int,
    ): Plan {
        if (maxRows <= 0 || maxSourcesPerRow <= 0) return Plan()

        /** 分类名 → 各源该分类的源内地址（保序）；**每个源每个栏目只留一条** */
        val urls = LinkedHashMap<String, MutableList<CategorySource>>()
        /** 归一化 key（去空白 + 小写）→ 展示标题（取首次出现的写法） */
        val titleOfKey = LinkedHashMap<String, String>()
        /** 归一化 key → 首次出现序（输出稳定，不受 map 迭代顺序影响） */
        val firstSeenOfKey = HashMap<String, Int>()
        /** "key|sourceId" 去重表 */
        val seenPairs = HashSet<String>()

        for (sid in sourceOrder) {
            for (cat in catsBySource[sid].orEmpty()) {
                // 归一化：legado 源常用「月 度 榜」「玄 幻」这种插空格的写法对齐展示，
                // 不去空白的话它们会被当成不同栏目，跨源合并就废了。
                val label = cat.title.filterNot { it.isWhitespace() }
                if (label.isEmpty()) continue
                val key = label.lowercase()
                if (key !in firstSeenOfKey) firstSeenOfKey[key] = firstSeenOfKey.size
                val title = titleOfKey.getOrPut(key) { label }
                // 同一个源对同一个栏目只保留第一条地址：不少源把「月 度 榜」按分站列了十几遍，
                // 不去重的话 urls[栏目].size 统计的是「声明次数」而不是「覆盖源数」，
                // 排序结果会把这种**假覆盖**当成最热栏目（实测：条目 30 / 真实源 2），
                // 于是首页头几排全被同一个源独占的榜单页占住、其它源根本排不上。
                if (!seenPairs.add("$key|$sid")) continue
                urls.getOrPut(title) { mutableListOf() }.add(CategorySource(sid, cat.url))
            }
        }
        val names = titleOfKey.values.toList()

        val orderOf = HashMap<String, Int>(sourceOrder.size)
        sourceOrder.forEachIndexed { i, sid -> if (sid !in orderOf) orderOf[sid] = i }
        /** 源 id → 已被几排用过（轮转的依据） */
        val usage = HashMap<String, Int>()

        /** 从候选里挑 [limit] 个源：同排同源只出一次；优先用「被用得最少」的源 */
        fun pick(candidates: List<CategorySource>, limit: Int): List<CategorySource> {
            if (candidates.isEmpty() || limit <= 0) return emptyList()
            val picked = candidates
                .distinctBy { it.sourceId }
                .sortedWith(
                    compareBy<CategorySource> { usage[it.sourceId] ?: 0 }
                        .thenBy { orderOf[it.sourceId] ?: Int.MAX_VALUE },
                )
                .take(limit)
            picked.forEach { usage[it.sourceId] = (usage[it.sourceId] ?: 0) + 1 }
            return picked
        }

        // ---- 第一排：把各源带推荐语义的分类汇在一起 ----
        val recommendPool = ArrayList<CategorySource>()
        for (name in names) {
            if (!isRecommendLike(name)) continue
            recommendPool.addAll(urls[name].orEmpty())
        }
        val recommendTargets = pick(recommendPool, maxSourcesPerRow)

        // ---- 其余排：按**覆盖源数**倒序（跨源通用名靠前），同档按首次出现序 ----
        // urls 里已按「每源每栏目一条」去过重，所以 size 就是真实覆盖源数。
        val restNames = names
            .filter { !isRecommendLike(it) }
            .sortedWith(
                compareByDescending<String> { urls[it]?.size ?: 0 }
                    .thenBy { firstSeenOfKey[it.lowercase()] ?: Int.MAX_VALUE },
            )

        val rows = ArrayList<Row>()
        if (recommendTargets.isNotEmpty()) {
            rows.add(Row(RECOMMEND_TITLE, recommendTargets))
        }
        for (name in restNames) {
            if (rows.size >= maxRows) break
            val targets = pick(urls[name].orEmpty(), maxSourcesPerRow)
            if (targets.isNotEmpty()) rows.add(Row(name, targets))
        }

        // 兜底：普通栏目排排满（maxRows）后，可能还有源没挤进任何排
        // （典型是长尾独有名栏目源 / 零分类源只能拿虚拟题材词入口，挤不进热门栏目）。
        // 直接收进「更多源」行，保证「聚合的源数」尽量贴近全部启用源——
        // 否则这些源在首页推荐页整源隐形，只能到栏目下拉里找（用户实测漫画源少一截的根因之一）。
        // 兜底行不参与栏目下拉（不写进 urls），且不受 maxRows 限制（否则又回到截断）。
        val covered = rows.flatMap { it.targets }.map { it.sourceId }.toSet()
        val leftover = sourceOrder.filter { it !in covered }
        var i = 0
        while (i < leftover.size) {
            val chunk = leftover.subList(i, minOf(i + maxSourcesPerRow, leftover.size))
            val targets = chunk.mapNotNull { sid ->
                catsBySource[sid]?.firstOrNull()?.let { CategorySource(sid, it.url) }
            }
            if (targets.isNotEmpty()) rows.add(Row(EXTRA_SOURCES_TITLE, targets))
            i += maxSourcesPerRow
        }

        // 「栏目」下拉：首项固定「推荐」（横滑合览），其余按覆盖源数倒序 —— 与原发现页一致。
        // 「推荐」语义的分类已经汇进第一排，不再单独作为栏目重复出现。
        val categories = ArrayList<String>()
        if (recommendTargets.isNotEmpty()) categories.add(RECOMMEND_TITLE)
        categories.addAll(restNames.filter { urls[it].orEmpty().isNotEmpty() })

        return Plan(
            rows = rows,
            categories = categories,
            urls = urls.mapValues { it.value.toList() },
        )
    }

    /**
     * 选中某个栏目时实际要打哪些 `(源, 栏目地址)`。
     *
     * - [category] 为空或等于 [RECOMMEND_TITLE] → 返回空（调用方走横滑排，不聚合单栏目）
     * - 否则取该栏目名下各源地址，最多 [maxSourcesPerRow] 个（按源优先级）
     */
    fun targetsOf(
        plan: Plan,
        category: String,
        maxSourcesPerRow: Int,
    ): List<CategorySource> {
        if (category.isBlank() || category == RECOMMEND_TITLE) return emptyList()
        return plan.urls[category].orEmpty().take(maxSourcesPerRow)
    }
}
