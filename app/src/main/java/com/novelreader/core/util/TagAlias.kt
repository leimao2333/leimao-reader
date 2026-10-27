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
            // v1.8.1.9：AI 上色 / 机上色 / 乱涂机上色 等各站叫法（繁简、异体都收）
            "ai上色", "ai彩绘", "ai彩繪", "ai彩色", "ai上色版", "ai彩漫", "ai着色",
            "全彩乱涂机上色", "全彩亂凃機上色", "全彩亂塗機上色", "全彩机上色", "全彩機上色",
            "机上色", "機上色", "上色彩", "上色本", "上色漫", "全彩上色", "彩色上色", "着色",
            "ai color", "ai colored", "ai colour", "auto color", "auto colored", "auto colour",
            "full color", "fullcolor", "full-color", "full colored", "official colored",
            "official color", "fan colored", "fan color", "color", "colour", "colored", "coloured",
            "フルカラー", "フルカラー版", "カラー", "カラー版", "풀컬러", "컬러",
        ),
        setOf("黑白", "单色", "白黑", "黑白版", "monochrome", "mono", "b/w", "bw", "black and white", "モノクロ", "白黒", "흑백",
            "灰度", "灰阶", "grayscale", "greyscale", "グレースケール", "그레이스케일"),
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
        // v1.8.1.9：按 E-Hentai / 各图站常见分类补的载体与出版形态
        setOf("电子版", "電子版", "扫描版", "掃描版", "scan", "scanned", "scanlation",
            "web版", "webtoon", "网络版", "網路版", "在线版", "線上版", "web漫画", "web漫畫",
            "デジタル版", "스캔본"),
        setOf("同人志", "同人誌", "doujinshi", "doujin book", "동인지"),
        setOf("画师cg", "畫師cg", "artistcg", "artist cg", "artist cg set"),
        setOf("游戏cg", "遊戲cg", "gamecg", "game cg"),
        setOf("图集", "圖集", "imageset", "image set", "image collection", "画像集", "이미지셋"),
        setOf("画集", "畫集", "artbook", "art book", "art collection", "화집"),
        setOf("设定集", "設定集", "setting material", "setting materials", "設定資料集", "설정집"),
        setOf("杂志", "雜誌", "magazine", "雑誌", "잡지"),
        setOf("无对白", "無對白", "无台词", "無台詞", "speechless", "no dialogue", "wordless", "セリフなし", "대사없음"),
        // ===== 语言 / 地区 =====
        setOf("英语", "英文", "eng", "english", "英語", "english scanlation", "영어"),
        setOf("日语", "日文", "japanese", "jp", "日本語", "일본어"),
        setOf("中文", "汉化", "翻译", "chinese", "cn", "zh", "汉化组", "translated", "中国語", "중국어"),
        setOf("韩漫", "韩国", "韩文", "korean", "manhwa", "kr", "韓国", "한국"),
        setOf("国产", "国漫", "chinese comic", "domestic"),
        setOf("欧美", "western", "west", "american"),
        // v1.8.1.9：机翻 / AI 翻译 等译制方式（与「中文 / 汉化」的语种含义区分开）
        setOf("机翻", "機翻", "ai翻译", "ai翻譯", "ai汉化", "ai漢化", "机翻汉化", "機翻漢化",
            "gpt翻译", "gpt翻譯", "chatgpt翻译", "自动翻译", "机器翻译", "機翻版", "机翻版",
            "machine translation", "mtl", "auto translate", "auto translation"),
        // ===== 载体 =====
        setOf("漫画", "comic", "manga", "comics", "卡通", "cartoon", "만화"),
        setOf("动画", "动漫", "anime", "animation", "アニメ", "애니", "动画片", "動畫片"),
        setOf("小说", "novel", "web novel", "小説", "소설"),
        setOf("剧集", "drama", "ドラマ", "드라마"),
        // ===== 体型 / 属性 =====
        setOf(
            "巨乳", "大胸", "爆乳", "豪乳", "豐乳", "丰乳", "丰满", "豐滿", "巨乳娘",
            "big breasts", "big boobs", "large breasts", "huge breasts", "big bust",
            "oppai", "おっぱい", "巨乳もの", "거유",
            // v1.8.7.0（待办 #28）：「巨乳美乳」拆出来的是「美乳」，同一维度
            "美乳", "美乳娘",
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
        setOf("制服", "校服", "学生服", "school uniform", "seifuku", "制服姿", "교복",
            // v1.8.7.0（待办 #28）：「制服诱惑」是同一栏目的常见写法
            "制服诱惑", "制服誘惑"),
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
        // v1.8.1.9：常见场景 / 身份（各站标签体系里高频出现）
        setOf("社团", "社團", "部活", "club", "school club", "サークル", "동아리"),
        setOf("咖啡馆", "咖啡厅", "咖啡館", "cafe", "café", "coffee shop", "喫茶店", "카페"),
        setOf("便利店", "便利商店", "convenience store", "コンビニ", "편의점"),
        setOf("医院", "醫院", "hospital", "clinic", "病院", "병원"),
        setOf("公寓", "apartment", "mansion", "マンション", "아파트"),
        setOf("邻居", "鄰居", "neighbor", "neighbour", "next door", "隣人", "이웃"),
        setOf("同学", "同學", "classmate", "schoolmate", "クラスメート", "반친구"),
        setOf("前辈", "先辈", "先輩", "senpai", "senior", "선배"),
        setOf("后辈", "後輩", "kouhai", "kohai", "junior", "후배"),
        setOf("上司", "boss", "supervisor", "superior", "상사"),
        setOf("口罩", "mask", "face mask", "マスク", "마스크"),
        setOf("帽子", "hat", "cap", "모자"),
        setOf("围巾", "圍巾", "scarf", "muffler", "マフラー", "목도리"),
        // ===== 分级 =====
        setOf("成人", "限制级", "18禁", "r18", "hentai", "adult", "18+", "ero", "ecchi",
            // v1.8.7.0（待办 #28）：采集站把同一栏写成「情色 / 色情 / 成人片」，
            // 归到「成人」才能整类过滤（`成人:成人` 与 `成人:情色` 命中同一批）
            "情色", "色情", "情色片", "色情片", "成人片", "av", "黄片", "黃片"),
        // v1.8.4.0：采集站把成人向动画写成「里番动漫 / 里番」，拆标签时会拆成
        // 「动漫」+「里番」两个（见 VideoTagLexicon.splitCompoundTag）；
        // 各种写法先归到「里番」，搜「裏番」「hentai anime」也能命中同一批。
        //
        // v1.8.8.1：按用户反馈补一批站方常用写法（成人动漫 / 禁漫 / 18+动画 / 工口动漫 …）。
        // 这类标签在源站没有真分类页，只能按写法搜；写法越全，覆盖到的源越多。
        setOf("里番", "裏番", "里番动漫", "里番動畫", "里番动画", "里番劇", "里番剧",
            "成人动漫", "成人動漫", "成人动画", "成人動畫", "成人漫画", "成人漫畫",
            "禁漫", "18+动画", "18+動畫", "18+动漫", "18+動漫",
            "工口动漫", "工口動漫", "工口动画", "工口動畫", "工口",
            "エロアニメ", "hentai anime"),
        setOf("全年龄", "一般向", "all ages", "general", "safe", "全年齢"),
        // ===== 「有无修饰」的写法变体（v1.7.6.0）=====
        // 各站对同一件事的叫法不统一，而它们**分布在不同源上**：只按字面匹配时，
        // 选其中一个标签只会打到收录它的那几个源，其余源明明有同类内容却整个不参与。
        // 这一组把它们打通，让「无码」也能命中只标了「無修正」的源。
        //
        // v1.8.7.1：**所有「没有打码」的写法统一收进这一组**，按语系分段列出。
        // 此前「无修版 / 無修正完全版」被错放在「无删减」组里 —— 那是「没剪片」
        // （uncut）的意思，与「没打码」不是一回事，放在一起会让搜「无删减」的人
        // 搜到一堆只是没马赛克的片子。
        setOf(
            // 规范名与简繁主写法
            "无码", "無碼", "无码版", "無碼版", "无码破解", "無碼破解",
            // 「修正」系（日文源最常用）
            "无修正", "無修正", "未修正", "无修饰", "無修飾", "無修正版本", "無修正完全版",
            "修正なし", "无修版",
            // 「马赛克 / 格仔」系（港台源常用）
            "无马赛克", "無馬賽克", "无遮", "無遮", "步兵", "无格仔", "無格仔", "去格仔",
            // 「去码」系（v1.8.4.1）：AI 去码普及后源的写法多了这一批
            "去码", "去碼", "去码版", "去碼版", "去修正", "去马赛克", "去馬賽克",
            "去除马赛克", "去除馬賽克",
            // AI 去码变体
            "ai去码", "ai去碼", "ai去马赛克", "ai去馬賽克", "ai无码", "ai無碼",
            "ai解码", "ai解碼", "解码版", "解碼版",
            // 英文 / 韩文
            "uncensored", "uncensor", "uncensored version", "decensored", "de-censored",
            "ai uncensored", "ai decensored", "ai uncensor",
            "no mosaic", "no-mosaic", "nomosaic", "no censor", "no-censor", "무수정", "노모자이크",
        ),
        setOf("有码", "有碼", "有修正", "有马赛克", "有馬賽克", "修正", "骑兵", "騎兵", "打码", "打碼",
            "censored", "mosaic", "モザイク", "모자이크"),
        // 「没剪片」而不是「没打码」：uncut / 完整版，与上一组是两个维度
        setOf("无删减", "無刪減", "未删减", "未刪減", "完整版", "完全版", "uncut", "unrated"),
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
        // v1.8.1.9：画风 / 制作方式补充
        setOf("厚涂", "厚塗", "thick paint", "thick painting", "厚塗り"),
        setOf("赛璐璐", "賽璐璐", "cel shading", "cell shading", "cel-shading", "セル画"),
        setOf("水彩", "watercolor", "watercolour", "水彩画", "수채화"),
        setOf("像素", "pixel art", "pixelart", "pixel", "ドット絵", "픽셀"),
        setOf("3dcg", "3d cg", "3d-cg", "3d render", "3dモデル"),
        setOf("ai生成", "ai作画", "ai绘画", "ai繪畫", "ai绘", "ai繪", "ai art", "ai-art", "ai artwork",
            "ai generated", "ai-generated", "stable diffusion", "novelai", "midjourney",
            "ai插图", "ai插畫", "aiイラスト", "ai그림"),
        // ===== 情境（成人向标签的常见叫法）=====
        setOf("群交", "多人", "3p", "threesome", "group sex", "乱交", "亂交", "輪姦", "윤간"),
        setOf("露出", "野外", "野外露出", "exhibitionism", "public sex", "露出プレイ"),
        setOf("偷窥", "偷窺", "窥视", "窺視", "voyeur", "peeping", "のぞき",
            // v1.8.7.0（待办 #28）：「自拍偷拍」拆出来的是「偷拍」，与「偷窥」同一类内容
            "偷拍", "偷拍视频", "偷拍自拍"),
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
        // ===== 影视类型补充（v1.8.4.0，待办 #11）=====
        // 采集站的 `vod_type_name` 会用到这些短词（「剧情片」「犯罪片」），
        // 而漫画词表里没有对应的组，于是它们只能各自成键、互相搜不到。
        // 规范名统一取组内首词的中文短词，「X片 / X劇」由 [genreBaseOf] 剥后缀归进来。
        setOf("剧情", "劇情", "剧情片", "劇情片", "正剧", "drama film"),
        setOf("犯罪", "犯罪片", "crime", "警匪", "警匪片", "刑侦", "刑偵", "缉毒", "緝毒", "黑帮", "黑幫"),
        setOf("家庭", "家庭片", "家庭剧", "家庭劇", "亲情", "親情", "family film"),
        setOf("灾难", "災難", "灾难片", "災難片", "disaster"),
        setOf("传记", "傳記", "传记片", "傳記片", "biography", "biopic"),
        setOf("纪录片", "記錄片", "记录片", "紀錄片", "documentary", "docu"),
        setOf("古装", "古裝", "古装剧", "古裝劇", "costume drama"),
        // ===== 影视地区（v1.8.4.0，待办 #12）=====
        // 地区标签由 [VideoTagLexicon] 从 `vod_area` 直接产出（不走归一，避免被别的组改写），
        // 这里建组是为了**搜索侧**能互通：搜「港剧」也要搜到只标了「香港」的片子。
        // 「韩国」刻意不建组 —— 它在漫画词表里属于「韩漫」，影视这边按原样独立成标签。
        setOf("香港", "港片", "港剧", "港劇", "香港片", "hongkong", "hong kong"),
        setOf("台湾", "臺灣", "台片", "台剧", "台劇", "taiwan"),
        setOf("美国", "美國", "美剧", "美劇", "美影", "好莱坞", "好萊塢", "hollywood"),
        setOf("日本", "日剧", "日劇", "日影", "japan"),
        setOf("泰国", "泰國", "泰剧", "泰劇", "thai"),
        setOf("印度", "宝莱坞", "寶萊塢", "bollywood", "india"),
        setOf("英国", "英國", "英剧", "英劇", "british"),
        setOf("法国", "法國", "法剧", "法劇", "french"),
        setOf("日韩", "日韓", "日韩剧", "日韩片"),
        setOf("港台", "港臺", "港台剧", "港台片"),
        // ===== 影视题材同义词（v1.8.7.0，待办 #28）=====
        // 采集站 `vod_type_name` 里同一件事有多种叫法，不并组就会各自成键：
        // 搜「伦理」搜不到只标了「理论片」的源，搜「综艺」搜不到只标「综艺节目」的。
        // 规范名统一取最常用的中文短词，「X片 / X劇」后缀由 [genreBaseOf] 剥掉再查。
        setOf(
            "伦理", "倫理", "伦理片", "倫理片", "伦理剧", "倫理劇",
            "理论片", "理論片", "理论", "理論", "三级伦理", "三級倫理",
            "三级片", "三級片", "三级", "三級", "三级电影", "erotic", "softcore",
        ),
        setOf("综艺", "綜藝", "综艺节目", "綜藝節目", "variety", "variety show"),
        setOf("真人秀", "实境秀", "實境秀", "reality show", "reality tv"),
        setOf("脱口秀", "脫口秀", "talk show"),
        setOf("歌舞", "歌舞片", "musical"),
        setOf("青春", "青春片", "youth"),
        setOf("同性", "同性恋", "同性戀", "同志", "lgbt", "queer"),
        // ===== 影视一级类型（v1.8.7.6）=====
        // 源站给同一个一级类型起的名千奇百怪：「网络电影」「4K电影」「邵氏电影」
        // 在用户眼里就是「电影」，但以前各归各 —— 搜「电影」只能打到恰好也叫
        // 「电影」的那几个源，其余源整片不参与，覆盖面被白白砍掉一大截。
        // 注意：组内不要写已在别组用过的词（体育 / 动画 / 动漫 / 纪录片 / 剧集
        // 都各自有组），groupOf 是「后写覆盖先写」，重复成员会把原来的组打散。
        setOf(
            "电影", "電影", "电影片", "電影片", "高清电影", "高清電影", "网络电影",
            "網絡電影", "4K电影", "4k电影", "4K電影", "Netflix电影", "邵氏电影",
            "大片", "movie", "film",
        ),
        setOf(
            "电视剧", "電視劇", "连续剧", "連續劇", "电视连续剧", "電視連續劇",
            "欧美电视剧", "歐美電視劇", "电视剧场", "電視劇場", "国产连续剧",
        ),
        setOf("短剧", "短劇", "微剧", "微劇", "微短剧", "微短劇"),
        setOf("预告片", "預告片", "预告", "預告", "trailer"),
        setOf("资讯", "資訊", "news"),
        // 成人向采集站的常见栏目名（「素人搭讪」「探花系列」「福利姬」…）
        setOf("素人", "素人娘", "amateur"),
        setOf("探花", "探花系列", "探花视频"),
        setOf("网红", "網紅", "influencer", "internet celebrity"),
        setOf("解说", "解說", "解说视频", "commentary"),
        setOf("传媒", "傳媒", "传媒公司"),
        setOf("重口", "重口味", "hardcore"),
        setOf("强奸", "強姦", "rape", "forced"),
        setOf("乱伦", "亂倫", "incest"),
        setOf("激情", "激情视频", "激情片"),
        setOf("门事件", "事件门", "scandal"),
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
        canonicalKey[norm(raw)]?.let { return it }
        // v1.8.4.0（待办 #11）：影视源把类型写成「动作片 / 爱情片 / 科幻片」，
        // 而受控词表的规范名是短词。剥掉影视后缀再查一次，两边才会落到同一个键上。
        genreBaseOf(raw)?.let { base -> canonicalKey[norm(base)]?.let { return it } }
        return raw
    }

    /**
     * 影视类后缀（v1.8.4.0）。
     *
     * 顺序从长到短：「电视剧」要先于「剧」，「电影」要先于「影」这类前缀才不会被切错。
     * 只在这些后缀前面是**已登记的题材词**时才剥离 —— 「卡片」「照片」「唱片」
     * 的基词不在词表里，因此原样保留，不会被误改成「卡」「照」「唱」。
     */
    private val FILM_SUFFIXES = listOf(
        "电视剧", "電視劇", "连续剧", "連續劇", "电影", "電影", "片", "剧", "劇",
    )

    /** 剥掉影视后缀后的题材基词；基词不在受控词表里时返回 null */
    private fun genreBaseOf(raw: String): String? {
        val text = raw.trim()
        for (suffix in FILM_SUFFIXES) {
            if (text.length <= suffix.length || !text.endsWith(suffix)) continue
            val base = text.dropLast(suffix.length)
            if (base.isBlank()) continue
            if (canonicalKey.containsKey(norm(base)) || groupOf.containsKey(norm(base))) return base
        }
        return null
    }

    /** 用户主动选择成人分级 / 修饰状态标签时，视为本次搜索明确请求成人源。 */
    fun isExplicitAdultIntent(tag: String): Boolean =
        canonicalOf(tag) in EXPLICIT_ADULT_CANONICALS

    private val EXPLICIT_ADULT_CANONICALS = setOf("成人", "无码", "有码", "里番")

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
        // 「动作片」这类影视写法先剥后缀再找组，否则查询侧和索引侧各说各话
        val group = groupOf[key] ?: genreBaseOf(tag)?.let { groupOf[norm(it)] }
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
            ?: genreBaseOf(raw)?.let { base ->
                GROUPS.firstOrNull { aliases -> aliases.any { norm(it) == norm(base) } }
            }
            ?: return listOf(raw)
        return (listOf(raw) + group)
            .distinctBy(::norm)
    }

    /**
     * **单个源站一次搜索最多展开几种写法**（v1.8.1.0 起 2；v1.8.7.1 提到 3）。
     *
     * 同义组动辄二三十个写法（「无码」27 个、「全彩」42 个），而 [com.novelreader.data.cache.TagPlan.searchEntriesFor]
     * 是「每个源 × 每个写法」各发一次请求。搜「无码 全彩」时 = 源数 × 27 + 源数 × 42，
     * 二十个源就是一千多个请求，再乘上复合搜索的翻页 —— 这是「搜个标签要等好几分钟、
     * 最后还空手而归」的真正原因。
     *
     * 为什么从 2 提到 3：原来按组内声明顺序取前两个，结果永远是**简繁一对**
     * （`无码` + `無碼`），日文写法（`修正なし`）与英文写法（`uncensored`）永远轮不到 ——
     * 用户看到的就是「说好的同义词组合搜索，实际只搜了两个字面几乎一样的词」。
     * 现在改为按 [searchTermsForQuery] 的字符集代表性挑选，先保中文 / 日文 / 英文
     * 各一个代表，再按声明顺序补满。
     *
     * **v1.8.8.9：3 → 6。** 当初压到 3 是因为「每个源 × 每个写法」全部并发派出去，
     * 写法和源数的乘积直接决定请求量，27 个写法扛不住。v1.8.8.9 起标签搜索改成
     * 「同一个源同时只发 1 个请求 + 死源本轮只问一次」（见 TagSearchViewModel 的
     * deadSources / sourceLocks）：死源无论配了几个写法都只付 1 次超时代价，
     * 活源则按 1 秒上下的节奏依次把几种写法跑完。于是限制可以放宽到 6 ——
     * 覆盖简 / 繁 / 日 / 英四种叫法之外还能再兜两个常见变体，而实测总耗时反而比
     * 3 个写法时更短（因为死源不再被重复问）。
     *
     * 不继续往上抬的原因：活源是串行取写法，单请求约 1~1.5 秒、单源预算 8 秒，
     * 6 个刚好跑满；再多也是被预算砍掉，白白多打请求。
     */
    const val MAX_SEARCH_TERMS_PER_SOURCE = 6

    /**
     * 供**发请求**用的写法列表（v1.8.7.1 改挑选口径）。
     *
     * 不再「取组内前 N 个」，而是**按语系挑代表**：用户输入永远排第一，第二给组内
     * 第一个不同写法（简繁互补的那个主同义词），第三给一个外文写法（优先拉丁、其次日文）。
     *
     * 这样「无码」发出去的三个写法是 `无码` / `無碼` / `uncensored` ——
     * 覆盖简体站、繁体站、英文站三种叫法；用户输 `無修正` 时同理得到
     * `無修正` / `无码` / `uncensored`，不会出现「只搜了两个字面几乎一样的词」。
     *
     * 分类页入口不受此限：那条路径只按真实分类 url 请求，不展开写法。
     */
    fun searchTermsForQuery(
        tag: String,
        limit: Int = MAX_SEARCH_TERMS_PER_SOURCE,
    ): List<String> {
        val all = searchTermsFor(tag)
        if (limit <= 0 || all.size <= limit) return all
        val picked = LinkedHashSet<String>()
        // 1) 用户输入永远排第一
        picked += all.first()
        // 2) 组内第一个不同写法：通常是简繁互补的那个主同义词
        //    （`無修正` → `无码`），少了它等于把另一半中文源整个放弃
        if (picked.size < limit) all.firstOrNull { it !in picked }?.let { picked += it }
        // 3) 一个外文写法：优先拉丁（英文站数量通常更多），其次日文
        for (script in listOf(2, 1)) {
            if (picked.size >= limit) break
            if (picked.any { scriptOf(it) == script }) continue
            all.firstOrNull { scriptOf(it) == script }?.let { picked += it }
        }
        // 仍有空位（组里没有外文写法）→ 按声明顺序补满
        for (term in all) {
            if (picked.size >= limit) break
            picked += term
        }
        return picked.toList()
    }

    /** 写法所属字符集：0 = 中文，1 = 日文（含假名），2 = 拉丁 */
    private fun scriptOf(term: String): Int = when {
        term.all { it.code < 128 } -> 2
        term.any { it in '\u3040'..'\u30FF' } -> 1
        else -> 0
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
