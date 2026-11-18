package com.novelreader.core.text

/**
 * 正文处理流水线的阶段。
 *
 * 之所以用枚举而不是字符串 / 下标：阶段顺序要能被调用方配置（阅读页、搜索页、
 * 后台体检的诉求各不相同），枚举能在编译期挡掉拼错的阶段名。
 */
enum class PipelineStage {
    /** 净化：统一换行符、清控制字符、去多余空白、折叠空行 */
    SANITIZE,

    /** 替换：应用用户自定义替换规则（[ReplaceRule]） */
    REPLACE,

    /** 高亮：给正文里命中的搜索词打标记 */
    HIGHLIGHT,

    /** 段首缩进：给每个段落加缩进前缀 */
    INDENT,

    /** 质量检查：只做测量，不改动正文 */
    QUALITY,
}

/**
 * 一处高亮命中。
 *
 * @param term 命中的词（被标记包住的那段原文）
 * @param start 在**最终正文**（[PipelineResult.text]）中的起始下标（含）
 * @param end 在最终正文中的结束下标（不含）
 */
data class HighlightHit(
    val term: String,
    val start: Int,
    val end: Int,
)

/**
 * 单个阶段的状态摘要。
 *
 * 每个子类型对应一个阶段，字段含义见各自的注释。
 *
 * 关键取舍：这里记录的是「处理后正文的**状态量**」而不是「本轮改了多少」。
 * 增量统计（删掉几行、加了几个标记）天生不幂等 —— 同一段正文处理两次，
 * 第二次的增量必然是 0，两次的结果对象就永远不相等，幂等约束形同虚设。
 * 状态量则任何时候都一致，还能反过来回答「正文里还有几处没替换干净」。
 */
sealed class StageSummary {

    /** 该摘要对应的阶段 */
    abstract val stage: PipelineStage

    /** 面向 UI 的一句话说明（可直接展示） */
    abstract val note: String

    /**
     * 净化：正文里剩余的空行组数与有害控制字符数。
     *
     * 两者都应为 0（或达到配置上限）才算净化干净 —— 用状态量而不是「删掉了几行」，
     * 是为了让同一个结果对象可以被缓存、被比较。
     */
    data class Sanitize(
        val blankLineGroups: Int,
        val controlChars: Int,
        override val note: String,
    ) : StageSummary() {
        override val stage: PipelineStage get() = PipelineStage.SANITIZE
    }

    /**
     * 替换：已处理干净的规则条数、正文里仍残留的模式匹配次数。
     *
     * 「已处理干净」= 该规则的模式在最终正文里已无残留（要么被替换掉了，
     * 要么本来就不匹配）；「残留」则直接告诉 UI「还有几处没替换掉」。
     */
    data class Replace(
        val appliedRules: Int,
        val remainingMatches: Int,
        override val note: String,
    ) : StageSummary() {
        override val stage: PipelineStage get() = PipelineStage.REPLACE
    }

    /** 高亮：命中的处数、参与匹配的搜索词个数 */
    data class Highlight(
        val hits: Int,
        val terms: Int,
        override val note: String,
    ) : StageSummary() {
        override val stage: PipelineStage get() = PipelineStage.HIGHLIGHT
    }

    /** 段首缩进：已经带上缩进前缀的段落数 */
    data class Indent(
        val indentedParagraphs: Int,
        override val note: String,
    ) : StageSummary() {
        override val stage: PipelineStage get() = PipelineStage.INDENT
    }

    /** 质量检查：综合分与是否低质量 */
    data class Quality(
        val score: Int,
        val lowQuality: Boolean,
        override val note: String,
    ) : StageSummary() {
        override val stage: PipelineStage get() = PipelineStage.QUALITY
    }

    /** 该阶段没跑（开关关闭 / 缺少必要参数） */
    data class Skipped(
        override val stage: PipelineStage,
        override val note: String,
    ) : StageSummary()
}

/**
 * 正文流水线的配置：各阶段的**开关**与**参数**。
 *
 * 所有开关默认关闭 —— 流水线是纯配置驱动的，调用方不显式打开任何阶段时，
 * 正文原样返回（零行为变化），既有调用点不会被隐式改变。
 * 想跑一条完整的阅读链路用 [ContentPipelineConfig.reading]。
 */
data class ContentPipelineConfig(

    // ===== 净化 =====

    /** 是否执行净化阶段（下面几项是它的子开关） */
    val sanitize: Boolean = false,

    /** 净化时是否统一换行符（`\r\n` / `\r` → `\n`） */
    val normalizeLineBreaks: Boolean = true,

    /** 净化时是否删除控制字符（含 BOM；换行符保留） */
    val stripControlChars: Boolean = true,

    /** 净化时是否去掉行尾空白与整体首尾空白 */
    val trimWhitespace: Boolean = true,

    /** 净化时是否折叠过多空行 */
    val collapseBlankLines: Boolean = true,

    /** 最多保留几个连续空行（0 = 段落之间不留空行） */
    val maxBlankLines: Int = 1,

    // ===== 替换 =====

    /** 替换规则列表（用既有 [ReplaceRule] 类型，编解码仍走 [TextReplaceRules]） */
    val replaceRules: List<ReplaceRule> = emptyList(),

    // ===== 高亮 =====

    /** 搜索词高亮：正文里命中的词会被打上标记（用于正文内搜索命中标注） */
    val highlightTerms: List<String> = emptyList(),

    /** 高亮标记的开始符 */
    val highlightStart: String = DEFAULT_HIGHLIGHT_START,

    /** 高亮标记的结束符 */
    val highlightEnd: String = DEFAULT_HIGHLIGHT_END,

    /** 高亮是否忽略大小写 */
    val highlightIgnoreCase: Boolean = false,

    // ===== 段首缩进 =====

    /** 是否给每个段落加段首缩进 */
    val indentEnabled: Boolean = false,

    /** 缩进字符（默认全角空格） */
    val indentChar: String = DEFAULT_INDENT_CHAR,

    /** 缩进宽度（缩进字符重复几次） */
    val indentWidth: Int = 2,

    // ===== 质量检查 =====

    /** 是否做正文质量检查（用 [ContentQualityChecker]） */
    val qualityCheck: Boolean = false,

    // ===== 阶段顺序 =====

    /** 阶段执行顺序；高亮会被强制挪到替换之后（见 [ContentPipeline] 的顺序约束） */
    val stageOrder: List<PipelineStage> = DEFAULT_STAGE_ORDER,
) {

    companion object {

        /** 默认阶段顺序：净化 → 替换 → 高亮 → 段首缩进 → 质量检查 */
        val DEFAULT_STAGE_ORDER: List<PipelineStage> = listOf(
            PipelineStage.SANITIZE,
            PipelineStage.REPLACE,
            PipelineStage.HIGHLIGHT,
            PipelineStage.INDENT,
            PipelineStage.QUALITY,
        )

        /** 默认高亮标记：用不常见的符号，避免和正文里的标点撞车 */
        const val DEFAULT_HIGHLIGHT_START: String = "«"
        const val DEFAULT_HIGHLIGHT_END: String = "»"

        /** 默认缩进字符：全角空格（中文排版惯例） */
        const val DEFAULT_INDENT_CHAR: String = "　"

        /**
         * 典型阅读链路的预设：净化 + 质量检查。
         *
         * 段首缩进默认不开 —— 缩进属于「排版偏好」，交由阅读页的排版层决定更灵活；
         * 需要时用 [indentEnabled] 打开。
         */
        fun reading(
            replaceRules: List<ReplaceRule> = emptyList(),
            highlightTerms: List<String> = emptyList(),
            indentEnabled: Boolean = false,
        ): ContentPipelineConfig = ContentPipelineConfig(
            sanitize = true,
            replaceRules = replaceRules,
            highlightTerms = highlightTerms,
            indentEnabled = indentEnabled,
            qualityCheck = true,
        )
    }
}

/**
 * 流水线处理结果。
 *
 * @param text 处理后的正文
 * @param stageOrder 实际生效的阶段顺序（已施加「高亮必须在替换之后」的约束）
 * @param summaries 每个阶段的状态摘要，顺序与 [stageOrder] 一一对应
 * @param highlights 高亮命中（词 + 在 [text] 中的位置）；高亮阶段没跑时为空
 * @param quality 质量快照；没做质量检查（或正文为空）时为 null
 * @param lowQuality 是否被判定为低质量正文
 * @param orderAdjusted 是否因为顺序约束调整过调用方给的顺序
 */
data class PipelineResult(
    val text: String,
    val stageOrder: List<PipelineStage>,
    val summaries: List<StageSummary>,
    val highlights: List<HighlightHit>,
    val quality: ContentQuality?,
    val lowQuality: Boolean,
    val orderAdjusted: Boolean,
)

/**
 * 正文处理流水线（B8）。
 *
 * 把原本散落各处的正文处理收成一条**可配置**的流水线：
 * 净化 → 替换 → 高亮 → 段首缩进（+ 质量检查）。
 * 既有组件（[TextCleaner] / [TextReplaceRules] / [ContentQualityChecker]）都只是被**调用**，
 * 不做修改；流水线只负责「按什么顺序、带着什么参数、产出什么摘要」。
 *
 * 设计取舍：
 * - **纯 Kotlin**（只用 JDK 与本包既有组件），不碰 Android / Compose，
 *   能在 JVM 单测里跑，也能放后台线程批量跑；
 * - **摘要记录「处理后正文的状态量」而不是「本轮改了多少」**：
 *   增量统计会让同一段正文处理两次得到两份不同的摘要（第二次必然是 0 增量），
 *   破坏幂等；状态量则任何时候都一致，还能反过来回答「正文里还有几处没替换干净」；
 * - **阶段幂等**：同一段正文处理两次，结果对象完全相等 —— 高亮不会重复打标记、
 *   段首缩进不会叠成四格、净化不会二次折叠、质量分不随处理次数漂移；
 *   替换阶段的幂等性取决于用户规则本身是否收敛（规则把 `a` 换成 `aa` 就不收敛），
 *   流水线不对用户规则做额外迭代，只保证「收敛规则处理两次 == 处理一次」；
 * - **不吞异常**：单条替换规则出错（非法正则）由 [TextReplaceRules] 自己跳过；
 *   流水线自身对空正文 / 纯空白 / 乱字符一律不抛异常。
 */
object ContentPipeline {

    /**
     * 处理正文。
     *
     * @param raw 原始正文；**null 视为空串**（调用方常从数据库 / 网络拿到可空正文）
     * @param config 处理配置
     */
    fun process(raw: String?, config: ContentPipelineConfig): PipelineResult {
        val source = raw ?: ""
        val plan = resolveOrder(config)
        // 高亮是否真的会跑：只有跑高亮才需要保护标记区
        val highlightActive = PipelineStage.HIGHLIGHT in plan.stages && canHighlight(config)

        var text = source
        val executed = mutableSetOf<PipelineStage>()

        for (stage in plan.stages) {
            when (stage) {
                PipelineStage.SANITIZE -> {
                    if (config.sanitize) {
                        text = sanitize(text, config)
                        executed += stage
                    }
                }

                PipelineStage.REPLACE -> {
                    if (config.replaceRules.isNotEmpty()) {
                        text = applyReplace(text, config, highlightActive)
                        executed += stage
                    }
                }

                PipelineStage.HIGHLIGHT -> {
                    if (highlightActive) {
                        text = applyHighlight(text, config)
                        executed += stage
                    }
                }

                PipelineStage.INDENT -> {
                    if (config.indentEnabled && config.indentChar.isNotEmpty() && config.indentWidth > 0) {
                        text = applyIndent(text, config)
                        executed += stage
                    }
                }

                PipelineStage.QUALITY -> {
                    // 这里只记「跑过」，测量放在全部阶段之后（见 [qualityTarget]）
                    if (config.qualityCheck) executed += stage
                }
            }
        }

        // 高亮命中统一在**最终正文**上重新扫一遍：
        // 高亮之后还可能有别的阶段改动正文（缩进会挪动位置），
        // 事后扫描能让「词 + 位置」永远与最终正文对得上，
        // 也让「同一段正文处理两次」得到同一份命中列表。
        val highlights = if (PipelineStage.HIGHLIGHT in executed) {
            scanHighlights(text, config.highlightStart, config.highlightEnd)
        } else {
            emptyList()
        }

        // 质量检查测的是最终正文（去掉流水线内部的高亮标记），
        // 所以它在顺序表里的位置不影响结论 —— 质量只跟内容有关。
        // 空正文不扫描：空白正文由上层的「本章暂无内容」链路兜底，
        // 这里扫一遍既没意义也会刷屏（与既有仓库层处理口径一致）。
        val qualitySubject = if (PipelineStage.QUALITY in executed) {
            qualityTarget(text, config, highlightActive)
        } else {
            null
        }
        val quality = qualitySubject?.let { ContentQualityChecker.check(it) }
        // 判低质量直接用既有组件的判定口径（字数 / 广告 / 乱码三选一），
        // 不在这里复制一份阈值 —— 阈值改了只改一处，免得两处口径漂移。
        val lowQuality = qualitySubject != null &&
            ContentQualityChecker.isLikelyEmptyOrAd(qualitySubject)

        val summaries = plan.stages.map { stage ->
            summarize(stage, text, config, executed, quality, lowQuality, highlights)
        }

        return PipelineResult(
            text = text,
            stageOrder = plan.stages,
            summaries = summaries,
            highlights = highlights,
            quality = quality,
            lowQuality = lowQuality,
            orderAdjusted = plan.adjusted,
        )
    }

    // ------------------------------------------------------------------
    // 阶段顺序
    // ------------------------------------------------------------------

    /**
     * 阶段顺序约束（**硬约束**）：**高亮一定在替换之后**。
     *
     * 为什么：高亮靠往正文里插入标记（[ContentPipelineConfig.highlightStart] /
     * [ContentPipelineConfig.highlightEnd]）来标注命中位置，而替换规则是用户自定义的、
     * 可能命中这些标记字符。要是让替换跑在高亮后面，用户一条「删掉 « »」之类的规则
     * 就会把标记抹掉，高亮信息（词 + 位置）直接丢失，UI 只能显示一份没有高亮的正文。
     *
     * 所以无论调用方把阶段排成什么样，流水线都会把高亮挪到替换之后，
     * 并在 [PipelineResult.orderAdjusted] 上标出「顺序被调整过」。
     * 替换阶段压根不在顺序里时（没有规则可替）就不需要这个约束。
     */
    private data class OrderPlan(val stages: List<PipelineStage>, val adjusted: Boolean)

    private fun resolveOrder(config: ContentPipelineConfig): OrderPlan {
        val requested = config.stageOrder.ifEmpty { ContentPipelineConfig.DEFAULT_STAGE_ORDER }
        // 去重但保留首次出现的顺序：重复列同一阶段没有意义，
        // 但也不能因此让阶段跑两遍（高亮跑两遍就会重复打标记）。
        val distinct = requested.distinct()
        val highlightIdx = distinct.indexOf(PipelineStage.HIGHLIGHT)
        val replaceIdx = distinct.indexOf(PipelineStage.REPLACE)
        if (highlightIdx >= 0 && replaceIdx >= 0 && highlightIdx < replaceIdx) {
            val fixed = distinct.toMutableList()
            fixed.removeAt(highlightIdx)
            // 移除高亮不影响替换的下标（highlightIdx < replaceIdx），
            // 插到 replaceIdx 处正好落在替换之后。
            fixed.add(replaceIdx, PipelineStage.HIGHLIGHT)
            return OrderPlan(fixed, true)
        }
        return OrderPlan(distinct, false)
    }

    // ------------------------------------------------------------------
    // 各阶段实现
    // ------------------------------------------------------------------

    /**
     * 净化：统一换行符 → 清控制字符 → 去行尾空白 → 折叠空行 → 去整体首尾空白。
     *
     * 这里没有直接调 [TextCleaner.clean]：它的空行折叠策略固定为「最多留 1 个空行」、
     * 也不清控制字符，而流水线要求这两项都能配置（[ContentPipelineConfig.maxBlankLines] /
     * [ContentPipelineConfig.stripControlChars]）。换行符统一与行尾空白的口径与它保持一致。
     */
    private fun sanitize(text: String, config: ContentPipelineConfig): String {
        if (text.isEmpty()) return text
        var out = text
        if (config.normalizeLineBreaks) {
            out = out.replace("\r\n", "\n").replace('\r', '\n')
        }
        if (config.stripControlChars) {
            out = out.filter { !isHarmfulControl(it) }
        }
        if (config.trimWhitespace) {
            out = out.lineSequence().joinToString("\n") { it.trimEnd() }
        }
        if (config.collapseBlankLines) {
            out = collapseBlankLines(out, config.maxBlankLines)
        }
        if (config.trimWhitespace) {
            out = trimEdges(out, config)
        }
        return out
    }

    /**
     * 去掉整体首尾空白，但**保留缩进字符**。
     *
     * 为什么：全角空格属于空白，若照常 trim，段首缩进阶段加上的前缀会被净化阶段削掉。
     * 流水线允许调用方自定义顺序（缩进完全可能排在净化之前），
     * 这里保留缩进字符，缩进才不会因为阶段顺序不同而时有时无。
     */
    private fun trimEdges(text: String, config: ContentPipelineConfig): String {
        val keep = if (config.indentEnabled) config.indentChar.firstOrNull() else null
        return text.trim { c -> c.isWhitespace() && c != keep }
    }

    /**
     * 是否属于「有害控制字符」：ISO 控制符（换行符除外，它是正文结构的一部分）
     * 与 BOM。
     *
     * BOM 一起清掉是因为抓来的正文开头常带一个 `\uFEFF`，在阅读页上会显示成
     * 一个莫名其妙的空位；它也被 [ContentQualityChecker] 当成乱码字符。
     */
    private fun isHarmfulControl(c: Char): Boolean = (c.isISOControl() && c != '\n') || c == '\uFEFF'

    /**
     * 折叠连续空行：最多保留 [maxBlankLines] 个空行。
     *
     * 按行判断而不是按换行符个数算，所以「只含空格的行」也会被当成空行处理
     * （不依赖净化阶段先去过空白，两个开关可以独立生效）。
     */
    private fun collapseBlankLines(text: String, maxBlankLines: Int): String {
        if (text.isEmpty()) return text
        val max = maxBlankLines.coerceAtLeast(0)
        val lines = text.split("\n")
        val out = StringBuilder(text.length)
        var blankRun = 0
        for ((index, line) in lines.withIndex()) {
            val blank = line.isBlank()
            if (blank) blankRun++ else blankRun = 0
            // 超出的空行直接丢掉（连同它带来的换行符）
            if (blank && blankRun > max) continue
            if (index > 0) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    /**
     * 替换：应用用户替换规则，**高亮标记区内的原文不参与替换**。
     *
     * 为什么要保护标记区：正文里的高亮标记是流水线自己插进去的，
     * 用户规则若命中标记字符（哪怕只是「删掉 « »」）就会把标记抹掉，
     * 高亮信息（词 + 位置）随之丢失。标记区内的原文不参与替换、
     * 标记区外的照常替换 —— 这与「高亮必须在替换之后」是同一条约束的两面：
     * 高亮排在替换之后时正文里还没有标记，替换自然碰不到它们；
     * 而正文被处理过一轮（已有标记）再走一遍流水线时，替换也不能把标记啃掉。
     */
    private fun applyReplace(text: String, config: ContentPipelineConfig, highlightActive: Boolean): String {
        if (text.isEmpty() || config.replaceRules.isEmpty()) return text
        val spans = if (highlightActive) {
            markerSpans(text, config.highlightStart, config.highlightEnd)
        } else {
            emptyList()
        }
        if (spans.isEmpty()) return TextReplaceRules.apply(text, config.replaceRules)

        val out = StringBuilder(text.length)
        var cursor = 0
        for (span in spans) {
            out.append(TextReplaceRules.apply(text.substring(cursor, span.start), config.replaceRules))
            out.append(text, span.start, span.end)
            cursor = span.end
        }
        out.append(TextReplaceRules.apply(text.substring(cursor), config.replaceRules))
        return out.toString()
    }

    /**
     * 高亮：给命中的搜索词插入标记。
     *
     * 幂等的关键是**跳过已有的标记区**：遇到开始标记时，把到结束标记之间的整段
     * 原样抄过去，里面即使还有同样的词也不重复标记。
     */
    private fun applyHighlight(text: String, config: ContentPipelineConfig): String {
        if (text.isEmpty()) return text
        val terms = config.highlightTerms.filter { it.isNotEmpty() }.distinct()
        if (terms.isEmpty()) return text
        // 同位置命中多个词时取更长的那个，避免「短词先中、长词被切碎」
        val ordered = terms.sortedByDescending { it.length }

        val spans = markerSpans(text, config.highlightStart, config.highlightEnd)
        val out = StringBuilder(text.length + 32)
        var cursor = 0
        for (span in spans) {
            // 标记区之间是普通正文，在里面做高亮
            out.append(highlightSegment(text, cursor, span.start, ordered, config))
            out.append(text, span.start, span.end)
            cursor = span.end
        }
        out.append(highlightSegment(text, cursor, text.length, ordered, config))
        return out.toString()
    }

    /** 给 `text[from until to)` 里的搜索词打标记 */
    private fun highlightSegment(
        text: String,
        from: Int,
        to: Int,
        terms: List<String>,
        config: ContentPipelineConfig,
    ): String {
        if (to <= from) return ""
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val hit = findNextTerm(text, i, to, terms, config.highlightIgnoreCase)
            if (hit == null) {
                out.append(text, i, to)
                break
            }
            out.append(text, i, hit.start)
            out.append(config.highlightStart)
            out.append(text, hit.start, hit.end)
            out.append(config.highlightEnd)
            i = hit.end
        }
        return out.toString()
    }

    /** 找出 `text[from until to)` 里的第一个命中，返回命中区间；没有命中返回 null */
    private fun findNextTerm(
        text: String,
        from: Int,
        to: Int,
        terms: List<String>,
        ignoreCase: Boolean,
    ): TextRange? {
        if (from >= to) return null
        if (!ignoreCase) {
            var best: TextRange? = null
            for (term in terms) {
                val idx = text.indexOf(term, from)
                if (idx < 0 || idx + term.length > to) continue
                val range = TextRange(idx, idx + term.length)
                best = when {
                    best == null -> range
                    range.start < best.start -> range
                    range.start == best.start && range.end > best.end -> range
                    else -> best
                }
            }
            return best
        }
        // 忽略大小写时手写扫描：toLowerCase() 可能改变字符串长度（个别字符会展开），
        // 直接用小写副本匹配会让下标对不上。
        for (pos in from..to) {
            for (term in terms) {
                if (pos + term.length <= to &&
                    text.regionMatches(pos, term, 0, term.length, ignoreCase = true)
                ) {
                    return TextRange(pos, pos + term.length)
                }
            }
        }
        return null
    }

    /** 段首缩进：给每个非空段落加缩进前缀。 */
    private fun applyIndent(text: String, config: ContentPipelineConfig): String {
        if (text.isEmpty()) return text
        val prefix = indentPrefix(config)
        if (prefix.isEmpty()) return text
        val lines = text.split("\n")
        val out = StringBuilder(text.length + prefix.length * lines.size)
        for ((index, line) in lines.withIndex()) {
            if (index > 0) out.append('\n')
            // 已经有缩进前缀的不再加：保证缩进阶段幂等（处理两次不会叠成四格）
            if (line.isBlank() || line.startsWith(prefix)) {
                out.append(line)
            } else {
                out.append(prefix).append(line)
            }
        }
        return out.toString()
    }

    /** 缩进前缀 */
    private fun indentPrefix(config: ContentPipelineConfig): String =
        config.indentChar.repeat(config.indentWidth.coerceAtLeast(0))

    /** 扫描正文里已有的高亮标记区，返回命中词与位置 */
    private fun scanHighlights(text: String, start: String, end: String): List<HighlightHit> {
        if (text.isEmpty() || start.isEmpty() || end.isEmpty()) return emptyList()
        return markerSpans(text, start, end).map { span ->
            val coreStart = span.start + start.length
            val coreEnd = span.end - end.length
            HighlightHit(text.substring(coreStart, coreEnd), coreStart, coreEnd)
        }
    }

    /**
     * 找出正文里成对的高亮标记区（区间覆盖「开始标记 + 内容 + 结束标记」）。
     *
     * 没有配对结束标记的开始符被当作普通文本（不保护、也不当成命中区），
     * 否则一段残缺的标记会把后面整篇正文都吞进保护区。
     */
    private fun markerSpans(text: String, start: String, end: String): List<TextRange> {
        if (text.isEmpty() || start.isEmpty() || end.isEmpty()) return emptyList()
        val spans = mutableListOf<TextRange>()
        var i = 0
        while (i < text.length) {
            val s = text.indexOf(start, i)
            if (s < 0) break
            val e = text.indexOf(end, s + start.length)
            if (e < 0) {
                // 没有配对的结束标记：按普通文本处理，继续往后找
                i = s + start.length
                continue
            }
            spans += TextRange(s, e + end.length)
            i = e + end.length
        }
        return spans
    }

    /** 高亮是否具备执行条件：要有搜索词，且标记符合法（不能为空、两者不能相同） */
    private fun canHighlight(config: ContentPipelineConfig): Boolean =
        config.highlightTerms.any { it.isNotEmpty() } &&
            config.highlightStart.isNotEmpty() &&
            config.highlightEnd.isNotEmpty() &&
            config.highlightStart != config.highlightEnd

    /**
     * 质量检查的对象：最终正文去掉流水线自己插的高亮标记。
     *
     * 为什么要去掉标记：标记是流水线的内部产物，不是内容。
     * 留着它会让字数虚高、还可能被当成乱码，影响判分；
     * 去掉它，质量分只跟正文内容有关，跟「是否开了高亮」无关。
     */
    private fun qualityTarget(
        text: String,
        config: ContentPipelineConfig,
        highlightActive: Boolean,
    ): String? {
        // 空正文不扫描：空白正文由上层的「本章暂无内容」链路兜底，
        // 这里扫一遍既没意义也会刷屏（与既有仓库层处理口径一致）。
        if (text.isBlank()) return null
        return if (highlightActive) {
            stripMarkers(text, config.highlightStart, config.highlightEnd)
        } else {
            text
        }
    }

    /** 去掉正文里的高亮标记（保留标记包住的原文） */
    private fun stripMarkers(text: String, start: String, end: String): String {
        val spans = markerSpans(text, start, end)
        if (spans.isEmpty()) return text
        val out = StringBuilder(text.length)
        var cursor = 0
        for (span in spans) {
            out.append(text, cursor, span.start)
            out.append(text, span.start + start.length, span.end - end.length)
            cursor = span.end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    // ------------------------------------------------------------------
    // 摘要统计
    // ------------------------------------------------------------------

    /** 给一个阶段生成状态摘要（所有数字都从**最终正文**上算，保证幂等） */
    private fun summarize(
        stage: PipelineStage,
        text: String,
        config: ContentPipelineConfig,
        executed: Set<PipelineStage>,
        quality: ContentQuality?,
        lowQuality: Boolean,
        highlights: List<HighlightHit>,
    ): StageSummary {
        if (stage !in executed) {
            return StageSummary.Skipped(stage, skippedNote(stage))
        }
        return when (stage) {
            PipelineStage.SANITIZE -> {
                val groups = countBlankGroups(text)
                val controls = text.count { isHarmfulControl(it) }
                StageSummary.Sanitize(
                    blankLineGroups = groups,
                    controlChars = controls,
                    note = "空行 $groups 组 / 残留控制字符 $controls 个",
                )
            }

            PipelineStage.REPLACE -> {
                val total = config.replaceRules.size
                var applied = 0
                var remaining = 0
                for (rule in config.replaceRules) {
                    val left = countMatches(text, rule)
                    remaining += left
                    if (left == 0) applied++
                }
                StageSummary.Replace(
                    appliedRules = applied,
                    remainingMatches = remaining,
                    note = "已处理干净 $applied/$total 条规则（残留 $remaining 处匹配）",
                )
            }

            PipelineStage.HIGHLIGHT -> {
                val terms = config.highlightTerms.filter { it.isNotEmpty() }.distinct().size
                StageSummary.Highlight(
                    hits = highlights.size,
                    terms = terms,
                    note = "命中 ${highlights.size} 处 / ${terms} 个搜索词",
                )
            }

            PipelineStage.INDENT -> {
                val prefix = indentPrefix(config)
                val indented = if (prefix.isEmpty()) {
                    0
                } else {
                    TextCleaner.paragraphs(text).count { it.startsWith(prefix) }
                }
                StageSummary.Indent(
                    indentedParagraphs = indented,
                    note = "已缩进 $indented 段",
                )
            }

            PipelineStage.QUALITY -> {
                val score = quality?.score ?: 0
                val note = when {
                    quality == null -> "空正文，跳过扫描"
                    lowQuality -> "质量分 $score，判定为低质量"
                    else -> "质量分 $score，正常"
                }
                StageSummary.Quality(score = score, lowQuality = lowQuality, note = note)
            }
        }
    }

    private fun skippedNote(stage: PipelineStage): String = when (stage) {
        PipelineStage.SANITIZE -> "未启用净化"
        PipelineStage.REPLACE -> "没有替换规则"
        PipelineStage.HIGHLIGHT -> "没有搜索词"
        PipelineStage.INDENT -> "未启用段首缩进"
        PipelineStage.QUALITY -> "未启用质量检查"
    }

    /** 连续空行的组数（连续多个空行算一组） */
    private fun countBlankGroups(text: String): Int {
        if (text.isEmpty()) return 0
        var groups = 0
        var inBlank = false
        for (line in text.split("\n")) {
            if (line.isBlank()) {
                if (!inBlank) {
                    groups++
                    inBlank = true
                }
            } else {
                inBlank = false
            }
        }
        return groups
    }

    /** 正文里某条规则的匹配次数（用来回答「还有几处没替换干净」） */
    private fun countMatches(text: String, rule: ReplaceRule): Int {
        if (rule.pattern.isEmpty() || text.isEmpty()) return 0
        if (!rule.isRegex) {
            var count = 0
            var idx = text.indexOf(rule.pattern)
            while (idx >= 0) {
                count++
                idx = text.indexOf(rule.pattern, idx + rule.pattern.length)
            }
            return count
        }
        return runCatching { Regex(rule.pattern).findAll(text).count() }.getOrDefault(0)
    }

    /**
     * 内部用的半开区间 `[start, end)`。
     *
     * 不用 [IntRange]：它的 `end` / `second` 是扩展属性，和本文件里的
     * `end`（结束标记字符串）同名会撞车，编译期直接报错。
     */
    private data class TextRange(val start: Int, val end: Int)
}
