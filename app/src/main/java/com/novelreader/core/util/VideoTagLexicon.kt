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

    // ===== 分类名拆标签（v1.8.4.0：让影视和小说 / 漫画一样，一部作品带多个标签）=====

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
     * 把一个**影视分类名**拆成「一级类型 + 具体属性」（v1.8.4.0）。
     *
     * 采集站只给一个分类字符串，而它其实同时表达了两件事：
     * `里番动漫` = 动漫（一级类型）+ 里番（具体属性），`港剧` = 电视剧 + 香港。
     * 只存成一个标签时，「动漫」这个维度就整个丢了 —— 用户搜「动漫」搜不到里番，
     * 搜「里番」也搜不到只标了「里番动漫」的源。小说 / 漫画那边一部作品本来就有
     * 好几个标签（kind + 源自带 tags + 文本补充），影视这边靠这一步补齐。
     *
     * 返回**至少一个**标签，调用方无需判空；认不出的分类名原样返回。
     */
    fun splitCompoundTag(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val type = VideoTypeMap.typeOf(text) ?: return listOf(text)
        // 名字就是类型本身（电影 / 电影片 / 动漫频道）→ 只留类型这一个标签，
        // 顺带把「电影片」这类源内别名统一成规范名
        if (VideoTypeMap.isAliasOf(text, type)) return listOf(type)
        val detail = detailOf(text) ?: return listOf(type)
        if (detail == type) return listOf(type)
        return listOf(type, detail)
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
            val base = raw.dropLast(suffix.length)
            if (isNoise(base)) return null
            regionOf(base)?.let { return it }
        }
        return raw
    }

    /** 「其他片」「其它综艺」这类兜底分类不是标签，只留一级类型 */
    private fun isNoise(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return true
        if (text in NOISE_WORDS) return true
        return text.startsWith("其他") || text.startsWith("其它")
    }

    private val NOISE_WORDS = setOf("未知", "暂无", "无", "热门", "推荐", "最新", "全部", "更多", "综合")

    /** 分类名里出现即表示「一级类型」的词（按长度从长到短匹配，避免「电视剧」被「剧」抢先） */
    private val TYPE_WORDS: List<String> = listOf(
        "电视剧", "電視劇", "连续剧", "連續劇", "动画片", "動畫片", "纪录片", "記錄片", "记录片",
        "预告片", "預告片", "大电影", "动漫电影", "動漫電影", "电影", "電影", "动漫", "動漫",
        "动画", "動畫", "番剧", "番劇", "剧集", "劇集", "综艺", "綜藝", "短剧", "短劇",
        "体育", "體育", "资讯", "資訊", "影片",
    )

    /** 「国产剧」这种「地区 + 载体」写法里可剥的载体字 */
    private val GENERIC_SUFFIXES: List<String> = listOf("剧", "劇", "片")

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
