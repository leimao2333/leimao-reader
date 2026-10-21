package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType

/**
 * 条目标签派生（v1.6.5.0）。
 *
 * 详情页顶部「标签小栏」（`DetailTagBar`）要展示**每个个体的多个标签**，
 * 但三类源的标签载体各不相同，这里统一收口：
 *
 * - **浏览上下文**（[MediaItem.tags]）：从某个发现分类点进详情时，由发现页 /
 *   影视首页把分类名写进条目（见 `ExploreViewModel.aggregate`、
 *   `HomeViewModel.loadChannelCategory` / `loadVideoSections` / `loadVideoGrid`）。
 * - **视频**（苹果 CMS / 采集站）：`extras["typeName"]` = `vod_type_name`，
 *   列表与详情响应都带，是最稳的标签来源。
 * - **小说 / 漫画**（legado）：`extras["kind"]` = `ruleBookInfo.kind` 规则取值，
 *   即源自己声明的分类；状态词（连载中 / 已完结）不是标签，要过滤掉
 *   （legado 的 kind 偶尔被源写成状态，见 `BookMeta.statusText` 的兼容）。
 *
 * 分隔符拆分：源可能把多个分类写成一个字段（「玄幻, 奇幻」「动作/喜剧」），
 * 按 `,，、/|` + 空白切开；去重、限量（[MAX_TAGS]）后返回，保持来源顺序。
 */
object ItemTags {

    /** 单个条目最多展示几个标签（小栏展开后也沿用，防止异常源刷屏） */
    const val MAX_TAGS = 24

    /**
     * 从标题 / 简介里**补充**的标签上限（v1.7.9.0）。
     *
     * 源自带标签优先（语义最准），这里只补不足的部分，避免简介里随口提到的词
     * 把真实题材挤掉。
     */
    private const val MAX_ENRICH_TAGS = 12

    /**
     * [MediaItem.extras] 里「源自带标签」的键（逗号分隔，v1.7.0.0）。
     *
     * Venera 源的列表 / 详情响应本来就带每部作品的题材（包子漫画的 `type_names`、
     * MangaDex 的 tags…），以前在适配器里被丢掉。现在存进 extras 由这里读出来，
     * 漫画详情页因此能多出一行真实题材，而不是只有浏览上下文那一个分类名。
     */
    const val EXTRAS_TAGS = "tags"

    /**
     * 采集站 `vod_class` 的 extras 键（v1.8.7.0 待办 #28）。
     *
     * 苹果 CMS 的 `vod_type_name` 只给一个主分类，`vod_class` 才是「剧情,动作,悬疑」
     * 这种**多题材**字段 —— 有了它，一部片子的题材标签不必只靠标题窄口径去猜。
     */
    const val EXTRAS_CLASS = "class"

    /** 标签间分隔符：半/全角逗号、顿号、斜杠、竖线、空白 */
    private val SPLIT_REGEX = Regex("[,，、/|\\s]+")

    /**
     * 状态词判断（与 `BookMeta.normalizeStatus` 同口径，但不依赖 ui 层）。
     *
     * legado 的 `kind` 规则偶尔被源写成状态（「连载中」/「1」），
     * 这种值不是分类标签，派生时要过滤掉。
     */
    private fun isStatusLike(raw: String): Boolean {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return false
        return s.contains("完结") || s.contains("连载") ||
            s.contains("complete") || s.contains("serial") || s.contains("ongoing") ||
            s == "1" || s == "0"
    }

    /**
     * 派生 [MediaItem] 的标签列表（保序去重，可能为空）。
     *
     * 空列表 = 该源既没被分类浏览带入、详情也没声明分类，调用方应隐藏标签栏。
     */
    fun of(item: MediaItem): List<String> {
        val out = LinkedHashSet<String>()
        // 影视的分类名是复合词，浏览上下文与 type_name 一样要拆（v1.8.4.0）
        val compound = item.type == SourceType.VIDEO
        // 1) 浏览上下文优先（用户就是从那个分类点进来的，语义最准）
        item.tags.forEach { addSplit(out, it, dropStatus = false, compound = compound) }
        // 2) 按类型取 extras 派生
        when (item.type) {
            // 影视（v1.8.4.0）：分类名是**复合词**，一个字段表达两件事 ——
            // 「里番动漫」= 动漫 + 里番、「港剧」= 电视剧 + 香港。只存一个标签时
            // 「动漫」这个维度就丢了（搜动漫搜不到里番），所以先拆再归一。
            SourceType.VIDEO -> {
                addSplit(out, item.extras["typeName"], dropStatus = false, compound = true)
                // vod_class：多题材字段（v1.8.7.0 待办 #28），同一套拆分口径
                addSplit(out, item.extras[EXTRAS_CLASS], dropStatus = false, compound = true)
            }
            // legado 的 kind 偶尔被源写成状态（「连载中」/「1」），按片段过滤
            SourceType.NOVEL, SourceType.COMIC -> {
                addSplit(out, item.extras["kind"], dropStatus = true)
                // 源自带的题材标签（Venera 源响应里的 tags，v1.7.0.0）
                addSplit(out, item.extras[EXTRAS_TAGS], dropStatus = true)
            }
        }
        // 3) 标题 + 简介里能认出更多题材（v1.7.9.0）。
        // 不少源只给 0~1 个标签，只靠 extras 时详情页标签栏几乎空白；
        // 标题（「转生成为史莱姆」「魔王与勇者的日常」）与简介本身就写着题材。
        // 只对小说 / 漫画做 —— 影视简介多是剧情复述，抽出来噪声更大。
        //
        // v1.8.7.1：影视的标题抽取**不放在这里**。本函数的结果在 [TagTagger] 里被当作
        // 「站方声明」（置信度 100），把文本猜出来的词混进来会让标题里的「喜剧」
        // 压过别处的站方分类。影视那条走 TagTagger 的文本管道，置信度 60。
        var requiredFanworkTags = emptyList<String>()
        if (item.type == SourceType.NOVEL || item.type == SourceType.COMIC) {
            val text = buildString {
                append(item.title)
                item.desc?.takeIf { it.isNotBlank() }?.let {
                    append(' ')
                    append(it)
                }
            }
            requiredFanworkTags = FanworkTags.derive(text, out)
            requiredFanworkTags.forEach { out.add(it) }
            ComicTagLexicon.match(text, limit = MAX_ENRICH_TAGS, exclude = out)
                .forEach { out.add(it) }
        }
        if (out.size > MAX_TAGS) {
            if (requiredFanworkTags.isEmpty()) return out.take(MAX_TAGS)
            val required = requiredFanworkTags.filter { it in out }
            return (out.filterNot { it in required }.take(MAX_TAGS - required.size) + required)
                .take(MAX_TAGS)
        }
        return out.toList()
    }

    /**
     * 拆分单个原始字段并入集合（空片段跳过）。
     *
     * @param dropStatus true = 丢弃状态词片段（仅 legado kind 用：
     *   源可能把「连载中, 玄幻」写进 kind，状态片段不是分类）。
     * @param compound true = 先把片段按影视分类名拆成「一级类型 + 具体属性」
     *   （`里番动漫` → `动漫` + `里番`，v1.8.4.0）。
     */
    private fun addSplit(
        out: LinkedHashSet<String>,
        raw: String?,
        dropStatus: Boolean,
        compound: Boolean = false,
    ) {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return
        text.split(SPLIT_REGEX).forEach { part ->
            val tag = part.trim()
            if (tag.isEmpty()) return@forEach
            if (dropStatus && isStatusLike(tag)) return@forEach
            // v1.7.9.0：同义写法统一成规范名（妖精 / elf / エルフ → 精灵），
            // 否则同一部作品上会并排出现「妖精」和「精灵」两个意思一样的标签。
            // 语义相邻但不同义的组（天使/恶魔、姐姐/妹妹…）不在归一范围内。
            if (compound) {
                VideoTagLexicon.splitCompoundTag(tag).forEach { piece ->
                    TagAlias.canonicalOf(piece).takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
            } else {
                out.add(TagAlias.canonicalOf(tag))
            }
        }
    }
}
