package com.novelreader.core.search

/**
 * 翻页进度判定（v1.9.6.0）。
 *
 * 只回答一个问题：**这一页取回来的东西，是本轮从来没见过的新内容吗？**
 *
 * 为什么不能用「结果集有没有变大」来判：
 * 标签搜索结果里本来就掺着本地索引的上万条记录。网络每翻一页，命中的几乎都是它们，
 * 于是「新增行数 / 唯一条目数」恒为 0 —— 旧代码据此认为「源站在反复返回同一批」，
 * 翻到第 3 页就停了（真机 2026-11-15 18:05 复现：第 2、3 页各取回 58 / 40 条，新增 0）。
 *
 * 现在按「这一页的键是否本轮都已取回过」判定，与「有没有被收录进结果集」彻底解耦。
 */
class PageProgressTracker {

    /** 本轮取回来过的条目键（调用方用 `sourceId:id` 这类稳定身份） */
    private val fetched = HashSet<String>()

    /** 连续多少页都是「取回过的内容」 */
    var consecutiveRepeats: Int = 0
        private set

    /** 本轮一共看过多少条不同的内容 */
    val seenCount: Int get() = fetched.size

    /**
     * 登记一页结果。
     *
     * @return true = 这一页的内容本轮全都取回过（没有新东西）
     */
    fun observe(keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return false
        val nothingNew = fetched.containsAll(keys)
        fetched.addAll(keys)
        if (nothingNew) consecutiveRepeats++ else consecutiveRepeats = 0
        return nothingNew
    }

    fun reset() {
        fetched.clear()
        consecutiveRepeats = 0
    }
}
