package com.novelreader.source.parser

import com.novelreader.core.network.HttpClient
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.engine.HtmlSelectorEngine
import com.novelreader.source.engine.JsonRuleSet
import com.novelreader.source.engine.PlaceholderEngine

/**
 * JSON 源适配器。
 *
 * 基于 [JsonRuleSet] 规则集，通过「占位符替换 + HTTP 请求 + CSS 选择器提取」
 * 完成 search / detail / chapterList / content 的标准化实现。
 */
class JsonSourceAdapter(
    override val info: SourceInfo,
    private val ruleSet: JsonRuleSet,
) : SourceAdapter {

    private val type: SourceType = info.type

    // ===== 搜索 =====
    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        val rule = ruleSet.search ?: return emptyList()
        val url = PlaceholderEngine.render(
            rule.url,
            mapOf("keyword" to keyword, "page" to page.toString()),
        )
        val html = HttpClient.get(url)
        val list = HtmlSelectorEngine.extractList(html, rule.list ?: "", rule.fields)

        return list.map { fields ->
            MediaItem(
                id = fields["id"] ?: fields["url"] ?: "",
                title = fields["title"] ?: "",
                // v1.8.3.0：清洗封面（见 CoverUrl，JS undefined 拼出的假地址要清空）
                cover = com.novelreader.core.util.CoverUrl.sanitize(
                    absoluteUrl(ruleSet.baseUrl, fields["cover"]),
                ),
                author = fields["author"],
                desc = fields["desc"],
                type = type,
                detailUrl = absoluteUrl(ruleSet.baseUrl, fields["id"]) ?: fields["id"] ?: "",
                sourceId = info.id,
            )
        }
    }

    // ===== 详情 =====
    override suspend fun detail(item: MediaItem): MediaItem {
        val rule = ruleSet.detail ?: return item
        val url = PlaceholderEngine.render(rule.url, mapOf("itemUrl" to item.detailUrl))
        val html = HttpClient.get(url)

        var cover = item.cover
        var author = item.author
        var desc = item.desc
        rule.fields.forEach { (key, expr) ->
            val value = HtmlSelectorEngine.extract(html, expr)
            when (key) {
                "cover" -> com.novelreader.core.util.CoverUrl.sanitize(
                    absoluteUrl(ruleSet.baseUrl, value),
                )?.let { cover = it }
                "author" -> author = value ?: author
                "desc" -> desc = value ?: desc
                "title" -> Unit // 标题一般不变，忽略
                else -> Unit
            }
        }
        return item.copy(cover = cover, author = author, desc = desc)
    }

    // ===== 目录 =====
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val rule = ruleSet.chapterList ?: return emptyList()
        val url = PlaceholderEngine.render(rule.url, mapOf("detailUrl" to item.detailUrl))
        val html = HttpClient.get(url)
        val list = HtmlSelectorEngine.extractList(html, rule.list ?: "", rule.fields)

        return list.mapIndexed { index, fields ->
            val chapterUrl = absoluteUrl(ruleSet.baseUrl, fields["id"] ?: fields["url"])
            ChapterItem(
                id = chapterUrl ?: fields["id"] ?: "",
                title = fields["title"] ?: "",
                url = chapterUrl ?: "",
                index = index,
                sourceId = info.id,
            )
        }
    }

    // ===== 正文 =====
    override suspend fun content(chapter: ChapterItem): ContentResult {
        val rule = ruleSet.content ?: return ContentResult()
        val url = PlaceholderEngine.render(rule.url, mapOf("chapterUrl" to chapter.url))
        val html = HttpClient.get(url)

        return when {
            // 小说：提取正文文本
            rule.text != null -> {
                val raw = HtmlSelectorEngine.extract(html, rule.text) ?: ""
                val cleaned = HtmlSelectorEngine.removeElements(raw, rule.remove)
                ContentResult.novel(cleaned)
            }
            // 漫画：提取图片列表
            rule.images != null -> {
                val images = extractImageList(html, rule.images)
                ContentResult.comic(images)
            }
            // 视频：提取播放地址
            rule.videoUrl != null -> {
                val videoUrl = HtmlSelectorEngine.extract(html, rule.videoUrl)
                val absUrl = absoluteUrl(ruleSet.baseUrl, videoUrl) ?: videoUrl ?: ""
                ContentResult.video(absUrl, rule.headers)
            }
            else -> ContentResult()
        }
    }

    /** 提取漫画图片列表（从多个 img 元素提取 src） */
    private fun extractImageList(html: String, imagesExpr: String): List<String> {
        val doc = HtmlSelectorEngine.parse(html)
        val (selector, attr) = HtmlSelectorEngine.parseExpression(imagesExpr)
        val elements = if (selector.isEmpty()) doc.select("img") else doc.select(selector)
        return elements.mapNotNull { element ->
            val src = when (attr.lowercase()) {
                "src" -> element.attr("src")
                "data-src", "data-original" -> element.attr(attr).ifEmpty { element.attr("src") }
                else -> element.attr(attr)
            }
            absoluteUrl(ruleSet.baseUrl, src)
        }
    }

    // ===== 有效性自检 =====
    override suspend fun test(): Boolean {
        return runCatching {
            search("test", 1)
            true
        }.getOrDefault(false)
    }

    private fun absoluteUrl(baseUrl: String, url: String?): String? =
        HtmlSelectorEngine.toAbsoluteUrl(baseUrl, url)
}
