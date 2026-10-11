package com.novelreader.source.engine

/**
 * JSON 源规则数据模型。
 *
 * 将 JSON 源文件解析为结构化的规则集，供 JsonSourceAdapter 使用。
 * 对应需求文档中的 JSON 源规范。
 */
data class JsonRuleSet(
    /** 源元信息 */
    val name: String,
    val type: String,
    val version: String = "1.0.0",
    val author: String = "",
    val desc: String = "",
    val baseUrl: String = "",

    /** 搜索规则 */
    val search: Rule? = null,
    /** 详情规则 */
    val detail: Rule? = null,
    /** 目录规则 */
    val chapterList: Rule? = null,
    /** 正文规则 */
    val content: Rule? = null,
)

/**
 * 单条提取规则。
 */
data class Rule(
    /** 请求地址模板（含 {{keyword}} {{page}} 等占位符） */
    val url: String,
    /** 请求方法，默认 GET */
    val method: String = "GET",
    /** 列表项选择器（search/chapterList 使用） */
    val list: String? = null,
    /** 字段映射：字段名 -> 提取表达式（如 "a@href"、"div.title@text"） */
    val fields: Map<String, String> = emptyMap(),
    /** 正文文本提取表达式（content 使用，如 "#content@html"） */
    val text: String? = null,
    /** 图片列表提取表达式（漫画 content 使用，如 "#images img@src"） */
    val images: String? = null,
    /** 视频地址提取表达式（视频 content 使用，如 "video@src"） */
    val videoUrl: String? = null,
    /** 视频请求头（防盗链） */
    val headers: Map<String, String> = emptyMap(),
    /** 需移除的元素选择器（净化正文） */
    val remove: List<String> = emptyList(),
)
