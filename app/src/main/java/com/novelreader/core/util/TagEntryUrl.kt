package com.novelreader.core.util

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 标签入口地址的「伪 URL」协议（v1.6.6.0；v1.7.0.0 增加真分类入口）。
 *
 * 标签池 [com.novelreader.data.cache.TagPlan] 里每个标签对应若干 `(sourceId, url)` 入口，
 * 入口地址原本只可能是「源自己声明的发现分类页」(`exploreUrl`)。但漫画源绝大多数
 * **没有分类页**（25 个 Venera 源在协议层没有发现分类接口），于是这里补两种入口：
 *
 * 1. **按关键词搜索**（v1.6.6.0）：前缀 `search::`，把题材词当查询串交给源的
 *    [com.novelreader.domain.model.SourceAdapter.search]。缺点是**搜索只匹配标题**，
 *    漫画里按「恋爱」搜基本搜不到东西 —— 它只是「没有分类页时的兜底」。
 * 2. **走源自己的分类页**（v1.7.0.0）：前缀 `vcat::`，负载是一段 JSON：
 *    ```json
 *    {"c":"恋爱","p":"lianai","o":["all"],"t":"category"}
 *    ```
 *    `c` = 分类名（人看的）、`p` = 源自己的分类参数（Venera 的 `categoryParams[i]`，
 *    可能是字符串 / 数字 / 对象）、`o` = **其它过滤器的默认选择**（`categoryComics.optionList`
 *    各组的第一项，源自己会按位置取用）、`t` = `category`（走分类页）/ `search`（退回搜索）、
 *    `k` = `t=="search"` 时的关键词。
 *
 * 用伪 URL 而不是给 `TagPlan` 加新字段，是为了不动落盘格式与既有单测；
 * 前缀与 legado `exploreUrl` 文本列表里的 `标题::地址` 分隔约定不冲突
 * （那边的标题在前，这里整个串就是地址）。
 *
 * 消费方：[com.novelreader.source.venera.VeneraSourceAdapter.exploreFilter]。
 */
object TagEntryUrl {

    /** 伪 URL 前缀：按关键词搜索 */
    const val SEARCH_PREFIX = "search::"

    /** 伪 URL 前缀：Venera 源的分类页浏览 */
    const val CATEGORY_PREFIX = "vcat::"

    /**
     * 伪 URL 前缀：Venera 源的**发现页**（`explore` 数组里的第 N 项）。
     *
     * 少数源（カドコミ / Lanraragi / Komga 等）没有 `category.parts`，只有 `explore`。
     * 它们以前在 App 里一个分类入口都没有（浏览页整页空白、只能靠搜索），
     * 现在用 `vexp::<下标>` 指向源码里 `explore[i]`，点进去由 runtime 的
     * `explore` op 真去加载（v1.7.8.0）。
     */
    const val EXPLORE_PREFIX = "vexp::"

    /** 分类入口 */
    data class CategoryEntry(
        /** 分类名（人看的，也是标签名） */
        val name: String,
        /** 源自己的分类参数（可为任意 JSON；null = 源不区分） */
        val param: JsonElement?,
        /** 其它过滤器的默认选择（按 optionList 分组顺序） */
        val options: List<JsonElement?>,
        /** [kind] == "search" 时的关键词 */
        val keyword: String?,
        /** category（走分类页）/ search（退回搜索） */
        val kind: String,
    )

    /** 构造「按关键词搜索」入口 */
    fun search(keyword: String): String = SEARCH_PREFIX + keyword.trim()

    /**
     * 取搜索关键词；不是搜索入口则返回 null（调用方据此走普通发现分类）。
     */
    fun searchKeywordOf(url: String): String? {
        val u = url.trim()
        if (!u.startsWith(SEARCH_PREFIX)) return null
        return u.removePrefix(SEARCH_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    /** 构造「分类页浏览」入口（[kind] 非 category 时一并记下 `search` 回退用的关键词） */
    fun category(
        name: String,
        param: Any? = null,
        options: List<Any?> = emptyList(),
        keyword: String? = null,
        kind: String = "category",
    ): String {
        val obj = JsonObject()
        obj.addProperty("c", name)
        obj.add("p", gson().toJsonTree(param))
        obj.add("o", gson().toJsonTree(options))
        if (!keyword.isNullOrBlank()) obj.addProperty("k", keyword)
        if (kind != "category") obj.addProperty("t", kind)
        return CATEGORY_PREFIX + gson().toJson(obj)
    }

    /**
     * 解析分类入口；不是分类入口 / 负载损坏则返回 null。
     *
     * 损坏一律当作「不是分类入口」，让调用方走别的分支 —— 标签池是长期落盘的，
     * 旧数据 / 手改数据都不能让点标签直接崩。
     */
    fun categoryOf(url: String): CategoryEntry? {
        val u = url.trim()
        if (!u.startsWith(CATEGORY_PREFIX)) return null
        return runCatching {
            val obj = JsonParser.parseString(u.removePrefix(CATEGORY_PREFIX)).asJsonObject
            val name = obj.get("c")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()
            if (name.isEmpty()) return@runCatching null
            val options = obj.getAsJsonArray("o")?.map { it.takeIf { e -> !e.isJsonNull } } ?: emptyList()
            CategoryEntry(
                name = name,
                param = obj.get("p")?.takeIf { e -> !e.isJsonNull },
                options = options,
                keyword = obj.get("k")?.takeIf { it.isJsonPrimitive }?.asString,
                kind = obj.get("t")?.takeIf { it.isJsonPrimitive }?.asString ?: "category",
            )
        }.getOrNull()
    }

    private fun gson() = Gson()

    /** 构造「发现页」入口：`vexp::<explore 下标>` */
    fun explore(index: Int): String = EXPLORE_PREFIX + index

    /** 取发现页下标；不是发现页入口 / 负载不是非负整数则返回 null */
    fun exploreIndexOf(url: String): Int? {
        val u = url.trim()
        if (!u.startsWith(EXPLORE_PREFIX)) return null
        return u.removePrefix(EXPLORE_PREFIX).trim().toIntOrNull()?.takeIf { it >= 0 }
    }
}
