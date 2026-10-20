package com.novelreader.domain.model

import com.novelreader.core.rss.PaperMeta

/**
 * RSS 订阅源（领域模型，v1.3.1.0）。
 */
data class RssFeed(
    val feedId: Long,
    val url: String,
    val title: String,
    val siteUrl: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val groupName: String = "",
    /** 最近抓取时间（0 = 从未） */
    val lastFetchAt: Long = 0,
    /** 最近抓取失败原因（空 = 正常） */
    val lastError: String = "",
    val createdAt: Long = 0,
    /** 未读数 */
    val unreadCount: Int = 0,
)

/**
 * RSS 文章（领域模型，v1.3.1.0）。
 *
 * 冗余带上 [feedTitle] / [feedIcon]，便于「全部文章」流里直接展示来源。
 */
data class RssArticle(
    val articleId: Long,
    val feedId: Long,
    val title: String,
    val link: String = "",
    val author: String = "",
    val pubDate: Long = 0,
    val summary: String = "",
    val contentHtml: String = "",
    val imageUrl: String = "",
    /** 论文 DOI（v1.8.5.0；非期刊文章为空）。用于「按 DOI 补题录 / 跳出版社」 */
    val doi: String = "",
    /**
     * 论文题录（v1.8.5.0；非期刊文章为空对象）。
     *
     * 期刊源（Crossref 虚拟源或带 Prism 元数据的学术 RSS）才有内容，
     * 阅读页据此渲染题录卡并支持导出 BibTeX / RIS。
     */
    val paperMeta: PaperMeta = PaperMeta(),
    val read: Boolean = false,
    val starred: Boolean = false,
    val feedTitle: String = "",
    val feedIcon: String = "",
) {
    /** 是否有可展示的题录（阅读页决定要不要渲染题录卡） */
    val isPaper: Boolean get() = paperMeta.hasContent || doi.isNotBlank()
}

/**
 * 一个可订阅的期刊候选（v1.8.5.0）。
 *
 * 来自两处：内置顶刊预设（[com.novelreader.core.rss.JournalCatalog]）与
 * Crossref 期刊检索（用户想订阅任意刊）。两者都用同一套虚拟订阅地址，
 * 交给 [RssRepository.addFeed] 即可订阅。
 */
data class JournalCandidate(
    /** Crossref 首选 ISSN */
    val issn: String,
    /** 全部 ISSN（含电子版） */
    val issns: List<String> = emptyList(),
    val title: String = "",
    /** 中文简称（命中内置预设时才有，否则为空） */
    val shortName: String = "",
    val publisher: String = "",
    /** 学科分类（仅内置预设有） */
    val field: String = "",
    /** Crossref 收录量，越大越可能是正牌刊 */
    val totalDois: Int = 0,
    /** 是否是内置顶刊预设 */
    val isPreset: Boolean = false,
) {
    /**
     * 虚拟订阅地址（形如 `crossref:issn:0028-0836`）。
     *
     * 不是真实 URL：解析方在 `com.novelreader.core.rss.JournalFeed`，
     * 这里重复一次前缀只为让领域层不依赖 core（改前缀时同时改两边，
     * 由 `JournalCandidateTest` 断言两边一致）。
     */
    val subscribeUrl: String get() = "crossref:issn:${issn.trim()}"

    /** 列表里显示的名字：优先中文简称，其次期刊全名 */
    val displayName: String get() = shortName.ifBlank { title.ifBlank { issn } }
}

/**
 * 订阅 / 刷新单个源的结果。
 *
 * 不做异常抛出：RSS 是「批量抓取」场景，一个源挂了不该中断其余源，
 * 因此失败以 [error] 形式返回，由 UI 汇总提示。
 */
data class RssFeedResult(
    val feedId: Long,
    val title: String,
    val url: String,
    /** 本次新增文章数 */
    val newArticles: Int = 0,
    /**
     * 本次从源里一共解析出多少条（含库里已存在的，v1.8.4.2）。
     *
     * 用来区分两种「新增 0 篇」：**源本身就是空的**（PubMed 检索式没有结果、
     * 博客停更）和**源有内容但没有新内容**。以前两者提示一样，用户没法判断
     * 是订阅坏了还是源侧没东西。
     */
    val fetchedArticles: Int = 0,
    /** 失败原因（空 = 成功） */
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    /** 抓取成功但源里一条都没有 —— 不是失败，但值得单独提示 */
    val emptyFeed: Boolean get() = ok && fetchedArticles == 0
}
