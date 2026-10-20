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
     */
    fun merge(vararg batches: List<MediaItem>): List<MediaItem> {
        val byId = LinkedHashMap<String, MediaItem>()
        batches.forEach { batch ->
            batch.forEach { item -> byId[keyOf(item)] = item }
        }
        return byId.values.distinctBy { TagQuery.identityKey(it.title) }
    }
}
