package com.novelreader.core.rss

/**
 * 一篇期刊论文的题录元信息（v1.8.5.0）。
 *
 * 来源有两类，互为补充：
 * 1. **订阅源自带**：Science / Nature / PNAS / Cell 这类学术出版社的 RSS 用的是
 *    Prism（<https://prismstandard.org>）元数据，title/link 之外还带
 *    `prism:doi` / `prism:volume` / `prism:number` / `prism:startingPage` /
 *    `prism:publicationName` / `dc:creator` / `dc:date` —— 这些字段此前全被丢掉了，
 *    阅读页只剩一个孤零零的标题。
 * 2. **正文里抠**：`<content:encoded>` / `<description>` 往往写着
 *    `Science, Volume 394, Issue 6821, Page 278-278, October 2026`，
 *    没有 prism 字段的源（Nature 的 d41586 快讯）也能解析出期刊 / 卷 / 期 / 页 / 日期。
 *
 * 阅读页据此渲染「题录卡」，并支持导出 BibTeX / RIS。
 */
data class PaperMeta(
    /** DOI（规范化后，如 `10.1126/science.aek0152`；空 = 未取到） */
    val doi: String = "",
    /** 期刊名（Science / Nature / Cell …） */
    val journal: String = "",
    /** 卷 */
    val volume: String = "",
    /** 期 */
    val issue: String = "",
    /** 起始页 */
    val firstPage: String = "",
    /** 结束页 */
    val lastPage: String = "",
    /** 发表年（0 = 未知） */
    val year: Int = 0,
    /** 文章类型（research-article / news / editorial …，源写什么就用什么） */
    val articleType: String = "",
    /** 作者列表（已拆分清洗） */
    val authors: List<String> = emptyList(),
    /** 出版社 */
    val publisher: String = "",
) {

    /** 页码，如 `278-278` / `1234-1256`；只有一个字段时只显示它 */
    val pages: String
        get() = when {
            firstPage.isNotBlank() && lastPage.isNotBlank() && lastPage != firstPage ->
                "$firstPage-$lastPage"
            firstPage.isNotBlank() -> firstPage
            else -> lastPage
        }

    /**
     * 是否有可展示的题录信息（供阅读页决定要不要渲染题录卡）。
     *
     * 只认**书目字段**：DOI / 期刊 / 卷 / 期 / 页。
     * 作者和年份都不算 —— 普通博客文章也有作者和发布时间，
     * 凭这两项就套一张「题录卡」，会让人以为这是篇论文。
     */
    val hasContent: Boolean
        get() = doi.isNotBlank() || journal.isNotBlank() || volume.isNotBlank() ||
            issue.isNotBlank() || pages.isNotBlank()

    /**
     * 一行式引用格式：`Science, 394(6821): 278-278 (2026)`。
     * 阅读页题录卡副标题 / 复制引用时用。
     */
    fun citationLine(): String {
        val journalPart = if (journal.isNotBlank()) journal else ""
        val volPart = when {
            volume.isBlank() -> ""
            issue.isNotBlank() -> "$volume($issue)"
            else -> volume
        }
        val head = when {
            journalPart.isNotBlank() && volPart.isNotBlank() -> "$journalPart, $volPart"
            journalPart.isNotBlank() -> journalPart
            volPart.isNotBlank() -> volPart
            else -> ""
        }
        val withPages = if (pages.isNotBlank()) {
            if (head.isBlank()) pages else "$head: $pages"
        } else head
        return when {
            withPages.isBlank() && year > 0 -> year.toString()
            withPages.isNotBlank() && year > 0 -> "$withPages ($year)"
            else -> withPages
        }
    }

    /** DOI 的完整链接（无 DOI 时为空） */
    fun doiUrl(): String = if (doi.isBlank()) "" else "https://doi.org/$doi"

    /**
     * 用 [other] 补全自己为空的字段（阅读页「补全题录」用）。
     *
     * 为什么不是整体替换：Crossref 的题录可能比出版页面 meta 更全（卷 / 期 / 页），
     * 而页面 Highwire meta 的作者列表又可能比 Crossref 更全。两个来源各有所长，
     * 逐字段「已有就留、为空才补」比二选一更接近真相 —— 直接替换会出现
     * 「补全之后卷期页反而没了」这种倒退。
     */
    fun mergeWith(other: PaperMeta): PaperMeta = PaperMeta(
        doi = doi.ifBlank { other.doi },
        journal = journal.ifBlank { other.journal },
        volume = volume.ifBlank { other.volume },
        issue = issue.ifBlank { other.issue },
        firstPage = firstPage.ifBlank { other.firstPage },
        lastPage = lastPage.ifBlank { other.lastPage },
        year = year.takeIf { it > 0 } ?: other.year,
        articleType = articleType.ifBlank { other.articleType },
        authors = authors.ifEmpty { other.authors },
        publisher = publisher.ifBlank { other.publisher },
    )
}

/**
 * 论文题录解析（纯逻辑，v1.8.5.0）。
 *
 * 入口有三个，分别对应「源给了结构化字段」「源只在正文里写了一句引用」「只有链接」：
 * - [fromFeedFields]：Prism / Dublin Core 字段（学术出版社 RSS 的标准形态）
 * - [fromCitationLine]：正文里的一句期刊引用（无结构化字段时的兜底）
 * - [doiFrom]：从任意文本里抠 DOI（链接、正文、引用行都可能是它的藏身之处）
 */
object PaperMetaParser {

    /**
     * DOI 匹配：`10.` + 4~9 位数字 + `/` + 后缀。
     *
     * 结尾要剥掉常见标点——引用行里 DOI 后面常跟 `?af=R`（Science 的追踪参数）、
     * `.`、`,`、`;`、`)`。不做这层清洗，导出的 BibTeX 会带个句尾句号。
     */
    private val DOI_REGEX = Regex("""10\.\d{4,9}/[^\s"'<>\[\]()]+""", RegexOption.IGNORE_CASE)

    /** 作者分隔：换行 / 分号 / 顿号 / ` and ` / ` & ` / 多个连续逗号 */
    private val AUTHOR_SPLIT = Regex("""[\n\r;；、]+|\s+and\s+|\s*&\s*""")

    /**
     * 从学术 RSS 的结构化字段建题录。
     *
     * @param doi `prism:doi` 或 `dc:identifier`（形如 `doi:10.1126/...`）
     * @param publicationName `prism:publicationName`
     * @param volume / issue / firstPage / lastPage Prism 字段
     * @param articleType `dc:type`（Books et al. / Research Article / Editorial …）
     * @param creator `dc:creator`（可能多个，用换行 / 分号分隔）
     * @param citationText `<description>` / `<content:encoded>` 原文（兜底解析用）
     * @param link 文章链接（DOI 的备用来源）
     * @param pubDateMs 发布时间（年份 / coverDate 都取不到时用它）
     */
    fun fromFeedFields(
        doi: String = "",
        publicationName: String = "",
        volume: String = "",
        issue: String = "",
        firstPage: String = "",
        lastPage: String = "",
        articleType: String = "",
        creator: String = "",
        citationText: String = "",
        link: String = "",
        pubDateMs: Long = 0L,
    ): PaperMeta {
        val citation = citationText.takeIf { it.isNotBlank() }
            ?.let { fromCitationLine(it) }
            ?: PaperMeta()

        val resolvedDoi = firstNonBlank(
            normalizeDoi(doi),
            doiFrom(citationText),
            doiFrom(link),
        )

        // 期刊名：prism:publicationName → dc:source（已并进 citationText 的）→ 引用行。
        // 不拿站点域名兜底：题录卡显示 "science.org" 比空白更糟。
        val journal = firstNonBlank(
            publicationName.clean(),
            citation.journal,
        )

        // 年份：引用行 > 发布时间（RSS 的 pubDate 通常是上线日，与见刊年一致或接近）
        val year = citation.year.takeIf { it > 0 }
            ?: yearFromText(citationText)
            ?: if (pubDateMs > 0L) yearOf(pubDateMs) else 0

        return PaperMeta(
            doi = resolvedDoi,
            journal = journal,
            volume = firstNonBlank(volume.clean(), citation.volume),
            issue = firstNonBlank(issue.clean(), citation.issue),
            firstPage = firstNonBlank(firstPage.clean(), citation.firstPage),
            lastPage = firstNonBlank(lastPage.clean(), citation.lastPage),
            year = year,
            articleType = articleType.clean(),
            authors = splitAuthors(creator),
        )
    }

    /**
     * 从一句期刊引用里抠题录。
     *
     * 需要吃下的形态（都是真实样例）：
     * - `Science, Volume 394, Issue 6821, Page 278-278, October 2026`
     * - `Proceedings of the National Academy of Sciences, Volume 123, Issue 41, October 2026`
     * - `Nature, Published online: 19 October 2026; doi:10.1038/d41586-026-03317-3`
     */
    fun fromCitationLine(text: String): PaperMeta {
        val s = text.trim()
        if (s.isEmpty()) return PaperMeta()
        // 不是引用句就别硬解析：普通博客正文里也有逗号，
        // 把 "今天天气不错，适合写代码" 当成期刊名会凭空造出一张题录卡
        if (!looksLikeCitation(s)) return PaperMeta()

        val volume = s.regexOf(Regex("""Volume\s+([0-9]+[A-Za-z]?)""", RegexOption.IGNORE_CASE))?.clean()
        val issue = s.regexOf(Regex("""Issue\s+([0-9]+[A-Za-z]?)""", RegexOption.IGNORE_CASE))?.clean()
        val pageRange = s.regexOf(
            Regex("""Pages?\s+([0-9]+[A-Za-z]?(?:\s*[-–—]\s*[0-9]+[A-Za-z]?)?)""", RegexOption.IGNORE_CASE)
        )?.clean()
        val (first, last) = splitPages(pageRange)

        // 年份：优先 "月 年" / "年 月" 的完整表达，退化到任意 4 位年份
        val year = yearFromText(s)
            ?: s.regexGroup0(Regex("""\b(?:19|20)\d{2}\b"""))?.toIntOrNull()
            ?: 0

        // 期刊名：引用行开头到第一个 "," / ";" 为止；"Published online:" 这种前缀要剥掉
        val journal = s.substringBefore(',')
            .substringBefore(';')
            .removePrefix("The ")
            .trim()
            .takeIf { it.isNotEmpty() && !it.contains(':') }

        return PaperMeta(
            doi = doiFrom(s),
            journal = journal?.clean().orEmpty(),
            volume = volume.orEmpty(),
            issue = issue.orEmpty(),
            firstPage = first,
            lastPage = last,
            year = year ?: 0,
        )
    }

    /**
     * 从任意文本里抽 DOI（规范化：去掉 `doi:` 前缀与尾部标点）。
     */
    fun doiFrom(text: String): String {
        if (text.isBlank()) return ""
        val found = DOI_REGEX.find(text)?.value ?: return ""
        return normalizeDoi(found)
    }

    /** DOI 规范化：剥 `doi:` 前缀、URL 包裹、尾部标点 */
    fun normalizeDoi(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        // 从 "10.xxxx/" 起截：字段名（doi: / prism:doi:）或 URL 前缀一律剥掉
        val doiStart = Regex("""10\.\d{4,9}/""", RegexOption.IGNORE_CASE).find(s)?.range?.first
        if (doiStart != null) s = s.substring(doiStart)
        // 尾部标点与追踪参数
        s = s.trimEnd('.', ',', ';', ':', ')', ']', '>', '"', '\'')
        s = s.substringBefore('?')
        return s.trim().lowercase()
    }

    /**
     * 拆分作者串。
     *
     * 学术 RSS 的 `dc:creator` 常见三种写法：多个同名标签（解析后以换行拼接）、
     * `A; B`、`A and B`。学术人名按 "Family, Given" 存时不可按空格拆——
     * 这里只做「分隔符」层面的拆分，人名内部结构原样保留（引用导出时再格式化）。
     */
    fun splitAuthors(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(AUTHOR_SPLIT)
            .map { it.trim().trim(',', '.').trim() }
            .filter { it.isNotEmpty() && it.length in 1..80 }
            .distinct()
            .take(50)
    }

    /**
     * 这段文本像不像一句期刊引用。
     *
     * 判据取「学术出版社 RSS 摘要行的固定成分」：`Volume` / `Issue` / `Page` /
     * `doi:` / `Published online` 任一项命中即认为是。普通文章摘要不会同时出现这些词，
     * 而学术源的 description 几乎只有这几种形态。
     */
    fun looksLikeCitation(text: String): Boolean {
        if (text.length > 600) return false
        val t = text.lowercase()
        return t.contains("volume") || t.contains("issue") ||
            t.contains("page") || t.contains("doi:") ||
            t.contains("published online")
    }

    /** 文章页链接 → 期刊名（论文链接的路径里常带期刊缩写，最后的兜底） */
    private fun journalFromLink(link: String): String {
        if (link.isBlank()) return ""
        val host = runCatching { java.net.URI(link).host.orEmpty() }.getOrDefault("")
        return host.removePrefix("www.")
    }

    private fun splitPages(raw: String?): Pair<String, String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return "" to ""
        val normalized = s.replace(Regex("""\s*[-–—]\s*"""), "-")
        val first = normalized.substringBefore('-')
        val last = normalized.substringAfter('-', "")
        return first.trim() to last.trim().removePrefix("-").trim()
    }

    /** "19 October 2026" / "October 2026" → 年；拿不到返回 null */
    private fun yearFromText(s: String): Int? {
        val monthAlt = listOf(
            "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december",
        ).joinToString("|")
        // 月 年
        Regex("""\b(?:$monthAlt)\s+(\d{4})\b""", RegexOption.IGNORE_CASE).find(s)
            ?.let { return it.groupValues[1].toIntOrNull() }
        // 年 月
        Regex("""\b(\d{4})\s+(?:$monthAlt)\b""", RegexOption.IGNORE_CASE).find(s)
            ?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun yearOf(millis: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        return cal.get(java.util.Calendar.YEAR)
    }

    private fun String.regexOf(re: Regex): String? =
        re.find(this)?.groupValues?.getOrNull(1)

    /** 取正则的**完整匹配**（group 0） */
    private fun String.regexGroup0(re: Regex): String? =
        re.find(this)?.value

    private fun String.clean(): String = trim().trim(',', ';').trim()

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
