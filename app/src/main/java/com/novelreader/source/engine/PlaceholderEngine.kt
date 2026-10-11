package com.novelreader.source.engine

/**
 * 源引擎包说明。
 *
 * 本包预留给「规则引擎」相关能力：
 * - HTML 解析规则执行（CSS 选择器 + 属性提取）
 * - 占位符替换引擎（{{keyword}}、{{page}} 等）
 * - 结果字段映射引擎
 *
 * TODO(M2): 实现 HTML 解析与选择器引擎（可基于 jsoup 或自研轻量解析器）。
 */
object PlaceholderEngine {

    /**
     * 占位符替换。
     *
     * 支持的占位符：
     * - {{keyword}} 搜索关键词
     * - {{page}} 页码
     * - {{itemUrl}} / {{detailUrl}} 详情地址
     * - {{chapterUrl}} 章节地址
     *
     * @param template 含占位符的 URL 模板
     * @param values 占位符 -> 值的映射
     */
    fun render(template: String, values: Map<String, String>): String {
        var result = template
        values.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
