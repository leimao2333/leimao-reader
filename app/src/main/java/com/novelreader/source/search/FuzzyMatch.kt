package com.novelreader.source.search

import com.novelreader.domain.model.MediaItem
import kotlin.math.max

/**
 * 搜索结果模糊匹配与排序工具。
 *
 * 搜索源返回的结果往往与关键词不完全一致，本工具对结果做本地相似度评分，
 * 优先展示与关键词最匹配的条目，实现"模糊搜索"体验。
 */
object FuzzyMatch {

    /**
     * 计算字符串与关键词的相似度得分（0~100）。
     *
     * 评分策略（从高到低）：
     * 1. 完全相等 → 100
     * 2. 标题以关键词开头 → 90
     * 3. 标题包含关键词 → 80 + 关键词长度权重
     * 4. 编辑距离相似度（Levenshtein）→ 按相似度映射
     * 5. 无任何匹配 → 0
     */
    fun score(title: String, keyword: String): Int {
        val t = title.trim()
        val k = keyword.trim()
        if (t.isEmpty() || k.isEmpty()) return 0

        val tl = t.lowercase()
        val kl = k.lowercase()

        if (tl == kl) return 100
        if (tl.startsWith(kl)) return 90
        if (tl.contains(kl)) return 80 + minOf(kl.length * 2, 18)

        // 编辑距离相似度
        val dist = levenshtein(tl, kl)
        val maxLen = max(tl.length, kl.length)
        if (maxLen == 0) return 0
        val similarity = 1.0 - dist.toDouble() / maxLen
        // 相似度映射到 1~60 分（相似度 0.5 以上才给分）
        return if (similarity >= 0.5) (similarity * 60).toInt() else 0
    }

    /**
     * 对搜索结果按模糊匹配度降序排序，并过滤掉完全不相关的条目。
     *
     * @param items 搜索结果
     * @param keyword 搜索关键词
     * @param minScore 最低匹配分（低于此分的条目被过滤）
     */
    fun rank(items: List<MediaItem>, keyword: String, minScore: Int = 0): List<MediaItem> {
        if (keyword.isBlank()) return items
        return items
            .map { it to score(it.title, keyword) }
            .filter { (_, s) -> s >= minScore }
            .sortedByDescending { (_, s) -> s }
            .map { (item, _) -> item }
    }

    /**
     * 关键词是否与标题"模糊相关"（宽松判定，用于兜底不过滤）。
     */
    fun isRelated(title: String, keyword: String): Boolean =
        score(title, keyword) > 0

    /**
     * 计算两个字符串的编辑距离（Levenshtein）。
     */
    fun levenshtein(a: String, b: String): Int {
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la

        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)

        for (i in 1..la) {
            curr[0] = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // 删除
                    curr[j - 1] + 1,   // 插入
                    prev[j - 1] + cost // 替换
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[lb]
    }
}
