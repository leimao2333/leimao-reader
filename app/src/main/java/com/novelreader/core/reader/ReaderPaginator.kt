package com.novelreader.core.reader

import com.novelreader.core.text.ChineseLineBreaker

/**
 * 小说排版引擎（A1 排版引擎独立成类）。
 *
 * 把原先塞在 3000+ 行 `NovelReaderScreen.kt` 里的流程
 * 「原始章节文本 → 段落队列 → 逐页文本 + 每页字符区间」整体封装成类。
 *
 * ## 为什么用接口抽象 TextMeasurer
 *
 * 真正依赖 Compose 的只有「量一段文字渲染后有多少像素高」这一步。把它收窄成
 * [MeasureAdapter] 之后：
 * - 二分测量 / 分页主循环 / 断行点全部落在纯 Kotlin 的 [ReaderLayoutMath] 与本类里，
 *   可脱离 Compose 单测；
 * - UI 层的实现只需做 `measurer.measure(text = render(text), style = ...)`，
 *   并保证 `render` 与真正绘制同一份字串（段距占位行 / 首行缩进参与测量），
 *   否则「测量说放得下、渲染却多一行」会把末行裁掉——这是历史踩过的坑。
 *
 * ## 行为等价约定
 *
 * 主循环逐行复刻旧 `paginateByMeasurement`：段落队列、92% 提前收页、空段占位行、
 * 超长段落二分 + 自然断行、`guardLimit` 死循环保护都保持一致。
 * 唯一新增的是 [Result.pages] 里附带的字符区间与 A6 残页策略建议，不影响既有翻页。
 */
class ReaderPaginator(
    private val measurer: MeasureAdapter,
    private val config: Config,
) {

    /**
     * 像素测量桥，由 UI 层实现。
     *
     * 注意：约束的 maxHeight 必须是 Infinity（旧实现如此），因此 `didOverflowHeight`
     * 恒为 false，判定「放得下」只需比较返回高度与 limitPx，无需再传 limit 进来。
     */
    interface MeasureAdapter {
        /** 测量 [text] 渲染后的高度（px）；[text] 是原始页文本，render 由实现内部负责。 */
        fun measureHeight(text: String, widthPx: Int): Float

        /** 测量「标题 + 标题下方 Spacer」整块高度（px），含标题省略号与真实字号。 */
        fun measureTitleHeight(title: String, widthPx: Int): Float
    }

    /**
     * @param widthPx 可用文本宽度（px），内部会 coerceAtLeast(1)
     * @param heightPx 可用文本高度（px），首屏会先扣掉标题块高度
     * @param showTitle 是否显示章名（隐藏章名时首屏不预留标题高度）
     * @param pageFillRatio 页内容高度达到该比例就提前收页（旧实现 0.92）
     * @param guardLimit 主循环死循环保护上限（旧实现 20000）
     */
    data class Config(
        val widthPx: Float,
        val heightPx: Float,
        val showTitle: Boolean = false,
        val pageFillRatio: Float = 0.92f,
        val guardLimit: Int = 20000,
    )

    /** 一页的文本及其在分页文本流中的区间。 */
    data class Page(val text: String, val range: ReaderLayoutMath.TextRange)

    /** 排版结果：[pages] 供绘制，[partialStrategy] 是 A6 残页策略建议。 */
    data class Result(
        val pages: List<Page>,
        val partialStrategy: ReaderLayoutMath.PartialStrategy,
    ) {
        /** 兼容旧调用方的纯文本视图（不改变既有翻页 / 绘制逻辑）。 */
        val pageTexts: List<String> get() = pages.map { it.text }
    }

    /**
     * 执行分页。
     *
     * @param content 章节正文
     * @param chapterTitle 章名（[Config.showTitle] 为真时参与首屏高度计算）
     */
    fun paginate(content: String, chapterTitle: String = ""): Result {
        // 空白正文：与旧实现一致，直接给一页空串（调用方据此显示「本章无正文」）
        if (content.isBlank()) {
            return Result(
                pages = listOf(Page("", ReaderLayoutMath.TextRange(0, 0))),
                partialStrategy = ReaderLayoutMath.PartialStrategy.NORMAL,
            )
        }

        val width = config.widthPx.toInt().coerceAtLeast(1)
        val queue = ArrayDeque<String>()
        ReaderLayoutMath.splitParagraphs(content).forEach { queue.addLast(it) }

        // 标题高度必须在排版前量好：测量说「标题占 A 高」、渲染占 B 高，
        // 首屏就会多切或少切一行。测量失败按 0 处理，不阻断正文排版。
        val titleHeight: Float = if (config.showTitle && chapterTitle.isNotBlank()) {
            runCatching { measurer.measureTitleHeight(chapterTitle, width) }.getOrDefault(0f)
        } else 0f

        /** 同旧实现的 fitsHeight，但复用测量高度，避免同一串文本被量两次。 */
        fun fitsHeight(text: String, limitPx: Float): Float? {
            val height = measurer.measureHeight(text, width)
            return if (height <= limitPx) height else null
        }

        fun fits(text: String, limitPx: Float): Boolean = fitsHeight(text, limitPx) != null

        fun maxFittingLength(text: String, limitPx: Float): Int =
            ReaderLayoutMath.maxFittingLength(text.length) { fits(text.substring(0, it), limitPx) }

        fun naturalCut(text: String, limit: Int): Int =
            ReaderLayoutMath.naturalCut(text, limit) { t, i -> ChineseLineBreaker.adjustCut(t, i) }

        val pages = mutableListOf<String>()
        var isFirstPage = true
        var guard = 0

        while (queue.isNotEmpty() && guard < config.guardLimit) {
            guard++
            val remainingHeight =
                (config.heightPx - if (isFirstPage) titleHeight else 0f).coerceAtLeast(1f)
            val buffer = StringBuilder()

            // 逐段装页：空段落用 "\n" 占位，保证测量文本与渲染文本一致
            while (queue.isNotEmpty()) {
                val para = queue.first()
                val addition = when {
                    para.isEmpty() && buffer.isEmpty() -> "\n"
                    buffer.isEmpty() -> para
                    else -> "\n$para"
                }
                val trial = buffer.toString() + addition
                val trialHeight = fitsHeight(trial, remainingHeight) ?: break
                buffer.append(addition)
                queue.removeFirst()
                // 装到 92% 就收页，给段距 / 末行留余量（与旧实现同）
                if (trialHeight >= remainingHeight * config.pageFillRatio) break
            }

            val current = buffer.toString()
            if (current.isEmpty()) {
                // 一整段都放不下：二分出最大前缀，再按句读 / 避头尾自然断行
                val para = queue.first()
                val maxLen = maxFittingLength(para, remainingHeight)
                val cut = if (maxLen <= 0) 1 else naturalCut(para, maxLen)
                val safeCut = cut.coerceAtMost(para.length)
                val head = para.substring(0, safeCut)
                val tail = para.substring(safeCut)
                queue.removeFirst()
                if (tail.isNotEmpty()) queue.addFirst(tail)
                pages.add(head)
            } else {
                pages.add(current)
            }
            isFirstPage = false
        }

        // 与旧实现同：空结果兜底成一页空串，再过滤掉空页（仅单页时保留）
        val finalPages = pages.ifEmpty { listOf("") }.filter { it.isNotEmpty() || pages.size == 1 }
        val ranges = ReaderLayoutMath.pageRanges(finalPages)

        // A6：用首页字数估计满页容量，末页字数与之比值得出残页策略。
        // 这里只给「建议」，UI 当前沿用默认（不改变分页结果），避免动到翻页 / 高亮。
        val fullCapacity = finalPages.firstOrNull()?.length ?: 0
        val lastChars = finalPages.lastOrNull()?.length ?: 0
        val strategy = if (finalPages.size <= 1) {
            ReaderLayoutMath.PartialStrategy.NORMAL
        } else {
            ReaderLayoutMath.partialPageStrategy(lastChars, fullCapacity)
        }

        return Result(finalPages.zip(ranges) { text, range -> Page(text, range) }, strategy)
    }
}
