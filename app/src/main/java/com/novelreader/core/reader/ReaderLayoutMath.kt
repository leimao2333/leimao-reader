package com.novelreader.core.reader

/**
 * 排版纯数学（A1 排版引擎 / A6 残页处理）。
 *
 * 只做「字符串 / 数字」层面的确定性运算：段落切分、二分求最大容纳长度、
 * 自然断行点、分页字符区间、末页半页判定。**不 import android.* / androidx.compose.***，
 * 因此可以脱离 Compose 的 TextMeasurer 单独单测，也能在任意线程被排版引擎复用。
 *
 * 为什么这样拆：真正依赖 Compose 的只有「量一段文字有多少像素高」这一步，
 * 其余的二分边界、标点窗口搜索、阈值判定都是纯逻辑。把这些留在 UI 层会绑死
 * TextMeasurer、无法单测；抽成纯函数后，引擎只需注入一个测量回调即可
 * （见 [ReaderPaginator.MeasureAdapter]），单测则直接喂桩数据。
 */
object ReaderLayoutMath {

    /** 一段文本在分页文本流中的字符区间（[start], [end)），口径与 ReaderSentenceCursor.TextRange 一致。 */
    data class TextRange(val start: Int, val end: Int)

    /**
     * 末页「半页」判定阈值：末页字符数不足满页的 28% 时视为残页。
     *
     * 取 28% 而不是 50%，是因为中文小说一页通常排 20~30 行，只剩不到 1/3 页时
     * 留白非常扎眼，才值得做上提 / 居中处理；50% 会把大量正常末页误判成残页。
     */
    const val DEFAULT_PARTIAL_THRESHOLD: Float = 0.28f

    /** 自然断行时向前搜索句末标点的窗口（字符数），与旧实现保持一致。 */
    const val NATURAL_CUT_WINDOW: Int = 40

    /** 句子收尾断点（与旧 paginateByMeasurement 的 naturalCut 完全同源）。 */
    private val SENTENCE_BREAKS = charArrayOf('\n', '。', '！', '？', '”', '…')

    /**
     * 把原始章节文本切成段落（严格对应旧实现 `content.split("\n").map { it.trimEnd() }`）。
     *
     * 为什么**不过滤空串**：原文空行在排版时会被 [ReaderPaginator] 变成段距占位行，
     * 过滤掉会让「测量用的文本」和「渲染用的文本」不一致，末行就会被裁掉。
     * 空文本（isEmpty）返回空列表，由引擎统一兜底成一页空串。
     */
    fun splitParagraphs(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        return content.split("\n").map { it.trimEnd() }
    }

    /**
     * 二分求「0..length 中最长的、仍满足 [fits] 的前缀长度」。
     *
     * 对应旧实现的 `maxFittingLength`：这里把「测量一整串文字的高度」抽象成回调
     * [fits]，本函数只管二分边界。约定 fits 对前缀长度单调——能放下 n 字，
     * 必定放得下更少字，否则二分不成立（文本测量天然满足）。
     *
     * @param length 文本长度；<= 0 直接返回 0
     * @param fits 入参是前缀长度（1..length），返回该前缀是否放得下
     * @return 最大可容纳前缀长度；连 1 个字都放不下时返回 0
     */
    fun maxFittingLength(length: Int, fits: (Int) -> Boolean): Int {
        if (length <= 0) return 0
        var lo = 1
        var hi = length
        var best = 0
        while (lo <= hi) {
            // 用 lo + (hi - lo)/2 而非 (lo + hi)/2，避免超大文本时 int 溢出
            val mid = lo + (hi - lo) / 2
            if (fits(mid)) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /**
     * 求自然断行点：在理想切分点前 [NATURAL_CUT_WINDOW] 字内优先找句末标点，
     * 再把结果交给 [adjustCut] 做标点避头尾微调。
     *
     * 与旧 `naturalCut` 逐字等价；[adjustCut] 由调用方注入（生产传
     * `ChineseLineBreaker::adjustCut`，测试传桩），这样本函数不依赖 core/text 也能单测。
     * 只**前移**切分点、绝不超出理想宽度，保证切出的这一页不会撑破。
     *
     * @param limit 理想切分点（通常为二分得到的最大容纳长度）
     * @return 合法切分点；limit 超过文本长度时返回整段长度，limit <= 0 返回 0
     */
    fun naturalCut(text: String, limit: Int, adjustCut: (String, Int) -> Int): Int {
        if (limit >= text.length) return text.length
        if (limit <= 0) return 0
        val windowStart = (limit - NATURAL_CUT_WINDOW).coerceAtLeast(1)
        var cut = limit
        for (i in limit downTo windowStart) {
            if (text[i - 1] in SENTENCE_BREAKS) {
                cut = i
                break
            }
        }
        return adjustCut(text, cut)
    }

    /**
     * 计算每页在「分页文本流」（各页文本直接拼接，不含分页符）中的字符区间。
     *
     * 区间首尾相接、互不重叠：第 i 页的 start == 第 i-1 页的 end。
     * 用途是给朗读高亮 / 阅读进度换算提供统一的「第几页对应哪段字」口径，
     * 避免 UI 各处再用 `indexOf` 去猜位置。
     */
    fun pageRanges(pages: List<String>): List<TextRange> {
        val ranges = ArrayList<TextRange>(pages.size)
        var cursor = 0
        for (page in pages) {
            val end = cursor + page.length
            ranges.add(TextRange(cursor, end))
            cursor = end
        }
        return ranges
    }

    /** 末页内容是否不足满页的 [threshold]。满页字数 <= 0 或末页为空时不判残页。 */
    fun isPartialPage(
        lastPageChars: Int,
        fullPageChars: Int,
        threshold: Float = DEFAULT_PARTIAL_THRESHOLD,
    ): Boolean {
        if (fullPageChars <= 0 || lastPageChars <= 0) return false
        val ratio = lastPageChars.toFloat() / fullPageChars.toFloat()
        // threshold <= 0 时任何比例都不会 < threshold，天然安全
        return ratio < threshold
    }

    /** 残页处理策略（A6），纯策略建议，不碰 ViewModel / Compose。 */
    enum class PartialStrategy {
        /** 末页接近满页：保持原样 */
        NORMAL,

        /** 不足阈值一半：内容太少，建议上提合并到前一页 */
        MERGE_UP,

        /** 介于两者之间：保留半页，但垂直居中 / 填充留白 */
        KEEP_CENTERED,
    }

    /**
     * 根据末页 / 满页字数给出残页处理建议。
     *
     * 分档理由：不到阈值一半时半页几乎全是留白，合并到上一页更协调；
     * 刚低于阈值时还有一定内容量，强行合并会让上一页过挤，居中保留更稳妥。
     *
     * @param lastPageChars 末页字符数
     * @param fullPageChars 满页字符数（取首页字数作为估计）
     * @param threshold 半页阈值，默认 [DEFAULT_PARTIAL_THRESHOLD]
     */
    fun partialPageStrategy(
        lastPageChars: Int,
        fullPageChars: Int,
        threshold: Float = DEFAULT_PARTIAL_THRESHOLD,
    ): PartialStrategy {
        if (fullPageChars <= 0 || lastPageChars <= 0) return PartialStrategy.NORMAL
        val ratio = lastPageChars.toFloat() / fullPageChars.toFloat()
        if (ratio >= threshold) return PartialStrategy.NORMAL
        return if (ratio < threshold / 2f) PartialStrategy.MERGE_UP else PartialStrategy.KEEP_CENTERED
    }
}
