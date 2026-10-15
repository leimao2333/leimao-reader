package com.novelreader.core.util

/**
 * 影视「一级类型」归位表（v1.6.7.0）。
 *
 * **为什么需要这张表**：聚合几十个采集源时，各源的分类命名五花八门 ——
 * 同一种内容在不同源里可能叫「电影 / 电影片 / 4K电影 / 动作片」，
 * 而「动作片」在有些源是**一级**（该源分类表是平的）、在另一些源是「电影」下的**二级**。
 * 直接拿「父级为空」当一级类型，就会把 180 多个名字（含套图社、成人栏目、站内公告）
 * 全塞进「类型」下拉 —— 用户看到的就是一锅粥。
 *
 * 这里把各源叫法**归位**到 [ORDER] 里的 9 个规范类型，顺序即下拉与首页分区的顺序。
 * 归不进去的名字（成人栏目 / 套图社 / 站内公告这类不属于主流影视分类的）**不展示**，
 * 与「成人源内容沉底」的既有策略一致。
 *
 * 两类词条的区别：
 * - [Spec.aliases]：语义**等同该类型本身**的叫法，用来挑「这个类型」在各源的源内地址，
 *   不作为二级分类展示（否则「电影」的分类里会冒出「电影片 / 4K电影」）；
 * - [Spec.genres]：常见**二级分类**，各源常把它当一级返回，这里归位到该类型。
 */
object VideoTypeMap {

    /** 规范一级类型，**顺序即展示顺序**（下拉、首页分区行都用它） */
    val ORDER: List<String> = listOf(
        "电影", "电视剧", "综艺", "动漫", "短剧", "纪录片", "体育", "资讯", "预告片",
    )

    private data class Spec(
        val name: String,
        val aliases: List<String>,
        val genres: List<String>,
    )

    private val SPECS: List<Spec> = listOf(
        Spec(
            "电影",
            aliases = listOf(
                "电影", "电影片", "电影频道", "4K电影", "高清电影", "网络电影",
                "Netflix电影", "邵氏电影",
            ),
            genres = listOf(
                "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片", "战争片",
                "灾难片", "悬疑片", "犯罪片", "奇幻片", "惊悚片", "家庭篇", "古装片",
                "历史片", "伦理片", "动画片", "动画电影", "动漫电影", "冒险片",
                "武侠片", "枪战片", "文艺片", "其他片", "其它片",
            ),
        ),
        Spec(
            "电视剧",
            aliases = listOf(
                "电视剧", "连续剧", "电视连续剧", "国产连续剧", "Netflix自制剧",
                "欧美电视剧", "电视剧场",
            ),
            genres = listOf(
                "国产剧", "大陆剧", "港剧", "香港剧", "台剧", "台湾剧", "港澳剧",
                "港台剧", "美剧", "美国剧", "欧美剧", "韩剧", "韩国剧", "日剧",
                "日本剧", "日韩剧", "海外剧", "其他剧", "其它剧", "泰剧",
                "新加坡剧", "古装剧", "现代剧", "都市剧",
            ),
        ),
        Spec(
            "综艺",
            aliases = listOf("综艺", "综艺片", "综艺频道"),
            genres = listOf(
                "大陆综艺", "港台综艺", "日韩综艺", "欧美综艺", "其他综艺",
                "其它综艺", "演唱会", "真人秀", "脱口秀", "选秀",
            ),
        ),
        Spec(
            "动漫",
            aliases = listOf("动漫", "动漫片", "动漫频道"),
            genres = listOf(
                "国产动漫", "中国动漫", "日本动漫", "日韩动漫", "欧美动漫",
                "港台动漫", "海外动漫", "里番动漫", "有声动漫", "动画", "国漫",
                "日漫", "番剧", "国创",
            ),
        ),
        Spec(
            "短剧",
            aliases = listOf("短剧", "微短剧", "短剧大全", "短剧频道"),
            genres = listOf(
                "爽文短剧", "ai短剧", "AI漫剧", "漫剧", "擦边短剧", "反诈短剧",
                "古装仙侠", "现代都市", "穿越年代", "年代穿越", "言情总裁",
                "重生民国", "反转爽剧", "脑洞悬疑", "女频恋爱", "都市情感",
                "豪门恩怨", "神豪", "萌宝",
            ),
        ),
        Spec(
            "纪录片",
            aliases = listOf("纪录片", "记录片", "记录片儿"),
            genres = listOf("科普学习", "历史记录", "自然纪录", "人文历史", "探索"),
        ),
        Spec(
            "体育",
            aliases = listOf("体育", "体育赛事"),
            genres = listOf(
                "篮球", "足球", "斯诺克", "网球", "电竞", "综合体育", "排球",
                "乒乓球", "赛车", "搏击",
            ),
        ),
        Spec(
            "资讯",
            aliases = listOf("资讯", "新闻资讯", "站内新闻", "头条", "公告"),
            genres = listOf(
                "娱乐动态", "八卦爆料", "电影资讯", "影视资讯", "影片资讯",
                "预告资讯", "明星资讯", "演员", "明星",
            ),
        ),
        Spec(
            "预告片",
            aliases = listOf("预告片", "预告"),
            genres = listOf("预告解说", "影视解说", "电影解说", "解说", "花絮"),
        ),
    )

    /** 别名（= 类型本身）→ 规范类型 */
    private val ALIAS_TO_TYPE: Map<String, String> = buildMap {
        for (spec in SPECS) for (a in spec.aliases) put(norm(a), spec.name)
    }

    /** 二级分类 → 规范类型 */
    private val GENRE_TO_TYPE: Map<String, String> = buildMap {
        for (spec in SPECS) for (g in spec.genres) put(norm(g), spec.name)
    }

    /** 每个规范类型下、按出现顺序排列的别名（规范名排第一，挑地址时优先用） */
    private val ALIASES_BY_TYPE: Map<String, Set<String>> =
        SPECS.associate { it.name to it.aliases.map(::norm).toSet() }

    /**
     * 二级分类的**同义名**，统到一个规范名下。
     *
     * 各源对同一种内容叫法不同（`韩剧` / `韩国剧`、`港剧` / `香港剧`），
     * 归位后如果都留着，下拉里就会出现两组并排的近似项。归一之后：
     * 列表只显示规范名，查询时仍按**各源自己的**地址走
     * （[VideoCategoryPlan] 的地址表按规范名合并，逐源各用各的）。
     */
    private val GENRE_SYNONYMS: Map<String, String> = mapOf(
        "韩剧" to "韩国剧",
        "日剧" to "日本剧",
        "港剧" to "香港剧",
        "台剧" to "台湾剧",
        "泰剧" to "泰国剧",
        "美剧" to "美国剧",
        "大陆剧" to "国产剧",
        "内地剧" to "国产剧",
        "国内剧" to "国产剧",
    )

    /** 去掉空白后比较，避免「 电影 」「电影　」之类的脏名字漏掉 */
    private fun norm(raw: String): String =
        raw.trim().replace(" ", "").replace("\u3000", "")

    /**
     * 名字 → 规范类型；不认识返回 `null`（调用方可用父级分类再猜一层）。
     */
    fun typeOf(name: String): String? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return ALIAS_TO_TYPE[n] ?: GENRE_TO_TYPE[n]
    }

    /** 该名字是否是 [type] 的**别名**（即语义等同该类型本身，不该出现在二级分类里） */
    fun isAliasOf(name: String, type: String): Boolean =
        ALIAS_TO_TYPE[norm(name)] == type

    /** [type] 的全部别名（已归一化） */
    fun aliasesOf(type: String): Set<String> = ALIASES_BY_TYPE[type].orEmpty()

    /** 二级分类同义归一（`港剧` → `香港剧`）；没有同义词时原样返回 */
    fun canonicalGenre(name: String): String {
        val trimmed = name.trim()
        return GENRE_SYNONYMS[norm(trimmed)] ?: trimmed
    }
}
