package com.novelreader.core.util

/**
 * 首页推荐缓存是否可用（v1.9.5.0）。
 *
 * 之前这套判据直接写在 `HomeViewModel` 里，要求「枚举覆盖源数 × 2 ≥ 启用源总数」。
 * 本意是拦「冷启动时源还没从数据库恢复完，只枚举到 1 个源」的残缺计划，
 * 但源一多（真机 2700+）枚举受全局时限限制本来就覆盖不全 —— 于是
 * **缓存既写不进去、也读不出来**，用户每次切回首页都重新联网拉一遍。
 *
 * 改成两条各自独立、与实际覆盖规模无关的判据：
 * 1. 枚举至少覆盖 [MIN_COVERED_SOURCES] 个源（专门拦冷启动抢跑的极端残缺）；
 * 2. 缓存记录的启用源集合与当前相比，变化量在容忍范围内（少量启停 / 导入不作废）。
 *
 * 纯函数，便于离线单测。
 */
object FeedCachePolicy {

    /** 至少枚举到这么多个源，才认为这份计划不是「源还没恢复完」的残缺产物 */
    const val MIN_COVERED_SOURCES = 5

    /** 源集变化的绝对容忍量；另取当前启用源数的 5%，两者取大 */
    const val SOURCE_CHANGE_TOLERANCE = 20

    data class Input(
        /** 缓存里记录的、生成这份缓存时启用的源 id */
        val cachedSourceIds: Set<String>,
        /** 生成缓存时实际枚举覆盖了几个源 */
        val enumerated: Int,
        /** 缓存里的推荐排数量（空内容没有恢复价值） */
        val rowCount: Int,
        /** 当前启用的源 id */
        val enabledSourceIds: Set<String>,
    )

    /**
     * @param usable false = 这份缓存完全不能用（空内容 / 无源集 / 残缺计划）
     * @param stale  true = 内容还能先上屏，但源集变化较大，建议提示用户可刷新
     */
    data class Verdict(val usable: Boolean, val reason: String, val stale: Boolean = false)

    fun check(input: Input): Verdict {
        if (input.rowCount <= 0) return Verdict(false, "缓存没有内容")
        if (input.cachedSourceIds.isEmpty()) return Verdict(false, "缓存没有源集记录")

        val enabled = input.enabledSourceIds
        val cached = input.cachedSourceIds
        val changed = (enabled - cached).size + (cached - enabled).size
        val tolerance = maxOf(SOURCE_CHANGE_TOLERANCE, enabled.size / 20)
        // 源集大变也不再直接判废：先让旧内容上屏（首页本来就有「加载中保留旧内容」
        // 的 UI），标成 stale 提示用户可刷新。直接判废的代价是每次进首页都得
        // 等一轮 2400+ 源的枚举，正是「懒加载不见了」的体感来源。
        val stale = changed > tolerance

        // 残缺判据只跟「缓存自己那批源」比一个绝对下限，不再与当前启用源总数比
        val minCovered = minOf(cached.size, MIN_COVERED_SOURCES)
        if (input.enumerated < minCovered) {
            return Verdict(false, "枚举只覆盖 ${input.enumerated} 个源，疑似冷启动抢跑")
        }
        return Verdict(true, if (stale) "源集变化较大，先上屏旧内容" else "可用", stale)
    }
}
