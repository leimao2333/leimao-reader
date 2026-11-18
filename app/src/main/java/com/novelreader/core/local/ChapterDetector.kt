package com.novelreader.core.local

/**
 * 智能目录识别（B9 · 本地 TXT / EPUB 导入）。
 *
 * 为什么单独抽一个类：目录识别的本质是「模式匹配 + 结构启发式」，
 * 它既不该埋在 [LocalBookStore] 的 IO 里（不便单测），也不该散落到 UI 层
 * （导入页与阅读页要用同一套判定）。这里做成**纯 Kotlin、无 Android 依赖**的
 * object，任何一条规则都能用一行文本复现，回归测试直接断言即可。
 *
 * 识别分四层策略，按优先级依次尝试：
 *
 * 1. [Strategy.CN_EXPLICIT] 中文显式标记：第X章 / 节 / 回 / 卷 / 篇 / 集 / 幕 / 部 / 折 / 话、
 *    卷一、序章、楔子、引子、前言、后记、尾声、终章、番外……
 * 2. [Strategy.EN_EXPLICIT] 英文显式标记：Chapter N / CHAPTER I / Part 1 / Prologue /
 *    Epilogue / Afterword……
 * 3. [Strategy.VARIANT] 常见异体：正文 第X章、【第X章】、chapter-1、001 标题、1. 标题……
 * 4. [Strategy.STRUCTURED] 结构启发式：独占一行的短行 + 含数字 / 序号词。
 *
 * 策略的选择规则是「**先看数量再看优先级**」：某策略至少命中 [MIN_HITS] 处才直接采纳，
 * 这样「正文里偶然出现一行『第三章』」不会把整本书腰斩成两段；
 * 若所有策略都只命中 1 处，则取优先级最高的那一处（保住旧实现
 * 「整本书只有一章标题也能认出来」的行为）。
 *
 * 与旧正则的兼容口径：标题必须**独占行首**（旧实现用 `^\s*` 锚定单行，
 * 本类沿用），且标题标记之后的尾巴不超过 [MAX_SUFFIX_LENGTH] 字符（旧实现是 `.{0,60}`）。
 * 因此本类识别的目录是旧正则的**超集**，旧有行为不会丢。
 */
object ChapterDetector {

    /** 标题标记之后允许的最大尾巴长度：沿用旧实现 `.{0,60}` 的口径 */
    const val MAX_SUFFIX_LENGTH = 60

    /** 异体 / 结构策略下整行的长度上限（这类标题一般都很短） */
    const val MAX_SHORT_TITLE_LENGTH = 40

    /** 回退切分时单章的目标字数 */
    const val FALLBACK_CHUNK_SIZE = 20_000

    /** 采纳某策略所需的最小命中数（≥2 才认为「这是一本书的章节体系」） */
    private const val MIN_HITS = 2

    /** 中文序数：阿拉伯数字 + 中文数字（含「两」「零」等口语写法） */
    private const val CN_NUM = "[0-9零一二三四五六七八九十百千万两]+"

    /** 英文序数：阿拉伯数字 / 罗马数字 / 常见英文数词 */
    private const val EN_NUM =
        "(?:[0-9]+|[ivxlcdmIVXLCDM]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"

    /**
     * 中文显式标记。
     *
     * 「卷」单独出现（没有数字）不算标记——「卷」在正文里太常见；
     * 但「第X卷」「卷一」都算。
     */
    private val CN_EXPLICIT = Regex(
        """^(?:第\s*$CN_NUM\s*[章节回卷篇集幕部折话]|卷\s*$CN_NUM(?![章节回卷篇集])|序章|序言|楔子|引子|前言|后记|尾声|终章|自序|后序|番外|外篇|附录)"""
    )

    /** 英文显式标记（大小写不敏感；`CHAPTER I` 这类罗马数字序号也认） */
    private val EN_EXPLICIT = Regex(
        """^(?:chapter|part|book|section|episode|chap)\s+[-_]?$EN_NUM\b|prologue|epilogue|afterword|preface|foreword|introduction|acknowledgements|acknowledgments""",
        RegexOption.IGNORE_CASE,
    )

    /** 异体一：「正文 第X章」「正文：第X章」 */
    private val VARIANT_BODY_PREFIX = Regex("""^正文\s*[:：]?\s*第\s*$CN_NUM\s*[章节回卷篇集]""")

    /** 异体二：「【第X章】标题」「[第X章]」 */
    private val VARIANT_BRACKET = Regex("""^[【\[［]\s*第\s*$CN_NUM\s*[章节回卷篇集]\s*[】\]］]?""")

    /** 异体三：「chapter-1」「chapter_1」「ch.1」「ch1」「part-2」 */
    private val VARIANT_SLASH = Regex(
        """^(?:chapter|chap|ch|part|section|sec)[-_.]?\s*\d+""",
        RegexOption.IGNORE_CASE,
    )

    /** 异体四：「001 标题」「1. 标题」「1、标题」「1) 标题」「12: 标题」 */
    private val VARIANT_NUMBERED = Regex("""^\d{1,3}\s+\S|^\d{1,4}\s*[.、)）:：]\s*\S""")

    /** 结构启发式：行首是数字（放宽位数）+ 可选分隔符 + 标题文字 */
    private val STRUCTURED_NUMBER = Regex(
        """^\s*(?:\d{1,4}|[零一二三四五六七八九十百千]+)\s*[.、)）:：]?\s*\S"""
    )

    /** 结构启发式：整行就是一个序号词（如「楔子」「Prologue」） */
    private val STRUCTURED_ORDINAL = Regex(
        """^(?:序章|序言|楔子|引子|前言|后记|尾声|终章|番外|prologue|epilogue|afterword|preface)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 数量词防护：「3 天后」「12 点」「10 年前」这类正文里的数字短语不是标题。
     * 只在「数字开头」的异体 / 结构策略上生效——「第X章」不受影响。
     */
    private val QUANTITY_GUARD = Regex(
        """^\d{1,4}\s*[天月日年时分秒个只本条张位名人次元块岁点份]"""
    )

    /** 行尾出现句读 → 更像正文句子而不是标题 */
    private val SENTENCE_TAIL = Regex("""[。！？，；、!?,;]\s*$""")

    /** 策略枚举（顺序即优先级） */
    enum class Strategy {
        CN_EXPLICIT,
        EN_EXPLICIT,
        VARIANT,
        STRUCTURED,
    }

    private val STRATEGY_ORDER = listOf(
        Strategy.CN_EXPLICIT,
        Strategy.EN_EXPLICIT,
        Strategy.VARIANT,
        Strategy.STRUCTURED,
    )

    /**
     * 一个章节标记：标题 + 在原文中的字符区间（[start, end)，end 不含换行符）。
     */
    data class ChapterMark(val title: String, val start: Int, val end: Int)

    /**
     * 识别目录。
     *
     * @return 章节标记列表；**没有任何策略命中时返回空列表**（由调用方决定回退策略）
     */
    fun detect(text: String): List<ChapterMark> {
        if (text.isBlank()) return emptyList()
        val lines = scan(text)
        var fallback: List<ChapterMark> = emptyList()
        for (strategy in STRATEGY_ORDER) {
            val marks = match(strategy, lines)
            if (marks.size >= MIN_HITS) return marks
            // 只有一处命中时优先级最高的那层胜出（保留旧实现的行为）
            if (marks.isNotEmpty() && fallback.isEmpty()) fallback = marks
        }
        return fallback
    }

    /**
     * 识别目录；识别不到时按固定长度切分（回退方案）。
     *
     * 固定长度切分只在「整本书完全没有章节格式」时使用：把正文按 [chunkSize]
     * 字符切成若干段，尽量在换行处断开，避免把句子切成两半。
     */
    fun detectWithFallback(
        text: String,
        chunkSize: Int = FALLBACK_CHUNK_SIZE,
    ): List<ChapterMark> {
        val detected = detect(text)
        if (detected.isNotEmpty()) return detected
        return splitByLength(text, chunkSize)
    }

    /**
     * 判断单行是否是章节标题（供按行扫描的调用方复用同一套规则）。
     */
    fun isChapterTitle(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return matchesMarker(CN_EXPLICIT, trimmed) ||
            matchesMarker(EN_EXPLICIT, trimmed) ||
            matchesVariant(trimmed) ||
            matchesStructured(trimmed)
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 扫描出的行信息 */
    private class LineInfo(
        val start: Int,
        val end: Int,
        val trimmed: String,
        /** 前面是空行或文件开头（“独占一行”的结构特征） */
        val standalone: Boolean,
    )

    /**
     * 按行切分并记录真实偏移。
     *
     * 不用 `lineSequence()` 是因为它不返回每行的起止位置，而本类需要
     * 精确的字符区间（章节正文就是靠这个区间从原文里切出来的）；
     * 顺带修正了 `\r\n` 文本下用「行号累加」推算偏移会逐行漂移的老问题。
     */
    private fun scan(text: String): List<LineInfo> {
        val lines = mutableListOf<LineInfo>()
        var index = 0
        var prevBlank = true // 文件开头视为「前面是空行」
        while (index <= text.length) {
            val nl = text.indexOf('\n', index)
            val rawEnd = if (nl < 0) text.length else nl
            val contentEnd = if (rawEnd > index && text[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            val trimmed = text.substring(index, contentEnd).trim()
            lines += LineInfo(
                start = index,
                end = contentEnd,
                trimmed = trimmed,
                standalone = prevBlank && trimmed.isNotEmpty(),
            )
            prevBlank = trimmed.isEmpty()
            if (nl < 0) break
            index = nl + 1
        }
        return lines
    }

    private fun match(strategy: Strategy, lines: List<LineInfo>): List<ChapterMark> = when (strategy) {
        Strategy.CN_EXPLICIT -> lines.mapNotNull { line ->
            if (!matchesMarker(CN_EXPLICIT, line.trimmed)) return@mapNotNull null
            ChapterMark(line.trimmed, line.start, line.end)
        }

        Strategy.EN_EXPLICIT -> lines.mapNotNull { line ->
            if (!matchesMarker(EN_EXPLICIT, line.trimmed)) return@mapNotNull null
            ChapterMark(line.trimmed, line.start, line.end)
        }

        Strategy.VARIANT -> lines.mapNotNull { line ->
            val t = line.trimmed
            if (!matchesVariant(t)) return@mapNotNull null
            ChapterMark(t, line.start, line.end)
        }

        Strategy.STRUCTURED -> lines.mapNotNull { line ->
            val t = line.trimmed
            if (!line.standalone) return@mapNotNull null
            if (!matchesStructured(t)) return@mapNotNull null
            ChapterMark(t, line.start, line.end)
        }
    }

    /** 显式标记匹配：标记必须在行首，且其后的尾巴不能太长 */
    private fun matchesMarker(regex: Regex, trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        val m = regex.find(trimmed) ?: return false
        if (m.range.first != 0) return false
        return trimmed.length - m.value.length <= MAX_SUFFIX_LENGTH
    }

    /** 异体匹配：正文 第X章 / 【第X章】/ chapter-1 / 001 标题 */
    private fun matchesVariant(trimmed: String): Boolean {
        if (trimmed.isEmpty() || trimmed.length > MAX_SHORT_TITLE_LENGTH) return false
        if (QUANTITY_GUARD.containsMatchIn(trimmed)) return false
        if (SENTENCE_TAIL.containsMatchIn(trimmed)) return false
        return VARIANT_BODY_PREFIX.containsMatchIn(trimmed) ||
            VARIANT_BRACKET.containsMatchIn(trimmed) ||
            VARIANT_SLASH.containsMatchIn(trimmed) ||
            VARIANT_NUMBERED.containsMatchIn(trimmed)
    }

    /** 结构启发式匹配：短行 + 含数字 / 序号词 */
    private fun matchesStructured(trimmed: String): Boolean {
        if (trimmed.isEmpty() || trimmed.length > MAX_SHORT_TITLE_LENGTH) return false
        if (QUANTITY_GUARD.containsMatchIn(trimmed)) return false
        if (SENTENCE_TAIL.containsMatchIn(trimmed)) return false
        return STRUCTURED_NUMBER.containsMatchIn(trimmed) ||
            STRUCTURED_ORDINAL.containsMatchIn(trimmed)
    }

    /** 回退：按固定长度切分，尽量在换行处断开 */
    private fun splitByLength(text: String, chunkSize: Int): List<ChapterMark> {
        if (text.isBlank()) return emptyList()
        val size = chunkSize.coerceAtLeast(1)
        val marks = mutableListOf<ChapterMark>()
        var start = 0
        var part = 1
        while (start < text.length) {
            var end = (start + size).coerceAtMost(text.length)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end)
                if (nl > start) end = nl
            }
            marks += ChapterMark("第 $part 部分", start, end)
            start = end
            part++
        }
        return marks
    }
}
