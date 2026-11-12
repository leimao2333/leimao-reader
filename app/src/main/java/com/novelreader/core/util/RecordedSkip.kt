package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem

/**
 * 「只看新」的过滤口径（v1.9.5.8）。
 *
 * 用户攒到上万条收录后，同一个标签搜回来的头几屏全是老面孔 —— 本地索引结果本来
 * 就排在网络结果前面，翻十页也遇不到新东西。这里把「什么算已看过」收敛成一个口径，
 * 界面过滤与后台「整页都是老面孔就继续往后翻」用的必须是同一套键，否则
 * 会出现「界面藏了 30 条、后台却认为这一页有新的」这种自相矛盾的判断。
 *
 * 键的格式是 `sourceId|itemId`，与 `TagInstanceDao.indexedKeys` 里的 SQL 表达式
 * `sourceId || '|' || itemId`、以及卡片角标用的键完全一致 —— 三处任何一处换写法，
 * 过滤就会静默失效（不报错、只是不生效），所以统一走 [keyOf]。
 */
object RecordedSkip {

    /** 条目在「已收录」口径下的唯一键 */
    fun keyOf(item: MediaItem): String = "${item.sourceId}|${item.id}"

    /** 一批条目的键（过滤掉 sourceId / id 为空的脏数据） */
    fun keysOf(items: List<MediaItem>): List<String> = items.asSequence()
        .filter { it.sourceId.isNotBlank() && it.id.isNotBlank() }
        .map(::keyOf)
        .distinct()
        .toList()

    /**
     * 按开关决定哪些条目可以显示。
     *
     * @param recorded 本地索引已收录的键
     * @param skip false = 原样返回（不复制列表，避免每帧都造一个新 List）
     */
    fun visible(
        items: List<MediaItem>,
        recorded: Set<String>,
        skip: Boolean,
    ): List<MediaItem> {
        if (!skip || recorded.isEmpty() || items.isEmpty()) return items
        return items.filterNot { keyOf(it) in recorded }
    }

    /**
     * 这一批里**没被收录**的条数。
     *
     * 0 表示整页都是老面孔 —— 调用方据此决定「继续往后问一页」还是就此打住。
     */
    fun freshCount(items: List<MediaItem>, recorded: Set<String>): Int {
        if (items.isEmpty()) return 0
        if (recorded.isEmpty()) return items.size
        return items.count { keyOf(it) !in recorded }
    }
}
