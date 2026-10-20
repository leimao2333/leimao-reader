package com.novelreader.core.util

/**
 * 打标证据（v1.8.4.1）：**检索结果到底算不算「属于某个标签」**。
 *
 * 标签搜索的两种入口性质完全不同：
 * - **真实分类页**（`/vodshow/1--------1---.html` 这种）：源站自己把作品归到该分类，
 *   结果就是「该标签的成员」—— 可以当证据写进标签层；
 * - **关键词搜索**（`?wd=全彩`）：只证明「标题里出现了这个词」。源站的包含匹配很宽，
 *   搜「全彩」会回来《全面接触》，搜「无码」会回来一堆英文无关漫画。
 *
 * 以前打标是**整批**判定：只要这次搜索里每个标签都至少有一个分类页入口，就把所有
 * 结果一起打上这些标签 —— 混在里面的关键词结果于是被贴了假标签，之后搜这个标签
 * 又会被索引命中，错一次就一直在错。现在改成**按作品**判定，规则就两条：
 *
 * 1. 只有分类页命中的结果才有证据（关键词命中一律不算）；
 * 2. 证据必须**同源**：A 站《夏日》属于「无码」不能证明 B 站同名《夏日》也属于，
 *    那是两部作品。展示层的求交允许跨源同名（v1.7.9.9 口径，用户搜的是作品），
 *    但写标签层必须收紧 —— 错标签比漏标签难修得多。
 */
object TagProvenance {

    /** 证明键分隔符：源 ID 里不会出现 `|` */
    private const val SEP = "|"

    /**
     * 一条证明键：**同一个源 + 同一个归一化标题**。
     *
     * @param title 原始标题，内部会过 [TagQuery.identityKey]（去标点 / 空白 / 大小写）
     */
    fun proofKey(sourceId: String, title: String): String? {
        val source = sourceId.trim()
        if (source.isEmpty()) return null
        val key = TagQuery.identityKey(title)
        if (key.isEmpty()) return null
        return source + SEP + key
    }

    /**
     * 该作品被哪些标签**证明**过。
     *
     * @param hits 每个标签名 → 它的分类页里出现过的证明键集合
     * @return 证明成立的标签名（保持 [hits] 顺序）；一条都不成立时返回空列表
     */
    fun provenTags(
        sourceId: String,
        title: String,
        hits: List<Pair<String, Set<String>>>,
    ): List<String> {
        val key = proofKey(sourceId, title) ?: return emptyList()
        return hits.mapNotNull { (tag, keys) -> tag.takeIf { key in keys } }
    }

    /**
     * 把一批结果按「被证明的标签集合」分组，供调用方**逐组**写标签层
     * （不同组要带不同的分类页上下文，不能混在一批里）。
     *
     * 没被证明的作品进 `emptyList()` 那一组 —— 它们仍会入库，只是只按自身字段打标。
     */
    fun <T> groupByProof(
        items: List<T>,
        proven: Map<String, Set<String>>,
        keyOf: (T) -> String,
    ): Map<List<String>, List<T>> = items.groupBy { item ->
        proven[keyOf(item)].orEmpty().sorted()
    }
}
