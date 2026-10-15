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
    const val MAX_TAGS = 8

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
        // 1) 浏览上下文优先（用户就是从那个分类点进来的，语义最准）
        item.tags.forEach { addSplit(out, it, dropStatus = false) }
        // 2) 按类型取 extras 派生
        when (item.type) {
            SourceType.VIDEO -> addSplit(out, item.extras["typeName"], dropStatus = false)
            // legado 的 kind 偶尔被源写成状态（「连载中」/「1」），按片段过滤
            SourceType.NOVEL, SourceType.COMIC ->
                addSplit(out, item.extras["kind"], dropStatus = true)
        }
        if (out.size > MAX_TAGS) return out.take(MAX_TAGS)
        return out.toList()
    }

    /**
     * 拆分单个原始字段并入集合（空片段跳过）。
     *
     * @param dropStatus true = 丢弃状态词片段（仅 legado kind 用：
     *   源可能把「连载中, 玄幻」写进 kind，状态片段不是分类）。
     */
    private fun addSplit(out: LinkedHashSet<String>, raw: String?, dropStatus: Boolean) {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return
        text.split(SPLIT_REGEX).forEach { part ->
            val tag = part.trim()
            if (tag.isEmpty()) return@forEach
            if (dropStatus && isStatusLike(tag)) return@forEach
            out.add(tag)
        }
    }
}
