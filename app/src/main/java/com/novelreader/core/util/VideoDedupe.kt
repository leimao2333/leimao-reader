package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem
import com.novelreader.source.search.FuzzyMatch

/**
 * 视频条目去重（v1.7.6.4）。
 *
 * **为什么要按标题去重**：同一部片常被多个采集源收录，甚至同一源的不同分类
 * 各返回一次；只按 `(sourceId, id)` 去重会让首页同一张海报重复好几遍
 * （用户实测「童年阴影」连出 4 张）。
 *
 * 合并口径：
 * - 归一化标题（[FuzzyMatch.normalize]，去掉书名号 / 空白 / 标点差异）；
 * - 年份仅在 `extras["year"]` 存在时参与，避免把同名不同年的翻拍片合并掉；
 * - 同标题下优先保留**有封面**的那条（封面更完整），否则保留先出现的。
 */
object VideoDedupe {

    fun byTitle(items: List<MediaItem>): List<MediaItem> {
        if (items.size < 2) return items
        val out = ArrayList<MediaItem>(items.size)
        val index = HashMap<String, Int>(items.size)
        for (item in items) {
            val norm = FuzzyMatch.normalize(item.title)
            if (norm.isBlank()) {
                out.add(item)
                continue
            }
            val year = item.extras["year"].orEmpty().trim()
            val key = if (year.isBlank()) norm else "$norm|$year"
            val existing = index[key]
            if (existing == null) {
                index[key] = out.size
                out.add(item)
            } else if (out[existing].cover.isNullOrBlank() && !item.cover.isNullOrBlank()) {
                out[existing] = item
            }
        }
        return out
    }
}
