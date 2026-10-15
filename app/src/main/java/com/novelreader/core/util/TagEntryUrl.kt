package com.novelreader.core.util

/**
 * 标签入口地址的「伪 URL」协议（v1.6.6.0）。
 *
 * 标签池 [com.novelreader.data.cache.TagPlan] 里每个标签对应若干 `(sourceId, url)` 入口，
 * 入口地址原本只可能是「源自己声明的发现分类页」(`exploreUrl`)。但漫画源绝大多数
 * **没有分类页**（25 个 Venera 源在协议层就没有分类浏览接口），于是这里补一种入口：
 * **按关键词搜索**——把题材词当成查询串，交给同一个 [com.novelreader.domain.model.SourceAdapter.search]。
 *
 * 用伪 URL 而不是给 `TagPlan` 加新字段，是为了不动落盘格式与单测；
 * 前缀 `search::` 与 legado `exploreUrl` 文本列表里的 `标题::地址` 分隔约定不冲突
 * （那边的标题在前，这里整个串就是地址）。
 *
 * 消费方：[com.novelreader.data.repository.ContentRepositoryImpl.exploreFiltered]
 * 见到该前缀就改走搜索，其余入口照旧走发现分类。
 */
object TagEntryUrl {

    /** 伪 URL 前缀 */
    const val SEARCH_PREFIX = "search::"

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
}
