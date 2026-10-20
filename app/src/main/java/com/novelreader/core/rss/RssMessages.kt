package com.novelreader.core.rss

import com.novelreader.domain.model.RssFeedResult

/**
 * 订阅抓取结果的**提示文案**（v1.8.4.2）。
 *
 * 抽成纯函数是因为这里的措辞直接决定用户能不能看懂发生了什么：
 * 「源抓到了但一条都没有」和「抓取失败」以前提示几乎一样（都是「新增 0 篇」），
 * 用户只会反复重订阅。线上原型：PubMed 的 `rss/search/<id>/` 返回 200 +
 * 合法 `<rss><channel>`，但 `E-utilities` 查同一个词就是 `count: 0` ——
 * 源侧真的没内容，重订阅一百次也还是 0。
 */
object RssMessages {

    /** 添加订阅（可一次多个地址）后的汇总提示 */
    fun summarize(results: List<RssFeedResult>): String {
        if (results.isEmpty()) return "没有识别到可用的订阅地址"
        val ok = results.filter { it.ok }
        val bad = results.filterNot { it.ok }
        val empty = ok.filter { it.emptyFeed }
        val filled = ok.filterNot { it.emptyFeed }
        val parts = mutableListOf<String>()
        if (filled.isNotEmpty()) {
            parts += "已订阅 ${filled.size} 个源，新增 ${filled.sumOf { it.newArticles }} 篇"
        }
        // 抓取成功但源里一条都没有：不是失败，可用户看到的就是「没有文章」，
        // 必须说清楚是源侧为空，而不是订阅坏了
        empty.forEach { parts += "《${it.title.ifBlank { it.url }}》已订阅，但源里返回 0 篇" }
        if (empty.isNotEmpty()) parts += "该源当前没有内容，可稍后刷新或换一个订阅地址"
        bad.take(3).forEach { parts += "订阅失败：${it.error}" }
        if (bad.size > 3) parts += "另有 ${bad.size - 3} 个失败"
        return parts.joinToString("\n")
    }

    /** 刷新单个源后的提示 */
    fun refreshOne(result: RssFeedResult): String = when {
        result.emptyFeed -> "《${result.title}》刷新完成，但源里返回 0 篇"
        result.ok -> "已更新《${result.title}》，新增 ${result.newArticles} 篇"
        else -> "《${result.title}》刷新失败：${result.error}"
    }

    /** 批量刷新后的提示 */
    fun refreshAll(results: List<RssFeedResult>): String {
        val newCount = results.sumOf { it.newArticles }
        val failed = results.count { !it.ok }
        val emptyCount = results.count { it.emptyFeed }
        return buildString {
            append("已更新 ${results.size - failed}/${results.size} 个源，新增 $newCount 篇")
            if (emptyCount > 0) append("，$emptyCount 个源没有内容")
            if (failed > 0) append("，$failed 个失败")
        }
    }
}
