package com.novelreader.core.util

/**
 * 「全部源」搜索时的同站点限流（v1.9.4.0）。
 *
 * 真机实测：库里 2434 个已启用小说源，其中同一个站点被反复导入了几十个变体
 * （同名不同版本、不同子域、规则新旧不一）。它们会一起排进搜索队列，把 6 路并发
 * 和 45 秒整体时限全部吃光 —— 实测 45 秒只问到 159 个源，剩下 2275 个根本没机会跑，
 * 用户看到的就是「搜半天只有几条，然后一直在加载」。
 *
 * 做法：**仅在用户没有明确指定源**（sourceIds == null，即「全部源」）时生效，
 * 按站点根域分组，每个站点只保留规则最完整的若干个源。用户手动选了单源 / 多选 /
 * 分组时完全不干预，避免「我明明选了它却没搜」。
 *
 * 纯逻辑、泛型化，便于离线单测。
 */
object SourceSiteLimiter {

    /**
     * 按站点**交错**排序，不删任何源（v1.9.4.0 默认策略）。
     *
     * 单纯截断同站变体有风险：谁的「规则更完整」不等于谁能出结果，实测把能出结果的
     * 老变体挤掉后，结果从 4 条变 0 条。所以默认改为只调顺序 ——
     * 第 1 轮先问每个站点的最优源，第 2 轮再问各站的第 2 个……这样即便整体时限到了，
     * 也已经覆盖过尽可能多的**不同站点**，而不是把前几分钟全耗在少数站点的变体上。
     */
    fun <T> interleave(
        sources: List<T>,
        siteOf: (T) -> String,
        qualityOf: (T) -> Int = { 0 },
    ): List<T> {
        if (sources.isEmpty()) return sources
        val groups = LinkedHashMap<String, MutableList<IndexedValue<T>>>()
        sources.forEachIndexed { index, item ->
            val site = siteOf(item).trim().lowercase()
            val key = site.ifEmpty { "__no_site_$index" }
            groups.getOrPut(key) { mutableListOf() }.add(IndexedValue(index, item))
        }
        val sortedGroups = groups.values.map { members ->
            members.sortedWith(
                compareByDescending<IndexedValue<T>> { qualityOf(it.value) }
                    .thenBy { it.index },
            )
        }
        val out = ArrayList<T>(sources.size)
        var round = 0
        while (out.size < sources.size) {
            var added = false
            sortedGroups.forEach { group ->
                if (round < group.size) {
                    out.add(group[round].value)
                    added = true
                }
            }
            if (!added) break
            round++
        }
        return out
    }

    /**
     * @param perSite 每个站点最多保留几个源；<= 0 表示不限流
     * @param siteOf  返回站点根域；空串表示该源没有可判定站点（如本地源、Venera 源），单独成组全保留
     * @param qualityOf 同站内的保留优先级（值大者优先，例如源规则原文长度）
     */
    fun <T> limit(
        sources: List<T>,
        perSite: Int,
        siteOf: (T) -> String,
        qualityOf: (T) -> Int = { 0 },
    ): List<T> {
        if (perSite <= 0 || sources.isEmpty()) return sources

        val groups = LinkedHashMap<String, MutableList<IndexedValue<T>>>()
        sources.forEachIndexed { index, item ->
            val site = siteOf(item).trim().lowercase()
            // 没有站点的源必须各自独立成组，不能互相挤掉
            val key = site.ifEmpty { "__no_site_$index" }
            groups.getOrPut(key) { mutableListOf() }.add(IndexedValue(index, item))
        }

        val keepIndices = HashSet<Int>(sources.size)
        groups.values.forEach { members ->
            val kept = if (members.size <= perSite) {
                members
            } else {
                members.sortedWith(
                    compareByDescending<IndexedValue<T>> { qualityOf(it.value) }
                        .thenBy { it.index }, // 质量相同时保持原有（优先级）顺序，结果稳定
                ).take(perSite)
            }
            kept.forEach { keepIndices.add(it.index) }
        }
        // 输出顺序与输入一致，不打乱源自身的优先级
        return sources.filterIndexed { index, _ -> index in keepIndices }
    }
}
