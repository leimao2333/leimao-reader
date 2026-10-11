package com.novelreader.source.engine

/**
 * 占位符替换引擎。
 *
 * 负责把源规则模板中的 `{{keyword}}`、`{{page}}` 等占位符替换为运行时取值；
 * HTML 解析与选择器提取见 [HtmlSelectorEngine]。
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
