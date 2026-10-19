package com.novelreader.core.util

/**
 * 标签命中的排序与求交（v1.8.1.3）。
 *
 * 索引查询返回的是「每条作品在本组里的最高置信度」，复合查询要在**条目级别**求交
 * （等价 nested must），同时把置信度带进排序 —— 这正是待办里「按置信度加权排序」
 * 那一项：标签命中不再只是布尔值，站方 100 分 > 行为加权后的文本 75 分 > 纯文本 60 分。
 *
 * 纯函数无 Android 依赖，方便单测。
 */
data class TagHit(
    val sourceId: String,
    val itemId: String,
    /** 该作品在这一组里的最高置信度（0~100） */
    val score: Int = 0,
)

object TagRanking {

    /**
     * 组间 AND：各组命中集合求交。
     *
     * - **得分**取该作品在各组里的**最小值**（短板证据）：一组是站方 100、另一组只是
     *   文本 60，说明第二组证据弱，不该按 100 排到最前面；
     * - 输出按得分**降序**，得分相同时保持第一组的原始顺序（结果稳定可复现）；
     * - 任一组为空 → 交集为空。
     */
    fun intersect(groups: List<List<TagHit>>): List<TagHit> {
        if (groups.isEmpty()) return emptyList()
        if (groups.any { it.isEmpty() }) return emptyList()
        if (groups.size == 1) return groups[0].sortedByDescending { it.score }

        val maps = groups.map { hits ->
            val map = LinkedHashMap<Pair<String, String>, Int>(hits.size)
            hits.forEach { hit ->
                val key = hit.sourceId to hit.itemId
                val old = map[key]
                // 同一组内重复命中时取最高分（DAO 已 GROUP BY，这里只是兜底）
                map[key] = if (old == null || hit.score > old) hit.score else old
            }
            map
        }

        val out = ArrayList<TagHit>()
        maps.first().forEach { (key, score) ->
            var best = score
            var ok = true
            for (i in 1 until maps.size) {
                val other = maps[i][key]
                if (other == null) {
                    ok = false
                    break
                }
                if (other < best) best = other
            }
            if (ok) out += TagHit(key.first, key.second, best)
        }
        return out.sortedByDescending { it.score }
    }
}
