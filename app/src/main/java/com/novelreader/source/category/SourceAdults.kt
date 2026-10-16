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
     * **漫画 / 图源**的成人站识别（v1.7.2.0）。
     *
     * 影视聚合源用 `🔞` 前缀自报身份，漫画 / 图源（尤其 Venera 源）没有这个约定，
     * 于是 nhentai / ehentai / 禁漫天堂这些站的内容会**抢占首页推荐的第一屏**
     * —— 封面是无需点开就能看到的露骨图，谁在公共场合打开都一样尴尬。
     *
     * 这里按**已知站点名**和 Venera 源的 **author 字段（源自己的 key，如 `nhentai`
     * / `jm` / `wnacg` / `hitomi`）**识别。只收「本身就是 18+ 站」的名字，
     * 综合站（MangaDex / 拷贝漫画 / 包子漫画…）即使有成人分区也不在此列，
     * 免得把正常内容一起沉底。
     */
    private val ADULT_SITE_KEYWORDS = listOf(
        // 同人 / 图库站
        "nhentai", "ehentai", "exhentai", "e-hentai",
        "hitomi", "galleriesindex",
        "wnacg", "紳士漫畫", "绅士漫画",
        // 汉化 / 里漫站
        "h-comic", "hcomic", "禁漫", "18comic", "18漫画",
        "picacg", "哔咔",
        // 中文里漫聚合
        "热辣漫画", "热辣", "熱辣",
        // v1.7.5.0 漏网补充：mycomic 是英文成人漫画站（Venera 里显示名是「x」、
        // key 是 `mycomic`），此前只按名字判，被整批漏掉、成人图直接抢占推荐第一屏
        "mycomic", "18mh", "mh18",
        // 通用 18+ 标记词（源名 / desc 里出现即判成人，比站点名更稳）
        "r-18", "r18", "18+", "18禁", "hentai", "ecchi", "ero", "mature", "nsfw",
    )

    /**
     * 判断某个源是否为 18+ 成人源。
     *
     * @param name 源名（优先），可配合 [author] / [desc] 补充判定；
     *   Venera 源的 `author` 就是源自己的 key（`nhentai` / `jm` / `wnacg`…），
     *   比中文化的显示名更稳定，所以一并参与匹配
     */
    fun isAdult(name: String, author: String = "", desc: String = ""): Boolean {
        val n = name.trim()
        if (n.isEmpty() && author.isBlank()) return false
        // 名称以强前缀开头
        if (n.isNotEmpty() && ADULT_PREFIXES.any { n.startsWith(it) }) return true
        // 拼接名称 / 作者 / 描述，命中关键词即判为成人
        val text = listOf(n, author, desc)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()
        return ADULT_KEYWORDS.any { text.contains(it.lowercase()) } ||
            ADULT_SITE_KEYWORDS.any { text.contains(it.lowercase()) }
    }

    /** 便捷：直接对 [SourceInfo] 判定 */
    fun isAdult(info: SourceInfo): Boolean =
        isAdult(info.name, info.author, info.desc)
}

/** [SourceInfo] 扩展：是否为 18+ 成人源 */
val SourceInfo.isAdult: Boolean
    get() = SourceAdults.isAdult(this)
