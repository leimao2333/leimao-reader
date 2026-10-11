package com.novelreader.source.legado

import com.novelreader.core.network.HttpClient
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.source.engine.PlaceholderEngine
import org.jsoup.Jsoup

/**
 * legado 书源适配器。
 *
 * 实现 [SourceAdapter] 接口，用 legado 书源的规则完成
 * search / detail / chapterList / content 的标准化实现。
 *
 * legado 书源的占位符与自研格式略有差异：
 * - {{key}} 搜索关键词（同 {{keyword}}）
 * - {{page}} 页码
 * - 目录/正文地址直接为绝对 URL，或用 bookSourceUrl 拼接
 */
class LegadoSourceAdapter(
    override val info: SourceInfo,
    private val source: LegadoBookSource,
) : SourceAdapter {

    private val headers: Map<String, String> = source.parseHeaders()
    private val baseUrl: String = source.url.trimEnd('/')

    // ===== 搜索 =====
    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        if (source.searchUrl.isBlank()) return emptyList()

        val url = renderUrl(source.searchUrl, keyword, page)
        val content = fetch(url)
        if (content.isNullOrBlank()) return emptyList()

        // searchUrl 可能形如 "url,{json}"（逗号后是请求配置），取逗号前
        val actualUrl = url.substringBefore(",")

        // 判断响应是否为 JSON（@json 规则或 JSON 内容）
        val isJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")

        val listRule = source.ruleSearch["bookList"] ?: source.ruleSearch["list"] ?: ""
        val fieldRules = source.ruleSearch

        val list = if (isJson || listRule.startsWith("@json:")) {
            extractJsonResults(content, source.ruleSearch)
        } else {
            LegadoRuleEngine.extractList(content, listRule, fieldRules, baseUrl)
        }

        return list.map { fields ->
            val title = fields["name"] ?: fields["title"] ?: ""
            val bookUrl = fields["bookUrl"] ?: fields["url"] ?: ""
            val detailUrl = LegadoRuleEngine.toAbsoluteUrl(baseUrl, bookUrl) ?: bookUrl
            MediaItem(
                id = detailUrl.ifBlank { title },
                title = title,
                cover = LegadoRuleEngine.toAbsoluteUrl(baseUrl, fields["coverUrl"]),
                author = fields["author"],
                desc = fields["intro"] ?: fields["desc"],
                type = info.type,
                detailUrl = detailUrl,
                sourceId = info.id,
                extras = mapOf(
                    "kind" to (fields["kind"] ?: ""),
                    "lastChapter" to (fields["lastChapter"] ?: ""),
                ),
            )
        }.filter { it.title.isNotBlank() }
    }

    /** 处理 JSON 响应的搜索（@json 规则） */
    private fun extractJsonResults(content: String, rule: Map<String, String>): List<Map<String, String>> {
        return runCatching {
            val root = com.google.gson.JsonParser.parseString(content)
            // 尝试从 data 字段取数组
            val arr = if (root.isJsonObject && root.asJsonObject.has("data")) {
                root.asJsonObject.get("data")
            } else root
            val list = if (arr.isJsonArray) arr.asJsonArray.toList()
            else emptyList<com.google.gson.JsonElement>()

            list.map { node ->
                rule.mapValues { (_, r) ->
                    extractJsonField(node, r) ?: ""
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractJsonField(node: com.google.gson.JsonElement, rule: String): String? {
        val path = if (rule.startsWith("@json:")) rule.substringAfter("@json:") else rule
        return runCatching {
            var current = node
            val cleaned = path.removePrefix("$").removePrefix(".").trim()
            if (cleaned.isEmpty()) {
                if (current.isJsonPrimitive) current.asString else null
            } else {
                val tokens = cleaned.split(Regex("[.\\[\\]]")).filter { it.isNotBlank() }
                for (t in tokens) {
                    current = when {
                        current.isJsonObject -> current.asJsonObject.get(t) ?: return null
                        current.isJsonArray -> {
                            val idx = t.toIntOrNull() ?: return null
                            val arr = current.asJsonArray
                            if (idx < 0 || idx >= arr.size()) return null
                            arr.get(idx)
                        }
                        else -> return null
                    }
                }
                if (current.isJsonPrimitive) current.asString else current?.toString()
            }
        }.getOrNull()
    }

    // ===== 详情 =====
    override suspend fun detail(item: MediaItem): MediaItem {
        val rule = source.ruleBookInfo
        if (rule.isEmpty()) return item

        val url = if (item.detailUrl.startsWith("http")) item.detailUrl
        else LegadoRuleEngine.toAbsoluteUrl(baseUrl, item.detailUrl) ?: return item

        val content = fetch(url) ?: return item
        val doc = Jsoup.parse(content)

        var cover = item.cover
        var author = item.author
        var desc = item.desc

        fun fieldValue(key: String): String? {
            val r = rule[key] ?: return null
            return LegadoRuleEngine.extractString(content, r, baseUrl)
        }

        cover = fieldValue("coverUrl") ?: cover
        author = fieldValue("author") ?: author
        desc = fieldValue("intro") ?: desc

        return item.copy(cover = cover, author = author, desc = desc)
    }

    // ===== 目录 =====
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val rule = source.ruleToc
        if (rule.isEmpty()) return emptyList()

        val detailUrl = if (item.detailUrl.startsWith("http")) item.detailUrl
        else LegadoRuleEngine.toAbsoluteUrl(baseUrl, item.detailUrl) ?: return emptyList()

        val content = fetch(detailUrl) ?: return emptyList()

        // 目录可能有独立 URL（ruleToc.url），否则用详情页
        val tocUrl = rule["url"]?.let { LegadoRuleEngine.toAbsoluteUrl(baseUrl, it) }
        val listContent = if (tocUrl != null && tocUrl != detailUrl) fetch(tocUrl) ?: content else content

        val listRule = rule["chapterList"] ?: rule["list"] ?: return emptyList()
        val fieldRules = rule

        val isJson = listRule.startsWith("@json:") ||
            listContent.trimStart().startsWith("{") || listContent.trimStart().startsWith("[")

        val list = if (isJson) {
            extractJsonResults(listContent, rule)
        } else {
            LegadoRuleEngine.extractList(listContent, listRule, fieldRules, baseUrl)
        }

        return list.mapIndexed { index, fields ->
            val name = fields["chapterName"] ?: fields["name"] ?: fields["title"] ?: "第${index + 1}章"
            val chapterUrlRaw = fields["chapterUrl"] ?: fields["url"] ?: ""
            val chapterUrl = LegadoRuleEngine.toAbsoluteUrl(baseUrl, chapterUrlRaw) ?: chapterUrlRaw
            ChapterItem(
                id = chapterUrl.ifBlank { "$index" },
                title = name,
                url = chapterUrl,
                index = index,
                sourceId = info.id,
            )
        }
    }

    // ===== 正文 =====
    override suspend fun content(chapter: ChapterItem): ContentResult {
        val rule = source.ruleContent
        if (rule.isEmpty()) return ContentResult()

        val url = if (chapter.url.startsWith("http")) chapter.url
        else LegadoRuleEngine.toAbsoluteUrl(baseUrl, chapter.url) ?: return ContentResult()

        val content = fetch(url) ?: return ContentResult()

        return when (info.type) {
            com.novelreader.domain.model.SourceType.COMIC -> {
                extractComicImages(content, rule)
            }
            com.novelreader.domain.model.SourceType.VIDEO -> {
                extractVideoUrl(content, rule)
            }
            else -> {
                extractNovelText(content, rule)
            }
        }
    }

    /** 提取小说正文 */
    private fun extractNovelText(content: String, rule: Map<String, String>): ContentResult {
        val contentRule = rule["content"] ?: rule["text"] ?: ""
        val text = if (contentRule.isBlank()) {
            // 无规则时取 body 文本
            Jsoup.parse(content).body()?.text() ?: ""
        } else {
            // 正文规则可能是 @css:xxx 或常规选择器
            LegadoRuleEngine.extractString(content, contentRule, baseUrl) ?: ""
        }
        // 正则净化
        val cleaned = rule["replaceRegex"]?.let { regex ->
            runCatching { text.replace(Regex(regex), "") }.getOrDefault(text)
        } ?: text
        return ContentResult.novel(cleaned)
    }

    /** 提取漫画图片列表 */
    private fun extractComicImages(content: String, rule: Map<String, String>): ContentResult {
        val contentRule = rule["content"] ?: rule["images"] ?: ""
        if (contentRule.isBlank()) return ContentResult.comic(emptyList())

        val doc = Jsoup.parse(content)
        val images = mutableListOf<String>()

        // 支持 @css: 选择器提取多图
        if (contentRule.startsWith("@css:")) {
            val selector = contentRule.substringAfter("@css:")
            val imgAttr = rule["imageStyle"] ?: "src"
            doc.select(selector).forEach { el ->
                val src = when (imgAttr.uppercase()) {
                    "FULL" -> el.attr("src").ifEmpty { el.attr("data-src").ifEmpty { el.attr("data-original") } }
                    else -> el.attr(imgAttr.lowercase()).ifEmpty { el.attr("src") }
                }
                LegadoRuleEngine.toAbsoluteUrl(baseUrl, src)?.let { images.add(it) }
            }
        } else {
            // 常规选择器（如 class.xx@tag.img@html 或直接 html 内含 img）
            val html = LegadoRuleEngine.extractString(content, contentRule, baseUrl)
            if (html != null) {
                val subDoc = Jsoup.parse(html)
                subDoc.select("img").forEach { img ->
                    val src = img.attr("src").ifEmpty { img.attr("data-src").ifEmpty { img.attr("data-original") } }
                    LegadoRuleEngine.toAbsoluteUrl(baseUrl, src)?.let { images.add(it) }
                }
            }
        }
        return ContentResult.comic(images)
    }

    /** 提取视频播放地址 */
    private fun extractVideoUrl(content: String, rule: Map<String, String>): ContentResult {
        val contentRule = rule["content"] ?: rule["videoUrl"] ?: ""
        val videoUrl = if (contentRule.isBlank()) null
        else LegadoRuleEngine.extractString(content, contentRule, baseUrl)
        val abs = LegadoRuleEngine.toAbsoluteUrl(baseUrl, videoUrl) ?: videoUrl ?: ""
        return ContentResult.video(abs, headers)
    }

    // ===== 有效性自检 =====
    override suspend fun test(): Boolean {
        return runCatching {
            if (source.searchUrl.isNotBlank()) {
                search("测试", 1).isNotEmpty()
            } else true
        }.getOrDefault(true)
    }

    /** 渲染 URL（处理 {{key}}/{{keyword}}/{{page}} 占位） */
    private fun renderUrl(template: String, keyword: String, page: Int): String {
        val values = mapOf(
            "key" to keyword,
            "keyword" to keyword,
            "page" to page.toString(),
            "searchKey" to keyword,
        )
        var result = template
        values.forEach { (k, v) ->
            result = result.replace("{{$k}}", java.net.URLEncoder.encode(v, "UTF-8"))
        }
        return result
    }

    /** 带请求头抓取 */
    private suspend fun fetch(url: String): String? {
        return runCatching {
            HttpClient.get(url, headers)
        }.getOrNull()
    }
}
