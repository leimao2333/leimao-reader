package com.novelreader.core.util

import com.novelreader.domain.model.SourceInfo

/**
 * 「按分组搜索」的纯逻辑（v1.9.3.0）。
 *
 * 从搜索下拉组件里抽出来，一是让「分组 → 源 id 集合」的展开口径可以离线单测，
 * 二是避免组件里出现一堆只看得见 UI、测不到的规则。
 */
object SourceGroups {

    /**
     * 把源列表展开成「分组名 → 该组可用源 id 集合」，按分组名排序。
     *
     * 只收**已启用**且带分组名的源：
     * - 禁用源不该被选中，否则底层搜索会拿到一个永远没结果的 id；
     * - 组内一个可用源都没有时**不返回这个分组** —— 选了它会得到空 id 集合，
     *   而空集合在搜索里的语义是「全部源」，与用户「只搜这一组」的预期正好相反。
     *
     * 分组名统一 trim 后归一：源管理侧写入时已经 trim，这里再兜一次，
     * 防止老备份 / 手工改库带进前后空格导致同一个组被拆成两个。
     */
    fun of(sources: List<SourceInfo>): Map<String, Set<String>> {
        val grouped = LinkedHashMap<String, MutableSet<String>>()
        sources.forEach { source ->
            if (!source.enabled) return@forEach
            val name = source.groupName.trim()
            if (name.isEmpty()) return@forEach
            grouped.getOrPut(name) { LinkedHashSet() }.add(source.id)
        }
        return grouped.mapValues { (_, ids) -> ids.toSet() }.toSortedMap()
    }

    /**
     * 当前选中的源集合**恰好**等于某一组时返回组名，用于在下拉标题上显示「分组：X」。
     *
     * 要求完全相等（不是包含）：用户手动增减过源就不该再冒充「按某组搜索」。
     * 空集合代表「全部源」，不算任何一组。
     */
    fun matchingGroup(groups: Map<String, Set<String>>, selectedIds: Set<String>): String? =
        if (selectedIds.isEmpty()) null
        else groups.entries.firstOrNull { (_, ids) -> ids == selectedIds }?.key
}
