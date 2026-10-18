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

    // ===== 标签来源 =====
    const val ORIGIN_STATION = "站方"
    const val ORIGIN_TEXT = "文本"
    const val ORIGIN_BEHAVIOR = "行为"
    const val ORIGIN_VISUAL = "视觉"

    /** 站方自己声明的题材：最可信 */
    private const val CONF_STATION = 100
    /** 文本关键词命中：字面命中，语义只是相关 */
    private const val CONF_TEXT = 60

    /** 打标结果 */
    data class Tag(
        val name: String,
        val category: String,
        val confidence: Int,
        val origin: String,
    )

    /**
     * 给一部作品打标签。
     *
     * 同名标签保留**置信度更高**的那条 —— 后续视觉 / 行为管道接入时，
     * 只要给出更高分就能自然覆盖文本管道的猜测，无需改动调用方。
     */
    fun tag(item: MediaItem): List<Tag> {
        val out = LinkedHashMap<String, Tag>()
        // 1) 站方声明（kind / tags / 浏览上下文）
        ItemTags.of(item).forEach { raw ->
            val name = TagAlias.canonicalOf(raw)
            if (name.isBlank()) return@forEach
            merge(out, Tag(name, categoryOf(name), CONF_STATION, ORIGIN_STATION))
        }
        // 2) 文本：标题 + 简介（影视简介多为剧情复述，噪声大，只做小说 / 漫画）
        if (item.type == SourceType.NOVEL || item.type == SourceType.COMIC) {
            val text = buildString {
                append(item.title)
                item.desc?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
            }
            ComicTagLexicon.match(text, limit = 12).forEach { raw ->
                val name = TagAlias.canonicalOf(raw)
                if (name.isBlank()) return@forEach
                merge(out, Tag(name, categoryOf(name), CONF_TEXT, ORIGIN_TEXT))
            }
        }
        return out.values.toList()
    }

    private fun merge(out: MutableMap<String, Tag>, tag: Tag) {
        val old = out[tag.name]
        if (old == null || tag.confidence > old.confidence) out[tag.name] = tag
    }

    /**
     * 标签归类（受控词表层面）。
     *
     * 成人相关标签单独归到「成人」，便于隐藏成人内容时整类过滤；
     * 其余暂归「题材」——后续接视觉管道时再补「画风」「受众」。
     */
    private fun categoryOf(name: String): String = when {
        TagAlias.isExplicitAdultIntent(name) -> CATEGORY_ADULT
        else -> CATEGORY_GENRE
    }
}
