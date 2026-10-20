package com.novelreader.core.util

import com.novelreader.core.text.ChineseConverter
import com.novelreader.domain.model.MediaItem

/**
 * 标签查询纯逻辑（v1.5.10.0）。
 *
 * 标签搜索的**复合语义**（v1.8.0.0 明确口径）：
 *
 * 用户输入 `A B` 时，`A` 与 `B` 各自是一个**大集**（标签组）：
 * - `A` 大集 = `A` + 它在 [TagAlias.GROUPS] 里的全部转译写法（无码 / 無碼 / 无修正 /
 *   無修正 / uncensored …）；
 * - `B` 大集同理。
 *
 * 求值规则是**组内取并、组间取交**：
 * - 组内：一部作品只要命中该组**任意一个**写法，就算这一组满足（并集）；
 * - 组间：必须**每一组都满足**，作品才留下（交集）。
 *
 * 也就是「出来的内容要有两个或以上标签，且这两个标签分别来自这两个大集」。
 * 一个作品只在 A 组出现、或只在 B 组出现，都会被排除。
 *
 * 纯函数无 Android 依赖，方便单测。
 */
object TagQuery {

    // 正则只编译一次（v1.8.4.1）。去重 / 求交时每部作品都要算一次身份键，
    // 以前每次调用都 new 一个 Regex —— 一次搜索几千条、翻页再乘几轮，
    // 光编译正则就能把主线程占满（实测在模拟器上触发 ANR）。
    private val PERCENT_RE = Regex("%[0-9a-fA-F]{2}")
    private val PERCENT20_RE = Regex("(?i)%20")
    private val WHITESPACE_RE = Regex("[\\s　]+")
    private val BRACKET_RE = Regex("[（(\\[【〔《<][^）)\\]】〕》>]*[）)\\]】〕》>]")
    private val SEPARATOR_RE = Regex("[-—–_~～|/\\\\:：·]+")


    /**
     * 解析查询串为标签列表：按空白拆分、去空、去重、保序。
     *
     * 全角空格（U+3000，中文输入法常见）也按分隔符处理。
     */
    fun parse(query: String): List<String> =
        splitLogicalTags(query)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** 百分号编码的 `%20` 及 URL 表单编码的 `+` 也作为标签分隔符。 */
    private fun splitLogicalTags(query: String): List<String> {
        val hasPercentEncoding = PERCENT_RE.containsMatchIn(query)
        val withDecodedSeparators = query.replace(PERCENT20_RE, " ")
        val normalized = if (hasPercentEncoding) withDecodedSeparators.replace('+', ' ') else withDecodedSeparators
        return normalized.split(WHITESPACE_RE)
    }

    /** 标题归一化：去所有空白 + 小写，用于跨标签/跨源匹配同一部作品 */
    fun normalizeTitle(title: String): String =
        title.filterNot { it.isWhitespace() }.lowercase()

    /**
     * **作品身份键**（v1.8.0.0）：一部作品可能有多个等价键，任一相同即视为同一部。
     *
     * 只比 [normalizeTitle] 时，跨源同名作品经常对不上，交集被白丢：
     * - 繁体源「無碼」标题是繁体，简体源是简体 → 逐字不同；
     * - 站点习惯给标题挂后缀：「[作者名]」「(全彩)」「【完结】」「 - 第1卷」。
     *
     * 这里返回两层键：
     * 1. [normalizeTitle] 原口径（保留，向后兼容）；
     * 2. 净化键：去括号内容 → 去标点符号 → 繁体转简体 → 去空白小写。
     *
     * 净化键可能为空（标题全是标点）或与原口径相同，此时去重后只留一个。
     */
    fun matchKeys(title: String): Set<String> {
        val base = normalizeTitle(title)
        val cleaned = workKey(title)
        val out = LinkedHashSet<String>(2)
        if (base.isNotEmpty()) out.add(base)
        if (cleaned.isNotEmpty() && cleaned != base) out.add(cleaned)
        // 没有任何可比对的键（标题为空/纯标点）时返回空集：宁可判为「不匹配」，
        // 也不能让两个空标题互相命中——那会把无关作品合进交集。
        return out
    }

    /**
     * 作品净化键：剥掉标题里的**装饰性**成分，只留作品主体。
     *
     * 处理顺序有意如此：先去括号（内容整块丢弃）再去标点 —— 否则「(全彩)」先被拆成
     * 「全彩」反而混进主体。
     *
     * 繁体统一转简体，让 `無碼` 与 `无码` 落到同一个键上（[ChineseConverter] 只收录
     * 常用字，未收录的原样保留，不会转错）。
     */
    private fun workKey(title: String): String {
        var s = title
        // 英文括号 + 中文括号 + 方括号 + 书名/角括号，连同内部内容一起去掉
        s = s.replace(BRACKET_RE, " ")
        // 常见分隔后缀：--- 第12话 / - 番外 / ～xxx～
        s = s.replace(SEPARATOR_RE, " ")
        // 只保留文字与数字（中英日韩 + 数字），标点全部丢弃
        s = s.filter { it.isLetterOrDigit() }
        s = ChineseConverter.toSimplified(s)
        return s.lowercase()
    }

    /**
     * 作品**身份键**（单一、稳定）：用于结果去重 —— 同一部作品在不同源上标题写法
     * 不同（繁简 / 带作者后缀）时，[matchKeys] 是两个集合，但身份键应只留一个。
     *
     * 优先用净化键；标题全是标点等极端情况回退到归一标题。
     */
    fun identityKey(title: String): String =
        workKey(title)
            .ifEmpty { normalizeTitle(title) }
            .ifEmpty { title.trim() }

    /**
     * 复合标签求交（**组间取交**，组内并集由调用方先算好）。
     *
     * [results] 与用户的每个标签一一对应，每个元素是**该标签大集的并集**
     * （该标签 + 全部转译写法 + 全部源的结果）。返回同时出现在**所有**大集里的作品。
     *
     * 匹配口径（v1.8.0.0）：用 [matchKeys] 的多键判定 —— 作品的**任一**等价键
     * 出现在某个大集里，就算命中该大集。相比只比精确标题，繁体标题、带作者/状态
     * 后缀的同名作品不再被误判为不同作品。
     *
     * 单标签（results.size == 1）时原样返回；任一大集为空 → 交集为空
     * （语义正确：没有作品能同时属于一个空分类）。
     */
    fun intersect(results: List<List<MediaItem>>): List<MediaItem> {
        if (results.isEmpty()) return emptyList()
        if (results.size == 1) return results[0]
        if (results.any { it.isEmpty() }) return emptyList()

        // 每个大集建立一个「键池」，命中任一键即算该作品出现在这个集里
        val keyPools = results.map { items ->
            val pool = HashSet<String>(items.size * 2)
            items.forEach { item -> pool.addAll(matchKeys(item.title)) }
            pool
        }

        // 从最小的集合开始筛（候选少、比较次数少）
        val smallest = results.indices.minByOrNull { results[it].size } ?: return emptyList()
        val survivors = results[smallest].filter { item ->
            val keys = matchKeys(item.title)
            // 每个大集里都要能找到这个作品的某个等价键
            results.indices.all { idx -> idx == smallest || keys.any { it in keyPools[idx] } }
        }
        if (survivors.isEmpty()) return emptyList()

        val survivorKeys = HashSet<String>(survivors.size * 2)
        survivors.forEach { survivorKeys.addAll(matchKeys(it.title)) }

        // 按第一个标签的原始顺序输出，保持结果稳定可复现
        return results.first().filter { item -> matchKeys(item.title).any { it in survivorKeys } }
    }
}
