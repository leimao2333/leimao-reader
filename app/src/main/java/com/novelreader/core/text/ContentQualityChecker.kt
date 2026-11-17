package com.novelreader.core.text

/**
 * 正文质量快照（B6）。
 *
 * @param chars 字数（去掉空白后的字符数，与 [TextCleaner.charCount] 同口径）
 * @param paragraphs 段落数（非空行数，与 [TextCleaner.paragraphs] 同口径）
 * @param adHits 正文末尾命中的广告 / 站名尾巴关键词种数
 * @param gibberishRatio 乱码字符占比（0f~1f）
 * @param score 综合分（0~100，越高越像正经正文）
 */
data class ContentQuality(
    val chars: Int,
    val paragraphs: Int,
    val adHits: Int,
    val gibberishRatio: Float,
    val score: Int,
)

/**
 * 正文质量检查（B6）。
 *
 * 回答三个问题：这段正文**是不是空的**、**是不是只剩广告**、**是不是乱码**。
 * 典型用途：换源后的候选正文打分（挑分高的）、批量体检时标记「该源正文不可读」、
 * 阅读页对可疑章节给提示。
 *
 * 纯 Kotlin（只用 JDK），无 Android / 协程依赖，可单测、也可在后台线程批量跑。
 *
 * 设计取舍：
 * - **只数「确定是乱码」的字符**（替换符 / 制表方块 / 控制符 / 私用区 / 连续问号），
 *   不把外文、生僻标点算进去 —— 宁可漏判，不可把正常外文小说判成乱码。
 * - 关键词表只用**通用**书源尾巴话术，不写任何具体站点名（项目文案红线）。
 */
object ContentQualityChecker {

    /** 广告 / 站名尾巴关键词（均为各站通用话术，不含具体站名） */
    private val AD_KEYWORDS = listOf(
        "请记住本站",
        "记住本站",
        "本站域名",
        "最新网址",
        "手机版阅读",
        "加入书签",
        "加入书架",
        "收藏本站",
        "最新章节",
        "全文阅读",
        "无弹窗",
        "一秒记住",
        "www.",
        "http",
        "章节报错",
    )

    /**
     * 可以从正文末尾整行删掉的关键词子集。
     *
     * 比 [AD_KEYWORDS] 窄：「最新章节」「全文阅读」这类词也可能出现在正文句子里，
     * 只凭包含就删整行风险太大，所以净化尾巴只认明显是「站点尾巴」的那几个。
     */
    private val STRIP_MARKERS = listOf(
        "请记住本站",
        "记住本站",
        "本站域名",
        "最新网址",
        "最新地址",
        "手机版阅读",
        "手机阅读",
        "加入书签",
        "加入书架",
        "收藏本站",
        "一秒记住",
        "无弹窗",
        "章节报错",
    )

    /** 广告只在这些关键词出现在**末尾窗口**内时才计数（正文中段提到书名不算） */
    private const val TAIL_WINDOW = 200

    /** 整行都是网址 / 域名时也算广告尾巴（如 `https://xxx.com/1_1.html`） */
    private val URL_LINE = Regex("""^(?:https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)

    /** 单行超过这个长度就按正文看待，不做「整行删」判断 */
    private const val MAX_AD_LINE_LEN = 120

    /** 一次最多删这么多尾巴行，避免把整章都当广告删光 */
    private const val MAX_STRIP_LINES = 6

    /** 删完至少还要剩这么多字，否则视为误判，原样返回 */
    private const val MIN_KEEP_CHARS = 30

    /** 乱码判定：确定不是正文内容的字符（不含空格 / 换行） */
    private fun isBrokenChar(c: Char): Boolean = when {
        c == '\uFFFD' -> true // 替换符（解码失败）
        c == '□' || c == '■' || c == '▯' || c == '▮' -> true // 缺字方块
        c == '█' || c == '▓' || c == '▒' || c == '░' -> true // 色块（打码 / 乱码）
        c.isISOControl() -> true // 控制符
        c in '\uE000'..'\uF8FF' -> true // 私用区（字体私有字形）
        else -> false
    }

    /** 完整检查：字数 / 段落 / 广告命中 / 乱码占比 / 综合分 */
    fun check(text: String): ContentQuality {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val chars = normalized.count { !it.isWhitespace() }
        val paragraphs = normalized.split('\n').count { it.isNotBlank() }
        val adHits = countAdHits(normalized)
        val ratio = gibberishRatio(normalized)
        return ContentQuality(
            chars = chars,
            paragraphs = paragraphs,
            adHits = adHits,
            gibberishRatio = ratio,
            score = scoreOf(chars, paragraphs, adHits, ratio),
        )
    }

    /**
     * 是否「基本没内容」：字数 < 20、末尾广告命中 ≥ 3 种、或乱码占比 > 0.3。
     *
     * 三个条件都是**任一满足即判空**——这三种正文拿来给用户读都是白屏/广告页，
     * 区分对待没有意义；具体是哪一种看 [check] 的字段。
     */
    fun isLikelyEmptyOrAd(text: String): Boolean {
        val q = check(text)
        return q.chars < 20 || q.adHits >= 3 || q.gibberishRatio > 0.3f
    }

    /**
     * 去掉正文末尾的广告尾巴行。
     *
     * 规则（保守，只动末尾）：
     * 1. 从最后一行往前，只删「整行是网址」或「行内含 [STRIP_MARKERS] 且不超长」的行；
     * 2. 遇到第一行正常正文立刻停手，不碰正文中间的任何内容；
     * 3. 最多删 [MAX_STRIP_LINES] 行；删完剩余字数不足 [MIN_KEEP_CHARS] 视为误判，原样返回。
     *
     * 未删任何行时原样返回入参（连首尾空白都不动），方便调用方判断「有没有变」。
     */
    fun stripAdTail(text: String): String {
        if (text.isBlank()) return text
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n').toMutableList()
        var removed = 0

        while (lines.isNotEmpty() && removed < MAX_STRIP_LINES) {
            val last = lines.last()
            if (last.isBlank()) {
                lines.removeAt(lines.size - 1)
                continue
            }
            if (!isAdLine(last)) break
            lines.removeAt(lines.size - 1)
            removed++
        }

        if (removed == 0) return text
        val kept = lines.joinToString("\n").trimEnd()
        val keptChars = kept.count { !it.isWhitespace() }
        return if (keptChars < MIN_KEEP_CHARS) text else kept
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 统计末尾窗口内命中的广告关键词种数 */
    private fun countAdHits(normalized: String): Int {
        if (normalized.isBlank()) return 0
        val tail = if (normalized.length <= TAIL_WINDOW) {
            normalized
        } else {
            normalized.substring(normalized.length - TAIL_WINDOW)
        }
        val lower = tail.lowercase()
        return AD_KEYWORDS.count { keyword ->
            lower.contains(keyword.lowercase())
        }
    }

    /**
     * 乱码占比 = 确定乱码的字符数 / 非空白字符数。
     *
     * 连续 3 个以上问号整段计入（`?????` 是编码失败的典型产物，单个问号是正常标点）。
     */
    private fun gibberishRatio(normalized: String): Float {
        var total = 0
        var bad = 0
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            total++
            if (c == '?') {
                var j = i
                while (j < normalized.length && normalized[j] == '?') j++
                val run = j - i
                if (run >= 3) {
                    bad += run
                    total += run - 1
                }
                i = j
                continue
            }
            if (isBrokenChar(c)) bad++
            i++
        }
        if (total == 0) return 0f
        return (bad.toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * 综合分（0~100）。扣分项按「先扣致命的、再扣可疑的」排列：
     *
     * - 字数：< 20 扣 70（基本是空章 / 公告），< 100 扣 30，< 300 扣 10；
     * - 广告：每种关键词扣 10，最多扣 40；
     * - 乱码：占比 × 150，最多扣 50；
     * - 单段且 ≥ 600 字扣 10（丢了换行，阅读页会糊成一坨）。
     */
    private fun scoreOf(chars: Int, paragraphs: Int, adHits: Int, ratio: Float): Int {
        if (chars == 0) return 0
        var score = 100
        score -= when {
            chars < 20 -> 70
            chars < 100 -> 30
            chars < 300 -> 10
            else -> 0
        }
        score -= (adHits * 10).coerceAtMost(40)
        score -= (ratio * 150).toInt().coerceAtMost(50)
        if (paragraphs <= 1 && chars >= 600) score -= 10
        return score.coerceIn(0, 100)
    }

    /** 该行是否属于「可以整行删掉的广告尾巴」 */
    private fun isAdLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (URL_LINE.matches(t)) return true
        if (t.length > MAX_AD_LINE_LEN) return false
        return STRIP_MARKERS.any { t.contains(it, ignoreCase = true) }
    }
}
