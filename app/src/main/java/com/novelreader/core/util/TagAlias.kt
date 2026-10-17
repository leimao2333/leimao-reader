package com.novelreader.core.util

/**
 * 标签的**中英互译**（v1.7.2.0）。
 *
 * 痛点：漫画 / 图库的标签很多是英文或罗马字（`Yuri` / `BL` / `Sci-Fi` / `Isekai` /
 * `Slice of Life` / `ENG` …），而用户是拿中文搜的 —— 输「百合」搜不到 `Yuri`，
 * 输「英语」搜不到「英語 ENG」，标签池里明明有内容却像没有一样。
 * 反过来也一样：输 `yuri` 也该能搜到中文站的「百合」。
 *
 * 这里用「**等价组**」表达：同一组里的名字互为译名，搜其中任何一个就等于搜全组。
 * 不含任何成人向内容的语义判断，纯粹是译名表；成人内容沉底由 [SourceAdults] 负责。
 *
 * 匹配口径：去空白 + 小写（与 [TagQuery.normalizeTitle] 一致），所以
 * 「Sci-Fi」「sci fi」「SCI FI」互相等价。
 */
object TagAlias {

    /**
     * 等价组：每组内互为译名。
     *
     * 新增词条时按「同一含义」分组即可，顺序无所谓；组内第一个词仅用于日志可读性。
     */
    internal val GROUPS: List<Set<String>> = listOf(
        // ===== 题材 =====
        setOf("校园", "学院", "学园", "学校", "school", "campus", "academy"),
        setOf("科幻", "科学幻想", "scifi", "sci-fi", "science fiction", "sf"),
        setOf("奇幻", "魔幻", "fantasy", "magic"),
        setOf("魔法", "magical", "witch", "wizard", "sorcery"),
        setOf("热血", "少年", "动作", "格斗", "action", "battle action"),
        setOf("冒险", "adventure", "quest"),
        setOf("恋爱", "爱情", "言情", "纯爱", "romance", "love", "romantic"),
        setOf("后宫", "harem"),
        setOf("逆后宫", "乙女", "reverse harem", "otome"),
        setOf("悬疑", "推理", "悬念", "mystery", "suspense"),
        setOf("恐怖", "惊悚", "灵异", "horror", "thriller", "ghost"),
        setOf("治愈", "疗愈", "healing", "iyashikei", "heartwarming"),
        setOf("日常", "生活", "日常系", "slice of life", "daily life", "sol"),
        setOf("搞笑", "喜剧", "幽默", "comedy", "funny", "gag"),
        setOf("战斗", "battle", "combat", "fight"),
        setOf("异世界", "穿越", "异界", "isekai", "another world", "transmigration"),
        setOf("转生", "重生", "reincarnation", "rebirth", "reborn"),
        setOf("运动", "体育", "竞技", "sports", "athletic"),
        setOf("音乐", "唱歌", "乐队", "music", "band", "idol"),
        setOf("料理", "美食", "烹饪", "cooking", "food", "gourmet", "culinary"),
        setOf("职场", "上班族", "office", "workplace", "office lady", "ol"),
        setOf("历史", "historical", "history", "period"),
        setOf("军事", "战争", "military", "war", "warfare"),
        setOf("机甲", "机器人", "mecha", "robot", "mech"),
        setOf("末日", "末世", "废土", "apocalypse", "post-apocalyptic", "survival"),
        setOf("吸血鬼", "vampire", "blood"),
        setOf("丧尸", "僵尸", "zombie", "undead"),
        setOf("妖怪", "鬼怪", "神魔", "妖魔", "youkai", "yokai", "demon", "supernatural"),
        setOf("天使", "angel", "恶魔", "devil"),
        setOf("龙", "dragon"),
        setOf("精灵", "elf", "妖精", "fairy"),
        setOf("剑", "剑与魔法", "sword", "blade"),
        setOf("忍者", "ninja", "武士", "samurai"),
        setOf("侦探", "detective", "案件"),
        setOf("海盗", "pirate", "海贼"),
        setOf("泳装", "swimsuit", "beach"),
        setOf("变身", "性转", "性别转换", "gender bender", "transformation", "genderswap"),
        setOf("老师", "教师", "teacher", "sensei"),
        setOf("护士", "nurse"),
        setOf("人妻", "已婚", "milf", "housewife"),
        setOf("姐姐", "妹妹", "兄妹", "姐弟", "sister", "brother", "sibling", "imouto"),
        setOf("亲子", "育儿", "parenting", "family"),
        // ===== 性向 / 读者群 =====
        setOf("百合", "少女爱", "yuri", "gl", "girls love"),
        setOf("耽美", "男男", "bl", "boys love", "yaoi", "shounen ai", "boyslove"),
        setOf("少年漫画", "shounen", "shonen"),
        setOf("少女漫画", "shoujo", "shojo"),
        setOf("青年漫画", "seinen"),
        setOf("儿童", "子供", "kodomo", "kids", "children"),
        // ===== 小说题材（中文站为主）=====
        setOf("玄幻", "东方玄幻", "xuanhuan", "东方幻想"),
        setOf("武侠", "武打", "wuxia", "martial arts"),
        setOf("仙侠", "修仙", "修真", "xianxia", "cultivation"),
        setOf("都市", "现代都市", "城市", "urban", "city", "modern"),
        setOf("游戏", "网游", "电竞", "game", "gaming", "mmo", "virtual reality", "vr"),
        setOf("无限流", "无限", "infinite", "infinite flow"),
        setOf("系统", "系统流", "system"),
        setOf("快穿", "快穿文", "quick transmigration", "quick transmigrate"),
        setOf("虐恋", "虐文", "tragic love", "angst"),
        setOf("古言", "古代言情", "ancient romance"),
        setOf("现言", "现代言情", "modern romance"),
        setOf("同人", "二次创作", "doujin", "fanfiction", "fan fiction"),
        setOf("衍生", "fanwork"),
        setOf("无cp", "无cp文", "no romance", "no cp"),
        setOf("轻小说", "light novel", "ln"),
        // ===== 形态 / 状态 =====
        setOf("全彩", "彩色", "full color", "color", "colored"),
        setOf("黑白", "单色", "monochrome", "b/w", "black and white"),
        setOf("四格", "4-koma", "four panel"),
        setOf("短篇", "单话", "oneshot", "one shot", "short"),
        setOf("长篇", "long", "serialization"),
        setOf("连载", "连载中", "ongoing", "serial", "serialized", "updating"),
        setOf("完结", "已完结", "completed", "finished", "end", "ended", "complete"),
        setOf("新作", "新书", "新番", "new", "latest", "newest"),
        setOf("排行", "排行榜", "榜单", "ranking", "rank", "top"),
        setOf("热门", "人气", "hot", "popular", "popularity"),
        setOf("推荐", "精选", "recommend", "recommended", "featured", "pickup"),
        setOf("收藏", "favorite", "favourite", "bookmark"),
        // ===== 语言 / 地区 =====
        setOf("英语", "英文", "eng", "english", "英語", "english scanlation"),
        setOf("日语", "日文", "japanese", "jp", "日本語"),
        setOf("中文", "汉化", "翻译", "chinese", "cn", "zh", "汉化组", "translated"),
        setOf("韩漫", "韩国", "韩文", "korean", "manhwa", "kr"),
        setOf("国产", "国漫", "chinese comic", "domestic"),
        setOf("欧美", "western", "west", "american"),
        // ===== 载体 =====
        setOf("漫画", "comic", "manga", "comics", "卡通", "cartoon"),
        setOf("动画", "动漫", "anime", "animation"),
        setOf("小说", "novel", "web novel"),
        setOf("剧集", "drama"),
        // ===== 分级 =====
        setOf("成人", "限制级", "18禁", "r18", "hentai", "adult", "18+", "ero", "ecchi"),
        setOf("全年龄", "一般向", "all ages", "general", "safe"),
        // ===== 「有无修饰」的写法变体（v1.7.6.0）=====
        // 各站对同一件事的叫法不统一，而它们**分布在不同源上**：只按字面匹配时，
        // 选其中一个标签只会打到收录它的那几个源，其余源明明有同类内容却整个不参与。
        // 这一组把它们打通，让「无码」也能命中只标了「無修正」的源。
        setOf("无码", "無碼", "无修正", "無修正", "uncensored", "no mosaic", "nomosaic"),
        setOf("有码", "有碼", "修正", "censored", "mosaic"),
        // ===== 画质 / 字幕（各站写法差异）=====
        setOf("高清", "高画质", "hd", "high definition", "high quality", "hq"),
        setOf("超清", "超高清", "uhd", "4k", "ultra hd"),
        setOf("中字", "中文字幕", "中文配音", "chinese subtitle", "chs", "gb"),
        setOf("生肉", "无字幕", "no subtitle", "raw"),
        // ===== 独占 / 来源 =====
        setOf("独家", "独占", "exclusive"),
        setOf("cosplay", "角色扮演", "コスプレ"),
    )

    /** 归一化：去空白 + 小写（与 [TagQuery.normalizeTitle] 同口径） */
    private fun norm(raw: String): String = raw.filterNot { it.isWhitespace() }.lowercase()

    /** 归一化 key → 所在等价组 */
    private val groupOf: Map<String, Set<String>> = buildMap {
        for (group in GROUPS) {
            for (name in group) put(norm(name), group)
        }
    }

    /**
     * 取一个标签的**全部译名**（含自身，已归一化、去重）。
     *
     * 不在任何等价组里时返回「只有自己」的单元素集合 —— 调用方无需分支。
     */
    fun aliasesOf(tag: String): Set<String> {
        val key = norm(tag)
        val group = groupOf[key]
        if (group == null) return setOf(key)
        val out = LinkedHashSet<String>()
        out.add(key)
        group.forEach { out.add(norm(it)) }
        return out
    }

    /**
     * 把互为译名的一类标签**合并成一个**（v1.7.6.0）。
     *
     * 复合搜索是按「同时满足所有标签」求交的，所以「无码」与「無修正」若同时出现在
     * 输入里，会被当成**两个不同条件**去求交 —— 而它们各自分布在不同的源上，
     * 交集必然被切得很窄甚至为空。这和「点了两个标签却什么都搜不到」是一个道理。
     *
     * 这里按译名集合是否相交来判定重复，保留最先出现的那个名字，
     * 保持输入顺序（其余逻辑依赖「第一个标签决定源的排列顺序」）。
     *
     * @return 去重后的标签；无别名关系时原样返回同一批实例
     */
    fun dedupeAliases(tags: List<String>): List<String> {
        if (tags.size < 2) return tags
        val out = ArrayList<String>(tags.size)
        val seenKeys = HashSet<String>()
        for (tag in tags) {
            val keys = aliasesOf(tag)
            // 与已保留标签共享任一译名 → 视为同一个条件，跳过
            if (keys.any { it in seenKeys }) continue
            seenKeys.addAll(keys)
            out.add(tag)
        }
        return out
    }

    /**
     * 在标签池里找出与 [tag] 等价的**所有标签**（v1.7.2.0）。
     *
     * 命中口径刻意放宽两级：
     * 1. 池里标签**整体**归一化后一致（「Yuri」↔「yuri」）；
     * 2. 池里标签按空白拆开后**某一段**一致（「英語 ENG」拆出 `eng`，于是输「英语」
     *    也能命中它）—— Venera / 图站的复合标签（「英語 ENG」「Love Live」）很常见，
     *    只比对整体会漏掉它们。
     *
     * @param pool 标签池（原样标签名）
     * @return 池里与 [tag] 等价的标签，顺序跟随 [pool]；自身命中时排在最前
     */
    fun matchesInPool(tag: String, pool: Collection<String>): List<String> {
        val wanted = aliasesOf(tag)
        if (wanted.isEmpty()) return emptyList()
        val self = norm(tag)
        val exact = LinkedHashSet<String>()
        val segment = LinkedHashSet<String>()
        for (candidate in pool) {
            val c = norm(candidate)
            if (c.isEmpty()) continue
            if (c in wanted) {
                if (c == self) exact.add(candidate) else segment.add(candidate)
                continue
            }
            // 复合标签：拆段后任一段命中
            val parts = candidate.split(Regex("\\s+"))
            if (parts.size > 1 && parts.any { norm(it) in wanted }) {
                segment.add(candidate)
            }
        }
        return (exact + segment).toList()
    }
}
