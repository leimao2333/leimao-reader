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
        splitLogicalTags(query)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** 百分号编码的 `%20` 及 URL 表单编码的 `+` 也作为标签分隔符。 */
    private fun splitLogicalTags(query: String): List<String> {
        val hasPercentEncoding = Regex("%[0-9a-fA-F]{2}").containsMatchIn(query)
        val withDecodedSeparators = query.replace(Regex("(?i)%20"), " ")
        val normalized = if (hasPercentEncoding) withDecodedSeparators.replace('+', ' ') else withDecodedSeparators
        return normalized.split(Regex("[\\s　]+"))
    }

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
