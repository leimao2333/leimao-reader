package com.novelreader.core.rss

/**
 * 论文 PDF 直链识别（v1.8.5.0）。
 *
 * 为什么值得单独做：在手机上「打开原文 → 找 PDF 按钮 → 被弹窗拦住 → 再找一次」
 * 是读论文最烦的一步。各大出版社的 PDF 地址其实有**稳定规律**，能直接从 DOI 拼出来：
 *
 * | DOI 前缀 | 出版社 | PDF 规则 |
 * |---|---|---|
 * | `10.1126` | Science / AAAS | `/doi/pdf/{doi}` |
 * | `10.1038` | Nature | `/articles/{后缀}.pdf` |
 * | `10.1073` | PNAS | `/doi/pdf/{doi}` |
 * | `10.1021` | ACS（JACS） | `/doi/pdf/{doi}` |
 * | `10.1039` | RSC（Chemical Science） | 需要期刊代码，从原文链接里抠 |
 * | `10.1016` | Cell（Elsevier） | 需要 PII，从原文链接里抠 |
 *
 * 拼不出来就返回空串，UI 不显示 PDF 按钮 —— **不要**拿一个猜的地址让用户点开 404。
 */
object PaperPdf {

    /** 期刊代码（RSC 的 URL 里带，如 Chemical Science = `sc`） */
    private val RSC_JOURNAL_CODES = setOf(
        "sc", "cp", "cc", "nr", "ob", "fo", "ra", "an", "cs", "qm", "mb", "py",
    )

    /**
     * 由 DOI（必要时加原文链接）拼 PDF 直链。
     *
     * @return 直链；拼不出时为空串
     */
    fun pdfUrl(doi: String, link: String = ""): String {
        val d = PaperMetaParser.normalizeDoi(doi)
        if (d.isBlank()) return fromLink(link)
        val prefix = d.substringBefore('/')
        val suffix = d.substringAfter('/', "")

        return when (prefix) {
            "10.1126" -> "https://www.science.org/doi/pdf/$d"
            "10.1038" -> {
                // Nature 的文章页是 /articles/{后缀}，PDF 就是同名 .pdf
                if (suffix.isBlank()) "" else "https://www.nature.com/articles/$suffix.pdf"
            }
            "10.1073" -> "https://www.pnas.org/doi/pdf/$d"
            "10.1021" -> "https://pubs.acs.org/doi/pdf/$d"
            // Cell 与 RSC 的 PDF 路径依赖文章编号（PII / 期刊代码），只能从原文链接里取
            "10.1016", "10.1039" -> fromLink(link).ifBlank { "" }
            else -> fromLink(link)
        }
    }

    /**
     * 从原文链接推 PDF 地址。
     *
     * 已知两种可推的形态：
     * - Cell：`cell.com/cell/fulltext/S0092-8674(26)00123-4` → `cell.com/cell/pdf/S0092-8674(26)00123-4.pdf`
     * - RSC：`pubs.rsc.org/en/content/articlelanding/2026/sc/d5sc01234a` →
     *   `pubs.rsc.org/en/content/articlepdf/2026/sc/d5sc01234a`
     * - 已经是 PDF 链接：原样返回
     */
    fun fromLink(link: String): String {
        val l = link.trim()
        if (l.isEmpty()) return ""
        val lower = l.lowercase()
        if (lower.contains(".pdf")) return l

        // Cell / Elsevier：/fulltext/{PII}
        val pii = Regex("""/(?:fulltext|abstract)/(S[0-9]{4}-[0-9]{4}\([0-9]{2}\)[0-9]{5}-[0-9Xx])""")
            .find(l)?.groupValues?.getOrNull(1)
        if (pii != null) {
            val journalPath = l.substringAfter("cell.com/").substringBefore("/fulltext")
            if (journalPath.isNotBlank()) {
                return "https://www.cell.com/$journalPath/pdf/$pii.pdf"
            }
        }

        // RSC：/articlelanding/{year}/{journalCode}/{articleId}
        val rsc = Regex("""/content/articlelanding/(\d{4})/([a-z]{2})/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(l)
        if (rsc != null) {
            val (year, code, id) = rsc.destructured
            if (code.lowercase() in RSC_JOURNAL_CODES) {
                return "https://pubs.rsc.org/en/content/articlepdf/$year/${code.lowercase()}/$id"
            }
        }
        return ""
    }

    /**
     * 这个 DOI 是否属于「能拼出 PDF」的出版社。
     * UI 用它决定「打开 PDF」按钮是否置灰。
     */
    fun canResolve(doi: String, link: String = ""): Boolean = pdfUrl(doi, link).isNotBlank()
}
