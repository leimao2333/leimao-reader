package com.novelreader.core.util

/**
 * 标签的**同义 / 近义**归组（v1.7.2.0，v1.8.1.0 重做）。
 *
 * 痛点：同一个意思在不同源上有十几种写法 —— 中文、繁体、英文、日文、韩文，
 * 以及各站自己的叫法（「全彩」/「彩色」/「全彩本」/`Full Colored`/`フルカラー`）。
 * 用户拿其中一个词去搜，只能打到恰好也写这个词的那几个源，其余源整个不参与。
 *
 * 这里用「**等价组**」表达：同一组里的名字视为同一个搜索条件，搜其中任何一个
 * 就等于搜全组。分组口径是**相同或相近含义**，不是「经常一起出现」——
 * 反义或无关的词（天使/恶魔、姐姐/妹妹、性转/变身）不并组，否则会搜出明显无关的内容。
 *
 * 匹配口径：去空白 + 小写（与 [TagQuery.normalizeTitle] 一致），所以
 * 「Sci-Fi」「sci fi」「SCI FI」互相等价。
 */
object TagAlias {

    /**
     * 等价组：每组内互为同义 / 近义写法。
     *
     * 新增词条时按「同一含义」分组即可，顺序无所谓；组内**第一个词**是该组的规范名
     * （展示时会用它替换组内其他写法，所以首词要选最常用的中文词）。
     */
    internal val GROUPS: List<Set<String>> = listOf(
        // ===== 题材 =====
        setOf("校园", "学院", "学园", "学校", "school", "campus", "academy", "学園", "학교"),
        setOf("科幻", "科学幻想", "scifi", "sci-fi", "science fiction", "sf", "サイエンスフィクション", "공상과학"),
        setOf("奇幻", "魔幻", "fantasy", "ファンタジー", "판타지"),
        setOf("魔法", "magical", "witch", "wizard", "sorcery", "マジック", "마법"),
        setOf("热血", "熱血", "hot blooded", "hot-blooded", "열혈"),
        setOf("动作", "動作", "action", "battle action", "バトルアクション", "액션"),
        setOf("格斗", "格鬥", "fighting", "combat", "격투"),
        setOf("少年", "shounen", "shonen", "少年向"),
        setOf("冒险", "adventure", "quest", "冒険", "모험"),
        setOf("恋爱", "爱情", "言情", "纯爱", "romance", "love", "romantic", "恋愛", "연애", "ラブ"),
        setOf("后宫", "harem", "ハーレム", "하렘"),
        setOf("逆后宫", "乙女", "reverse harem", "otome", "逆ハーレム", "역하렘"),
        setOf("悬疑", "推理", "悬念", "mystery", "suspense", "ミステリー", "미스터리"),
        setOf("恐怖", "惊悚", "灵异", "horror", "thriller", "ghost", "ホラー", "공포"),
        setOf("治愈", "疗愈", "healing", "iyashikei", "heartwarming", "癒し", "ヒーリング", "힐링"),
        setOf("日常", "生活", "日常系", "slice of life", "daily life", "sol", "スライスオブライフ", "일상"),
        setOf("搞笑", "喜剧", "幽默", "comedy", "funny", "gag", "コメディ", "코미디"),
        setOf("战斗", "battle", "fight", "バトル", "배틀"),
        setOf("异世界", "穿越", "异界", "isekai", "another world", "transmigration", "異世界", "이세계"),
        setOf("转生", "重生", "reincarnation", "rebirth", "reborn", "転生", "환생"),
        setOf("运动", "体育", "竞技", "sports", "athletic", "スポーツ", "스포츠"),
        setOf("音乐", "音楽", "music", "음악"),
        setOf("偶像", "idol", "アイドル", "아이돌"),
        setOf("料理", "美食", "烹饪", "cooking", "food", "gourmet", "culinary", "グルメ", "요리"),
        setOf("职场", "上班族", "office", "workplace", "office lady", "ol", "オフィス", "직장"),
        setOf("历史", "historical", "history", "period", "歴史", "역사"),
        setOf("军事", "战争", "military", "war", "warfare", "戦争", "전쟁"),
        setOf("机甲", "机器人", "mecha", "robot", "mech", "ロボット", "로봇"),
        setOf("末日", "末世", "废土", "apocalypse", "post-apocalyptic", "survival", "終末", "アポカリプス", "아포칼립스"),
        setOf("吸血鬼", "vampire", "ヴァンパイア", "バンパイア", "뱀파이어"),
        setOf("丧尸", "僵尸", "zombie", "undead", "ゾンビ", "좀비"),
        setOf("妖怪", "鬼怪", "妖魔", "youkai", "yokai", "ようかい", "요괴"),
        setOf("天使", "angel", "天使もの", "천사"),
        setOf("恶魔", "惡魔", "devil", "demon", "悪魔", "악마"),
        setOf("龙", "dragon", "竜", "ドラゴン", "드래곤"),
        setOf("精灵", "elf", "妖精", "fairy", "エルフ", "フェアリー", "요정"),
        setOf("剑", "剑与魔法", "sword", "blade", "剣", "ソード", "검"),
        setOf("忍者", "ninja", "武士", "samurai", "ニンジャ", "닌자", "侍", "사무라이"),
        setOf("侦探", "detective", "案件", "探偵", "탐정"),
        setOf("海盗", "pirate", "海贼", "海賊", "해적"),
        setOf("变身", "變身", "transformation", "henshin", "変身", "변신"),
        setOf("性转", "性轉", "性别转换", "gender bender", "genderswap", "性転換", "성전환"),
        setOf("老师", "教师", "teacher", "sensei"),
        setOf("护士", "nurse", "ナース", "간호사"),
        setOf("人妻", "已婚", "主妇", "主婦", "milf", "housewife", "유부녀"),
        setOf("亲子", "育儿", "parenting", "family", "家族", "가족"),
        setOf("职业", "工作", "career", "profession"),
        // ===== 性向 / 读者群 =====
        setOf("百合", "少女爱", "yuri", "gl", "girls love", "ゆり", "백합"),
        setOf("耽美", "男男", "bl", "boys love", "yaoi", "shounen ai", "boyslove", "ボーイズラブ", "야오이"),
        setOf("少年漫画", "shounen manga", "shonen manga", "소년만화"),
        setOf("少女漫画", "shoujo", "shojo", "소녀만화"),
        setOf("青年漫画", "seinen", "청년만화"),
        setOf("儿童", "子供", "kodomo", "kids", "children", "어린이"),
        // ===== 小说题材（中文站为主）=====
        setOf("玄幻", "东方玄幻", "xuanhuan", "东方幻想"),
        setOf("武侠", "武打", "wuxia", "martial arts", "무협"),
        setOf("仙侠", "修仙", "修真", "xianxia", "cultivation", "선협"),
        setOf("都市", "现代都市", "城市", "urban", "city", "modern", "도시"),
        setOf("游戏", "网游", "电竞", "game", "gaming", "mmo", "virtual reality", "vr", "ゲーム", "게임"),
        setOf("无限流", "无限", "infinite", "infinite flow", "インフィニット", "인피니트"),
        setOf("系统", "系统流", "system", "システム", "시스템"),
        setOf("快穿", "快穿文", "quick transmigration", "quick transmigrate"),
        setOf("虐恋", "虐文", "虐心", "tragic love", "angst"),
        setOf("古言", "古代言情", "ancient romance"),
        setOf("现言", "现代言情", "modern romance"),
        setOf("同人", "二次创作", "doujin", "fanfiction", "fan fiction"),
        setOf("衍生", "fanwork"),
        setOf("无cp", "无cp文", "no romance", "no cp"),
        setOf("轻小说", "light novel", "ln", "ライトノベル", "라이트노벨"),
        setOf("签到", "签到流", "sign in", "check in"),
        setOf("直播", "直播间", "live stream", "livestream"),
        setOf("种田", "種田", "种田文", "farming", "slow life"),
        setOf("无敌", "无敌流", "龙傲天", "龍傲天", "爽文", "overpowered", "op protagonist"),
        setOf("扮猪吃虎", "扮豬吃虎", "隐藏实力", "hiding strength"),
        setOf("豪门", "豪門", "豪门世家", "rich family", "chaebol"),
        setOf("总裁", "總裁", "霸道总裁", "ceo", "president"),
        setOf("契约婚姻", "契約婚姻", "先婚后爱", "先婚後愛", "contract marriage"),
        setOf("甜宠", "甜寵", "甜文", "宠文", "寵文", "sweet romance", "fluff"),
        setOf("宫斗", "宮鬥", "palace intrigue"),
        setOf("宅斗", "宅鬥", "family intrigue"),
        setOf("神医", "神醫", "医仙", "醫仙", "divine doctor"),
        setOf("学霸", "學霸", "学神", "學神", "top student"),
        setOf("灵气复苏", "靈氣復甦", "qi revival"),
        setOf("诸天", "諸天", "诸天万界", "all worlds"),
        setOf("综漫", "綜漫", "综漫文", "crossover"),
        setOf("赘婿", "贅婿", "son in law"),
        // ===== 形态 / 出版 =====
        // 「全彩」这一组是各站叫法分歧最典型的：全彩 / 彩色 / 彩色本 / 彩色全本 /
        // 全彩本 / 彩漫 / 上色版 / Full Colored / フルカラー …说的都是同一件事。
        setOf(
            "全彩", "全彩色", "全彩版", "全彩本", "全彩全本", "全彩漫画", "全彩漫畫",
            "彩色", "彩色版", "彩色本", "彩色全本", "彩色漫画", "彩色漫畫",
            "彩版", "彩漫", "彩绘", "彩繪", "彩页", "彩頁", "上色", "上色版",
            "彩图", "彩圖", "全彩图",
            "full color", "fullcolor", "full-color", "full colored", "official colored",
            "official color", "fan colored", "fan color", "color", "colour", "colored", "coloured",
            "フルカラー", "フルカラー版", "カラー", "カラー版", "풀컬러", "컬러",
        ),
        setOf("黑白", "单色", "白黑", "黑白版", "monochrome", "mono", "b/w", "bw", "black and white", "モノクロ", "白黒", "흑백"),
        setOf("四格", "4-koma", "four panel", "4コマ", "4컷"),
        setOf("短篇", "短篇集", "短篇漫画", "一话完结", "一話完結", "短编", "oneshot", "one shot", "short", "読み切り", "短編", "단편"),
        setOf("长篇", "長編", "长篇连载", "long", "serialization", "장편"),
        setOf("连载", "连载中", "未完", "ongoing", "serial", "serialized", "updating", "連載", "연재"),
        setOf("完结", "已完结", "全本", "完本", "completed", "finished", "end", "ended", "complete", "完結", "완결"),
        setOf("单行本", "單行本", "单册", "單冊", "合订本", "合訂本", "tankoubon", "volume", "collected volume", "単行本", "단행본"),
        setOf("总集篇", "總集篇", "合集", "omnibus", "anthology", "総集編"),
        setOf("番外", "番外篇", "外传", "外傳", "spin off", "spin-off", "side story", "番外編", "외전"),
        setOf("休载", "休載", "hiatus", "휴재"),
        setOf("新作", "新书", "新番", "new", "latest", "newest", "신작"),
        setOf("排行", "排行榜", "榜单", "ranking", "rank", "top", "ランキング", "랭킹"),
        setOf("热门", "人气", "hot", "popular", "popularity", "人気", "인기"),
        setOf("推荐", "精选", "recommend", "recommended", "featured", "pickup", "おすすめ", "추천"),
        setOf("收藏", "favorite", "favourite", "bookmark", "お気に入り", "즐겨찾기"),
        // ===== 语言 / 地区 =====
        setOf("英语", "英文", "eng", "english", "英語", "english scanlation", "영어"),
        setOf("日语", "日文", "japanese", "jp", "日本語", "일본어"),
        setOf("中文", "汉化", "翻译", "chinese", "cn", "zh", "汉化组", "translated", "中国語", "중국어"),
        setOf("韩漫", "韩国", "韩文", "korean", "manhwa", "kr", "韓国", "한국"),
        setOf("国产", "国漫", "chinese comic", "domestic"),
        setOf("欧美", "western", "west", "american"),
        // ===== 载体 =====
        setOf("漫画", "comic", "manga", "comics", "卡通", "cartoon", "만화"),
        setOf("动画", "动漫", "anime", "animation", "アニメ", "애니"),
        setOf("小说", "novel", "web novel", "小説", "소설"),
        setOf("剧集", "drama", "ドラマ", "드라마"),
        // ===== 体型 / 属性 =====
        setOf(
            "巨乳", "大胸", "爆乳", "豪乳", "豐乳", "丰乳", "丰满", "豐滿", "巨乳娘",
            "big breasts", "big boobs", "large breasts", "huge breasts", "big bust",
            "oppai", "おっぱい", "巨乳もの", "거유",
        ),
        setOf(
            "贫乳", "平胸", "小胸", "微乳", "扁胸", "幼乳",
            "small breasts", "flat chest", "petite breasts", "ぺったん", "貧乳", "빈유",
        ),
        setOf("御姐", "熟女", "大姐姐", "成熟女性", "mature woman", "mature female", "older woman", "oneesan", "お姉さん", "성숙녀"),
        setOf("萝莉", "로리", "ロリ", "loli", "lolita"),
        setOf("正太", "쇼타", "ショタ", "shota"),
        setOf("扶她", "扶他", "双性", "futanari", "futa", "ふたなり", "hermaphrodite"),
        setOf("肌肉", "肌肉男", "muscular", "muscle", "マッチョ", "근육"),
        setOf("触手", "tentacle", "tentacles", "触手責め", "촉수"),
        setOf("催眠", "hypnosis", "hypnotism", "催眠術", "최면"),
        setOf("寝取", "寢取", "ntr", "netorare", "cuckold", "牛头人", "寝取られ", "네토라레"),
        setOf("调教", "調教", "bdsm", "sm", "bondage", "調教もの", "조교"),
        setOf("兽耳", "獸耳", "猫耳", "貓耳", "animal ears", "kemonomimi", "cat ears", "獣耳", "네코미미", "켐노미미"),
        setOf("眼镜", "眼鏡", "眼镜娘", "眼鏡娘", "glasses", "megane", "メガネ", "안경"),
        setOf("丝袜", "絲襪", "黑丝", "黑絲", "白丝", "白絲", "网袜", "網襪", "连裤袜", "連褲襪",
            "过膝袜", "過膝襪", "绝对领域", "pantyhose", "thighhighs", "thigh highs", "stocking",
            "stockings", "knee socks", "タイツ", "ニーソ", "스타킹"),
        // ===== 发色 / 发型 =====
        setOf("长发", "長髮", "long hair", "ロングヘア", "긴머리"),
        setOf("短发", "短髮", "short hair", "ショートヘア", "단발"),
        setOf("双马尾", "雙馬尾", "twintails", "twin tails", "ツインテール", "트윈테일"),
        setOf("马尾", "馬尾", "ponytail", "ポニーテール", "포니테일"),
        setOf("银发", "銀髮", "白发", "白髮", "silver hair", "white hair", "銀髪", "白髪", "은발", "백발"),
        setOf("金发", "金髮", "blonde", "blond", "golden hair", "金髪", "금발"),
        setOf("黑发", "黑髮", "black hair", "黒髪", "흑발"),
        // ===== 服装 / 穿着 =====
        setOf("制服", "校服", "学生服", "school uniform", "seifuku", "制服姿", "교복"),
        setOf("和服", "浴衣", "着物", "和装", "kimono", "yukata", "기모노"),
        setOf("泳装", "泳裝", "泳衣", "水着", "swimsuit", "beach", "bikini", "比基尼", "ビキニ", "수영복", "비키니"),
        setOf("婚纱", "婚纱礼服", "wedding dress", "wedding", "ウェディングドレス", "웨딩드레스"),
        setOf("内衣", "內衣", "bra", "panties", "lingerie", "下着", "속옷"),
        setOf("女仆装", "メイド服", "maid outfit", "maid uniform"),
        // ===== 身份 / 职业 =====
        setOf("女高中生", "女子高中生", "jk", "女子高生", "high school girl", "여고생"),
        setOf("大学生", "女大学生", "college student", "university student", "대학생"),
        setOf("巫女", "miko", "shrine maiden", "みこ"),
        setOf("修女", "nun", "シスター", "수녀"),
        setOf("空姐", "flight attendant", "ca", "스튜어디스"),
        setOf("秘书", "秘書", "secretary", "비서"),
        // ===== 关系 =====
        setOf("姐姐", "姊姊", "elder sister", "姉", "누나", "언니"),
        setOf("妹妹", "妹", "imouto", "妹さん", "여동생"),
        setOf("姐妹", "姊妹", "sister", "sisters", "姉妹", "자매"),
        setOf("兄弟", "brother", "brothers", "兄弟姉妹", "형제"),
        setOf("兄妹", "姐弟", "sibling", "siblings", "남매"),
        setOf("青梅竹马", "青梅竹馬", "幼驯染", "幼馴染", "childhood friend", "childhood friends", "소꿉친구"),
        setOf("夫妻", "夫妇", "夫婦", "married couple", "husband and wife", "부부"),
        setOf("恋人", "情侣", "情侶", "lovers", "couple", "カップル", "커플"),
        setOf("师生", "师生恋", "teacher and student", "teacher student"),
        // ===== 分级 =====
        setOf("成人", "限制级", "18禁", "r18", "hentai", "adult", "18+", "ero", "ecchi"),
        setOf("全年龄", "一般向", "all ages", "general", "safe", "全年齢"),
        // ===== 「有无修饰」的写法变体（v1.7.6.0）=====
        // 各站对同一件事的叫法不统一，而它们**分布在不同源上**：只按字面匹配时，
        // 选其中一个标签只会打到收录它的那几个源，其余源明明有同类内容却整个不参与。
        // 这一组把它们打通，让「无码」也能命中只标了「無修正」的源。
        setOf(
            "无码", "無碼", "无码版", "無碼版", "无修正", "無修正", "未修正", "无修饰", "無修飾",
            "无马赛克", "無馬賽克", "无遮", "無遮", "步兵", "無修正版本", "修正なし",
            "uncensored", "uncensor", "uncensored version", "decensored",
            "no mosaic", "no-mosaic", "nomosaic", "no censor", "no-censor", "무수정", "노모자이크",
        ),
        setOf("有码", "有碼", "有修正", "有马赛克", "有馬賽克", "修正", "骑兵", "騎兵", "打码", "打碼",
            "censored", "mosaic", "モザイク", "모자이크"),
        setOf("无删减", "無刪減", "未删减", "未刪減", "完整版", "uncut", "unrated", "无修版", "完全版", "無修正完全版"),
        // ===== 画质 / 字幕 =====
        setOf("高清", "高画质", "清晰", "高清版", "hd", "high definition", "high quality", "hq", "高画質"),
        setOf("超清", "超高清", "原画", "原画质", "uhd", "4k", "ultra hd", "超高画質"),
        setOf("中字", "中文字幕", "中文配音", "chinese subtitle", "chs", "gb"),
        setOf("生肉", "无字幕", "no subtitle", "raw"),
        // ===== 画风 / 表现形式 =====
        setOf("萌系", "萌", "moe", "萌え", "모에"),
        setOf("写实", "寫實", "realistic", "リアル", "리얼"),
        setOf("q版", "q版人物", "chibi", "ちび", "sd"),
        setOf("线稿", "線稿", "lineart", "line art", "下書き"),
        setOf("草图", "草稿", "sketch", "ラフ", "스케치"),
        // ===== 情境（成人向标签的常见叫法）=====
        setOf("群交", "多人", "3p", "threesome", "group sex", "乱交", "亂交", "輪姦", "윤간"),
        setOf("露出", "野外", "野外露出", "exhibitionism", "public sex", "露出プレイ"),
        setOf("偷窥", "偷窺", "窥视", "窺視", "voyeur", "peeping", "のぞき"),
        setOf("痴汉", "痴漢", "chikan", "molestation", "치한"),
        setOf("怀孕", "懷孕", "妊娠", "pregnant", "pregnancy", "임신"),
        setOf("母乳", "乳汁", "lactation", "breast milk", "母乳プレイ"),
        setOf("睡眠", "睡着", "sleeping", "somno", "睡眠play"),
        setOf("时间停止", "時間停止", "time stop", "time-stop"),
        setOf("精神控制", "洗脑", "洗腦", "mind control", "brainwash"),
        setOf("监禁", "監禁", "囚禁", "confinement", "imprisonment"),
        setOf("卖春", "賣春", "援交", "风俗", "風俗", "prostitution", "solicitation"),
        setOf("产卵", "産卵", "egg laying", "oviposition"),
        // ===== 独占 / 来源 =====
        setOf("独家", "独占", "exclusive"),
        setOf("cosplay", "角色扮演", "コスプレ"),
        // ===== 常见题材 / 角色（中英日韩，v1.7.9.0）=====
        setOf("魔王", "마왕", "마왕님", "demon king", "demon lord", "archdemon"),
        setOf("勇者", "용사", "hero", "brave", "勇者様"),
        setOf("魔法少女", "마법소녀", "magical girl", "mahou shoujo"),
        setOf("恶役千金", "악역영애", "悪役令嬢", "villainess", "villainous daughter"),
        setOf("圣女", "성녀", "聖女", "saint"),
        setOf("女仆", "메이드", "メイド", "maid"),
        setOf("骑士", "기사", "騎士", "knight"),
        setOf("法师", "마법사", "魔法使い", "mage", "wizardess"),
        setOf("弓箭手", "궁수", "弓使い", "archer"),
        setOf("盗贼", "도적", "盗賊", "thief", "rogue"),
        setOf("牧师", "사제", "僧侶", "priest", "cleric"),
        setOf("召唤师", "소환사", "召喚士", "summoner"),
        setOf("炼金术", "연금술", "錬金術", "alchemy", "alchemist"),
        setOf("兽人", "오크", "オーク", "orc", "beastman"),
        setOf("哥布林", "고블린", "ゴブリン", "goblin"),
        setOf("狼人", "늑대인간", "狼男", "werewolf", "lycan"),
        setOf("矮人", "드워프", "ドワーフ", "dwarf"),
        setOf("魔族", "마족", "마인", "魔人族", "demon race"),
        setOf("大小姐", "아가씨", "お嬢様", "ojousama", "young lady"),
        setOf("公主", "공주", "姫", "princess"),
        setOf("王子", "왕자", "王子様", "prince"),
        setOf("女帝", "여제", "empress"),
        setOf("皇后", "황후", "empress consort"),
        setOf("王妃", "왕비", "queen consort"),
        setOf("贵族", "귀족", "貴族", "noble", "aristocrat"),
        setOf("初恋", "첫사랑", "first love"),
        setOf("暗恋", "짝사랑", "secret crush", "unrequited love"),
        setOf("超能力", "초능력", "esper", "psychic", "superpower"),
        setOf("副本", "던전", "ダンジョン", "dungeon", "instance"),
        setOf("娱乐圈", "연예계", "芸能界", "entertainment industry", "showbiz"),
        setOf("克苏鲁", "크툴루", "クトゥルフ", "cthulhu", "lovecraftian"),
        setOf("宫廷", "궁정", "宮廷", "royal court", "court intrigue"),
        setOf("篮球", "농구", "バスケ", "basketball"),
        setOf("足球", "축구", "サッカー", "soccer", "football"),
        setOf("网球", "테니스", "テニス", "tennis"),
    )

    /**
     * 标签匹配归一化：百分号编码按一次 UTF-8 解码，再去空白并转小写。
     * 编码形式与原标签因此属于同一个匹配值，但入口仍保留各自原始名称。
     */
    private fun norm(raw: String): String =
        decodePercentEncoded(raw).filterNot { it.isWhitespace() }.lowercase()

    private fun decodePercentEncoded(value: String): String {
        if (!Regex("%[0-9a-fA-F]{2}").containsMatchIn(value)) return value
        return runCatching {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(value)
    }

    /** 归一化 key → 所在等价组 */
    private val groupOf: Map<String, Set<String>> = buildMap {
        for (group in GROUPS) {
            for (name in group) put(norm(name), group)
        }
    }

    /**
     * **不做展示归一的组**（组首词）。
     *
     * 这些组里语义相关但各有独立叫法，把「姐姐」显示成「妹妹」、把「变身」显示成「性转」
     * 都会误导读者。它们仍留在等价组里 —— 搜索时互相包含，只是标签名各自保留原样。
     */
    private val KEEP_ORIGINAL_GROUPS: Set<String> = setOf(
        "热血", "天使", "姐姐", "妹妹", "妖怪", "吸血鬼", "剑", "忍者", "变身",
        "格斗", "恶魔", "性转", "少年", "职业",
    )

    /** 归一化 key → 该组的**规范名**（组内第一个词） */
    private val canonicalKey: Map<String, String> = buildMap {
        for (group in GROUPS) {
            val first = group.firstOrNull() ?: continue
            if (first in KEEP_ORIGINAL_GROUPS) continue
            for (name in group) put(norm(name), first)
        }
    }

    /**
     * 取标签的**规范名**（同义组内第一个词，如 妖精 / elf / エルフ → 精灵）。
     *
     * 不在任何等价组里、或所在组属于 [KEEP_ORIGINAL_GROUPS] 时返回原词
     * （去首尾空白）—— 调用方无需分支，自定义分类（「本周推荐」这类）不会被改写。
     */
    fun canonicalOf(tag: String): String {
        val raw = tag.trim()
        if (raw.isEmpty()) return raw
        return canonicalKey[norm(raw)] ?: raw
    }

    /** 用户主动选择成人分级 / 修饰状态标签时，视为本次搜索明确请求成人源。 */
    fun isExplicitAdultIntent(tag: String): Boolean =
        canonicalOf(tag) in EXPLICIT_ADULT_CANONICALS

    private val EXPLICIT_ADULT_CANONICALS = setOf("成人", "无码", "有码")

    /**
     * 该标签是否是**已登记的译名**（会在展示时被改名）。
     * 供「池子合并」判断要不要改写标签名用：不在组里的词原样保留。
     */
    fun isKnownAlias(tag: String): Boolean = canonicalKey.containsKey(norm(tag.trim()))

    /**
     * 取一个标签的**全部同义写法**（含自身，已归一化、去重）。
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

    /** 返回该标签组所有可用于源站关键词搜索的原始写法，优先保留用户输入。 */
    fun searchTermsFor(tag: String): List<String> {
        val raw = tag.trim()
        if (raw.isEmpty()) return emptyList()
        val group = GROUPS.firstOrNull { aliases -> aliases.any { norm(it) == norm(raw) } }
            ?: return listOf(raw)
        return (listOf(raw) + group)
            .distinctBy(::norm)
    }

    /**
     * 把互为同义的一组标签**合并成一个**（v1.7.6.0）。
     *
     * 复合搜索是按「同时满足所有标签」求交的，所以「无码」与「無修正」若同时出现在
     * 输入里，会被当成**两个不同条件**去求交 —— 而它们各自分布在不同的源上，
     * 交集必然被切得很窄甚至为空。这和「点了两个标签却什么都搜不到」是一个道理。
     *
     * 这里按同义集合是否相交来判定重复，保留最先出现的那个名字，
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
            // 与已保留标签共享任一写法 → 视为同一个条件，跳过
            if (keys.any { it in seenKeys }) continue
            seenKeys.addAll(keys)
            out.add(tag)
        }
        return out
    }

    /**
     * 在标签池里找出与 [tag] 等价的所有标签（v1.7.2.0）。
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
