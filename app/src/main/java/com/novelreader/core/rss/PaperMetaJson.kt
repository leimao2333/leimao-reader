package com.novelreader.core.rss

import com.google.gson.Gson

/**
 * [PaperMeta] 的 JSON 序列化（v1.8.5.0）。
 *
 * 题录之所以要落库：Crossref 抓回来的题录在「刷新全部」时会被重新拉一遍，
 * 但**题录不是每次都有**（Crossref 的 abstract 只有部分刊提供，official RSS 的
 * Prism 字段也可能整站下线）。入库后阅读页、引用导出、题录卡就不再依赖网络。
 *
 * 为什么用 JSON 而不是拆列：[PaperMeta] 的字段以后只会加不会少（基金号、
 * 引用数、ORCID、文章类型细分…），每加一个字段就要做一次 Room 迁移；
 * 一列 JSON 加字段零迁移成本，而题录只在阅读页反序列化一次，性能无关紧要。
 */
object PaperMetaJson {

    private val gson: Gson = Gson()

    /** 序列化；空题录返回空串（省得给几百万条普通博客文章都写一个 `{}`） */
    fun toJson(meta: PaperMeta): String {
        if (!meta.hasContent) return ""
        return runCatching { gson.toJson(meta) }.getOrDefault("")
    }

    /** 反序列化；空串 / 解析失败一律返回空题录（不能让一篇脏数据把阅读页搞崩） */
    fun fromJson(json: String?): PaperMeta {
        val s = json?.trim().orEmpty()
        if (s.isEmpty()) return PaperMeta()
        return runCatching { gson.fromJson(s, PaperMeta::class.java) }.getOrNull() ?: PaperMeta()
    }
}
