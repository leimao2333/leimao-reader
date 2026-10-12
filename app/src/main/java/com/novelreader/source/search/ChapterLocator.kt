package com.novelreader.source.search

import com.novelreader.domain.model.ChapterItem

/**
 * 换源时的章节定位。
 *
 * 旧实现在 ViewModel 内联：只做「标题完全相等 → 标题包含 → 回退第一章」，
 * 于是源改了章节命名风格（如多了「正文 」前缀）就必定跳回第一章，「换源形同虚设」。
 *
 * 这里改为四级策略，并抽成纯函数以便单测覆盖：
 * 1. 标题完全相等
 * 2. 标题归一化后相等（[FuzzyMatch.normalize] 去掉空白/标点等噪声）
 * 3. 标题互相包含
 * 4. 以上都失败 → 按「已读章节比例」映射到目标目录（近似续读位置，而不是粗暴回第一章）
 */
object ChapterLocator {

    /**
     * @param newChapters 目标源的章节列表
     * @param currentTitle 当前正在读的章节标题
     * @param currentPosition 当前章节在原目录中的下标（0 起）；-1 表示未知
     * @param oldTotal 原目录章节总数（0 表示未知）
     * @return 目标列表中应跳转的下标（保证落在 `0..newChapters.lastIndex`）
     */
    fun locate(
        newChapters: List<ChapterItem>,
        currentTitle: String,
        currentPosition: Int,
        oldTotal: Int,
    ): Int {
        if (newChapters.isEmpty()) return 0
        val lastIndex = newChapters.size - 1

        val title = currentTitle.trim()
        if (title.isNotEmpty()) {
            newChapters.indexOfFirst { it.title.trim() == title }.let { if (it >= 0) return it }

            val key = FuzzyMatch.normalize(title)
            if (key.isNotEmpty()) {
                newChapters.indexOfFirst { FuzzyMatch.normalize(it.title) == key }
                    .let { if (it >= 0) return it }
            }

            newChapters.indexOfFirst {
                val x = it.title.trim()
                x.isNotEmpty() && (x.contains(title, ignoreCase = true) || title.contains(x, ignoreCase = true))
            }.let { if (it >= 0) return it }
        }

        // 标题对不上：按阅读比例映射，避免一律回到第一章
        if (oldTotal > 0 && currentPosition >= 0) {
            val ratio = (currentPosition + 1f) / oldTotal.toFloat()
            return (ratio * newChapters.size).toInt().coerceIn(0, lastIndex)
        }
        return 0
    }
}
