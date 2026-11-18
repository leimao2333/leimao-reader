package com.novelreader.core.reader

/**
 * 正文切句游标（A12 朗读逐句跟随高亮）。
 *
 * 把「正文 → 句子列表」和「句子下标 → 全文字符区间」这两件事抽成纯函数，
 * 不依赖 Android / Compose，便于单测与在 ViewModel 侧做分段续播。
 *
 * ## 切句规则
 *
 * - 句末标点：中文 `。！？；……` 与英文 `.!?;`；标点**保留**在句子里；
 * - 连续标点（`？！`、`……`）与紧随其后的右引号/右括号（`”’」』）】》` 等）
 *   并入同一句，避免「引号内标点」被劈成两句；
 * - 换行 `\n` / `\r` 视为句子边界（一句不会跨段落）；
 * - 句首 / 句尾空白会被忽略（trim），但 [locate] 返回的区间仍以原文下标为准；
 * - 英文句号 `.` 做**缩写 / 小数 / 链接内点**的粗筛（见 [isAbbreviationPeriod]），
 *   避免把 `Mr.`、`3.14`、`www.example.com` 这类误切成句。
 *
 * ## 为什么这样设计
 *
 * 朗读需要「知道现在读到第几句」，好让阅读页把那一句高亮出来。TtsController
 * 本身只暴露「一段读完」的回调（[com.novelreader.core.tts.TtsController.onUtteranceDone]），
 * 没有「读到第几句」的进度；所以 ViewModel 沿用现有的「分段续播」，把「段」的粒度
 * 从段落缩到句子，段下标即句下标，不需要改动 TtsController。这里只负责把正文
 * 切成句子、并按下标给出区间，供 ViewModel 切段和 UI 高亮共用同一套口径。
 */
object ReaderSentenceCursor {

    /** 一句话在原文中的字符区间（[start], [end)）。 */
    data class TextRange(val start: Int, val end: Int)

    /** 视为句末的标点（含英文 `.`，其缩写误切在 [isAbbreviationPeriod] 里单独过滤）。 */
    private val SENTENCE_ENDERS = setOf('。', '！', '？', '；', '…', '.', '!', '?', ';')

    /** 句末标点之后紧跟的右引号 / 右括号，并入同一句。 */
    private val CLOSING_MARKS = setOf(
        '"', '\'', '”', '’', '」', '』', '）', ')', '】', '》', '〉', '〗', ']', '}', '〕',
    )

    /**
     * 常见英文尊称 / 拉丁缩写的小集合，用于让句号前的这类词不切句。
     *
     * 只覆盖最常见的几类，不是完整词典——完整缩写消歧需要词典 + 上下文，
     * 在「朗读切句」这个场景里用最小集合换取最常见的正确性就够了。
     */
    private val KNOWN_TITLES = setOf(
        "Mr", "Mrs", "Ms", "Dr", "Prof", "St", "No", "vs", "etc", "e.g", "i.e",
    )

    /**
     * 把正文切成句子（按原文顺序）。
     *
     * @return 每句都是去掉首尾空白后的原文子串；空文本返回空列表。
     */
    fun sentences(text: String): List<String> =
        segment(text).map { text.substring(it.start, it.end) }

    /**
     * 定位第 [sentenceIndex] 句在原文中的字符区间。
     *
     * @return 越界返回 null；区间 [start, end) 与 [sentences] 的下标一一对应。
     */
    fun locate(text: String, sentenceIndex: Int): TextRange? =
        segment(text).getOrNull(sentenceIndex)

    /** 单次扫描切出所有句子区间。 */
    private fun segment(text: String): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var i = 0
        val n = text.length
        while (i < n) {
            // 跳过句首空白（含换行），保证句子不带着前导空白
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n) {
                val c = text[i]
                if (c == '\n' || c == '\r') break // 换行 = 句子边界（不跨段）
                if (c in SENTENCE_ENDERS) {
                    // 英文句号的缩写/小数/链接内点粗筛：不当作句末，继续向后扫
                    if (c == '.' && isAbbreviationPeriod(text, i)) {
                        i++
                        continue
                    }
                    i++
                    // 连续标点（？！……）与句末右引号/右括号并入本句
                    while (i < n && (text[i] in SENTENCE_ENDERS || text[i] in CLOSING_MARKS)) i++
                    break
                }
                i++
            }
            var end = i
            // 句尾空白不纳入区间（区间仍基于原文下标）
            while (end > start && text[end - 1].isWhitespace()) end--
            if (end > start) ranges.add(TextRange(start, end)) else i++
        }
        return ranges
    }

    /**
     * 英文句号是否属于「缩写 / 小数 / 链接内点」，即**不应**在此切句。
     *
     * 三条粗筛（任一命中即视为不切）：
     * 1. 前后都是数字 —— 小数 `3.14`；
     * 2. 后一个字符是小写字母 —— 链接 / 文件名内的点 `a.b`、`www.example.com`；
     * 3. 句号前的单词在 [KNOWN_TITLES] 里 —— `Mr.`、`e.g.` 这类尊称/缩写。
     */
    private fun isAbbreviationPeriod(text: String, i: Int): Boolean {
        val prev = text.getOrNull(i - 1)
        val next = text.getOrNull(i + 1)
        if (prev?.isDigit() == true && next?.isDigit() == true) return true
        if (next?.isLowerCase() == true) return true
        var j = i - 1
        while (j >= 0 && (text[j].isLetter() || text[j] == '.')) j--
        val token = text.substring(j + 1, i)
        return token in KNOWN_TITLES
    }
}
