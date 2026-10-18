package com.novelreader.core.util

/**
 * 同人作品标签与可检索原著目录。
 *
 * 只在标题、简介或来源标签明确表示同人/衍生创作时添加「同人」和「原著」，
 * 避免把普通作品里偶然提到某个 IP 误标成同人。
 */
object FanworkTags {
    private data class Work(val name: String, val aliases: List<String> = emptyList())

    private val works = listOf(
        Work("原神", listOf("Genshin Impact", "Genshin")),
        Work("崩坏：星穹铁道", listOf("崩铁", "星穹铁道", "Honkai: Star Rail", "Honkai Star Rail")),
        Work("崩坏3", listOf("崩坏三", "Honkai Impact 3rd")),
        Work("明日方舟", listOf("Arknights")),
        Work("绝区零", listOf("Zenless Zone Zero", "ZZZ")),
        Work("鸣潮", listOf("Wuthering Waves")),
        Work("阴阳师"),
        Work("第五人格", listOf("Identity V")),
        Work("王者荣耀"),
        Work("英雄联盟", listOf("League of Legends", "LOL")),
        Work("Fate"),
        Work("东方Project", listOf("东方Project", "Touhou Project", "東方Project")),
        Work("舰队Collection", listOf("舰娘", "Kantai Collection", "艦これ")),
        Work("碧蓝航线", listOf("Azur Lane")),
        Work("Love Live!", listOf("LoveLive", "Love Live")),
        Work("偶像大师", listOf("THE IDOLM@STER", "Idolmaster")),
        Work("初音未来", listOf("Hatsune Miku", "VOCALOID", "Vocaloid")),
        Work("刀剑神域", listOf("Sword Art Online", "SAO")),
        Work("鬼灭之刃", listOf("Demon Slayer", "Kimetsu no Yaiba")),
        Work("咒术回战", listOf("Jujutsu Kaisen")),
        Work("海贼王", listOf("ONE PIECE", "One Piece")),
        Work("火影忍者", listOf("Naruto")),
        Work("名侦探柯南", listOf("Detective Conan", "Case Closed")),
        Work("进击的巨人", listOf("Attack on Titan", "Shingeki no Kyojin")),
        Work("排球少年", listOf("Haikyuu", "Haikyuu!!")),
        Work("蓝色监狱", listOf("Blue Lock")),
        Work("文豪野犬", listOf("Bungo Stray Dogs")),
        Work("银魂", listOf("Gintama")),
        Work("宝可梦", listOf("Pokemon", "Pokémon", "精灵宝可梦")),
        Work("龙珠", listOf("Dragon Ball")),
        Work("JOJO的奇妙冒险", listOf("JoJo's Bizarre Adventure", "JoJo")),
        Work("全职猎人", listOf("Hunter x Hunter", "HUNTER×HUNTER")),
        Work("魔法少女小圆", listOf("Puella Magi Madoka Magica", "Madoka Magica")),
        Work("夏目友人帐", listOf("Natsume's Book of Friends")),
        Work("犬夜叉"),
        Work("黑子的篮球", listOf("Kuroko's Basketball")),
        Work("Free!"),
        Work("我的英雄学院", listOf("My Hero Academia", "Boku no Hero Academia")),
        Work("间谍过家家", listOf("Spy x Family")),
        Work("东京复仇者", listOf("Tokyo Revengers")),
        Work("文豪与炼金术师", listOf("文豪とアルケミスト")),
        Work("全职高手"),
        Work("盗墓笔记"),
        Work("魔道祖师"),
        Work("天官赐福"),
    )

    /** 作品名对应的规范标签及检索词，供小说和漫画标签池共同使用。 */
    val searchTags: List<Pair<String, String>> = buildList {
        add("同人" to "同人")
        add("原著" to "原著")
        works.forEach { work ->
            add("原著·${work.name}" to work.name)
        }
    }.distinctBy { it.first }

    private val fanworkMarkers = listOf(
        Regex("同人(?:文|作品|创作|漫画|小说|志)|同人(?=$|[^\\p{IsHan}A-Za-z])|\\p{IsHan}同人(?=$|[^\\p{IsHan}])|二次创作|二创|衍生作品|衍生文|原作向"),
        Regex("fan\\s*fiction|fanfiction|fan\\s*work|fanwork|doujin", RegexOption.IGNORE_CASE),
    )

    private val workMatchers = works.map { work ->
        val aliases = (listOf(work.name) + work.aliases).distinct()
        val matcher = aliases.map { alias ->
            val escaped = Regex.escape(alias)
            if (alias.any { it.code < 128 }) {
                Regex("(?<![A-Za-z0-9])$escaped(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            } else {
                Regex(escaped, RegexOption.IGNORE_CASE)
            }
        }
        work.name to matcher
    }

    /**
     * 从可见文本及源提供的标签中提取同人/原著标签。
     * 有同人标记但无法识别作品名时，仍保留通用「原著」标签，不猜测具体作品。
     */
    fun derive(text: String, sourceTags: Collection<String> = emptyList()): List<String> {
        val metadata = sourceTags.joinToString(" ")
        val combined = "$metadata $text"
        if (fanworkMarkers.none { it.containsMatchIn(combined) } &&
            sourceTags.none { TagAlias.canonicalOf(it.trim()) in setOf("同人", "衍生") }
        ) {
            return emptyList()
        }

        val out = linkedSetOf("同人", "原著")
        workMatchers.forEach { (name, matchers) ->
            if (out.count { it.startsWith("原著·") } >= MAX_RECOGNIZED_WORKS) return@forEach
            if (matchers.any { it.containsMatchIn(combined) }) out += "原著·$name"
        }
        return out.toList()
    }

    private const val MAX_RECOGNIZED_WORKS = 8
}
