package com.novelreader.core.text

/**
 * 中文断行 / 标点避头尾（对标开源阅读的 `ChineseLineBreaker`）。
 *
 * 排版里的「避头尾」规则：
 * - **避头**：句读、右括号、右引号等不能出现在一行开头（标点悬在行首很难看）；
 * - **避尾**：左括号、左引号等不能出现在一行末尾（开符号应与内容同处一行）。
 *
 * 阅读页按宽度切分正文时，理想切分点往往正好落在标点前/后，此时需要把切分点
 * 微调一两个字。本工具只做纯字符串运算，**不依赖 Android / Compose**，可在任意
 * 线程、任意排版引擎中复用（Compose 换行、TTS 分段、分页缓存均可用）。
 *
 * 约定：切分点 `index` 表示「前半段为 text[0 until index]，后半段为 text[index until length]」，
 * 合法取值范围是 `1 until text.length`；文本长度不足 2 时无法切分，统一返回 0。
 */
object ChineseLineBreaker {

    /** 默认微调窗口（可前后各找 8 个字符） */
    const val DEFAULT_WINDOW: Int = 8

    /**
     * 避头标点：不能出现在行首。
     * 收尾标点（句读 / 右括号 / 右引号 / 省略号 / 破折号 / 千分号等）。
     */
    private const val FORBIDDEN_LINE_START: String =
        "，。、；：？！）】》」』〉〗〕｝〙〛”’…—·%‰℃,.;:?!)]}"

    /**
     * 避尾标点：不能出现在行尾。
     * 起始标点（左括号 / 左引号等开符号）。
     */
    private const val FORBIDDEN_LINE_END: String =
        "（【《「『〈〖〔｛〘〚“‘([{"

    /** 句末标点：优先在这些字符之后断开（含换行，换行本身即天然断点） */
    private const val SENTENCE_END: String = "。！？…!?\n"

    /** 该字符能否出现在行首？返回 true 表示不能（需要把切分点前移） */
    fun isForbiddenAtLineStart(c: Char): Boolean = FORBIDDEN_LINE_START.indexOf(c) >= 0

    /** 该字符能否出现在行尾？返回 true 表示不能（需要把切分点前移到它之前） */
    fun isForbiddenAtLineEnd(c: Char): Boolean = FORBIDDEN_LINE_END.indexOf(c) >= 0

    /**
     * 把理想切分点微调到一个不违反避头尾的合法位置。
     *
     * 调整顺序：
     * 1. `text[cut]` 是避头标点 → 逐字前移，把标点带到上一行（连续多个避头标点一起前移）；
     * 2. `text[cut - 1]` 是避尾标点 → 继续前移，让开括号 / 开引号整体落到下一行；
     * 3. 再检查一次行首（第 2 步可能把避头标点顶到行首），行首干净优先于行尾；
     * 4. 保底：若仍不合法（极端情况如整段都是标点），在窗口内取最近的合法断点。
     *
     * 前 3 步只把切分点**前移**，所以不会来回震荡，也绝不会超出调用方给的理想宽度。
     * 返回值一定落在 `1..text.length - 1`，文本长度不足 2 时返回 0（表示无法切分）。
     * 纯函数，无副作用，可在任意线程调用。
     *
     * @param text 待切分的正文（可含 `\n`）
     * @param cutIndex 理想切分点，越界会自动夹取
     * @param window 允许微调的最大字数，<= 0 表示不调整
     */
    fun adjustCut(text: String, cutIndex: Int, window: Int = DEFAULT_WINDOW): Int {
        val len = text.length
        if (len < 2) return 0
        val w = window.coerceAtLeast(0)
        var cut = cutIndex.coerceIn(1, len - 1)

        // 1) 行首避头：向前找，把避头标点留给上一行
        var back = 0
        while (cut > 1 && back < w && isForbiddenAtLineStart(text[cut])) {
            cut--
            back++
        }

        // 2) 行尾避尾：继续前移，让开括号 / 开引号整体落到下一行
        //    （若改为后移，行尾虽不再是开符号，但会把开符号挤成孤零零一个字，仍属错误排版）
        var endBack = 0
        while (cut > 1 && endBack < w && isForbiddenAtLineEnd(text[cut - 1])) {
            cut--
            endBack++
        }

        // 3) 第 2 步可能把避头标点又顶到行首，行首干净优先
        var recheck = 0
        while (cut > 1 && recheck < w && isForbiddenAtLineStart(text[cut])) {
            cut--
            recheck++
        }

        // 4) 保底：极端的全标点段落里上面几步可能把预算耗光，
        //    此时在窗口内找最近的合法断点（优先向前，避免超出理想宽度）
        if (isForbiddenAtLineStart(text[cut]) || isForbiddenAtLineEnd(text[cut - 1])) {
            for (d in 1..w) {
                val backPos = cut - d
                if (backPos >= 1 && isCleanCut(text, backPos)) {
                    cut = backPos
                    break
                }
                val forwardPos = cut + d
                if (forwardPos <= len - 1 && isCleanCut(text, forwardPos)) {
                    cut = forwardPos
                    break
                }
            }
        }

        return cut.coerceIn(1, len - 1)
    }

    /**
     * 先做避头尾调整，再在窗口内优先把切分点落在句末标点（`。！？…\n`）之后。
     *
     * 用于自动翻页 / 分页：整句收尾的一页读起来更自然。窗口内找不到句末标点
     * （或找到的位置仍会违反避头尾）时，退回 [adjustCut] 的结果。
     *
     * @return 合法切分点，范围 `1..text.length - 1`；长度不足 2 时返回 0
     */
    fun adjustCutPreferPunctuation(
        text: String,
        cutIndex: Int,
        window: Int = DEFAULT_WINDOW
    ): Int {
        val len = text.length
        if (len < 2) return 0
        val w = window.coerceAtLeast(0)
        val adjusted = adjustCut(text, cutIndex, w)

        // 由近及远向两侧搜索；同距离优先「前移」，避免切分点超出理想宽度把这一页撑破
        for (d in 0..w) {
            if (d > 0) {
                val before = adjusted - d
                if (before in 1..(len - 1) && isLegalSentenceCut(text, before)) return before
            }
            val after = adjusted + d
            if (after in 1..(len - 1) && isLegalSentenceCut(text, after)) return after
        }
        return adjusted
    }

    /** [index] 处的切分是否完全不违反避头尾（index 必须已落在合法范围内） */
    private fun isCleanCut(text: String, index: Int): Boolean =
        !isForbiddenAtLineStart(text[index]) && !isForbiddenAtLineEnd(text[index - 1])

    /** `index` 是否为「紧跟句末标点、且不违反避头尾」的断点 */
    private fun isLegalSentenceCut(text: String, index: Int): Boolean {
        if (index !in 1..(text.length - 1)) return false
        if (SENTENCE_END.indexOf(text[index - 1]) < 0) return false
        return isCleanCut(text, index)
    }
}
