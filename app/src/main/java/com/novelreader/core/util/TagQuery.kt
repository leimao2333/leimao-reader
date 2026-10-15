package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem

/**
 * 标签查询纯逻辑（v1.5.10.0）。
 *
 * 标签搜索支持**单标签**与**复合标签**（标签之间用空格分开）：
 * - 单标签：直接取该标签聚合的内容；
 * - 复合标签：各标签分别聚合后**按标题求交集**（同一部作品同时出现在
 *   所有标签分类里才保留），实现「玄幻 完结」这类组合筛选。
 *
 * 纯函数无 Android 依赖，方便单测。
 */
object TagQuery {

    /**
     * 解析查询串为标签列表：按空白拆分、去空、去重、保序。
     *
     * 全角空格（U+3000，中文输入法常见）也按分隔符处理。
     */
    fun parse(query: String): List<String> =
        query.split(Regex("[\\s　]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * 按标签池解析查询串（v1.7.0.0）。
     *
     * [parse] 只用空白当分隔符，可**标签名本身就可能带空格** —— Venera 源的
     * 分类里有「英語 ENG」「Love Live」「SAO 刀劍神域」这类名字。点这种热门标签
     * 会拼出 `热血 英語 ENG` 这样的查询串，再按空白拆就散成三个不存在的标签，
     * 复合搜索于是永远查不到东西（用户看到的正是「复合搜索没作用」）。
     *
     * 这里在拆完之后做一次**最长合并**：相邻若干段拼起来如果正好是池里的标签，
     * 就当成一个标签。规则：
     * 1. 整串命中池里的标签 → 直接返回它（用户手输「恋爱 校园」而池里确实有这个名字时）；
     * 2. 否则从当前位置起，优先尝试「3 段 / 2 段」的合并，命中就取最长的那段；
     * 3. 都不命中就按单段处理 —— 池里没有的标签（用户手输新词）行为与 [parse] 一致。
     *
     * 池里同时存在「恋爱」与「恋爱 校园」时按最长匹配走（合并优先级更高），
     * 这是唯一能做到「点哪个是哪个」的口径。
     */
    fun parseWithPool(query: String, pool: Collection<String>): List<String> {
        val raw = query.split(Regex("[\\s　]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (raw.isEmpty()) return emptyList()
        // 1) 整串就是一个标签（含空格的标签被单独点选时最常见的情形）
        val whole = raw.joinToString(" ")
        if (pool.contains(whole)) return listOf(whole)

        val known = pool.toHashSet()
        val out = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            var merged: String? = null
            var consumed = 1
            val maxLen = minOf(MAX_MERGED_TOKENS, raw.size - i)
            for (len in maxLen downTo 2) {
                val candidate = raw.subList(i, i + len).joinToString(" ")
                if (candidate in known) {
                    merged = candidate
                    consumed = len
                    break
                }
            }
            if (merged != null) {
                out += merged
                i += consumed
            } else {
                out += raw[i]
                i += 1
            }
        }
        return out.distinct()
    }

    /** 合并解析最多把几段拼成一个标签（3 段足够覆盖「SAO 刀劍神域」这类名字） */
    private const val MAX_MERGED_TOKENS = 3

    /** 标题归一化：去所有空白 + 小写，用于跨标签/跨源匹配同一部作品 */
    fun normalizeTitle(title: String): String =
        title.filterNot { it.isWhitespace() }.lowercase()

    /**
     * 复合标签交集：[results] 与标签一一对应（每个元素是该标签聚合的内容列表），
     * 返回同时出现在**所有**标签里的作品（按第一个标签里的出现顺序）。
     *
     * 单标签（results.size == 1）时等价于原样返回。
     * 任一标签结果为空 → 交集为空（语义正确：没有作品同时属于两个分类）。
     */
    fun intersect(results: List<List<MediaItem>>): List<MediaItem> {
        if (results.isEmpty()) return emptyList()
        if (results.size == 1) return results[0]
        if (results.any { it.isEmpty() }) return emptyList()

        // 从最小的集合开始构建候选，减少查找表体积
        val ordered = results.sortedBy { it.size }
        val candidateKeys = ordered.first().mapTo(LinkedHashSet()) { keyOf(it) }
        for (i in 1 until ordered.size) {
            val present = ordered[i].mapTo(HashSet()) { keyOf(it) }
            candidateKeys.retainAll(present)
            if (candidateKeys.isEmpty()) return emptyList()
        }

        // 按第一个标签的原始顺序输出
        return results.first().filter { keyOf(it) in candidateKeys }
    }

    /** 交集匹配键：归一化标题（跨源同名作品视为同一部） */
    private fun keyOf(item: MediaItem): String = normalizeTitle(item.title)
}
