package com.novelreader.source.category

import com.novelreader.domain.model.SourceInfo

/**
 * 18+（成人）源识别。
 *
 * 成人视频源在聚合源里通过源名**以 `🔞` 前缀标记**（如 `🔞精品资源`、`🔞桃花资源`），
 * 这是目前唯一稳定可靠的信号。聚合源 `video-18.json` 里的 28 个源全部带该前缀。
 *
 * 识别规则（按优先级，命中即返回 true）：
 * 1. 源名以 `🔞` 或 `18+` / `成人` 开头；
 * 2. 源名 / 作者 / 描述含 `🔞` 标记；
 * 3. 源名含 `18+` / `成人` / `成人视频` / `成人影视` 等关键词。
 */
object SourceAdults {

    /** 名称开头即 18+ 的强信号前缀 */
    private val ADULT_PREFIXES = listOf("🔞", "18+", "18禁", "成人", "R18", "R-18")

    /** 名称/描述中出现的 18+ 关键词（含敏感语义） */
    private val ADULT_KEYWORDS = listOf(
        "🔞", "18+", "18禁", "成人视频", "成人影视", "成人片", "成人资源",
        "R18", "R-18", "r18", "r-18",
    )

    /**
     * 判断某个源是否为 18+ 成人源。
     *
     * @param name 源名（优先），可配合 [author] / [desc] 补充判定
     */
    fun isAdult(name: String, author: String = "", desc: String = ""): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        // 名称以强前缀开头
        if (ADULT_PREFIXES.any { n.startsWith(it) }) return true
        // 拼接名称 / 作者 / 描述，命中关键词即判为成人
        val text = listOf(n, author, desc)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return ADULT_KEYWORDS.any { text.contains(it) }
    }

    /** 便捷：直接对 [SourceInfo] 判定 */
    fun isAdult(info: SourceInfo): Boolean =
        isAdult(info.name, info.author, info.desc)
}

/** [SourceInfo] 扩展：是否为 18+ 成人源 */
val SourceInfo.isAdult: Boolean
    get() = SourceAdults.isAdult(this)
