package com.novelreader.source.category

import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType

/**
 * 源智能分类器。
 *
 * 根据源名称、作者、描述、基础地址等元信息，通过关键词匹配将源归类。
 * 分类结果用于源管理页的分类筛选展示。
 *
 * 分类体系（内容向）：
 * - 玄幻 / 奇幻 / 修仙
 * - 都市 / 言情
 * - 历史 / 军事
 * - 科幻 / 悬疑 / 灵异
 * - 武侠 / 仙侠
 * - 游戏 / 竞技
 * - 二次元 / 轻小说
 * - 综合（无法识别或跨类型）
 */
object SourceCategory {

    /** 分类关键词表：分类名 -> 命中关键词列表 */
    private val CATEGORY_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "玄幻" to listOf("玄幻", "奇幻", "异界", "斗气", "魔法", "修真", "修仙", "仙侠", "玄幻小说"),
        "都市" to listOf("都市", "言情", "现言", "青春", "校园", "婚恋", "总裁", "都市小说"),
        "历史" to listOf("历史", "军事", "古代", "穿越", "宫斗", "历史小说"),
        "科幻" to listOf("科幻", "末世", "星际", "未来", "机甲", "科幻小说"),
        "悬疑" to listOf("悬疑", "灵异", "惊悚", "推理", "侦探", "恐怖", "罪案"),
        "武侠" to listOf("武侠", "江湖", "功夫", "武动"),
        "游戏" to listOf("游戏", "电竞", "竞技", "网游", "王者", "英雄联盟", "LOL"),
        "二次元" to listOf("二次元", "轻小说", "动漫", "动画", "漫画", "番剧", "ACGN", "同人"),
        "影视" to listOf("影视", "电影", "电视剧", "综艺", "视频", "影视源"),
        "有声" to listOf("有声", "听书", "音频", "广播剧"),
    )

    /** 通用/无法识别的分类名 */
    const val CATEGORY_GENERAL = "综合"

    /**
     * 对单个源进行分类。
     *
     * @return 分类名（中文）
     */
    fun classify(info: SourceInfo): String {
        // 先按类型优先分类：漫画源 → 二次元，视频源 → 影视
        val typeHint = when (info.type) {
            SourceType.COMIC -> "二次元"
            SourceType.VIDEO -> "影视"
            SourceType.NOVEL -> null
        }

        // 拼接可匹配的文本
        val text = listOf(info.name, info.author, info.desc, info.baseUrl)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

        // 关键词命中打分：命中关键词数越多、关键词越长，越可信
        var bestCategory: String? = null
        var bestScore = 0

        for ((category, keywords) in CATEGORY_KEYWORDS) {
            var score = 0
            for (kw in keywords) {
                if (text.contains(kw.lowercase())) {
                    score += kw.length
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestCategory = category
            }
        }

        // 类型提示优先于名称匹配（漫画源即便名字像小说，也归入二次元）
        if (typeHint != null && bestScore <= 0) {
            return typeHint
        }
        // 名称/描述有明确命中时用它；否则退回类型提示或综合
        return bestCategory ?: typeHint ?: CATEGORY_GENERAL
    }

    /** 所有可选分类（用于筛选 UI） */
    fun allCategories(): List<String> = CATEGORY_KEYWORDS.map { it.first } + CATEGORY_GENERAL

    /**
     * 对源列表进行分组。
     *
     * @return 分类名 -> 该分类下的源列表（保持原有顺序）
     */
    fun groupByCategory(sources: List<SourceInfo>): Map<String, List<SourceInfo>> =
        sources.groupBy { classify(it) }
}
