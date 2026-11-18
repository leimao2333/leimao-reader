package com.novelreader.core.util

import com.novelreader.core.text.ChineseConverter
import kotlin.math.max
import kotlin.math.min

/**
 * 书籍冲突检测 / 去重（C9）。
 *
 * **背景**：书架和搜索结果里，同一部作品常常从多个来源各进来一条（不同源站、
 * 不同版本、不同抓取时间），书名写法还千差万别——带卷次、带作者标注、繁简混用、
 * 全角半角混排。用户看到的是「书架里有五本一样的书」，需要一份结论：
 *
 * 1. 哪些条目其实是**同一本书**（[detect]）；
 * 2. 同一组里**该保留哪一条**（[ConflictGroup.recommendedId] / [rankedIds]）。
 *
 * **设计约束**：
 * - 纯逻辑，不依赖 Room / Android / 网络，能在 JVM 单测里跑；
 * - 输入是抽象的 [BookEntry]，不绑定任何实体，搬运由调用方负责；
 * - **判定必须确定性**：同样输入永远同样输出——不引入随机、不读时钟，
 *   排序一路用 id 兜底，避免 UI 上「重复项」每次刷新都换位置。
 *
 * **判定口径**（宁可漏合，不可错合）：
 * 1. 归一化书名完全相同 → 强匹配；作者也相同时置信度最高（[SCORE_IDENTICAL]），
 *    作者缺失时降一档（[SCORE_AUTHOR_MISSING]）但仍算重复；
 * 2. 书名互相包含 / 只差一个字，且**作者相同** → 匹配；
 * 3. 书名相同但**作者不同** → 判定为不同作品（同名书太常见，不能只看书名）；
 * 4. 其余（书名差异大、书名为空）→ 不算重复。
 *
 * 「作者相同」这条硬约束同时是**分组时的安全阀**：同名不同作者的两本书绝不会被
 * 归到一组，哪怕中间夹着一条没写作者的条目把两边连起来（见 [detect] 的
 * 冲突检查）。
 */

/**
 * 书籍条目的抽象视图。
 *
 * 刻意不与 Room 实体 / `MediaItem` 绑定：这个组件要能被 ViewModel、导入流程、
 * 书架合并逻辑共同复用，谁来搬运数据由谁决定。
 *
 * @param id 条目唯一 id（同一本书在不同源下应当是不同 id）
 * @param title 书名（可能带书名号、卷次、作者标注等装饰）
 * @param author 作者（可空；可能带「（著）」「[作者]」「 出品」等修饰）
 * @param sourceId 来源 id
 * @param sourceName 来源名（可空）
 * @param wordCount 字数（可空）
 * @param coverUrl 封面地址（可空）
 * @param chapterCount 章节数（可空）
 * @param updatedAt 更新时间戳（可空，毫秒）
 */
data class BookEntry(
    val id: String,
    val title: String,
    val author: String?,
    val sourceId: String,
    val sourceName: String?,
    val wordCount: Int?,
    val coverUrl: String?,
    val chapterCount: Int?,
    val updatedAt: Long?,
)

/**
 * 判定理由的取值集合。
 *
 * UI 上要能解释「为什么这两条被合并了」，所以理由必须是**有限、可枚举**的一组，
 * 而不是一段随输入变化的自由文本；具体到某一次的措辞放在 [ConflictVerdict.detail]。
 */
enum class ConflictReason(val label: String) {
    /** 书名与作者都完全一致——最强的一条证据 */
    TITLE_AND_AUTHOR_IDENTICAL("书名与作者完全相同"),

    /** 书名完全一致，但至少一方没写作者，置信度降一档 */
    TITLE_IDENTICAL_AUTHOR_MISSING("书名完全相同但作者信息缺失"),

    /** 书名高度相似（包含关系 / 一字之差）且作者相同 */
    TITLE_SIMILAR_SAME_AUTHOR("书名高度相似且作者相同"),

    /** 书名相似，但作者信息缺失，置信度打折 */
    TITLE_SIMILAR_AUTHOR_MISSING("书名相似但作者信息缺失"),

    /** 作者明确不同——同名不同书，直接否决 */
    AUTHOR_CONFLICT("作者不同，判定为不同作品"),

    /** 书名差异过大 */
    TITLE_DIFFERENT("书名差异过大"),

    /** 书名为空，没有可比对的依据 */
    TITLE_EMPTY("书名为空，无法比较"),
}

/**
 * 一对条目的判定结果。
 *
 * @param score 置信度（0~1）；[ConflictReason.AUTHOR_CONFLICT] 时恒为 0
 * @param reason 判定理由（枚举）
 * @param detail 面向用户的说明文案
 */
data class ConflictVerdict(
    val score: Float,
    val reason: ConflictReason,
    val detail: String,
)

/**
 * 一组冲突条目（同一本书的多个来源 / 版本）。
 *
 * @param members 组内条目，按归一化书名 + 作者 + id 的固定顺序排列（与输入顺序无关）
 * @param score 组置信度，取组内**最弱的一对**的分值（保守口径）
 * @param reason 面向用户的判定理由文案
 * @param recommendedId 建议保留的条目 id
 * @param rankedIds 候选保留顺序（最优 → 最差）
 * @param reasonCode 判定理由的枚举值，便于 UI 做分支处理
 */
data class ConflictGroup(
    val members: List<BookEntry>,
    val score: Float,
    val reason: String,
    val recommendedId: String,
    val rankedIds: List<String>,
    val reasonCode: ConflictReason,
)

/**
 * 书籍冲突检测器（纯逻辑）。
 *
 * 见文件头注释：判定口径、确定性约束、置信度分级都在那里。
 */
object BookConflictDetector {

    /** 默认判定阈值：相似度 ≥ 该值才算「同一本书」 */
    const val DEFAULT_THRESHOLD = 0.9f

    /** 书名与作者都完全一致时的置信度 */
    const val SCORE_IDENTICAL = 1.0f

    /** 书名完全一致但作者缺失时的置信度（比完全一致低一档，仍高于默认阈值） */
    const val SCORE_AUTHOR_MISSING = 0.92f

    /**
     * 作者信息缺失时对书名相似度的折扣。
     *
     * 没有作者就没法排除「同名不同书」，所以打折；但书名完全一致时走
     * [SCORE_AUTHOR_MISSING] 这一档（更高），两者要分开看。
     */
    const val AUTHOR_MISSING_FACTOR = 0.92f

    // ---------------------------------------------------------------------
    // 归一化
    // ---------------------------------------------------------------------

    /**
     * 书名归一化：把「同一个书名的各种写法」压成同一个键。
     *
     * 处理顺序有意如此：
     * 1. 全角 → 半角（中文站点的全角书名极常见，不转换会全部对不上）；
     * 2. 转小写（英文书名的 `Harry Potter` / `HARRY POTTER` 是同一部）；
     * 3. 括号段处理：卷次（「(1)」「第2卷」「上」）与作者/版本标注
     *    （「(著)」「完结」「无删减」）整段丢掉，其余括号只脱掉括号本身；
     * 4. 独立出现的「第N卷/部/篇」也丢掉；
     * 5. 只保留文字与数字（标点、书名号、空格一并清掉）；
     * 6. 繁体 → 简体（`無碼` 与 `无码` 是同一部）。
     *
     * 第 3 步里的括号内容判定刻意保守：只有确定是「卷次」或「角色/版本标注」
     * 的才整段丢弃，其余（如「（黑暗森林）」这种副标题）保留内容，
     * 否则会把《三体》和《三体Ⅱ：黑暗森林》抹成同一个键。
     */
    fun normalizeTitle(raw: String): String = normalizeTitleInternal(raw, null)

    /**
     * 作者归一化：把「同一个作者的各种写法」压成同一个键。
     *
     * 处理顺序：
     * 1. 全角 → 半角、转小写；
     * 2. 括号段：内容是角色词（「著」「编著」「译者」「作者」）就整段丢掉，
     *    否则只脱掉括号（`[张三]` → `张三`）；
     * 3. 按常见分隔符拆成多作者，各自清洗后**排序**再拼接——
     *    这样「张三/李四」和「李四,张三」能对上，多作者作品跨源不易漏合；
     * 4. 剥掉首尾的角色词（`张三 著`、`XX出品`、`作者：张三`）；
     * 5. 只保留文字与数字，繁体转简体；
     * 6. 「佚名 / 未知 / unknown」这类占位作者一律当成**没有作者**（返回 null），
     *    否则两个都写「佚名」的书会被误判成同作者。
     *
     * 返回 null 表示「作者不可用」，调用方要按缺失作者的口径处理。
     */
    fun normalizeAuthor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = toHalfWidth(raw.trim()).lowercase()
        s = processBrackets(s, dropAuthorKey = null)
        val parts = s.split(AUTHOR_SEPARATOR)
            .map { stripRoleTokens(it.trim()) }
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .map { ChineseConverter.toSimplified(it) }
            .filter { it !in AUTHOR_PLACEHOLDERS }
            .distinct()
        if (parts.isEmpty()) return null
        return parts.sorted().joinToString(AUTHOR_JOIN)
    }

    // ---------------------------------------------------------------------
    // 判定
    // ---------------------------------------------------------------------

    /**
     * 两本书的相似度（0~1）。
     *
     * 值本身就是判定结果：≥ 阈值即视为同一本书。作者不同则直接返回 0，
     * 不给「书名一样就够」的余地。
     */
    fun similarity(a: BookEntry, b: BookEntry): Float = evaluate(a, b).score

    /**
     * 两本书的完整判定：置信度 + 理由 + 说明文案。
     *
     * UI 想解释「为什么判定为重复」时用它；只想拿个分数时用 [similarity]。
     */
    fun evaluate(a: BookEntry, b: BookEntry): ConflictVerdict =
        evaluatePrepared(prepare(a), prepare(b))

    /**
     * 两本书的作者是否明确冲突（都写了作者且不相同）。
     *
     * 这是分组时的安全阀：同名不同作者的书绝不能进同一组。
     */
    fun authorsConflict(a: BookEntry, b: BookEntry): Boolean {
        val ka = normalizeAuthor(a.author)
        val kb = normalizeAuthor(b.author)
        return ka != null && kb != null && ka != kb
    }

    /**
     * 把一批条目聚成冲突组（组内 ≥ 2 条才算冲突组）。
     *
     * 算法（贪心 + 冲突检查，全程确定性）：
     * 1. **同 id 去重**：同一个 id 就是同一条记录，保留信息更完整的那份
     *    （章节 / 字数 / 封面 / 更新时间依次比较），避免一个 id 在结果里出现两次；
     * 2. **固定顺序**：按（归一化书名, 归一化作者, id）排序后再处理，
     *    因此结果与调用方传入的顺序无关；
     * 3. **贪心成组**：依次把条目放进第一个「能装下它」的组，装不下的另开一组。
     *    「能装下」= 组内没有任何成员与它作者冲突，且组内至少有一个成员与它
     *    相似度 ≥ 阈值。作者冲突的检查是必需的：没有它，一条没写作者的条目
     *    会把「同名不同作者」的两本书连成一组，导致误删。
     *
     * @param entries 待检测条目（可含不同来源的同一本书）
     * @param threshold 判定阈值，默认 [DEFAULT_THRESHOLD]
     * @param sourceWeights 来源权重（可选），值越大越「权威」，用于保留建议排序
     */
    fun detect(
        entries: List<BookEntry>,
        threshold: Float = DEFAULT_THRESHOLD,
        sourceWeights: Map<String, Int> = emptyMap(),
    ): List<ConflictGroup> {
        if (entries.isEmpty()) return emptyList()
        val th = threshold.coerceIn(0.01f, 1.0f)
        val prepared = prepare(entries, sourceWeights)
        val groups = ArrayList<MutableList<Prepared>>()
        for (item in prepared) {
            var placed = false
            for (group in groups) {
                var conflict = false
                var best = 0f
                for (member in group) {
                    val verdict = evaluatePrepared(member, item)
                    if (verdict.reason == ConflictReason.AUTHOR_CONFLICT) {
                        conflict = true
                        break
                    }
                    if (verdict.score > best) best = verdict.score
                }
                if (!conflict && best >= th) {
                    group.add(item)
                    placed = true
                    break
                }
            }
            if (!placed) groups.add(mutableListOf(item))
        }
        return groups
            .filter { it.size >= 2 }
            .map { buildGroup(it, sourceWeights) }
    }

    /**
     * 保留建议的排序：最优 → 最差。
     *
     * 优先级（依次比较，先分出胜负才看下一条）：
     * 1. 章节数多——内容更完整；
     * 2. 字数多——同样指向完整度；
     * 3. 有封面——书架上没封面的条目观感最差；
     * 4. 更新时间新——更新的版本通常更接近源站现状；
     * 5. 来源权重大——由调用方给出（默认全 0，退化成按 id 稳定排序）；
     * 6. id 字典序——最终兜底，保证结果确定。
     */
    fun rank(entries: List<BookEntry>, sourceWeights: Map<String, Int> = emptyMap()): List<BookEntry> =
        entries.sortedWith(keeperOrder(sourceWeights))

    /**
     * 保留建议：给一组条目挑出最该留下的那一条。
     *
     * 排序规则见 [rank]。
     */
    fun recommend(entries: List<BookEntry>, sourceWeights: Map<String, Int> = emptyMap()): BookEntry? =
        entries.minWithOrNull(keeperOrder(sourceWeights))

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    /** 内部用的条目视图：预先算好归一化结果，避免成对比较时反复跑正则 */
    private class Prepared(
        val entry: BookEntry,
        val authorKey: String?,
        /** 不做「剔除书名里的作者」时的归一化书名 */
        val plainTitle: String,
        /** 剔除「书名括号里写的就是自己的作者」之后的归一化书名 */
        val ownTitle: String,
        /** 书名里是否带括号——决定要不要算额外的书名变体 */
        val hasBrackets: Boolean,
    ) {
        /**
         * 书名变体缓存。
         *
         * 「按对方作者再算一遍书名」这一步要跑一遍正则，成对比较时会被调用很多次，
         * 按（作者键）缓存下来即可，书架几百条时能省掉大量重复计算。
         */
        val variantCache = HashMap<String, String>()
    }

    private fun prepare(entry: BookEntry): Prepared {
        val authorKey = normalizeAuthor(entry.author)
        return Prepared(
            entry = entry,
            authorKey = authorKey,
            plainTitle = normalizeTitle(entry.title),
            ownTitle = normalizeTitleInternal(entry.title, authorKey),
            hasBrackets = BRACKET_PAIR.containsMatchIn(entry.title),
        )
    }

    private fun prepare(entries: List<BookEntry>, sourceWeights: Map<String, Int>): List<Prepared> {
        // 同 id 去重：同一个 id 就是同一条记录，留信息更完整的那份
        val byId = LinkedHashMap<String, BookEntry>()
        val order = keeperOrder(sourceWeights)
        for (e in entries) {
            val previous = byId[e.id]
            if (previous == null) byId[e.id] = e
            else if (order.compare(e, previous) < 0) byId[e.id] = e
        }
        val list = byId.values.map { prepare(it) }
        // 固定顺序：结果与传入顺序无关
        return list.sortedWith(compareBy({ it.plainTitle }, { it.authorKey ?: "" }, { it.entry.id }))
    }

    private fun evaluatePrepared(a: Prepared, b: Prepared): ConflictVerdict {
        if (a.ownTitle.isEmpty() || b.ownTitle.isEmpty()) {
            return ConflictVerdict(0f, ConflictReason.TITLE_EMPTY, ConflictReason.TITLE_EMPTY.label)
        }
        val sameAuthor = a.authorKey != null && a.authorKey == b.authorKey
        val conflict = a.authorKey != null && b.authorKey != null && a.authorKey != b.authorKey
        if (conflict) {
            return ConflictVerdict(
                0f,
                ConflictReason.AUTHOR_CONFLICT,
                "作者不同（${a.authorKey} / ${b.authorKey}），判定为不同作品",
            )
        }
        val titleScore = titleSimilarity(a, b)
        return when {
            titleScore >= 1f && sameAuthor -> ConflictVerdict(
                SCORE_IDENTICAL,
                ConflictReason.TITLE_AND_AUTHOR_IDENTICAL,
                "归一化书名与作者完全一致",
            )

            titleScore >= 1f -> ConflictVerdict(
                SCORE_AUTHOR_MISSING,
                ConflictReason.TITLE_IDENTICAL_AUTHOR_MISSING,
                "归一化书名完全一致，但作者信息缺失，置信度较低",
            )

            titleScore <= 0f -> ConflictVerdict(
                0f,
                ConflictReason.TITLE_DIFFERENT,
                ConflictReason.TITLE_DIFFERENT.label,
            )

            sameAuthor -> ConflictVerdict(
                titleScore,
                ConflictReason.TITLE_SIMILAR_SAME_AUTHOR,
                "归一化书名高度相似且作者一致",
            )

            else -> ConflictVerdict(
                titleScore * AUTHOR_MISSING_FACTOR,
                ConflictReason.TITLE_SIMILAR_AUTHOR_MISSING,
                "归一化书名相似但作者信息缺失，置信度较低",
            )
        }
    }

    private fun buildGroup(members: List<Prepared>, sourceWeights: Map<String, Int>): ConflictGroup {
        // 组置信度取最弱的一对：组里只要有一对站不住，整组就不该被当成「确认重复」
        var worstScore = Float.MAX_VALUE
        var worst: ConflictVerdict? = null
        for (i in members.indices) {
            for (j in i + 1 until members.size) {
                val verdict = evaluatePrepared(members[i], members[j])
                if (verdict.score < worstScore) {
                    worstScore = verdict.score
                    worst = verdict
                }
            }
        }
        val ranked = members.map { it.entry }.sortedWith(keeperOrder(sourceWeights))
        val reason = worst?.reason ?: ConflictReason.TITLE_DIFFERENT
        return ConflictGroup(
            members = members.map { it.entry },
            score = if (worst == null) 0f else worstScore,
            reason = worst?.detail ?: reason.label,
            recommendedId = ranked.first().id,
            rankedIds = ranked.map { it.id },
            reasonCode = reason,
        )
    }

    /**
     * 书名相似度：取多种归一化变体之间的最大值。
     *
     * 变体来源：
     * - 书名里没括号 → 只有一个变体（绝大多数情况）；
     * - 书名里带括号 → 额外算「把括号内容当成对方作者剔除」的变体，
     *   这样「三体（刘慈欣）」和「三体」也能对上。
     */
    private fun titleSimilarity(a: Prepared, b: Prepared): Float {
        val av = titleVariants(a, b)
        val bv = titleVariants(b, a)
        var best = 0f
        for (x in av) {
            for (y in bv) {
                val s = titleSimilarityOf(x, y)
                if (s > best) best = s
            }
        }
        return best
    }

    private fun titleVariants(a: Prepared, other: Prepared): List<String> {
        if (!a.hasBrackets) return listOf(a.ownTitle)
        val out = ArrayList<String>(3)
        out.add(a.ownTitle)
        if (a.plainTitle != a.ownTitle) out.add(a.plainTitle)
        val otherKey = other.authorKey
        if (otherKey != null && otherKey != a.authorKey) {
            val variant = a.variantCache.getOrPut(otherKey) {
                normalizeTitleInternal(a.entry.title, otherKey)
            }
            if (variant != a.ownTitle && variant != a.plainTitle) out.add(variant)
        }
        return out
    }

    /**
     * 两个归一化书名的相似度（0~1）。
     *
     * 中文书名不能按字符粗暴比较：
     * - 单字书名（「爱」「三」）和一个长书名包含它，字符重合度极高但显然不是同一本；
     * - 四字书名差一个字（「斗破苍穹」/「斗破苍茫」）和两字书名差一个字
     *   （「三体」/「三生」）危险程度完全不同。
     * 所以这里按「短的一方有多长」设门槛：太短的标题要求更强的证据。
     */
    private fun titleSimilarityOf(t1: String, t2: String): Float {
        if (t1.isEmpty() || t2.isEmpty()) return 0f
        if (t1 == t2) return 1f
        val minLen = min(t1.length, t2.length)
        val maxLen = max(t1.length, t2.length)
        // 长度差超过较短者本身：不可能是同一本书
        if (maxLen - minLen > minLen) return 0f
        // 包含关系：一方是另一方的子串
        if (t1.contains(t2) || t2.contains(t1)) {
            if (minLen < 2) return 0f
            return 0.88f + 0.10f * (minLen.toFloat() / maxLen)
        }
        if (!hasEnoughOverlap(t1, t2)) return 0f
        val distance = levenshtein(t1, t2)
        // 一字之差通常来自错别字 / 版本差异，但只在标题不算太短时才敢这么判
        if (distance == 1 && minLen >= 3) return 0.93f
        return 1f - distance.toFloat() / maxLen
    }

    /**
     * 便宜的字符重合度预筛：重合不足一半直接判定为不相似。
     *
     * 只是为了少跑几次编辑距离，判定结果不受影响。
     */
    private fun hasEnoughOverlap(t1: String, t2: String): Boolean {
        val chars = HashSet<Char>(t1.length)
        for (c in t1) chars.add(c)
        var hits = 0
        for (c in t2) if (chars.contains(c)) hits++
        return hits * 2 >= t1.length
    }

    /** 编辑距离（Levenshtein），两行滚动数组 */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** 保留建议的排序规则；见 [rank] 的说明 */
    private fun keeperOrder(sourceWeights: Map<String, Int>): Comparator<BookEntry> =
        compareByDescending<BookEntry> { it.chapterCount ?: -1 }
            .thenByDescending { it.wordCount ?: -1 }
            .thenByDescending { !it.coverUrl.isNullOrBlank() }
            .thenByDescending { it.updatedAt ?: Long.MIN_VALUE }
            .thenByDescending { sourceWeights[it.sourceId] ?: 0 }
            .thenBy { it.sourceName ?: "" }
            .thenBy { it.id }

    // ---------------------------------------------------------------------
    // 文本清洗
    // ---------------------------------------------------------------------

    /**
     * 书名归一化的内部实现。
     *
     * @param dropAuthorKey 当书名括号里的内容等于这个作者键时，整段丢掉
     *   （「三体（刘慈欣）」遇到作者「刘慈欣」时应当归一成「三体」）
     */
    private fun normalizeTitleInternal(raw: String, dropAuthorKey: String?): String {
        if (raw.isBlank()) return ""
        var s = toHalfWidth(raw.trim()).lowercase()
        s = processBrackets(s, dropAuthorKey)
        s = STANDALONE_VOLUME.replace(s, " ")
        s = s.filter { it.isLetterOrDigit() }
        if (s.isEmpty()) return ""
        return ChineseConverter.toSimplified(s)
    }

    /**
     * 括号段处理。
     *
     * 括号内容属于「卷次」或「角色/版本标注」时整段丢掉，其余只脱掉括号本身。
     * 括号里的内容可能就是作者（dropAuthorKey 命中时同样整段丢掉）。
     */
    private fun processBrackets(text: String, dropAuthorKey: String?): String {
        if (!BRACKET_PAIR.containsMatchIn(text)) return text
        val out = StringBuilder(text.length)
        var last = 0
        for (match in BRACKET_PAIR.findAll(text)) {
            out.append(text, last, match.range.first)
            val inner = match.groupValues[1].trim()
            val kept = keepBracketContent(inner, dropAuthorKey)
            if (kept != null) out.append(kept)
            last = match.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    /** 返回 null 表示整段丢弃；返回字符串表示保留（可能是去掉括号后的内容） */
    private fun keepBracketContent(inner: String, dropAuthorKey: String?): String? {
        if (inner.isEmpty()) return null
        // 卷次标记：「(1)」「第2卷」「上」「下」
        if (VOLUME_EXACT.contains(inner)) return null
        if (VOLUME_NUMBER.matches(inner)) return null
        if (VOLUME_ORDINAL.matches(inner)) return null
        // 角色 / 版本标注：「著」「编著」「完结」「无删减」……
        if (DECORATION_WORDS.any { inner.contains(it) }) return null
        // 括号里写的就是作者本人
        if (dropAuthorKey != null && normalizeAuthor(inner) == dropAuthorKey) return null
        return inner
    }

    /**
     * 剥掉首尾的角色词：`张三 著`、`XX出品`、`作者：张三`。
     *
     * 只剥首尾，不动中间——中间的「著」多半是书名的一部分。
     */
    private fun stripRoleTokens(part: String): String {
        var s = part.trim()
        var changed = true
        while (changed) {
            changed = false
            for (token in ROLE_TOKENS) {
                if (s.length > token.length && s.startsWith(token)) {
                    s = s.removePrefix(token)
                    changed = true
                    break
                }
                if (s.length > token.length && s.endsWith(token)) {
                    s = s.removeSuffix(token)
                    changed = true
                    break
                }
            }
        }
        // 整个部分就是一个角色词（「著」「作者」）时视为没有内容
        if (ROLE_TOKENS.any { it == s }) return ""
        return s.trim()
    }

    /** 全角 → 半角（含全角空格） */
    private fun toHalfWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val code = c.code
            sb.append(
                when {
                    code == 0x3000 -> ' '
                    code in 0xFF01..0xFF5E -> (code - 0xFEE0).toChar()
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // 词表 / 正则
    // ---------------------------------------------------------------------

    /** 括号对：中文 / 英文 / 方头括号 */
    private val BRACKET_PAIR = Regex(
        "[（(【\\[｛{〈「『《〔〖]([^（()【】\\[\\]｛｝〈〉「」『』《》〔〕〖〗]{0,60})[）)】\\]｝}〉」』》〕〗]"
    )

    /** 独立的「第N卷/部/篇/集/章/回」标记 */
    private val STANDALONE_VOLUME = Regex("第\\s*[0-9０-９一二三四五六七八九十百千两]+\\s*[卷部篇集章节回]")

    /** 括号里是纯数字：几乎一定是卷号 */
    private val VOLUME_NUMBER = Regex("[0-9０-９]+")

    /** 括号里是「第N卷」这类序数标记 */
    private val VOLUME_ORDINAL = Regex("第?[0-9０-９一二三四五六七八九十百千两]+[卷部篇集章节回]?")

    /** 括号里是单独的上/中/下/全/完：卷册标记 */
    private val VOLUME_EXACT = setOf("上", "中", "下", "全", "完")

    /** 括号里出现这些词时整段丢弃：角色标注或版本标注 */
    private val DECORATION_WORDS = listOf(
        "著", "編著", "编著", "编译", "編譯", "译者", "譯者", "译者", "原作者", "原作",
        "出品", "制作", "製作", "校对", "校對", "编辑", "編輯", "整理", "作者", "筆名",
        "笔名", "又名", "作品", "完结", "完結", "完本", "全本", "全集", "精校",
        "无删减", "未删减", "高清", "完整版", "免费", "连载", "連載", "正版", "首发",
        "txt", "下载", "最新",
    )

    /** 作者字段里要剥掉的角色词（长的优先，避免「编著」被「著」先吃掉） */
    private val ROLE_TOKENS = listOf(
        "编著者", "著作者", "原作者", "笔名", "又名", "编著", "編著", "主编", "主編",
        "出品", "制作", "製作", "校对", "校對", "编辑", "編輯", "整理", "译者", "譯者",
        "编译", "編譯", "作者", "原作", "原著", "合著", "著", "编", "編", "译", "譯", "by",
    )

    /** 多作者分隔符（不含「·」——它可能是名字的一部分，交给标点清洗处理） */
    private val AUTHOR_SEPARATOR = Regex("[,，、/／|｜&＆;；\\s]+")

    /** 多作者拼接符 */
    private const val AUTHOR_JOIN = "|"

    /** 占位作者：等同于「没有作者」 */
    private val AUTHOR_PLACEHOLDERS = setOf(
        "佚名", "匿名", "无名氏", "无名", "未知", "不详", "暂无", "暂缺", "作者",
        "unknown", "unknow", "anon", "anonymous", "none", "null", "undefined", "na",
        "著", "编", "编著", "译",
    )
}
