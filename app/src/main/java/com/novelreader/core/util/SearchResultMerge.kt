package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem

/**
 * 搜索结果合并去重（v1.8.4.1）。
 *
 * 标签搜索的结果来自两处：**本地索引**（`local_tags` 里的旧标题）和**网络**（源站刚返回的
 * 标题）。同一部作品两边都在时，标题可能差一个字（源站改名、索引里存的是校对版），
 * 按标题去重就留下了两条；而列表的 LazyColumn 用 `sourceId:id` 当 key ——
 * 两条同一个 id 的记录直接把 Compose 顶崩（`Key ... was already used`）。
 *
 * 所以合并分两步，顺序不能反：
 * 1. 先按 `sourceId:id` 收敛（同一个源的同一条作品只留一条，**后到的覆盖先到的** ——
 *    网络标题更新，让它盖掉索引里的旧标题）；
 * 2. 再按作品身份键（归一化标题）收敛，跨源的同名作品只留一条。
 */
object SearchResultMerge {

    /** 列表 key / 条目身份：同一个源的同一条作品 */
    fun keyOf(item: MediaItem): String = "${item.sourceId}:${item.id}"

    /**
     * 合并若干批结果（前一批的位置优先，内容以后到的为准）。
     *
     * 保持顺序：作品第一次出现的位置不变，只是内容被后面的批次覆盖。
     * 只用于**一次性**合并；翻页追加请用 [Accumulator]，否则每次都要把整个列表
     * 重新算一遍标题键（O(n²)，几千条时会把主线程占死）。
     */
    fun merge(vararg batches: List<MediaItem>): List<MediaItem> {
        val byId = LinkedHashMap<String, MediaItem>()
        batches.forEach { batch ->
            batch.forEach { item -> byId[keyOf(item)] = item }
        }
        return byId.values.distinctBy { TagQuery.identityKey(it.title) }
    }

    /**
     * 增量累加器（v1.8.4.1）：翻页时只处理**新到的一页**，不再重算整个列表。
     *
     * 之前的写法是每追加一页就把「已有结果 + 新结果」整体去重一次 ——
     * 每次都要给每部作品算标题身份键（正则 + 繁简转换），滚十几屏就是
     * 几十万次，主线程直接卡到 ANR。这里把身份键记在表里，追加是 O(本页条数)。
     */
    class Accumulator {

        private val byId = LinkedHashMap<String, MediaItem>()

        /**
         * 已经收进来的**唯一条目数**（`sourceId:id` 口径，v1.8.8.0）。
         *
         * 与「列表行数」不是一回事：跨源同名作品会被折叠成一行，行数不变但唯一条目数在涨。
         * 翻页判定要用这个数 —— 只按行数判断会把「同一部作品又多了一个源」误判成「没有新内容」。
         */
        val uniqueCount: Int get() = byId.size

        /** 标题身份键 → 作品 id（跨源同名只留第一条） */
        private val titleOwner = HashMap<String, String>()

        /** 重置成一批新结果（新搜索开始时调用） */
        fun reset(items: List<MediaItem>): List<MediaItem> {
            byId.clear()
            titleOwner.clear()
            return addAll(items)
        }

        /** 追加一批结果，返回当前快照（可直接喂给 StateFlow） */
        fun addAll(items: List<MediaItem>): List<MediaItem> {
            items.forEach { item ->
                val id = keyOf(item)
                val titleKey = TagQuery.identityKey(item.title)
                val owner = titleOwner[titleKey]
                if (byId.containsKey(id)) {
                    // 同一条作品又出现：内容以新到的为准，位置保持不变
                    byId[id] = item
                    if (owner == null) titleOwner[titleKey] = id
                    return@forEach
                }
                if (owner != null && owner != id) return@forEach
                byId[id] = item
                titleOwner[titleKey] = id
            }
            return ArrayList(byId.values)
        }
    }
}
