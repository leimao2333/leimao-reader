package com.novelreader.core.util

import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType

/**
 * 自动打标器（v1.8.1.2「中间标签层」第一步）。
 *
 * 方案原文：网络源没有结构化标签，要在源数据之上建一个独立的标签层，
 * 搜索与分类只信任这一层，源站字段只当原始素材之一。
 *
 * 手机端当前可用的**两条打标管道**：
 * 1. **站方管道**（[ORIGIN_STATION]）：源自己声明的题材（legado `kind`、
 *    Venera `tags`），由 [ItemTags] 读出 —— 最准，置信度 100。
 * 2. **文本管道**（[ORIGIN_TEXT]）：标题 + 简介过题材词表（[ComicTagLexicon]），
 *    「全彩」「恋爱」这类只写在标题里的属性靠它补上；字面命中 ≠ 语义确定，
 *    置信度低于站方，查询时可先用它、有站方信号时自动覆盖。
 *
 * 另两条（视觉模型 ONNX / LLM 文本分类）需要端侧模型文件与推理，
 * 属于后续阶段；端口已按 [ORIGIN_VISUAL] 预留。行为信号同理（[ORIGIN_BEHAVIOR]）。
 *
 * 输出全部落到 `tag_instances` 表（每条标签一行），供嵌套式 AND 查询使用。
 */
object TagTagger {

    // ===== 标签大类 =====
    const val CATEGORY_GENRE = "题材"
    const val CATEGORY_ADULT = "成人"
    const val CATEGORY_STYLE = "画风"
    const val CATEGORY_AUDIENCE = "受众"

    // ----- 影视专属大类（v1.8.4.0，待办 #12）-----
    // 采集站的 vod_area / vod_lang / vod_year / vod_actor 一直躺在 extras 里没进标签层，
    // 这几类把它们变成可搜索的维度（`地区:香港`、`年代:2025`）。
    const val CATEGORY_REGION = "地区"
    const val CATEGORY_LANGUAGE = "语言"
    const val CATEGORY_ERA = "年代"
    const val CATEGORY_CAST = "主创"

    // ===== 标签来源 =====
    const val ORIGIN_STATION = "站方"
    const val ORIGIN_TEXT = "文本"
    const val ORIGIN_BEHAVIOR = "行为"
    const val ORIGIN_VISUAL = "视觉"

    /**
     * 「搜索命中」来源（v1.8.9.9）：用户在标签页搜了某个词，结果里标题命中的那批。
     *
     * 它和「站方 / 文本」的区别在于**证据强度**：标题里出现某个词，不等于作品真的
     * 属于这个标签。所以这类标签只用来**计数**（让「已收录 N 部」跟得上搜索结果），
     * **不参与本地召回** —— `refsWithAny` 会显式排除这个来源，否则搜一次就把整批
     * 结果钉成「本地已收录」，之后每次搜索都绕过判定直接合并，越搜越脏。
     */
    const val ORIGIN_SEARCH = "搜索"

    /** 站方自己声明的题材：最可信 */
    private const val CONF_STATION = 100
    /** 文本关键词命中：字面命中，语义只是相关 */
    private const val CONF_TEXT = 60

    /** 搜索命中：只够用来计数，不足以证明归属（低于文本匹配的 60） */
    private const val CONF_SEARCH = 45

    /**
     * 影视标题里最多补几个文本标签（v1.8.7.1）。
     *
     * 比小说 / 漫画少得多：视频标题本来就短，抽太多会把「中字」「高清」这种
     * 泛属性堆满标签栏，把真正的题材挤下去。
     */
    private const val MAX_VIDEO_TEXT_TAGS = 6

    /** 打标结果 */
    data class Tag(
        val name: String,
        val category: String,
        val confidence: Int,
        val origin: String,
    )

    /**
     * 外部上下文标签（v1.8.1.9）。
     *
     * 用户从某个标签的**真实分类页**搜到这部作品时，「它出现在这个分类下」本身就是一条
     * 证据。以前 [tag] 只看作品自身字段（标题 / 简介 / 站方 kind），拿不到这个信息，
     * 于是「去全彩分类页搜一次」并不会给作品补上全彩 —— 搜索只是搜索，不改变标签层。
     */
    data class ContextTag(
        val name: String,
        val confidence: Int,
        val origin: String = ORIGIN_STATION,
    )

    /**
     * 搜索命中用的上下文标签（v1.8.9.9）。
     *
     * 用户在标签页搜了某个词，标题命中的结果就该在「这个标签收录了多少部」里体现出来 ——
     * 否则搜到 500 部、标签上还写着 350，看起来就像打标没生效。
     * 但这类标签**证据很弱**（标题里出现 ≠ 真的属于），所以给低置信度并标记来源，
     * 由 `refsWithAny` 在召回时整体排除：**只计数，不进本地召回**。
     */
    fun searchContext(tags: Collection<String>): List<Pair<String, Int>> =
        tags.map { TagAlias.canonicalOf(it) to CONF_SEARCH }

    /**
     * 给一部作品打标签。
     *
     * @param context 外部上下文标签（分类页归属）。与站方 / 文本管道一样参与 [merge]：
     *   同名取更高分 —— 站方自述 100 不会被分类页 90 顶掉，文本猜的 60 会被提到 90。
     *
     * 同名标签保留**置信度更高**的那条 —— 后续视觉 / 行为管道接入时，
     * 只要给出更高分就能自然覆盖文本管道的猜测，无需改动调用方。
     */
    fun tag(
        item: MediaItem,
        context: List<ContextTag> = emptyList(),
    ): List<Tag> {
        val out = LinkedHashMap<String, Tag>()
        val video = item.type == SourceType.VIDEO
        // 1) 站方声明（kind / tags / 浏览上下文）
        ItemTags.of(item).forEach { raw ->
            val name = TagAlias.canonicalOf(raw)
            if (name.isBlank()) return@forEach
            merge(out, Tag(name, categoryOf(name, video), CONF_STATION, ORIGIN_STATION))
        }
        // 2) 影视：把源里已有、却一直没进标签层的维度补上（v1.8.4.0）
        //    地区 / 语言 / 年代 / 主创来自结构化字段（置信度同站方）；
        //    标题题材是文本猜的（60 分），会被站方 typeName 覆盖。
        if (video) {
            VideoTagLexicon.derive(item).forEach { derived ->
                val name = if (derived.canonicalize) TagAlias.canonicalOf(derived.name) else derived.name
                if (name.isBlank()) return@forEach
                merge(out, Tag(name, derived.category, derived.confidence, derived.origin))
            }
        }
        // 3) 文本：标题 + 简介（影视简介多为剧情复述，噪声大，只做小说 / 漫画）
        if (item.type == SourceType.NOVEL || item.type == SourceType.COMIC) {
            val text = buildString {
                append(item.title)
                item.desc?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
            }
            ComicTagLexicon.match(text, limit = 12).forEach { raw ->
                val name = TagAlias.canonicalOf(raw)
                if (name.isBlank()) return@forEach
                merge(out, Tag(name, categoryOf(name, video = false), CONF_TEXT, ORIGIN_TEXT))
            }
        } else if (video) {
            // 影视标题（v1.8.7.1）：词表与漫画同一套（[ComicTagLexicon] 建表时已纳入
            // 通用题材与全部同义组），差别只在元信息排除集 —— 换成
            // [ComicTagLexicon.VIDEO_META_TAGS] 后，「无码 / 有码 / 中字 / 高清」这类
            // 对筛片有价值、却被漫画口径当噪声丢掉的维度得以保留。
            //
            // **只抽标题，不抽简介**：采集站的 `vod_content` 基本是剧情复述，
            // 「爱情」「家庭」出现在一部恐怖片的简介里很正常；而标题信息密度高、噪声低。
            //
            // 置信度仍是 [CONF_TEXT]：这是猜出来的，同名时会被站方 typeName 覆盖。
            ComicTagLexicon.match(
                text = item.title,
                limit = MAX_VIDEO_TEXT_TAGS,
                metaTags = ComicTagLexicon.VIDEO_META_TAGS,
            ).forEach { raw ->
                val name = TagAlias.canonicalOf(raw)
                if (name.isBlank()) return@forEach
                merge(out, Tag(name, categoryOf(name, video), CONF_TEXT, ORIGIN_TEXT))
            }
        }
        // 4) 分类页上下文（v1.8.1.9）：从「全彩」这类分类页命中的作品，补上该分类标签。
        //    置信度按入口类型由调用方给（真实分类页高、纯关键词搜索低或不给）。
        context.forEach { ctx ->
            val name = TagAlias.canonicalOf(ctx.name)
            if (name.isBlank()) return@forEach
            merge(out, Tag(name, categoryOf(name, video), ctx.confidence, ctx.origin))
        }
        return out.values.toList()
    }

    private fun merge(out: MutableMap<String, Tag>, tag: Tag) {
        val old = out[tag.name]
        if (old == null || tag.confidence > old.confidence ||
            // 同分时让「维度类」大类胜出：影视的「香港」既是分类名（题材）又是
            // vod_area 派生的地区，两条路径都会产出它，落库只能留一条 ——
            // 留成题材的话 `地区:香港` 就查不到了。
            (tag.confidence == old.confidence && rank(tag.category) > rank(old.category))
        ) {
            out[tag.name] = tag
        }
    }

    /** 大类优先级：分级 > 维度（地区/语言/年代/主创）> 题材 */
    private fun rank(category: String): Int = when (category) {
        CATEGORY_ADULT -> 3
        CATEGORY_REGION, CATEGORY_LANGUAGE, CATEGORY_ERA, CATEGORY_CAST -> 2
        else -> 1
    }

    /**
     * 标签归类（受控词表层面）。
     *
     * 成人相关标签单独归到「成人」，便于隐藏成人内容时整类过滤；
     * 影视的地区 / 语言 / 年代能认出来的，归到对应维度大类（v1.8.4.0）——
     * 分类名拆出来的「香港」「国语」「2025」要和 `vod_area` 派生的是同一类，
     * 否则 `地区:香港` 只查得到一半。
     * 其余暂归「题材」——后续接视觉管道时再补「画风」「受众」。
     */
    private fun categoryOf(name: String, video: Boolean): String = when {
        TagAlias.isExplicitAdultIntent(name) -> CATEGORY_ADULT
        !video -> CATEGORY_GENRE
        VideoTagLexicon.regionOf(name) != null -> CATEGORY_REGION
        VideoTagLexicon.languageOf(name) != null -> CATEGORY_LANGUAGE
        VideoTagLexicon.yearOf(name) != null -> CATEGORY_ERA
        else -> CATEGORY_GENRE
    }
}
