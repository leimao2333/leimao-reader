package com.novelreader.core.util

import com.novelreader.domain.model.CategorySource
import com.novelreader.domain.model.ExploreCategory

/**
 * 视频分类合并（v1.6.7.0）的**纯逻辑**。
 *
 * 把「各源自己的分类列表」合并成一份跨源视图：
 * - [Plan.tree]：规范**类型**名 → 其下**分类**名（保序、跨源按名合并去重）
 * - [Plan.urls]：分类名 → 各源该分类的**源内地址**
 * - [Plan.typeUrls]：类型名 → 各源**该类型自身**的源内地址（每源最多一条；
 *   「其他」没有统一地址，每源收前几条杂项）
 *
 * **两个关键点**
 *
 * 1. **身份必须是分类名**：苹果 CMS 的分类地址就是该源的 `type_id`
 *    （`1` / `2` / `9` …），只在**同一个源内部**唯一。聚合 40 多个源时，
 *    不同源的不同分类会撞同一个数字 —— 旧实现拿它当身份，于是出现
 *    「点 A 分类却出来 B 分类的内容」「下拉里两个分类同时打勾」
 *    「大标题显示成另一个分类的名字」这一连串问题。
 *
 * 2. **类型必须归位**：各源把「动作片」当一级还是二级并不一致，直接按
 *    `parent` 是否为空判定，会把 180 多个名字都当成类型。这里统一用
 *    [VideoTypeMap] 归位到 9 个规范类型，顺序固定，首页分区不再随源顺序乱跳；
 *    归不进去的（成人栏目 / 套图社 / 站内分区…）全部进**「其他」**兜底类型，
 *    按覆盖源数倒序排列，一个都不丢。
 */
object VideoCategoryPlan {

    /**
     * 「其他」类型下**每个源**最多收录几条杂项地址。
     *
     * 线上实测杂项分类高度集中：101 个未归位名字只来自 13 个源，最多的单个源
     * 占了 37 条。OkHttp 默认同 Host 并发 5，一次全打只会排队然后撞上 10s 的
     * 探测超时（白等还拿不全）。每源截 5 条刚好跑满并发额度、不排队；
     * **单个杂项分类仍可在二级下拉里精确选中**，下拉列表也永远列全，
     * 不受这个上限影响。
     */
    private const val MAX_OTHER_URLS_PER_SOURCE = 5

    /**
     * @param tree 规范类型名 → 二级分类名（保序）
     * @param urls 分类名 → 各源该分类的源内地址（保序，顺序即源优先级）
     * @param typeUrls 类型名 → 各源该类型自身的源内地址（每源最多一条，保序）
     */
    data class Plan(
        val tree: Map<String, List<String>> = emptyMap(),
        val urls: Map<String, List<CategorySource>> = emptyMap(),
        val typeUrls: Map<String, List<CategorySource>> = emptyMap(),
    )

    /**
     * 合并各源的分类树。
     *
     * @param sourceOrder 存活源 id，**顺序即优先级**（决定 [Plan.tree] 里二级分类的先后
     *   与 [Plan.urls] / [Plan.typeUrls] 里各源的先后）
     * @param catsBySource 各源的分类（含二级；来自 `exploreCategoryTree`）
     *
     * 规则：
     * - 每个分类（不论一级二级）都往 [Plan.urls] 里记一条 `(源, 该源地址)`；
     * - 每个名字用 [VideoTypeMap] 归位到规范类型；表里没有的再看它挂在哪个已知类型下；
     * - 仍未归类的名字**全部进「其他」**（v1.6.8.0，此前是直接丢弃）；
     * - 归到某类型、但**不是该类型别名**的名字，才是该类型的二级分类；
     * - [Plan.typeUrls] 每源每类型只取一条，优先规范名，其次该源里 parent 为空的别名；
     *   「其他」例外 —— 它没有统一的类型地址，每源取前 [MAX_OTHER_URLS_PER_SOURCE] 条杂项。
     */
    fun build(
        sourceOrder: List<String>,
        catsBySource: Map<String, List<ExploreCategory>>,
    ): Plan {
        val urls = LinkedHashMap<String, MutableList<CategorySource>>()
        /** 名字 → 父级名字（保序去重），用于「表里没有就跟着父级走」 */
        val parents = LinkedHashMap<String, LinkedHashSet<String>>()
        /** 名字 → 首次出现序，保证输出稳定 */
        val firstSeen = LinkedHashMap<String, Int>()

        for (sid in sourceOrder) {
            for (cat in catsBySource[sid].orEmpty()) {
                val raw = cat.title.trim()
                if (raw.isEmpty()) continue
                // 同义名先归一（港剧 → 香港剧），下拉里就不会出现两组近似项；
                // 地址表按规范名合并，查询仍逐源用自己那条地址。
                val title = VideoTypeMap.canonicalGenre(raw)
                if (title !in firstSeen) firstSeen[title] = firstSeen.size
                urls.getOrPut(title) { mutableListOf() }.add(CategorySource(sid, cat.url))

                val parent = VideoTypeMap.canonicalGenre(cat.parent)
                if (parent.isNotEmpty() && parent != title) {
                    parents.getOrPut(title) { LinkedHashSet() }.add(parent)
                }
            }
        }

        // 名字 → 规范类型（先查表，再跟着父级走；都查不到进「其他」兜底）
        val resolved = HashMap<String, String>()
        fun typeOfName(name: String): String = resolved.getOrPut(name) {
            VideoTypeMap.typeOf(name)
                ?: parents[name].orEmpty().firstNotNullOfOrNull { VideoTypeMap.typeOf(it) }
                ?: VideoTypeMap.OTHER
        }

        // 类型 → 二级分类名（保序去重；类型别名自身不算分类）
        val genres = VideoTypeMap.ORDER.associateWith { LinkedHashSet<String>() }
        for (name in firstSeen.keys) {
            val type = typeOfName(name)
            if (VideoTypeMap.isAliasOf(name, type)) continue
            genres[type]?.add(name)
        }

        // 「其他」是杂项抽屉：按**覆盖源数倒序**重排 —— 跨源通用的名字
        // （日本伦理 / 港台三级 / 两性课堂…）排前面，单个源的长尾（套图社…）沉底。
        // 未归类父级（视频一区…）的子分类在这里被拍平，各自成为可选中项。
        val otherNames: List<String> = firstSeen.keys
            .filter { typeOfName(it) == VideoTypeMap.OTHER }
            .sortedWith(
                compareByDescending<String> { urls[it]?.size ?: 0 }
                    .thenBy { firstSeen[it] ?: 0 },
            )
        genres[VideoTypeMap.OTHER]?.let { bucket ->
            bucket.clear()
            bucket.addAll(otherNames)
        }

        // 类型 → 各源「该类型自身」的地址：每源最多一条，优先规范名，其次其 parent 为空的别名。
        // 「其他」没有统一地址，把每源前几条杂项地址收进来当合览用（见 MAX_OTHER_URLS_PER_SOURCE）。
        val typeUrls = LinkedHashMap<String, MutableList<CategorySource>>()
        for (type in VideoTypeMap.ORDER) {
            if (type == VideoTypeMap.OTHER) {
                val perSource = LinkedHashMap<String, MutableList<CategorySource>>()
                for (name in otherNames) {
                    for (cs in urls[name].orEmpty()) {
                        val list = perSource.getOrPut(cs.sourceId) { mutableListOf() }
                        if (list.size < MAX_OTHER_URLS_PER_SOURCE) list.add(cs)
                    }
                }
                val picked = sourceOrder.mapNotNull { perSource[it] }.flatten().toMutableList()
                if (picked.isNotEmpty()) typeUrls[type] = picked
                continue
            }
            val picked = mutableListOf<CategorySource>()
            for (sid in sourceOrder) {
                val cats = catsBySource[sid].orEmpty()
                    .filter { it.title.trim().isNotEmpty() && VideoTypeMap.isAliasOf(it.title.trim(), type) }
                val hit = cats.firstOrNull { it.title.trim() == type }
                    ?: cats.firstOrNull { it.parent.isBlank() }
                    ?: cats.firstOrNull()
                    ?: continue
                picked.add(CategorySource(sid, hit.url))
            }
            if (picked.isNotEmpty()) typeUrls[type] = picked
        }

        // 只保留「有分类或有自身地址」的类型，顺序恒定为 VideoTypeMap.ORDER
        val tree = LinkedHashMap<String, List<String>>()
        for (type in VideoTypeMap.ORDER) {
            val list = genres[type].orEmpty().toList()
            if (list.isNotEmpty() || typeUrls.containsKey(type)) tree[type] = list
        }

        return Plan(
            tree = tree,
            urls = urls.mapValues { it.value.toList() },
            typeUrls = typeUrls,
        )
    }

    /**
     * 一次网格查询实际要打哪些 `(源, 分类地址)`。
     *
     * - [category] 非空 = 该二级分类：逐源用**自己那条**地址；不认识该分类的源直接跳过
     *   （旧实现拿某一个源的 id 硬问所有源，这正是内容张冠李戴的根源）；
     * - [category] 为空、[type] 非空 = 整个类型：逐源用该源「类型自身」的地址
     *   （「其他」没有统一地址，此时是每源前几条杂项地址的合览）；
     * - 两者都为空 = 全部：逐源**不带分类地址**拉最新。
     *
     * @param availableSources 当前可用源（顺序无关，返回顺序跟随 [Plan]）
     */
    fun targetsOf(
        plan: Plan,
        type: String,
        category: String,
        availableSources: List<String>,
    ): List<CategorySource> {
        if (category.isNotBlank()) {
            return plan.urls[category].orEmpty().filter { it.sourceId in availableSources }
        }
        if (type.isBlank()) {
            return availableSources.map { CategorySource(it, "") }
        }
        // 类型自身的地址；理论上一定有，万一调用方传的是「某源原始叫法」
        // （比如旧缓存里的行标题）则退化成「按名字当分类查」这条老路。
        val byType = plan.typeUrls[type].orEmpty()
        val pool = if (byType.isNotEmpty()) byType else plan.urls[type].orEmpty()
        return pool.filter { it.sourceId in availableSources }
    }
}
