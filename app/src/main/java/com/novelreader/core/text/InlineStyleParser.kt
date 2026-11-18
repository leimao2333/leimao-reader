package com.novelreader.core.text

/**
 * 行内字符级样式（A11）。
 *
 * 正文里的「局部加粗 / 下划线 / 高亮 / 删除线 / 强调」在纯文本里只是几个符号，
 * 渲染层却需要知道**每一段文字分别是什么样式**。本文件把行内标记解析成结构化的
 * span 列表，交给 Compose 侧转成 `SpanStyle`。
 *
 * 纯 Kotlin（不依赖 Android / Compose），可在 JVM 单测里直接跑，
 * 也能在分页缓存、TTS 分段等纯逻辑层复用。
 *
 * ## 支持的标记
 *
 * | 写法 | 样式 | 含义 |
 * |---|---|---|
 * | `**文字**` | [InlineStyle.BOLD] | 加粗 |
 * | `__文字__` | [InlineStyle.UNDERLINE] | 下划线 |
 * | `==文字==` | [InlineStyle.HIGHLIGHT] | 文字背景高亮 |
 * | `~~文字~~` | [InlineStyle.STRIKETHROUGH] | 删除线 |
 * | `【文字】` | [InlineStyle.EMPHASIS] | 强调色 |
 *
 * ## 坐标口径（重要）
 *
 * [InlineSpan.range] 的下标基于**去掉标记后的纯文本**（即 [InlineStyleParser.plainText]
 * 的输出），不是原始字符串的下标。理由：渲染层最终画出来的是纯文本，span 下标必须与
 * 它对齐，否则渲染层每遇到一个标记就要自己做一次下标换算，早晚算错。
 * 需要「纯文本 + span」配套使用时，请直接用 [InlineStyleParser.analyze] 或
 * [InlineStyleParser.toStyledText] —— 两者同源，不会出现口径错位。
 *
 * ## 设计取舍
 *
 * - **未闭合的标记一律当普通文本**：正文来自书源抓取，不保证标记成对。
 *   若把 `**没有结尾` 当成「加粗到段尾」，后面几千字会被整个吞进一个 span，
 *   比不解析还糟。所以只有**确实找到配对结束符**才会产出 span。
 * - **嵌套用「区间允许重叠」表示**：`**普通__带下划线__的加粗**` 会产出两个 span
 *   （BOLD 覆盖整段、UNDERLINE 覆盖中间一段），不假设「一维区间只能不重叠」。
 *   渲染层按 span 叠加样式即可。
 * - **转义用反斜杠**：`\*` 渲染成字面量 `*`，不会被当成标记起点。
 *   只对标记符号本身转义（`* _ = ~ \ 【 】`），其余 `\x` 原样保留，
 *   免得正文里的 Windows 路径、正则表达式被吃掉反斜杠。
 * - **同类型标记不支持自嵌套**：`**a **b** c**` 按「最近的同标记配对」处理，
 *   得到 `a ` 与 ` c` 两段加粗、`b` 留在外面；跨类型嵌套（加粗里套下划线）完全支持。
 */

/** 行内样式种类 */
enum class InlineStyle {
    /** `**加粗**` —— 字重加粗 */
    BOLD,

    /** `__下划线__` —— 文字下划线 */
    UNDERLINE,

    /** `==高亮==` —— 文字背景色 */
    HIGHLIGHT,

    /** `~~删除线~~` —— 文字删除线 */
    STRIKETHROUGH,

    /** `【强调】` —— 强调色（文字本身变色） */
    EMPHASIS,
}

/**
 * 一段带样式的文字。
 *
 * @param range 文字在**纯文本**中的下标区间（闭区间，两端都取到）
 * @param style 该段应用的样式
 */
data class InlineSpan(val range: IntRange, val style: InlineStyle)

/**
 * 解析结果：纯文本 + 样式段。
 *
 * @param text 去掉标记、还原转义后的纯文本
 * @param spans 样式段，下标基于 [text]
 */
data class InlineParseResult(val text: String, val spans: List<InlineSpan>)

/**
 * 一段纯文本及其生效的全部样式。
 *
 * 嵌套时同一段会带多个样式（例如「加粗 + 下划线」），
 * 用集合而不是单个样式，才能表达这种叠加关系。
 *
 * @param text 这段的文字
 * @param styles 生效的样式集合（按嵌套层级由外到内排列）
 */
data class StyledRun(val text: String, val styles: Set<InlineStyle>)

/**
 * 纯文本 + 样式段的配对结构，供 Compose 侧直接转 `AnnotatedString`。
 *
 * @param text 去掉标记、还原转义后的纯文本
 * @param runs 纯文本按样式边界切开的片段，顺序拼接后等于 [text]
 */
data class StyledText(val text: String, val runs: List<StyledRun>)

/**
 * 行内标记解析器。
 *
 * 典型用法（渲染层）：
 * ```
 * val styled = InlineStyleParser.toStyledText(chapterText)
 * // styled.runs.forEach { run -> withStyle(run.styles) { append(run.text) } }
 * ```
 */
object InlineStyleParser {

    /** 标记定义：开始符 / 结束符 / 对应样式 */
    private class Marker(val open: String, val close: String, val style: InlineStyle)

    private val BOLD = Marker("**", "**", InlineStyle.BOLD)
    private val UNDERLINE = Marker("__", "__", InlineStyle.UNDERLINE)
    private val HIGHLIGHT = Marker("==", "==", InlineStyle.HIGHLIGHT)
    private val STRIKETHROUGH = Marker("~~", "~~", InlineStyle.STRIKETHROUGH)
    private val EMPHASIS = Marker("【", "】", InlineStyle.EMPHASIS)

    /** 可以被反斜杠转义的字符：只覆盖标记符号本身，避免误伤正文里的其它反斜杠 */
    private const val ESCAPABLE = "*_=~\\【】"

    /** 解析树节点：纯文字 / 带样式的子树 */
    private sealed interface Node {
        /** 已经解析好的纯文字（转义已还原） */
        data class Text(val value: String) : Node

        /** 一段带样式的文字，[children] 是去掉标记后的内容 */
        data class Styled(val style: InlineStyle, val children: List<Node>) : Node
    }

    /**
     * 一次解析的中间状态：原文 + 「已确认没有结束符」的区间记忆。
     *
     * 书源抓来的正文里未闭合的标记很常见（作者少写一个 `**`、`】` 落笔很常见）。
     * 如果每次遇到未闭合标记都从当前位置一路扫到段尾，一章里几十个未闭合标记就会
     * 把解析拖成 O(n²)。这里把「扫过整段且没找到结束符」记下来，
     * 后续查询落在已确认区间里就直接跳过扫描 —— 结果不变，只是省掉重复劳动。
     */
    private class ParseState(val text: String) {
        /** 已确认「该区间内不存在某类型结束符」的记录（区间闭区间） */
        private val noClose = HashMap<Marker, MutableList<IntRange>>()

        /** 查询区间 [from, to) 是否已被确认没有该类型的结束符 */
        fun isKnownEmpty(marker: Marker, from: Int, to: Int): Boolean {
            val records = noClose[marker] ?: return false
            // 已知 [s, t) 里没有结束符，则它的任何子区间里也没有
            return records.any { from >= it.first && to <= it.last }
        }

        /** 记录「[from, to) 里没有该类型的结束符」 */
        fun markEmpty(marker: Marker, from: Int, to: Int) {
            val records = noClose.getOrPut(marker) { mutableListOf() }
            if (records.any { from >= it.first && to <= it.last }) return
            records.add(from..to)
        }
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /**
     * 解析正文中的行内标记，返回样式段列表。
     *
     * @param text 原始正文
     * @return 样式段，下标基于 [plainText] 的输出；没有标记时返回空列表
     */
    fun parse(text: String): List<InlineSpan> = analyze(text).spans

    /**
     * 解析正文，返回「纯文本 + 样式段」。
     *
     * 这是最常用的入口：两者配套返回，调用方不必再关心下标口径。
     *
     * @param text 原始正文
     */
    fun analyze(text: String): InlineParseResult {
        val nodes = parseTree(text)
        val plain = StringBuilder()
        val spans = mutableListOf<InlineSpan>()
        collectSpans(nodes, plain, spans)
        // 同一位置的嵌套 span 让外层（更长的那段）排在前面：先加粗、再叠加下划线
        spans.sortWith(compareBy({ it.range.first }, { -it.range.last }))
        return InlineParseResult(plain.toString(), spans)
    }

    /**
     * 只取纯文本：去掉配对成功的标记、还原转义，未闭合的标记原样保留。
     *
     * @param text 原始正文
     */
    fun plainText(text: String): String {
        val nodes = parseTree(text)
        val plain = StringBuilder()
        collectSpans(nodes, plain, mutableListOf())
        return plain.toString()
    }

    /**
     * 把正文切成「纯文本片段 + 每片段生效样式」的列表。
     *
     * 片段在样式边界处切开，嵌套时同一片段会带多个样式。
     *
     * @param text 原始正文
     */
    fun toRuns(text: String): List<StyledRun> = toStyledText(text).runs

    /**
     * 解析成「纯文本 + 样式段」的配对结构，方便 Compose 侧逐段渲染。
     *
     * @param text 原始正文
     */
    fun toStyledText(text: String): StyledText {
        val nodes = parseTree(text)
        val builder = RunBuilder()
        builder.visit(nodes, emptyList())
        builder.flush()
        return StyledText(builder.plainText(), builder.runs())
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 把正文解析成节点树（树里的文字已经是纯文字） */
    private fun parseTree(text: String): List<Node> {
        if (text.isEmpty()) return emptyList()
        val state = ParseState(text)
        val nodes = mutableListOf<Node>()
        parseRegion(state, 0, text.length, nodes)
        return nodes
    }

    /**
     * 递归解析 `[from, to)` 区间，结果追加到 [sink]。
     *
     * 遇到标记时先向后找配对的结束符，**找到才吞掉标记**；找不到就当普通文本，
     * 这样未闭合的标记不会把后面的正文一起吃掉。
     */
    private fun parseRegion(state: ParseState, from: Int, to: Int, sink: MutableList<Node>) {
        val text = state.text
        var i = from
        while (i < to) {
            val c = text[i]

            // 转义：\x → 字面量 x；不可转义的字符保留反斜杠本身
            if (c == '\\') {
                if (i + 1 < to && isEscapable(text[i + 1])) {
                    sink += Node.Text(text[i + 1].toString())
                    i += 2
                } else {
                    sink += Node.Text("\\")
                    i++
                }
                continue
            }

            val marker = markerOf(c)
            if (marker != null && i + marker.open.length <= to && text.startsWith(marker.open, i)) {
                val contentStart = i + marker.open.length
                val close = findClose(state, contentStart, to, marker)
                if (close > contentStart) {
                    val children = mutableListOf<Node>()
                    parseRegion(state, contentStart, close, children)
                    sink += Node.Styled(marker.style, children)
                    i = close + marker.close.length
                    continue
                }
                // 没找到配对结束符（或内容为空）→ 当普通文本，且只前进一个字符，
                // 不跳过整段标记，避免误吞后面的正文
                sink += Node.Text(c.toString())
                i++
                continue
            }

            sink += Node.Text(c.toString())
            i++
        }
    }

    /**
     * 在 `[start, to)` 里找 [marker] 的结束符位置，找不到返回 -1。
     *
     * 跳过转义序列，避免 `\*\*` 这种字面量被当成了结束符。
     */
    private fun findClose(state: ParseState, start: Int, to: Int, marker: Marker): Int {
        if (state.isKnownEmpty(marker, start, to)) return -1
        val text = state.text
        var i = start
        while (i < to) {
            val c = text[i]
            if (c == '\\' && i + 1 < to && isEscapable(text[i + 1])) {
                i += 2
                continue
            }
            if (marker.close.length == 1) {
                if (c == marker.close[0]) return i
            } else if (i + marker.close.length <= to && text.startsWith(marker.close, i)) {
                return i
            }
            i++
        }
        state.markEmpty(marker, start, to)
        return -1
    }

    /** 首字符 → 标记 */
    private fun markerOf(c: Char): Marker? = when (c) {
        '*' -> BOLD
        '_' -> UNDERLINE
        '=' -> HIGHLIGHT
        '~' -> STRIKETHROUGH
        '【' -> EMPHASIS
        else -> null
    }

    /** 该字符能否被反斜杠转义 */
    private fun isEscapable(c: Char): Boolean = c in ESCAPABLE

    /**
     * 遍历节点树：把纯文字写进 [plain]，把每个样式节点记成一个 span。
     *
     * @return 本次遍历追加的纯文字长度（用于换算 span 的下标）
     */
    private fun collectSpans(
        nodes: List<Node>,
        plain: StringBuilder,
        spans: MutableList<InlineSpan>,
    ): Int {
        var added = 0
        for (node in nodes) {
            when (node) {
                is Node.Text -> {
                    plain.append(node.value)
                    added += node.value.length
                }
                is Node.Styled -> {
                    val start = added
                    added += collectSpans(node.children, plain, spans)
                    // 空内容不产出 span（没有文字可加样式）
                    if (added > start) spans += InlineSpan(start..(added - 1), node.style)
                }
            }
        }
        return added
    }

    /**
     * 把节点树摊平成「文字片段 + 生效样式集合」。
     *
     * 相邻且样式相同的片段会合并成一段，避免渲染层产生大量碎片。
     */
    private class RunBuilder {
        private val result = mutableListOf<StyledRun>()
        private var pending = StringBuilder()
        private var pendingStyles: Set<InlineStyle>? = null

        fun visit(nodes: List<Node>, styles: List<InlineStyle>) {
            for (node in nodes) {
                when (node) {
                    is Node.Text -> append(node.value, styles)
                    is Node.Styled -> visit(node.children, styles + node.style)
                }
            }
        }

        private fun append(text: String, styles: List<InlineStyle>) {
            if (text.isEmpty()) return
            val set = styles.toSet()
            val current = pendingStyles
            if (current != null && current == set) {
                pending.append(text)
                return
            }
            flush()
            pendingStyles = set
            pending.append(text)
        }

        fun flush() {
            val styles = pendingStyles ?: return
            if (pending.isNotEmpty()) result += StyledRun(pending.toString(), styles)
            pending = StringBuilder()
            pendingStyles = null
        }

        fun runs(): List<StyledRun> = result

        /** 片段按顺序拼接就是纯文本 */
        fun plainText(): String = result.joinToString("") { it.text }
    }
}
