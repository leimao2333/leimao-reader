package com.novelreader.core.util

import com.novelreader.core.search.RecallOutcome
import com.novelreader.domain.model.MediaItem

/**
 * 一条结果对某个标签的判定结果（v1.8.9.1）。
 *
 * 三态而不是二值，是整套搜索方案的核心：
 * 以前只有「命中 / 不命中」，而「不命中」里混着三种完全不同的情况 ——
 * 源说没这部（真不命中）、源超时了（不知道）、源的关键词检索宽松（可疑）。
 * 把它们一起丢掉，用户看到的就是「结果越搜越少」。
 */
enum class TagVerdict {
    /** 明确命中：分类页里抓到过，或标题确实带这个写法 */
    MATCHED,

    /** 明确不命中：源正常返回了，但标题里没有这个题材词（关键词检索的宽松命中） */
    MISMATCHED,

    /** 不知道：源超时/报错，或者这类标签本来就不会出现在标题里 */
    UNKNOWN,
}

/**
 * 一个标签的**判定上下文**（v1.8.9.1）：把写法归一化与分级词判定预先算好。
 *
 * 为什么需要它：判定是「逐条结果 × 逐标签」跑的，一次搜索要算几万次。
 * 每次直接调 [TagAlias.searchTermsFor] 都会重跑一遍繁简归一化 + 正则编译，
 * 实测把主线程卡到输入事件超时（2026-10-27 真机 ANR 复现）。
 * 这里每个标签只算一次，逐条判定退化成几次字符串包含。
 */
class TagContext private constructor(
    val tag: String,
    /** 已归一化的写法（小写、去空格） */
    private val terms: List<String>,
    /**
     * 分级 / 状态词（成人向题材的写法）。
     *
     * 这类词几乎不会写进作品标题，标题不含它说明不了什么；也正因为如此，
     * 结果层对它们只认「成人源自己的分类页」这一种证据 —— 普通源的分区里
     * 常把整站目录挂在任意分类下，那类「证据」只会带来一堆无关内容。
     */
    val isExplicitAdult: Boolean,
) {

    /** 归一化后的标题是否带这个标签的任一写法 */
    fun matchesNormalized(normalizedTitle: String): Boolean =
        normalizedTitle.isNotEmpty() && terms.any { normalizedTitle.contains(it) }

    /**
     * 判定顺序（改动前先读一遍，每条都各有原因）：
     * 1. **分类页证据优先** —— 站方归类最可信，不拿标题去否它；
     * 2. **标题命中 → matched** —— 白纸黑字写在标题里的，比「源的状态」可信；
     * 3. **源不可用 → unknown** —— 超时是「不知道」，绝不是「没有」；
     * 4. **分级 / 状态词 + 成人源 → unknown**；
     * 5. 其余才是 [TagVerdict.MISMATCHED]。
     *
     * v1.9.1.1：第 2、3 条换了位置。以前「源超时」排在「标题命中」之前，于是同一个源
     * 只要有一个入口超时，它**已经抓回来、标题明明白白带着标签词**的结果也一起被降级成
     * UNKNOWN —— 这些条目随后不算 proven，在 proven 充足时整批被隐藏。真机上表现就是
     * 「搜着搜着少了一大批」。超时只能说明「可能还有更多」，不能反过来否定已知命中。
     *
     * [adultSource] 是第 4 条的前提，不能省：分级词（无码 / 有码 / 里番 / 成人）
     * 几乎不会写进标题，所以在**做这类内容的源**上「标题没写」等于「不确定」。
     * 但一般向源返回的《夢幻西遊》不是「不确定」，是「这个源根本不做这个题材」——
     * 以前一律判 unknown，于是它们被当成「没被否证」保留下来，一屏全是无关内容。
     */
    fun verdict(
        normalizedTitle: String,
        provenByCategory: Boolean,
        sourceOutcome: RecallOutcome?,
        adultSource: Boolean = true,
    ): TagVerdict {
        if (provenByCategory) return TagVerdict.MATCHED
        if (matchesNormalized(normalizedTitle)) return TagVerdict.MATCHED
        if (sourceOutcome != null && sourceOutcome.isUnknown) return TagVerdict.UNKNOWN
        if (isExplicitAdult && adultSource) return TagVerdict.UNKNOWN
        return TagVerdict.MISMATCHED
    }

    companion object {
        /**
         * v1.9.2.0：写法表改用 [TagAlias.searchTermsOf] —— 组内成员（如「彩绘」）只认
         * 它自己那一个写法。用整组写法判定的话，单标签搜索会被标题里写着「全彩」的
         * 作品混进来，判定层等于把召回层的精确性又抹平了。
         */
        fun of(tag: String): TagContext = TagContext(
            tag = tag,
            terms = TagAlias.searchTermsOf(tag)
                .map(TagVerdicts::normalizeTitle)
                .filter { it.isNotEmpty() },
            isExplicitAdult = TagAlias.isExplicitAdultIntent(tag),
        )
    }
}

/** 标签判定的便捷入口（批量场景请用 [TagContext]，别在循环里调这里的单条重载）。 */
object TagVerdicts {

    /** 标题归一化：小写 + 去空格（判定与比较的唯一口径） */
    fun normalizeTitle(title: String): String = title.lowercase().replace(" ", "")

    fun contextOf(tag: String): TagContext = TagContext.of(tag)

    fun of(
        title: String,
        tag: String,
        provenByCategory: Boolean,
        sourceOutcome: RecallOutcome?,
    ): TagVerdict = TagContext.of(tag)
        .verdict(normalizeTitle(title), provenByCategory, sourceOutcome)

    /** 标题里是否出现了该标签的任一写法 */
    fun matchesTitle(title: String, tag: String): Boolean =
        TagContext.of(tag).matchesNormalized(normalizeTitle(title))
}

/**
 * 结果层的取舍与排序策略（v1.8.9.1）。
 *
 * 取舍只有一条主线：**有证据的留下，没证据的排后面**。
 * - 「有证据」= 至少一个标签明确命中（分类页抓到过，或标题确实带这个写法）；
 * - 没证据的（源超时 / 分级词不进标题 / 关键词检索宽松命中）**不是垃圾，只是不确定**：
 *   有足够多确凿结果时（≥ [minResults]）不显示，免得把「和标签没关系的东西」混进来；
 *   确凿结果不够时全部补上 —— 宁可给几条「可能不太相关」，也不要甩一个空结果页。
 * - 排序**不再按标题长度**：那会把长标题的无关内容顶到前面（v1.8.9.1 真机反馈）。
 *   同一档内保持召回顺序（`sortedWith` 是稳定排序）。
 */
object TagResultPolicy {

    const val MIN_RESULTS = 20

    data class Candidate(
        val item: MediaItem,
        val verdicts: List<TagVerdict>,
    ) {
        val matchedCount: Int get() = verdicts.count { it == TagVerdict.MATCHED }
        val unknownCount: Int get() = verdicts.count { it == TagVerdict.UNKNOWN }
        val mismatchedCount: Int get() = verdicts.count { it == TagVerdict.MISMATCHED }
        val allKnown: Boolean get() = mismatchedCount == 0

        /**
         * 有证据：**每个**标签都明确命中（多标签 = 同时满足 / 交集）。
         *
         * 两层口径不同，别混：
         * - **组内**（同一个标签的 27 个写法）任意一个命中就算 —— 由
         *   [TagAlias.searchTermsOf] 展开的写法表负责（v1.9.2.0 起只有整组写法
         *   「无码组」才展开整组，普通写法只有它自己），判定时 `terms.any` 即可；
         * - **组间**（「无码 恋爱」两个标签）必须都命中 —— 用户 2026-10-27 明确要求交集。
         */
        val proven: Boolean get() = verdicts.isNotEmpty() && matchedCount == verdicts.size
    }

    /**
     * 取舍 + 排序。
     *
     * v1.9.1.1 新增 [keep]：**已经上过屏的条目永不撤回**。
     *
     * 以前的版本是「纯函数式」取舍 —— 每次拿当前候选集重新分区，proven 一旦攒够
     * [minResults] 条就把其余的全部砍掉。流式搜索里这意味着：屏幕上明明已经有 60 条，
     * 下一批数据到货让 proven 从 19 变成 20，整个列表瞬间缩成 20 条。用户看到的就是
     * 「搜着搜着突然变少」。
     *
     * 收敛（结果集随召回单调变全）是搜索的基本预期，所以这里把「已展示」当成一种状态：
     * 调用方把上过屏的 key 记下来，这里保证它们始终在输出里（排在 proven 之后）。
     * 结果就是**数量只增不减**，而「无关内容不要堆在前面」的产品意图由排序继续保证。
     *
     * @param keep 该条目是否已经展示过；为 true 时无论取舍档位如何都保留
     */
    fun rank(
        candidates: List<Candidate>,
        minResults: Int = MIN_RESULTS,
        keep: (MediaItem) -> Boolean = { false },
    ): List<MediaItem> {
        if (candidates.isEmpty()) return emptyList()
        val (proven, rest) = candidates.partition { it.proven }
        val ordered = proven
            .sortedWith(
                compareByDescending<Candidate> { it.matchedCount }.thenBy { it.unknownCount },
            )
            .map { it.item }
        // 已经上过屏的不再撤回。它们可能因为「源后来超时」从 proven 掉到 rest，
        // 但那只是判定口径变了，用户眼里那条内容并没有消失的理由。
        val sticky = rest.filter { keep(it.item) }.map { it.item }
        val supplement = if (ordered.size >= minResults) {
            emptyList()
        } else {
            // 证据不足：把「没被否掉但也没证实」的按召回顺序补上。
            // **明确不匹配的不补**（v1.8.9.2）：它们是「已经判定为无关」的，补在后面
            // 正是真机反馈的「下面还是一堆和标签没关系的东西」。
            rest.filter { it.allKnown && !keep(it.item) }.map { it.item }
        }
        val merged = ArrayList<MediaItem>(ordered.size + sticky.size + supplement.size)
        val seen = HashSet<String>(ordered.size + sticky.size + supplement.size)
        (ordered + sticky + supplement).forEach { item ->
            if (seen.add("${item.sourceId}:${item.id}")) merged += item
        }
        if (merged.isNotEmpty()) return merged
        // 一条确凿的、一条不确定的都没有：才退回明确不匹配的那批，
        // 至少别甩一个空结果页（这时用户更需要知道「确实没搜到」）。
        return rest.sortedBy { it.mismatchedCount }.map { it.item }
    }
}
