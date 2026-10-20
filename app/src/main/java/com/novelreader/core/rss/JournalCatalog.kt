package com.novelreader.core.rss

/**
 * 一本期刊的预设信息（v1.8.5.0）。
 *
 * @property issn 用于 Crossref 按刊拉最新论文（这是主路径：稳定、免 key、不惧反爬）
 * @property rssUrl 官方 RSS（Science / Nature / Cell / PNAS 可用；ACS 与 RSC 有 Cloudflare 拦截，
 *   留空表示「不提供」，避免给出一个必然 403 的地址）
 */
data class JournalPreset(
    /** 稳定标识（同时用作订阅 URL 的 issn 段） */
    val issn: String,
    /** 期刊全名（英文，与出版社一致） */
    val title: String,
    /** 中文简称（列表 / 分组里显示） */
    val shortName: String,
    /** 出版社 */
    val publisher: String,
    /** 学科分类 */
    val field: String,
    /** 官方 RSS（空 = 该刊不提供 / 被反爬拦住，只走 Crossref） */
    val rssUrl: String = "",
    /** 一句话说明（订阅列表里作为副标题） */
    val description: String = "",
) {
    /** 虚拟订阅地址：`crossref:issn:0028-0836` */
    val feedUrl: String get() = JournalFeed.urlOf(issn)
}

/**
 * 顶刊预设库（v1.8.5.0）。
 *
 * 六本都是**化学 / 综合类**的高频阅读对象（用户点名要求）：
 * Science、Nature、Cell、Chemical Science、PNAS、JACS。
 *
 * 为什么订阅走 Crossref 而不是官方 RSS：
 * - ACS（JACS）与 RSC（Chemical Science）的 RSS 有 Cloudflare 挑战，App 里直接抓会 403；
 * - Crossref 的 `/journals/{issn}/works` 免 key、稳定、返回结构化题录（DOI / 作者 / 卷期页），
 *   比各家 RSS 的字段还齐；
 * - Science / Nature / Cell / PNAS 的 RSS 能抓，但**不含摘要**（description 只有一句引用），
 *   而 Crossref 部分刊带 `abstract`，且能按 DOI 精确补全。
 *
 * 所以：**默认用 Crossref 拉题录**，官方 RSS 作为可选项列在下面（用户想加就加）。
 */
object JournalCatalog {

    val PRESETS: List<JournalPreset> = listOf(
        JournalPreset(
            issn = "0036-8075",
            title = "Science",
            shortName = "Science",
            publisher = "AAAS",
            field = "综合",
            rssUrl = "https://www.science.org/action/showFeed?type=etoc&feed=rss&jc=science",
            description = "AAAS 旗舰刊，每周目录（官方 RSS 可用）",
        ),
        JournalPreset(
            issn = "0028-0836",
            title = "Nature",
            shortName = "Nature",
            publisher = "Springer Nature",
            field = "综合",
            rssUrl = "https://www.nature.com/nature.rss",
            description = "Springer Nature 旗舰刊（官方 RSS 可用）",
        ),
        JournalPreset(
            issn = "0092-8674",
            title = "Cell",
            shortName = "Cell",
            publisher = "Elsevier",
            field = "生命科学",
            rssUrl = "https://www.cell.com/cell/current.rss",
            description = "Cell Press 旗舰刊（官方 RSS 可用）",
        ),
        JournalPreset(
            issn = "2041-6520",
            title = "Chemical Science",
            shortName = "Chem Sci",
            publisher = "Royal Society of Chemistry",
            field = "化学",
            rssUrl = "",
            description = "RSC 旗舰开放获取刊（官方 RSS 有反爬，走 Crossref）",
        ),
        JournalPreset(
            issn = "0027-8424",
            title = "Proceedings of the National Academy of Sciences",
            shortName = "PNAS",
            publisher = "NAS",
            field = "综合",
            rssUrl = "https://www.pnas.org/action/showFeed?type=etoc&feed=rss&jc=pnas",
            description = "美国科学院院刊（官方 RSS 可用）",
        ),
        JournalPreset(
            issn = "0002-7863",
            title = "Journal of the American Chemical Society",
            shortName = "JACS",
            publisher = "American Chemical Society",
            field = "化学",
            rssUrl = "",
            description = "ACS 化学旗舰刊（官方 RSS 有反爬，走 Crossref）",
        ),
    )

    /** 按 ISSN 精确取预设 */
    fun byIssn(issn: String): JournalPreset? =
        PRESETS.firstOrNull { it.issn == issn.trim() }

    /**
     * 模糊匹配（名称 / 简称 / ISSN 子串）。
     *
     * 用户在搜索框里敲 "jacs"、"化学"、"nature" 都能命中；也用于把
     * Crossref 检索回来的期刊与预设对上（对上就显示中文简称）。
     */
    fun find(query: String): List<JournalPreset> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return PRESETS
        return PRESETS.filter {
            it.title.lowercase().contains(q) ||
                it.shortName.lowercase().contains(q) ||
                it.issn.contains(q) ||
                it.publisher.lowercase().contains(q) ||
                it.field.contains(q)
        }
    }

    /**
     * 把 Crossref 返回的期刊映射回预设（按 ISSN 命中，含电子版 ISSN）。
     */
    fun matchPreset(journal: JournalRef): JournalPreset? =
        PRESETS.firstOrNull { preset ->
            journal.issns.any { it == preset.issn } || journal.issn == preset.issn
        }
}
