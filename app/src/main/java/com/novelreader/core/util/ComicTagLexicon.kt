package com.novelreader.core.util

/**
 * 从**标题 / 简介文本**里抽取题材标签（v1.7.9.0）。
 *
 * 背景：单部作品的标签长期只有「浏览上下文那一个分类名」+「源自带的几个 tags」，
 * 不少源（尤其是没有分类能力的 legado 漫画源、以及只给一两个 tag 的 Venera 源）
 * 点进详情页标签栏几乎是空的。而标题与简介里其实写满了题材词
 * （「转生成为史莱姆」「魔王与勇者的日常」「被吸血鬼养大的我」…）。
 *
 * 词表不另起一套：直接复用两处已有配置 ——
 * - [ComicGenres.ALL] 与 [NovelGenres.ALL]：漫画和小说题材词；
 * - [TagAlias.GROUPS]：96 组同义译名（含英文 / 日文 / 韩文）。
 * 所以「精灵」的抽取能命中 `elf` / `エルフ` / `요정` / `妖精` 四种写法。
 *
 * 两条硬约束（都是为了不误伤）：
 * 1. **单字中文不参与**（长度 < 2 的中文词跳过）：「龙」「剑」「神」会命中
 *    「龙珠」「剑风传奇」「神奇」这类无关文本，抽出来的标签比没有更糟。
 * 2. **元信息词不参与**（[META_TAGS]）：「漫画」「动画」「连载中」这类是形式/状态，
 *    每部作品都会命中，抽了等于给每条都加同一个标签。
 */
object ComicTagLexicon {

    private data class Entry(
        /** 归一后的匹配词（去空白 + 小写） */
        val key: String,
        /** 命中的标签名（同义组已归一到规范名） */
        val tag: String,
        /** 纯 ASCII 词用词边界匹配，避免 `ol` 命中 `follow` 之类的误伤 */
        val pattern: Regex?,
    )

    /** 不参与抽取的元信息 / 状态词（形式、语言、连载状态、榜单入口） */
    private val META_TAGS: Set<String> = setOf(
        "漫画", "动画", "小说", "剧集", "卡通", "comic", "manga", "anime", "novel",
        "全年龄", "成人", "无码", "有码", "高清", "超清", "中字", "生肉",
        "连载", "连载中", "完结", "已完结", "新作", "新书", "新番",
        "排行", "排行榜", "榜单", "热门", "人气", "推荐", "精选", "收藏",
        "独家", "独占", "cosplay", "角色扮演",
        "英语", "日语", "中文", "韩漫", "国产", "欧美",
    )

    /** 视频里**保留**的「元信息」词（见 [VIDEO_META_TAGS]） */
    private val VIDEO_EXTRA_KEEP: Set<String> =
        setOf("全年龄", "成人", "无码", "有码", "高清", "超清", "中字", "生肉")

    /**
     * 视频口径的元信息排除集（v1.8.7.1）。
     *
     * 与漫画的唯一差别：**有无修饰 / 分级 / 字幕 / 画质这几类词要保留**。
     * 漫画把它们当「元信息」是因为每本书都有（抽了等于每条都加同一个标签），
     * 而影视这边它们恰恰是用户最常用来筛片的维度 —— 一部片标了「无码」还是「有码」，
     * 是决定要不要看的信息，不该被当成噪声丢掉。
     *
     * 载体词（`漫画` `动画` `小说`）与连载状态仍然排除：视频的类型维度由
     * [VideoTypeMap] 负责，重复抽一遍只会让标签栏多一行没用的「动画」。
     */
    val VIDEO_META_TAGS: Set<String> = META_TAGS - VIDEO_EXTRA_KEEP

    private fun norm(raw: String): String = raw.filterNot { it.isWhitespace() }.lowercase()

    /** 是否值得参与文本匹配（过短的词会大量误伤） */
    private fun usable(word: String): Boolean {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return false
        val ascii = trimmed.all { it.code < 128 }
        return if (ascii) {
            // 英文单/双字符（gl / bl / sf / ol）在自由文本里误命中率太高
            trimmed.length >= 3 && trimmed.any { it.isLetter() }
        } else {
            trimmed.length >= 2
        }
    }

    private val entries: List<Entry> = buildList {
        val seen = HashSet<String>()
        fun register(word: String, tag: String) {
            val w = word.trim()
            if (!usable(w)) return
            // v1.8.7.1：元信息词**不再在建表时剔除**，改为 [match] 按调用方口径过滤。
            // 漫画把「无码 / 有码」当元信息，视频却要把它们当正经标签 —— 建表时一刀切
            // 就再也拿不回来了。
            val normalizedTag = TagAlias.canonicalOf(tag)
            val key = norm(w)
            if (key.isEmpty() || !seen.add(key)) return
            val ascii = w.all { it.code < 128 }
            val pattern = if (ascii) {
                Regex("(?<![a-z0-9])" + Regex.escape(key) + "(?![a-z0-9])")
            } else {
                null
            }
            add(Entry(key = key, tag = normalizedTag, pattern = pattern))
        }
        // 同义组先入：它们带别名，且 tag 会被归一到规范名
        for (group in TagAlias.GROUPS) {
            val canonical = group.firstOrNull() ?: continue
            for (word in group) register(word, canonical)
        }
        // 再补 ComicGenres 里的长尾中文题材词（同名词已存在则跳过）
        for (genre in ComicGenres.ALL) register(genre, genre)
        for (genre in NovelGenres.ALL) register(genre, genre)
    }
        // 长词优先：先命中「魔法少女」再命中「魔法」，避免被短词抢占
        .sortedByDescending { it.key.length }

    /**
     * 从文本里抽取标签。
     *
     * @param text 标题 + 简介（越靠前的词越容易被视为核心题材，但这里只做包含匹配）
     * @param limit 最多返回几个
     * @param exclude 已有标签（不重复添加）
     * @param metaTags 元信息排除集；默认漫画口径，视频传 [VIDEO_META_TAGS]（v1.8.7.1）
     */
    fun match(
        text: String,
        limit: Int = 6,
        exclude: Set<String> = emptySet(),
        metaTags: Set<String> = META_TAGS,
    ): List<String> {
        if (text.isBlank() || limit <= 0) return emptyList()
        val compact = norm(text)
        val lower = text.lowercase()
        val excluded = exclude.map { TagAlias.canonicalOf(it) }.toHashSet()
        val out = LinkedHashSet<String>()
        for (entry in entries) {
            if (out.size >= limit) break
            if (entry.tag in metaTags) continue
            if (entry.tag in excluded || entry.tag in out) continue
            val hit = entry.pattern?.containsMatchIn(lower) ?: compact.contains(entry.key)
            if (hit) out.add(entry.tag)
        }
        return out.toList()
    }
}
