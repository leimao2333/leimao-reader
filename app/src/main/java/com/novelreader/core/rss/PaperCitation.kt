package com.novelreader.core.rss

/**
 * 论文题录导出（v1.8.5.0）。
 *
 * 目标场景：在磊毛阅读里追 Science / Nature / Cell 的新论文，看到值得引的，
 * 直接复制成 **BibTeX**（LaTeX 用户）或 **RIS**（EndNote / Zotero / NoteExpress）
 * 粘进文献管理器，不必回浏览器点「导出题录」。学术出版社网页上这一步通常要走
 * 「Download citation → 选格式 → 再下载」，移动端尤其难用。
 *
 * 全部是纯字符串拼装，不依赖网络，可离线单测。
 */
object PaperCitation {

    /** RIS 支持的文献类型映射（Crossref type → RIS type） */
    private val RIS_TYPES = mapOf(
        "journal-article" to "JOUR",
        "journal-issue" to "JOUR",
        "proceedings-article" to "CONF",
        "book-chapter" to "CHAP",
        "book" to "BOOK",
        "monograph" to "BOOK",
        "report" to "RPRT",
        "posted-content" to "JOUR",
        "dataset" to "DATA",
    )

    /**
     * 生成 BibTeX 条目。
     *
     * @param meta 题录（DOI / 期刊 / 卷 / 期 / 页 / 年 / 类型）
     * @param title 文章标题
     * @param authors 作者列表，`["Zhang San", "Wang Wu"]`
     */
    fun bibtex(meta: PaperMeta, title: String, authors: List<String>): String {
        val entryType = bibtexEntryType(meta)
        val key = citeKey(meta, title, authors)
        val sb = StringBuilder()
        sb.append("@").append(entryType).append("{").append(key).append(",\n")

        sb.append("  title = {").append(escapeBibtex(title)).append("},\n")
        if (authors.isNotEmpty()) {
            sb.append("  author = {").append(escapeBibtex(authors.joinToString(" and "))).append("},\n")
        }
        meta.journal.takeIf { it.isNotBlank() }?.let {
            sb.append("  journal = {").append(escapeBibtex(it)).append("},\n")
        }
        if (meta.volume.isNotBlank()) sb.append("  volume = {").append(meta.volume).append("},\n")
        if (meta.issue.isNotBlank()) sb.append("  number = {").append(meta.issue).append("},\n")
        if (meta.pages.isNotBlank()) sb.append("  pages = {").append(meta.pages).append("},\n")
        if (meta.year > 0) sb.append("  year = {").append(meta.year).append("},\n")
        if (meta.doi.isNotBlank()) sb.append("  doi = {").append(meta.doi).append("},\n")
        meta.publisher.takeIf { it.isNotBlank() }?.let {
            sb.append("  publisher = {").append(escapeBibtex(it)).append("},\n")
        }

        // 去掉最后一行多余的逗号
        val body = sb.toString().trimEnd('\n').trimEnd(',')
        return "$body\n}\n"
    }

    /**
     * 生成 RIS 条目（Reference Manager 交换格式，每行 `TAG: value`）。
     *
     * 每条记录以 `TY  - ` 开头、`ER  - ` 结尾。
     */
    fun ris(meta: PaperMeta, title: String, authors: List<String>): String {
        val sb = StringBuilder()
        sb.append("TY  - ").append(risType(meta)).append("\n")
        authors.forEach { a ->
            sb.append("AU  - ").append(a.trim()).append("\n")
        }
        sb.append("TI  - ").append(title.trim()).append("\n")
        if (meta.journal.isNotBlank()) {
            sb.append("T2  - ").append(meta.journal.trim()).append("\n")
        }
        if (meta.year > 0) {
            sb.append("PY  - ").append(meta.year).append("\n")
        }
        if (meta.volume.isNotBlank()) sb.append("VL  - ").append(meta.volume).append("\n")
        if (meta.issue.isNotBlank()) sb.append("IS  - ").append(meta.issue).append("\n")
        if (meta.firstPage.isNotBlank()) sb.append("SP  - ").append(meta.firstPage).append("\n")
        if (meta.lastPage.isNotBlank()) sb.append("EP  - ").append(meta.lastPage).append("\n")
        if (meta.doi.isNotBlank()) sb.append("DO  - ").append(meta.doi).append("\n")
        if (meta.publisher.isNotBlank()) sb.append("PB  - ").append(meta.publisher.trim()).append("\n")
        sb.append("UR  - ").append(linkOf(meta)).append("\n")
        sb.append("ER  - \n")
        return sb.toString()
    }

    /**
     * BibTeX cite key：`zhang2026nobel`（第一作者姓 + 年 + 标题首个实词）。
     *
     * 作者名没有姓（单名，如中文名）时退回 DOI 末段 / 标题前 8 个字符，
     * 永远给得出值 —— 空的 cite key 会让 BibTeX 直接报错。
     *
     * 标题分词要把连字符也当分隔符：`Nobel-prizewinning breakthroughs` 不拆的话
     * 首个实词是 `Nobel-prizewinning`，cite key 变成 `zhang2026nobelprizewinning`
     * —— 又长又难认，违背 cite key「短且唯一」的初衷。
     */
    fun citeKey(meta: PaperMeta, title: String, authors: List<String>): String {
        val year = if (meta.year > 0) meta.year.toString() else ""
        val surname = authors.firstOrNull()?.let { familyName(it) }?.lowercase()?.sanitizeKey()
        val word = title.split(Regex("[\\s,:;\\-–—_/]+"))
            .firstOrNull { it.length > 3 && !it.lowercase().startsWith("the") && !it.lowercase().startsWith("a") }
            ?.lowercase()?.sanitizeKey()
        val fromDoi = meta.doi.substringAfterLast('/').sanitizeKey().take(8)
        val key = listOfNotNull(
            surname?.takeIf { it.isNotBlank() },
            year.takeIf { it.isNotBlank() },
            word?.takeIf { it.isNotBlank() } ?: fromDoi.takeIf { it.isNotBlank() },
        )
        if (key.isEmpty()) return "untitled"
        return key.joinToString("").take(48)
    }

    /** BibTeX 条目类型：期刊论文 @article，其余按 meta.articleType / publisher 猜 */
    private fun bibtexEntryType(meta: PaperMeta): String {
        val type = meta.articleType.lowercase()
        return when {
            type.contains("review") -> "article"
            type.contains("editorial") || type.contains("news") || type.contains("letter") -> "article"
            type.contains("conference") || type.contains("proceeding") -> "inproceedings"
            type.contains("book") -> "book"
            type.contains("chapter") -> "incollection"
            else -> if (meta.journal.isNotBlank()) "article" else "misc"
        }
    }

    private fun risType(meta: PaperMeta): String {
        val type = meta.articleType.lowercase()
        return when {
            type.isBlank() -> if (meta.journal.isNotBlank()) "JOUR" else "GEN"
            else -> RIS_TYPES.entries.firstOrNull { type.contains(it.key) }?.value ?: "JOUR"
        }
    }

    /** "Zhang San" / "San Zhang" / "Zhang, San" → 姓 */
    private fun familyName(author: String): String {
        val a = author.trim()
        if (a.contains(',')) return a.substringBefore(',').trim()
        val parts = a.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        // 西文姓名最后一段是姓（"San Zhang" → Zhang）
        return parts.last().takeIf { it.first().isUpperCase() } ?: parts.first()
    }

    private fun linkOf(meta: PaperMeta): String =
        meta.doiUrl().ifBlank { "https://doi.org/" }

    /**
     * BibTeX 特殊字符转义。
     *
     * `&` `%` `$` `#` 是 Must 级别（不转义直接报错），`_` 是会悄悄毁掉数学模式的
     * 那一档 —— 标题里出现 `CO_2` / `deep_learning` 时，LaTeX 会把 `_` 当成下标命令。
     * 化学与机器学习论文的标题撞上它的概率不低，所以一并转掉。
     */
    private fun escapeBibtex(s: String): String =
        s.replace("\\", "\\textbackslash{}")
            .replace("&", "\\&")
            .replace("%", "\\%")
            .replace("$", "\\$")
            .replace("#", "\\#")
            .replace("_", "\\_")

    private fun String.sanitizeKey(): String =
        lowercase().replace(Regex("[^a-z0-9]"), "")
}
