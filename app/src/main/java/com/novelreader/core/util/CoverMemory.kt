package com.novelreader.core.util

import java.util.concurrent.ConcurrentHashMap

/**
 * 封面的**进程内**记忆（v1.7.4.0）。
 *
 * 解决的是「站点列表页不给封面，但详情页给」这一类源：实测 52PO小说、海马读书
 * 的 `ruleSearch` / `ruleExplore` 都没有 `coverUrl`，而 `ruleBookInfo` **有** ——
 * 于是列表页整排都是占位块，可一旦点进详情，封面其实拿得到。
 *
 * 这里的做法是：详情页拿到封面后按 `(源, 书)` 记一笔，同一会话内再回到列表时
 * 把它补回去。用户点过一次的书，之后在首页 / 搜索结果里就有封面了。
 *
 * 只做内存态是刻意的：封面 URL 带签名 / 时效，落盘反而可能拿到过期地址；
 * 而「点过详情」这件事本身就只在当前会话里成立（书架另有自己的封面字段持久化）。
 */
object CoverMemory {

    /** 上限：源动辄上百、每个源几十条列表，不封顶会把内存吃成一个大 Map */
    private const val MAX_ENTRIES = 4_000

    private val covers = ConcurrentHashMap<String, String>()

    private fun key(sourceId: String, bookId: String) = "$sourceId\u0000$bookId"

    /** 记下一本书的封面（详情页拿到后调用） */
    fun remember(sourceId: String, bookId: String, cover: String?) {
        if (sourceId.isBlank() || bookId.isBlank() || cover.isNullOrBlank()) return
        if (covers.size >= MAX_ENTRIES) covers.clear() // 满了就整表重置，简单可预期
        covers[key(sourceId, bookId)] = cover
    }

    /** 取回记住的封面；没记住返回 null */
    fun recall(sourceId: String, bookId: String): String? =
        covers[key(sourceId, bookId)]

    /**
     * 给一批条目补封面：只对**本来没有封面**的条目生效。
     *
     * @return 补过封面的新列表（无变化时返回原列表，避免多余分配）
     */
    fun fill(items: List<com.novelreader.domain.model.MediaItem>): List<com.novelreader.domain.model.MediaItem> {
        if (items.isEmpty()) return items
        var changed = false
        val out = items.map { item ->
            if (!item.cover.isNullOrBlank()) return@map item
            val cover = recall(item.sourceId, item.id) ?: return@map item
            changed = true
            item.copy(cover = cover)
        }
        return if (changed) out else items
    }
}
