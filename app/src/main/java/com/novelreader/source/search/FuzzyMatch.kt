package com.novelreader.source.search

import com.novelreader.domain.model.MediaItem
import kotlin.math.max

/**
 * 搜索结果匹配 / 排序 / 分组工具。
 *
 * 两种搜索模式：
 * - **精准搜索**（fuzzy=false）：只保留书名与关键词在归一化后完全一致的结果，
 *   不相关的噪音一律过滤。
 * - **模糊搜索**（fuzzy=true）：本地相似度评分排序，但收紧匹配阈值——
 *   完全不相关（score=0）的结果被过滤，避免"模糊"变成"无关"。
 *
 * 另外提供 [groupDuplicates]：把「书名 + 作者一致」的多源结果折叠成组，
 * 供搜索结果页聚合展示与换源。
 */
object FuzzyMatch {

    /** 相似度评分档位（供 UI 展示与调试） */
    const val SCORE_EXACT = 100
    const val SCORE_NORM_EXACT = 98
    const val SCORE_PREFIX = 90
    const val SCORE_CONTAINS_BASE = 80
    const val SCORE_AUTHOR_BONUS = 5
    const val MIN_FUZZY_SCORE = 30

    /**
     * 归一化：去空白、书名号、常见标点，转小写，全角转半角。
     * 用于「精准」判定与分组 key，避免《剑来》/剑 来/剑来 判为不同书。
     */
    fun normalize(s: String): String {
        return normalizeRaw(s, stripAuthorPrefix = false)
    }

    /**
     * 作者归一化：在 [normalize] 基础上，额外去掉来源前缀（「番茄小说」「笔趣阁」等）
     * 及连接符，使「番茄小说 · 某某」「某某」判为同一作者，正确合并同名书。
     *
     * 策略：归一化后，若以来源前缀开头（后跟分隔符），则截去前缀。
     */
    fun normalizeAuthor(s: String): String {
        return normalizeRaw(s, stripAuthorPrefix = true)
    }

    private fun normalizeRaw(s: String, stripAuthorPrefix: Boolean): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c.isWhitespace() -> {}
                c == '《' || c == '》' || c == '“' || c == '”' || c == '"' ||
                    c == '「' || c == '」' || c == '『' || c == '』' -> {}
                c == '：' || c == ':' || c == '，' || c == ',' || c == '。' ||
                    c == '·' || c == '!' || c == '！' || c == '?' || c == '？' -> {}
                else -> {
                    // 全角字母数字转半角
                    val code = c.code
                    if (code in 0xFF01..0xFF5E) sb.append((code - 0xFEE0).toChar())
                    else sb.append(c.lowercaseChar())
                }
            }
        }
        val normalized = sb.toString()
        if (!stripAuthorPrefix) return normalized
        // 去掉来源前缀：归一化后标点已去除，形如「番茄小说某某」。
        // 若已知前缀在开头出现，截去前缀，剩余即作者名。
        for (prefix in AUTHOR_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                val rest = normalized.substring(prefix.length)
                if (rest.isNotEmpty()) return rest
            }
        }
        return normalized
    }

    /** 常见来源前缀（归一化后），用于作者归一化去前缀合并。 */
    private val AUTHOR_PREFIXES = listOf(
        "番茄小说", "番茄", "笔趣阁",
        "起点中文网", "起点", "飞卢小说", "飞卢", "纵横中文网", "纵横",
        "17k小说网", "17k", "晋江文学城", "晋江", "红袖添香", "红袖",
        "潇湘书院", "潇湘", "书旗小说", "书旗", "塔读文学", "塔读",
    )

    /**
     * 计算标题与关键词的相似度得分（0~100）。
     *
     * 评分策略（从高到低）：
     * 1. 完全相等 → 100
     * 2. 归一化后相等 → 98
     * 3. 标题以关键词开头 → 90
     * 4. 标题包含关键词 → 80 + 关键词长度权重（最多 +18）
     * 5. 编辑距离相似度（收紧：≥0.7 才给分，≤2 字关键词要求 ≥0.8）
     *
     * @param authorMatch 作者与关键词一致时追加 [SCORE_AUTHOR_BONUS]（可选加成）
     */
    fun score(title: String, keyword: String, authorMatch: Boolean = false): Int {
        val t = title.trim()
        val k = keyword.trim()
        if (t.isEmpty() || k.isEmpty()) return 0

        val tl = t.lowercase()
        val kl = k.lowercase()
        val tn = normalize(t)
        val kn = normalize(k)

        val base = when {
            tl == kl -> SCORE_EXACT
            tn == kn -> SCORE_NORM_EXACT
            tn.startsWith(kn) -> SCORE_PREFIX
            tn.contains(kn) -> SCORE_CONTAINS_BASE + minOf(kn.length * 2, 18)
            else -> {
                // 编辑距离兜底：阈值收紧，短关键词要求更高，避免不相关书混入
                val dist = levenshtein(tn, kn)
                val maxLen = max(tn.length, kn.length)
                if (maxLen == 0) return 0
                val similarity = 1.0 - dist.toDouble() / maxLen
                val threshold = if (kn.length <= 2) 0.8 else 0.7
                if (similarity >= threshold) (similarity * 50).toInt() else 0
            }
        }
        // 编辑距离兜底的分数（<80）不给作者加成，避免弱匹配被顶到前排
        return if (authorMatch && base >= SCORE_CONTAINS_BASE) {
            (base + SCORE_AUTHOR_BONUS).coerceAtMost(99)
        } else base
    }

    /**
     * 模糊模式：按匹配度降序排序，并过滤不相关条目。
     *
     * 排序优先级（用户预期：同名排最前，后面是类似名字）：
     * 1. 书名归一化后与关键词完全一致（同名）→ 绝对最前，作者一致者再优先；
     * 2. 书名以关键词开头；
     * 3. 书名包含关键词；
     * 4. 编辑距离相似（类似名字），按相似度。
     *
     * 用显式分层键而非单一分数值，避免 contains 的分数与「同名」档位撞车。
     *
     * @param minScore 最低匹配分，默认 [MIN_FUZZY_SCORE]——低于此分视为不相关直接过滤
     */
    fun rank(items: List<MediaItem>, keyword: String, minScore: Int = MIN_FUZZY_SCORE): List<MediaItem> {
        if (keyword.isBlank()) return dedupe(items)
        val kn = normalize(keyword)
        return dedupe(
            items
                .map { item ->
                    val s = score(item.title, keyword, authorMatch = item.author?.let { normalizeAuthor(it) == kn } == true)
                    item to s
                }
                .filter { (_, s) -> s >= minScore }
                .sortedWith(
                    compareByDescending<Pair<MediaItem, Int>> { (item, _) -> titleTier(item.title, kn) }
                        .thenByDescending { (_, s) -> s }
                        .thenBy { (item, _) -> item.title.length }
                )
                .map { (item, _) -> item }
        )
    }

    /**
     * 标题匹配档位（用于排序主键，越大越靠前）：
     * 3 = 归一化后与关键词完全一致（同名）；2 = 以关键词开头；1 = 包含关键词；0 = 其他（编辑距离兜底）。
     */
    private fun titleTier(title: String, kn: String): Int {
        val tn = normalize(title)
        return when {
            tn == kn -> 3
            tn.startsWith(kn) -> 2
            tn.contains(kn) -> 1
            else -> 0
        }
    }

    /**
     * 精准模式：只保留书名归一化后与关键词完全一致的结果（作者一致者优先）。
     * 一个源都没命中时回退到「标题以关键词开头」的最短结果，避免过度死板。
     */
    fun exactRank(items: List<MediaItem>, keyword: String): List<MediaItem> {
        if (keyword.isBlank()) return dedupe(items)
        val kn = normalize(keyword)
        val exact = items.filter { normalize(it.title) == kn }
        if (exact.isNotEmpty()) {
            return dedupe(
                exact.sortedWith(
                    compareBy<MediaItem> { it.author?.let { a -> normalize(a) == kn } != true }
                        .thenBy { it.title.length }
                )
            )
        }
        // 回退：标题以关键词开头（如「剑来」命中「剑来同人」），取最短标题
        return dedupe(
            items
                .filter { normalize(it.title).startsWith(kn) }
                .sortedBy { it.title.length }
        )
    }

    /**
     * 去除「同一源 + 同一本书」的重复项（搜索时同一源可能返回重复条目）。
     *
     * 去重键：sourceId + item.id（回退到详情 URL / 归一化书名+作者）。
     * 保留首次出现者（已按匹配分排序，即最优者）。
     */
    fun dedupe(items: List<MediaItem>): List<MediaItem> {
        if (items.size <= 1) return items
        val seen = HashSet<String>(items.size)
        val out = ArrayList<MediaItem>(items.size)
        for (item in items) {
            val key = buildString {
                append(item.sourceId)
                append('|')
                append(item.id.ifBlank { item.detailUrl })
                if (item.id.isBlank() && item.detailUrl.isBlank()) {
                    append('|')
                    append(normalize(item.title))
                    append('|')
                    append(normalizeAuthor(item.author ?: ""))
                }
            }
            if (seen.add(key)) out.add(item)
        }
        return out
    }

    /**
     * 把搜索结果按「书名 + 作者」归一化分组（用于结果折叠）。
     *
     * 作者为空时仅按书名分组。组内保持原顺序（源返回序），
     * 组间按组内最高匹配分排序（由调用方先 rank 后传入即已有序）。
     *
     * @return 分组列表，每组至少 1 条
     */
    fun groupDuplicates(items: List<MediaItem>): List<List<MediaItem>> {
        val groups = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            val author = item.author?.trim()?.let { normalizeAuthor(it) }.orEmpty()
            val key = "${normalize(item.title)}|${author}"
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.toList()
    }

    /** 关键词是否与标题"模糊相关"（宽松判定，用于兜底不过滤）。 */
    fun isRelated(title: String, keyword: String): Boolean =
        score(title, keyword) > 0

    /** 计算两个字符串的编辑距离（Levenshtein）。 */
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
