package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem

/**
 * 影视标签词表（v1.8.4.0，待办 #11 / #12）。
 *
 * 为什么影视需要单独一张表：小说 / 漫画靠 legado 的 `kind` 与 Venera 的 `tags`，
 * 一部作品通常带 2~5 个题材；而苹果 CMS 系采集站只给 `vod_type_name` 一个字段，
 * 于是「香港 2025 年的动作片」在标签层只剩一个「动作片」，地区 / 年代 / 语言
 * 这些**源里明明有、却从没进过标签层**的维度全丢了 —— 用户想「找 2025 年的港片」
 * 无从下手，标签搜索看起来就像只认题材。
 *
 * 这里把三件事收口（纯逻辑，不碰数据库）：
 * 1. **地区 / 语言归一**：`vod_area` 的「内地 / 大陆 / 国产」是同一个意思，
 *    统一成一个规范名，否则搜「内地」搜不到标了「大陆」的那批；
 * 2. **年代**：`vod_year` 的 4 位年份直接成标签（2025 这种最直白，不搞「2020 年代」）；
 * 3. **主创**：`vod_actor` / `vod_director` 取前几个名字 —— 这是「搜演员」的唯一来源，
 *    源不提供结构化演员表时只能靠它。
 *
 * 标题里的题材词**只做窄口径补充**（见 [genresFromTitle]）：影视简介多是剧情复述，
 * 「爱情」出现在一部恐怖片的简介里很正常，所以只认标题、且只认歧义小的词。
 */
object VideoTagLexicon {

    /** 一条派生标签 */
    data class Derived(
        val name: String,
        /** 标签大类（[TagTagger.CATEGORY_*]） */
        val category: String,
        val confidence: Int,
        val origin: String,
        /** 是否要再过一遍 [TagAlias.canonicalOf]（地区 / 年代 / 人名不该被改写成别的词） */
        val canonicalize: Boolean = true,
    )

    /** 站方结构化字段：最可信 */
    private const val CONF_STATION = 100

    /** 标题关键词命中：字面相关，不等于语义确定 */
    private const val CONF_TEXT = 60

    /** 一部片子最多记几个主演（再多就把标签池淹了） */
    private const val MAX_CAST = 2

    /** 标题里最多补几个题材（标题本来就不长，3 个足够） */
    private const val MAX_TITLE_GENRES = 3

    /** 地区规范名（`vod_area` 的常见取值及其变体） */
    private val REGIONS: Map<String, String> = buildMap {
        // 国产：采集站的「大陆 / 内地 / 国产」是一个意思，统一到已在词表里的「国产」
        listOf("大陆", "大陸", "内地", "內地", "国产", "國產", "中国大陆", "中國大陸", "mainland")
            .forEach { put(it, "国产") }
        listOf("香港", "港片", "港剧", "港劇", "香港片", "hongkong", "hong kong").forEach { put(it, "香港") }
        listOf("台湾", "臺灣", "台片", "台剧", "台劇", "taiwan").forEach { put(it, "台湾") }
        listOf("美国", "美國", "美剧", "美劇", "美影", "好莱坞", "好萊塢", "hollywood").forEach { put(it, "美国") }
        // 「韩国」不并进词表里的「韩漫」组 —— 那是漫画来源口径，影视这边按地区独立成标签
        listOf("韩国", "韓國", "韩剧", "韓劇", "韩影", "韓影").forEach { put(it, "韩国") }
        listOf("日本", "日剧", "日劇", "日影", "japan").forEach { put(it, "日本") }
        listOf("泰国", "泰國", "泰剧", "泰劇", "thai").forEach { put(it, "泰国") }
        listOf("印度", "宝莱坞", "寶萊塢", "bollywood", "india").forEach { put(it, "印度") }
        listOf("英国", "英國", "英剧", "英劇", "british").forEach { put(it, "英国") }
        listOf("法国", "法國", "法剧", "法劇", "french").forEach { put(it, "法国") }
        listOf("欧美", "歐美", "西方", "western").forEach { put(it, "欧美") }
        listOf("日韩", "日韓").forEach { put(it, "日韩") }
        listOf("港台", "港臺").forEach { put(it, "港台") }
        // v1.8.4.0：分类名里常出现「中国动漫」「海外动漫」，两个词本身就是地区
        listOf("中国", "中國").forEach { put(it, "国产") }
        listOf("海外", "海外劇", "海外剧").forEach { put(it, "海外") }
        // v1.8.7.0（待办 #28）：成人向采集站按「亚洲 / 欧美」分区（`亚洲情色`），
        // 双字以上才能进 [REGION_PREFIXES] 做前缀拆分
        listOf("亚洲", "亞洲", "asia").forEach { put(it, "亚洲") }
    }

    /**
     * 单字地区（「港剧」「美剧」「日漫」这类二级分类的残留部分）。
     *
     * 拆分类名时先把类型词（「剧」「动漫」）剥掉，剩下的常常只有一个字 ——
     * 这个字单独成标签没有意义，能对上地区的就换成规范地区名。
     */
    private val SHORT_REGIONS: Map<String, String> = mapOf(
        "港" to "香港", "台" to "台湾", "美" to "美国", "韩" to "韩国",
        "日" to "日本", "泰" to "泰国", "英" to "英国", "法" to "法国", "印" to "印度",
    )

    /** 语言规范名（`vod_lang` 的常见取值） */
    private val LANGUAGES: Map<String, String> = buildMap {
        listOf("国语", "國語", "普通话", "普通話", "汉语", "漢語").forEach { put(it, "国语") }
        listOf("粤语", "粵語", "广东话", "廣東話").forEach { put(it, "粤语") }
        listOf("英语", "英語", "英文").forEach { put(it, "英语") }
        listOf("日语", "日語", "日文").forEach { put(it, "日语") }
        listOf("韩语", "韓語", "韩文", "韓文").forEach { put(it, "韩语") }
        listOf("法语", "法語").forEach { put(it, "法语") }
        listOf("德语", "德語").forEach { put(it, "德语") }
        listOf("泰语", "泰語").forEach { put(it, "泰语") }
        listOf("俄语", "俄語").forEach { put(it, "俄语") }
        listOf("闽南语", "閩南語", "台语", "台語").forEach { put(it, "闽南语") }
        listOf("西班牙语", "西班牙語").forEach { put(it, "西班牙语") }
        listOf("意大利语", "義大利語").forEach { put(it, "意大利语") }
    }

    /**
     * 标题里可以放心认的题材词。
     *
     * 只收**歧义小**的：像「爱情」「家庭」这种既可能是题材也可能是剧情的词也收了，
     * 但只在**标题**里认（简介里出现不算）。歧义大的（「青春」既指年龄也指题材）
     * 一律不收，宁可漏也不污染。
     */
    private val TITLE_GENRES: List<String> = listOf(
        "动作", "喜剧", "爱情", "科幻", "恐怖", "惊悚", "悬疑", "推理", "犯罪", "警匪",
        "战争", "剧情", "动画", "武侠", "古装", "奇幻", "冒险", "灾难", "传记", "历史",
        "音乐", "运动", "家庭", "儿童", "丧尸", "僵尸", "怪兽", "间谍", "黑帮", "歌舞",
        "超级英雄", "纪录片",
    )

    /**
     * 派生一部影视作品的全部附加标签（地区 / 语言 / 年代 / 主创 / 标题题材）。
     *
     * 不包含 `typeName` —— 那条走 [ItemTags]，因为详情页标签栏要展示它。
     */
    fun derive(item: MediaItem): List<Derived> {
        val out = LinkedHashSet<Derived>()

        splitValues(item.extras["area"]).forEach { raw ->
            val name = REGIONS[normalize(raw)] ?: return@forEach
            out += Derived(name, TagTagger.CATEGORY_REGION, CONF_STATION, TagTagger.ORIGIN_STATION, canonicalize = false)
        }
        splitValues(item.extras["lang"]).forEach { raw ->
            val name = LANGUAGES[normalize(raw)] ?: return@forEach
            out += Derived(name, TagTagger.CATEGORY_LANGUAGE, CONF_STATION, TagTagger.ORIGIN_STATION)
        }
        yearOf(item.extras["year"])?.let {
            out += Derived(it, TagTagger.CATEGORY_ERA, CONF_STATION, TagTagger.ORIGIN_STATION, canonicalize = false)
        }
        // 主演最多 2 个、导演 1 个：再多就不是标签，是演员表
        splitValues(item.extras["actor"]).filter(::looksLikeName).take(MAX_CAST).forEach {
            out += Derived(it, TagTagger.CATEGORY_CAST, CONF_STATION, TagTagger.ORIGIN_STATION, canonicalize = false)
        }
        splitValues(item.extras["director"]).filter(::looksLikeName).take(1).forEach {
            out += Derived(it, TagTagger.CATEGORY_CAST, CONF_STATION, TagTagger.ORIGIN_STATION, canonicalize = false)
        }
        genresFromTitle(item.title).forEach {
            out += Derived(it, TagTagger.CATEGORY_GENRE, CONF_TEXT, TagTagger.ORIGIN_TEXT)
        }
        return out.toList()
    }

    // ===== 分类名拆标签（v1.8.4.0 起；v1.8.7.0 待办 #28 扩成通用展开）=====

    /** 地区归一（`内地` / `大陆` / `国产` → `国产`）；不是地区返回 null */
    fun regionOf(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return REGIONS[normalize(text)] ?: SHORT_REGIONS[text]
    }

    /** 语言归一（`普通话` → `国语`）；不是语言返回 null */
    fun languageOf(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return LANGUAGES[normalize(text)]
    }

    /**
     * 把一个**影视分类名**拆成多个标签（v1.8.4.0 起；v1.8.7.0 待办 #28 扩成通用展开）。
     *
     * 采集站只给一个分类字符串，而它其实同时表达了好几件事：
     * - `里番动漫` = 动漫（一级类型）+ 里番（属性）；
     * - `港剧` = 电视剧 + 香港；
     * - `日本无码` = 地区 + 属性；`传媒-糖心传媒` = 栏目 + 厂牌；
     * - `自拍偷拍` / `少女萝莉` / `巨乳美乳` = **两个题材词直接粘在一起**。
     *
     * 只存成一个标签时，其余维度整个丢失：用户搜「动漫」搜不到里番，搜「无码」搜不到
     * 「日本无码」，搜「偷窥」搜不到「自拍偷拍」。小说 / 漫画那边一部作品本来就有好几个
     * 标签（kind + 源自带 tags + 文本补充），影视这边靠这一步补齐。
     *
     * 展开顺序（每一步都要求「整段能完整解释」，解释不了就退回上一步，绝不硬拆）：
     * 1. **类型维度**（`动作片` → `电影` + `动作`、`里番动漫` → `动漫` + `里番`；
     *    兜底分类名 `其他片` 只剩一级类型 `电影`）；
     * 2. **噪声整串**（`未知` / `最新` / `其他`…不是标签，直接丢弃）；
     * 3. **整串已是登记别名** → 取规范名（`cosplay` → `角色扮演`、`制服诱惑` → `制服`）；
     * 4. **地区前缀**（`日本无码` → `日本` + `无码`、`国产传媒` → `国产` + `传媒`）；
     * 5. **噪声后缀**（`探花系列` → `探花`、`抖阴视频` → `抖阴`）；
     * 6. **词典分词**（`自拍偷拍` → `自拍` + `偷拍`，要求每个字都被词表吃下）；
     * 7. 兜底：同义归一后的原串。
     *
     * 显式分隔符（`-` `－` `—` `/` `|` `·` `、`）先切段再逐段展开；切段要求两侧都不是
     * 纯数字（`SSNI-888` 这种片号不能被切成 `SSNI` + `888`）。
     *
     * 返回**至少一个**标签，调用方无需判空；认不出的分类名原样返回。
     */
    fun splitCompoundTag(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val parts = text.split('-', '－', '—', '/', '|', '·', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size > 1 && parts.all { isSplittablePart(it) }) {
            return parts.flatMap { expandOne(it) }.distinct()
        }
        return expandOne(text)
    }

    /** 分隔符切段的每一段是否值得单独成标签（纯数字 / 单字不切，`SSNI-888` 保持完整） */
    private fun isSplittablePart(part: String): Boolean =
        part.length >= 2 && !part.all { it.isDigit() }

    /** 单段展开（[splitCompoundTag] 的实际实现，detail 递归时长度严格递减） */
    private fun expandOne(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        // 1) 类型维度：动作片 → [电影, 动作]；其他片 → [电影]
        //    兜底分类名（`其他片` / `其它综艺`）自身不是标签，但**一级类型是** ——
        //    所以必须先认类型、后判噪声，否则整个串会被 isNoise 提前吞掉
        val type = VideoTypeMap.typeOf(text)
        if (type != null) {
            if (VideoTypeMap.isAliasOf(text, type)) return listOf(type)
            val detail = detailOf(text)
            // detail 与原串相同说明这一层解释不了（如「动画」自身），不要递归
            if (detail == null || detail == type || detail == text) return listOf(type)
            return (listOf(type) + expandOne(detail)).distinct()
        }

        // 2) 噪声整串（`未知` / `最新` / `其他`…）：不是标签
        if (isNoise(text)) return emptyList()

        // 3) 整串已是登记别名（cosplay / 制服诱惑 / 三级伦理）→ 取规范名
        if (TagAlias.isKnownAlias(text)) {
            val canonical = TagAlias.canonicalOf(text)
            if (canonical.isNotBlank()) return listOf(canonical)
        }

        // 4) 地区前缀：日本无码 → [日本, 无码]；国产传媒 → [国产, 传媒]
        regionPrefixOf(text)?.let { (region, rest) ->
            if (rest.isEmpty() || isNoise(rest)) return listOf(region)
            return (listOf(region) + expandOne(rest)).distinct()
        }

        // 5) 噪声后缀：探花系列 → 探花；抖阴视频 → 抖阴
        stripNoiseSuffix(text)?.let { return expandOne(it) }

        // 6) 词典分词：自拍偷拍 → [自拍, 偷窥]
        segment(text)?.let { return it }

        // 7) 兜底：同义归一
        return listOf(TagAlias.canonicalOf(text)).filter { it.isNotBlank() }
    }

    /** 从分类名里取出「具体属性」那一半；取不出（或明显是噪声）返回 null */
    private fun detailOf(text: String): String? {
        // 1) 剥类型词后缀：里番动漫 → 里番、国产动漫 → 国产、港台动漫 → 港台
        for (word in TYPE_WORDS) {
            if (text.length <= word.length || !text.endsWith(word)) continue
            val rest = text.dropLast(word.length).trim()
            if (rest.isEmpty()) continue
            return normalizeDetail(rest)
        }
        // 2) 没有类型词后缀：港剧 → 香港（整串就是一个地区别名）、动作片 → 动作片
        return normalizeDetail(text)
    }

    private fun normalizeDetail(raw: String): String? {
        if (isNoise(raw)) return null
        regionOf(raw)?.let { return it }
        // 「国产剧」这种：剥掉「剧」再认地区
        for (suffix in GENERIC_SUFFIXES) {
            if (raw.length <= suffix.length || !raw.endsWith(suffix)) continue
            val base = raw.dropLast(suffix.length).trim()
            if (isNoise(base)) return null
            regionOf(base)?.let { return base }
            // 不是地区：剥掉载体字后剩下的就是题材（「动作片」→「动作」），
            // 交给调用方继续按同义组归一
            return base
        }
        return raw
    }

    /** 地区前缀匹配（只认双字以上的地区名 —— 「美乳」不能被拆成「美」+「乳」） */
    private fun regionPrefixOf(text: String): Pair<String, String>? {
        for ((alias, region) in REGION_PREFIXES) {
            if (text.length <= alias.length || !text.startsWith(alias)) continue
            val rest = text.substring(alias.length)
            return region to rest
        }
        return null
    }

    /** 剥掉结尾的噪声词（`探花系列` → `探花`）；剥完是空 / 噪声时返回 null */
    private fun stripNoiseSuffix(text: String): String? {
        for (suffix in NOISE_SUFFIXES) {
            if (text.length <= suffix.length || !text.endsWith(suffix)) continue
            val rest = text.dropLast(suffix.length).trim()
            if (rest.isEmpty() || isNoise(rest)) return null
            return rest
        }
        return null
    }

    /**
     * 词典分词：要求**每个字**都被词表吃下，且至少切成两段。
     *
     * 全串覆盖才认，是为了不硬拆 —— `VR视角` 里「视角」不在词表，于是整串保留，
     * 不会变成「VR」+「视角」这种半截标签。
     */
    private fun segment(text: String): List<String>? {
        if (text.length < 4) return null
        val parts = ArrayList<String>(3)
        var i = 0
        while (i < text.length) {
            val hit = SEGMENT_WORDS.firstOrNull { w ->
                text.regionMatches(i, w, 0, w.length, ignoreCase = true)
            } ?: return null
            parts += hit
            i += hit.length
        }
        if (parts.size < 2) return null
        return parts.map { TagAlias.canonicalOf(it) }.filter { it.isNotBlank() }.distinct()
    }

    /** 「其他片」「其它综艺」这类兜底分类不是标签，只留一级类型 */
    private fun isNoise(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return true
        if (text in NOISE_WORDS) return true
        return text.startsWith("其他") || text.startsWith("其它")
    }

    private val NOISE_WORDS = setOf(
        "未知", "暂无", "无", "热门", "推荐", "最新", "全部", "更多", "综合",
        // v1.8.7.0（待办 #28）：采集站的「精品 / 精选 / 系列 / 专区」是栏目修饰词，
        // 单独成标签没有信息量（「国产精品」只该留「国产」）
        "精品", "精選", "精选", "系列", "視頻", "视频", "专区", "專區", "频道", "頻道",
        "合集", "大全", "片单", "片單",
    )

    /** 结尾可剥的噪声词（比 [NOISE_WORDS] 更进一步：剥掉后剩下的部分才是标签） */
    private val NOISE_SUFFIXES: List<String> = listOf(
        "视频", "視頻", "系列", "专区", "專區", "频道", "頻道", "精选", "精選",
        "合集", "大全", "片单", "片單", "推荐", "推薦",
    )

    /** 分类名里出现即表示「一级类型」的词（按长度从长到短匹配，避免「电视剧」被「剧」抢先） */
    private val TYPE_WORDS: List<String> = listOf(
        "电视剧", "電視劇", "连续剧", "連續劇", "动画片", "動畫片", "纪录片", "記錄片", "记录片",
        "预告片", "預告片", "大电影", "动漫电影", "動漫電影", "电影", "電影", "动漫", "動漫",
        "动画", "動畫", "番剧", "番劇", "剧集", "劇集", "综艺", "綜藝", "短剧", "短劇",
        "体育", "體育", "资讯", "資訊", "影片",
    )

    /** 「国产剧」这种「地区 + 载体」写法里可剥的载体字 */
    private val GENERIC_SUFFIXES: List<String> = listOf("剧", "劇", "片")

    /**
     * 影视栏目里的**题材 / 属性词**（v1.8.7.0 待办 #28）。
     *
     * 这些词在 `vod_type_name` 里经常两两粘在一起（`自拍偷拍`、`少女萝莉`、`巨乳美乳`），
     * 单靠同义组认不出来，所以单列一张分词词表。已在 [TagAlias.GROUPS] 里的词不重复列。
     */
    private val VIDEO_GENRES: List<String> = listOf(
        "自拍", "偷拍", "素人", "搭讪", "探花", "网红", "主播", "福利姬", "重口",
        "解说", "传媒", "激情", "少女", "强奸", "乱伦", "美乳", "三级", "诱惑",
        "换脸", "网曝", "独播", "枪战", "文艺", "选秀", "演唱会", "微电影", "av",
    )

    /** 分词时跳过的一级类型 / 元信息词（它们由 [VideoTypeMap] 归位，不参与题材分词） */
    private val SEGMENT_SKIP: Set<String> = setOf(
        "电影", "电视剧", "连续剧", "动漫", "动画", "综艺", "纪录片", "体育", "资讯", "预告片",
        "短剧", "剧集", "番剧", "卡通", "漫画", "小说", "视频", "精品", "精选", "系列", "专区",
        "频道", "合集", "大全", "其他", "其它", "全部", "热门", "推荐", "最新", "更多", "综合",
    )

    /** 分词词表：同义组全部写法 + [VIDEO_GENRES]，按长度倒序（贪心最长匹配） */
    private val SEGMENT_WORDS: List<String> = buildList<String> {
        val seen = HashSet<String>()
        fun register(word: String, allowShortAscii: Boolean = false) {
            val w = word.trim()
            if (w.length < 2) return
            val ascii = w.all { it.code < 128 }
            // 英文两字符（gl / bl / sf）在自由分词里误命中率太高；但 `AV解说` 这类
            // 栏目名里的 `av` 是明确列进来的，按词表放行
            if (ascii && w.length < if (allowShortAscii) 2 else 3) return
            val key = w.lowercase()
            if (key in SEGMENT_SKIP) return
            if (seen.add(key)) add(w)
        }
        TagAlias.GROUPS.forEach { group -> group.forEach { register(it) } }
        VIDEO_GENRES.forEach { register(it, allowShortAscii = true) }
    }.sortedByDescending { it.length }

    /** 地区前缀表（只取双字以上的写法，按长度倒序，避免「中国」被「中」抢先） */
    private val REGION_PREFIXES: List<Pair<String, String>> = REGIONS.entries
        .filter { it.key.length >= 2 }
        .sortedByDescending { it.key.length }
        .map { it.key to it.value }

    /** 标题里认题材（按出现顺序，最多 [MAX_TITLE_GENRES] 个） */
    fun genresFromTitle(title: String): List<String> {
        if (title.isBlank()) return emptyList()
        return TITLE_GENRES
            .mapNotNull { word -> title.indexOf(word).takeIf { it >= 0 }?.let { it to word } }
            .sortedBy { it.first }
            .map { it.second }
            .take(MAX_TITLE_GENRES)
    }

    /** `vod_year` → 4 位年份标签；非法 / 越界返回 null */
    fun yearOf(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.length != 4 || text.any { !it.isDigit() }) return null
        val year = text.toIntOrNull() ?: return null
        return text.takeIf { year in 1900..2100 }
    }

    /**
     * 人名过滤：采集站的 `vod_actor` 常写成「张三,李四」，
     * 但也会出现「未知」「佚名」「暂无」这类占位词，不能当标签。
     */
    private fun looksLikeName(raw: String): Boolean {
        val name = raw.trim()
        if (name.length !in 2..20) return false
        if (name.all { it.isDigit() }) return false
        return name !in PLACEHOLDER_NAMES
    }

    private val PLACEHOLDER_NAMES = setOf(
        "未知", "未知演员", "佚名", "暂无", "无", "其他", "其它", "群星", "内详", "详",
    )

    /** 多值字段拆分（`,` `，` `、` `/` `|` 与空白），去空、去重 */
    private fun splitValues(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return text.split(Regex("[,，、/|\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun normalize(raw: String): String = raw.trim().lowercase()
}
