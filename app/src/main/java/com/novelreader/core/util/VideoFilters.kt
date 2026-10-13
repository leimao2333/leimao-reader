package com.novelreader.core.util

/**
 * 片库筛选（v1.3.5.0）的**纯逻辑**：选项生成 + 提交值映射。
 *
 * 采集站（苹果 CMS / maccms10）的 `ac=detail` 支持四个筛选维度：
 * `t`（分类）、`area`（地区）、`year`（年份）、`order`（排序）。
 *
 * 为什么选项要写死在本地，而不是像分类那样从接口拿：
 * - `ac=list` 只返回 `class`（分类），**没有**地区 / 年份 / 排序的可选值接口；
 * - 各站对同一概念写法不同（「大陆」/「内地」），没有标准枚举。
 *
 * 影响面可控：源不认某个参数时通常只是**忽略**它（返回默认列表），不会报错，
 * 所以这属于渐进增强，可以安全地默认开启。
 *
 * 「全部」统一映射为空串；[com.novelreader.source.moontv.MacCmsParser.buildUrl]
 * 会把空值参数过滤掉，因此「全部」等价于不传该参数。
 */
object VideoFilters {

    /** UI 上「不限」的标签，集中定义避免各处硬编码 */
    const val ALL_LABEL = "全部"

    /** 地区候选（maccms 常见的 `vod_area` 取值） */
    private val AREA_VALUES = listOf(
        "大陆", "香港", "台湾", "美国", "韩国", "日本",
        "泰国", "英国", "法国", "印度", "俄罗斯", "其它",
    )

    /** 年份窗口：从当前年往前数几项（含当前年） */
    const val YEAR_WINDOW = 11

    /** 排序：label → maccms 的 `order` 取值 */
    private val ORDER_VALUES = listOf(
        "最新" to "time",
        "最热" to "hits",
        "评分" to "score",
    )

    /** 一个筛选项：[label] 给 UI 显示，[value] 提交给采集接口（空串 = 不传） */
    data class Option(val label: String, val value: String)

    /** 「不限」选项（value 为空串，交由 buildUrl 过滤） */
    val ALL = Option(ALL_LABEL, "")

    fun areas(): List<Option> = listOf(ALL) + AREA_VALUES.map { Option(it, it) }

    /**
     * 年份由当前年推算，避免写死列表过几年就「没有最新年份」。
     *
     * @param currentYear 当前年（显式传参，便于单测固定时间）
     */
    fun years(currentYear: Int): List<Option> =
        listOf(ALL) +
            (currentYear downTo currentYear - YEAR_WINDOW + 1).map { Option(it.toString(), it.toString()) }

    fun orders(): List<Option> = listOf(ALL) + ORDER_VALUES.map { Option(it.first, it.second) }

    /**
     * 组装提交给采集接口的筛选参数（保序，便于日志阅读与单测断言）。
     *
     * 只放非空项 —— [com.novelreader.source.moontv.MacCmsParser.buildUrl] 同样会过滤空值，
     * 这里显式过滤是为了让「全部」的语义在单测里可断言。
     */
    fun toFilterMap(area: String, year: String, order: String): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            if (area.isNotBlank()) put("area", area)
            if (year.isNotBlank()) put("year", year)
            if (order.isNotBlank()) put("order", order)
        }

    /**
     * 下拉按钮上要显示的文案。
     *
     * 未选中（空串）时回退到维度名（如「地区」），而不是显示「全部」——
     * 这样四个按钮在未筛选时是「分类 | 地区 | 年份 | 排序」，一眼能看出能点什么。
     */
    fun labelOf(options: List<Option>, selected: String, fallback: String): String = when {
        selected.isBlank() -> fallback
        else -> options.firstOrNull { it.value == selected }?.label ?: selected
    }
}
