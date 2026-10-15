package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem

/**
 * 复合搜索的纯决策逻辑（v1.7.0.0）。
 *
 * **为什么推倒重来**：v1.5.10.0 的复合搜索是「各标签分别聚合后按标题求**全局**交集」。
 * 实际跑下来基本查不到东西，因为交集要求同一部作品同时出现在两个标签的结果里，
 * 而两组结果来自**不同的源、不同的一页**：
 * - 苹果 CMS 采集站的每部片子只属于一个 `type_id`，动作片第一页与科幻片第一页
 *   天生没有交集；
 * - 漫画源以前绑的是「按标题搜索」，两个题材词都要出现在标题里更是天方夜谭。
 *
 * 现在的口径是**同源交集**：先只保留「每个选中标签都有入口」的源（跨源同名作品
 * 本来就不是同一部帖子，算进来只是噪声），再在每个源内部求交集 ——
 * 源自己的分类体系下，一部作品可以同时属于多个标签，这才是「复合筛选」的本意。
 * 同一页找不到时由调用方翻到第 2 页继续找（见 `TagSearchViewModel`）。
 *
 * 纯函数无 Android 依赖，方便单测。
 */
object TagCompound {

    /**
     * 参与的源：**同时拥有全部标签入口**的源。
     *
     * 输出顺序 = 第一个标签的入口顺序（标签池按收集顺序存，源自带的手工排序会体现出来），
     * 保证同样的输入永远得到同样的源顺序，结果稳定、可复现。
     *
     * 只有一个标签时返回该标签的全部源（等价于单标签搜索的源集合）。
     */
    fun participants(entriesPerTag: List<List<Pair<String, String>>>): List<String> {
        if (entriesPerTag.isEmpty()) return emptyList()
        if (entriesPerTag.size == 1) return entriesPerTag[0].map { it.first }.distinct()
        val rest = entriesPerTag.drop(1)
            .map { list -> list.mapTo(HashSet()) { it.first } }
        return entriesPerTag[0].map { it.first }.distinct()
            .filter { src -> rest.all { src in it } }
    }

    /**
     * 同源交集：`byTag[i]` 是第 i 个标签的「源 → 该源这一页的结果」。
     *
     * 返回**同一源内**同时出现在所有标签结果里的作品（按标题归一化匹配），
     * 源之间按 [byTag] 第一个标签里的源顺序拼接、源内保持原顺序。
     * 任一标签在该源没有结果 → 该源不产出（源内部不成立交集）。
     */
    fun intersectBySource(byTag: List<Map<String, List<MediaItem>>>): List<MediaItem> {
        if (byTag.isEmpty()) return emptyList()
        val out = mutableListOf<MediaItem>()
        for ((sourceId, firstList) in byTag[0]) {
            if (firstList.isEmpty()) continue
            var keys = firstList.mapTo(LinkedHashSet()) { TagQuery.normalizeTitle(it.title) }
            var ok = true
            for (i in 1 until byTag.size) {
                val list = byTag[i][sourceId]
                if (list.isNullOrEmpty()) {
                    ok = false
                    break
                }
                val present = list.mapTo(HashSet()) { TagQuery.normalizeTitle(it.title) }
                keys = keys.retainAllTo(present)
                if (keys.isEmpty()) {
                    ok = false
                    break
                }
            }
            if (!ok) continue
            firstList.filterTo(out) { TagQuery.normalizeTitle(it.title) in keys }
        }
        return out
    }

    /** 合并多页结果，按 `sourceId + 条目 id` 去重、保序 */
    fun merge(lists: List<List<MediaItem>>): List<MediaItem> {
        val seen = HashSet<String>()
        val out = mutableListOf<MediaItem>()
        lists.forEach { list ->
            list.forEach { item ->
                if (seen.add(item.sourceId + "\u0000" + item.id)) out += item
            }
        }
        return out
    }

    /**
     * 保序求交：Kotlin 的 `retainAll` 虽也是保序的，但这里显式构造新集合，
     * 避免调用方误以为改的是原集合。
     */
    private fun LinkedHashSet<String>.retainAllTo(other: Set<String>): LinkedHashSet<String> =
        LinkedHashSet<String>().also { kept -> this.forEach { if (it in other) kept.add(it) } }
}
