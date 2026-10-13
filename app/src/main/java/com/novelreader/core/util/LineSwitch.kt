package com.novelreader.core.util

import com.novelreader.domain.model.ChapterItem

/**
 * 播放页「换源 / 测速择优」的**纯决策逻辑**（v1.3.6.0）。
 *
 * 抽成纯函数是为了能离线单测：这两条规则（切哪一集、选哪条线路）
 * 判断细节多、边界杂，混在 ViewModel 里既不好测也容易写错。
 */
object LineSwitch {

    /**
     * 换源时在新线路上选哪一集。
     *
     * 规则：优先切到**同一集序号**（各线路的集序基本一致，用户视角就是「同一集」）；
     * 若新线路集数不够（例如只有预告 / 花絮），退回该线路第 1 集。
     *
     * @param wantedIndex 当前集的序号（各线路统一从 0 开始）
     * @return 目标集；线路为空时返回 `null`
     */
    fun pickChapter(chapters: List<ChapterItem>, wantedIndex: Int): ChapterItem? =
        chapters.getOrNull(wantedIndex) ?: chapters.firstOrNull()

    /**
     * 选出最快的线路下标。
     *
     * - 值为 `null` 的线路表示**不可达**，直接忽略（不能因为「没测出来」就当成快）；
     * - 全部不可达 / 还没测过时返回 `null`，调用方据此提示而不是瞎切。
     *
     * @return 最快线路下标；无可用结果时为 `null`
     */
    fun fastestIndex(speeds: Map<Int, Long?>): Int? =
        speeds.filterValues { it != null }
            .minByOrNull { it.value ?: Long.MAX_VALUE }
            ?.key

    /**
     * 某个下标是否就是最快的那条（用于 UI 打「最快」角标）。
     *
     * 单独抽出来是为了让「只有一条线路时不标最快」这类细节集中在一处。
     */
    fun isFastest(speeds: Map<Int, Long?>, index: Int, lineCount: Int): Boolean =
        lineCount > 1 && fastestIndex(speeds) == index
}
