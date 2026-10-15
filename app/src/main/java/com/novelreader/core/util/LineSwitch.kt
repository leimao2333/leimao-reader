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

    /**
     * 起播 / 自动纠错时该用哪条线路（v1.6.9.0，「自动优先有效源」的核心决策）。
     *
     * 规则（依次判断）：
     * 1. **记忆线路可达** → 用它 —— 用户上次的选择优先；
     * 2. 否则 **探测结果里最快且可达**的那条；
     * 3. 一条可达的都没有（还没测 / 全不可达）→ `null`，
     *    调用方据此保持现状或提示，而不是瞎切到某个没测过的线路。
     *
     * 注意：记忆线路**不可达时不会被选中** —— 「记住我上次用的源」和
     * 「优先有效源」冲突时，后者优先，否则用户会每次都被带回那条死线路。
     *
     * @param preferred 记忆里的线路下标（可能为 null / 越界）
     * @param speeds 探测结果：下标 → 延迟毫秒（`null` = 不可达，缺键 = 未测）
     */
    fun pickValid(preferred: Int?, speeds: Map<Int, Long?>, lineCount: Int): Int? {
        val reachable = speeds.filterValues { it != null }
        if (preferred != null && preferred in 0 until lineCount && reachable.containsKey(preferred)) {
            return preferred
        }
        return reachable.minByOrNull { it.value ?: Long.MAX_VALUE }?.key
    }

    /**
     * 播放出错后自动换的下一条线路：按顺序取第一个**没试过**的（v1.6.9.0）。
     *
     * 用 [tried] 记录本片本次会话里已失败的线路，保证每条最多自动试一次，
     * 不会在两条坏线路之间来回弹。
     *
     * @return 下一条待尝试的线路下标；全部试过则为 `null`（调用方此时如实报错）
     */
    fun nextUntried(lineCount: Int, tried: Set<Int>): Int? =
        (0 until lineCount).firstOrNull { it !in tried }
}
