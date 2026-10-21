package com.novelreader.core.util

/**
 * 标签搜索输入提示词的匹配与排序（v1.8.6.0 待办 #27）。
 *
 * 抽成纯函数是为了能单测：提示的排序规则（前缀优先 / 词短优先 / 覆盖源数垫后）
 * 一旦错位，用户看到的就是「打了一半的标签排在有意义的补全后面」，很难靠肉眼回归。
 *
 * 输入框只有一个纯字符串，语法上支持四种写法，提示必须**在同一个语法位置内补全**：
 * - `无码`：普通标签
 * - `-无码`：排除（`-` 前缀原样保留）
 * - `题材:无码`：大类限定（`:` 前原样保留）
 * - `无码 全彩`：空格分隔的多个条件，**只提示正在输入的那一个**（最后一个 token）
 */
object TagSuggestions {

    /** 默认最多展示几条：提示是「补全」不是「浏览」，超过一屏反而挡住输入框 */
    const val DEFAULT_LIMIT = 8

    /** 前缀命中（排在包含命中前面） */
    private const val SCORE_PREFIX = 0

    /** 包含命中 */
    private const val SCORE_CONTAINS = 1

    /** 不命中 */
    private const val SCORE_NONE = -1

    /**
     * 当前正在输入的词：最后一个空格分段，剥掉 `-` 排除与 `题材:` 大类前缀。
     *
     * 空串表示没有可提示的内容（输入为空、或只输了个 `-` / `题材:`）。
     */
    fun currentWord(query: String): String =
        query.split(' ')
            .lastOrNull { it.isNotBlank() }
            ?.substringAfter(':')
            ?.trimStart('-')
            ?.trim()
            ?: ""

    /**
     * 计算提示词列表。
     *
     * 过滤（「已成立的条件不再提示」）：
     * - 空格分隔的前几段（已写进查询的标签）全部视为已成立；
     * - **正在输入的最后一段**在该词本身就是池子里一个完整标签时也视为已成立 ——
     *   否则打完「无码」后面还跟着「无码」「无码漫画」一整串同根词，白占一屏。
     *
     * 判定口径：候选标签的规范名（[TagAlias.canonicalOf]）以任一已成立词开头即屏蔽，
     * 这样「无码」能连带挡掉「无码漫画」，别名写法（無修正 ↔ 无码）也归到同一族。
     *
     * 排序：匹配类型（前缀命中 < 包含命中）→ 补全量更短（`tag.length - 词长度`，
     * 同分时词更短的先出）→ 池内顺序（调用方传按覆盖源数倒序的列表，冷门长尾
     * 不会挤掉常用标签）。
     *
     * @param pool 标签池，**顺序即末位优先级**
     * @param query 完整输入串
     * @param limit 最多几条
     */
    fun of(pool: List<String>, query: String, limit: Int = DEFAULT_LIMIT): List<String> {
        val word = currentWord(query)
        if (word.isEmpty() || pool.isEmpty()) return emptyList()

        // 已写进查询的其它条件：前几段，剥掉 `-` 排除与 `大类:` 前缀后取规范名
        val committedUsed = query.split(' ')
            .filter { it.isNotBlank() }
            .dropLast(1)
            .map { TagAlias.canonicalOf(it.substringAfter(':').trimStart('-')) }
            .filter { it.isNotEmpty() }
            .toHashSet()

        // 正在输入的这个词已经是池子里的完整标签 → 也按「已成立」屏蔽同根词
        //
        // v1.8.7.1 修：原来写成 `committedUsed.isNotEmpty() || 池内命中`，
        // 只要前面已经有条件就把**正在输入的词**也当成「已成立」——
        // 于是输「无码 全」时候选全被「全」这个前缀自己屏蔽掉，第二个标签的提示永远出不来。
        // 「前面有条件」和「这个词已经写完」是两件事，只有后者才该屏蔽。
        val wordCanonical = TagAlias.canonicalOf(word)
        val wordIsComplete = pool.any { TagAlias.canonicalOf(it) == wordCanonical }
        val used: Set<String> =
            if (wordIsComplete) committedUsed + wordCanonical else committedUsed

        fun blockedBy(tag: String): Boolean {
            if (used.isEmpty()) return false
            val canonical = TagAlias.canonicalOf(tag)
            return used.any { canonical.startsWith(it) }
        }

        val rank = HashMap<String, Int>(pool.size)
        pool.forEachIndexed { index, tag -> rank.putIfAbsent(tag, index) }

        val needle = word.lowercase()
        return pool.asSequence()
            .filter { it.isNotBlank() }
            .filterNot { blockedBy(it) }
            .map { tag -> tag to matchScore(tag.lowercase(), needle) }
            .filter { (_, score) -> score >= 0 }
            .sortedWith(
                compareBy(
                    { (_, score) -> score },
                    { (tag, _) -> tag.length - needle.length },
                    { (tag, _) -> rank[tag] ?: Int.MAX_VALUE },
                ),
            )
            .map { (tag, _) -> tag }
            .distinctBy { TagAlias.canonicalOf(it) }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    /** 0 = 前缀命中，1 = 包含命中，-1 = 不命中（入参都已转小写） */
    fun matchScore(tag: String, word: String): Int = when {
        tag.startsWith(word) -> SCORE_PREFIX
        tag.contains(word) -> SCORE_CONTAINS
        else -> SCORE_NONE
    }

    /**
     * 点选提示词之后应得到的查询串：把**正在输入的那一段**原地替换成完整标签。
     *
     * 不能直接追加 —— 输入「无」时点「无码」会得到「无 无码」，两个大集求交必然为空。
     * `-` 与 `题材:` 前缀原样保留，其余分段保持原顺序。
     */
    fun applyToQuery(query: String, tag: String): String {
        val name = tag.trim()
        if (name.isEmpty()) return query
        val tokens = query.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (tokens.isEmpty()) return name
        val last = tokens.last()
        val prefix = buildString {
            if (last.startsWith("-")) append('-')
            if (last.contains(':')) append(last.substringBefore(':')).append(':')
        }
        tokens[tokens.lastIndex] = prefix + name
        return tokens.joinToString(" ")
    }

    /**
     * 点选提示词之后应得到的查询串（**继续输入**口径，v1.8.7.1）。
     *
     * 与 [applyToQuery] 的唯一差别是末尾补一个空格：
     * - 末尾有空格 → [currentWord] 只认最后一个非空 token，而它此时已是池内完整标签，
     *   会被当成「已成立」整族屏蔽，提示面板自然收起，不用额外维护「已关闭」状态；
     * - 用户想接着输入第二个标签时，光标已经落在分隔位，不用自己敲空格。
     *
     * 这是搜索框补全的通行行为：**点选 = 完成这一段输入，不等于提交搜索**。
     * 直接把点选绑成搜索会让「想输两个标签」的用户每选一个词就被打断一次。
     */
    fun completeForContinue(query: String, tag: String): String =
        applyToQuery(query, tag).trimEnd() + " "
}
