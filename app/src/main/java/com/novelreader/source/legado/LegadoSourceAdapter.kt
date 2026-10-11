package com.novelreader.source.legado

import com.novelreader.core.network.HttpClient
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
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
 * - {{$.字段}} 当前 JSON 节点的字段（JSON 源）
 * - {{baseUrl}} 源基础地址
 * - 目录/正文地址直接为绝对 URL，或用 bookSourceUrl 拼接
 */
class LegadoSourceAdapter(
    override val info: SourceInfo,
    private val source: LegadoBookSource,
) : SourceAdapter {

    private val headers: Map<String, String> = source.parseHeaders()
    // 清理 baseUrl：去掉末尾的 # 和 /
    private val baseUrl: String = source.url.trim().removeSuffix("#").trimEnd('/')

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
            // 清理简介中的 HTML 标签（legado intro 规则常插入 <br>）
            val intro = (fields["intro"] ?: fields["desc"])
                ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim()
            MediaItem(
                id = detailUrl.ifBlank { title },
                title = title,
                cover = LegadoRuleEngine.toAbsoluteUrl(baseUrl, fields["coverUrl"]),
                author = fields["author"],
                desc = intro,
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
    private fun extractJsonResults(
        content: String,
        rule: Map<String, String>,
        contextUrl: String = baseUrl,
    ): List<Map<String, String>> {
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
                    renderJsonRule(r, node, contextUrl) ?: ""
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 渲染 JSON 规则：支持纯字段名、@json: 路径、{{$.字段}} 模板、{{baseUrl}}、## 正则净化及简单 JS 表达式。
     *
     * 规则格式（legado 标准）：
     * - "title"                     → 纯字段名
     * - "@json:data.name"           → JSON 路径
     * - "http://x/{{$.book_id}}"    → 模板
     * - "chapter_title##正则"       → 取字段后正则删除
     * - "intro##正则##$1<br>"       → 取字段后正则替换
     *
     * @param rule 规则字符串
     * @param node 当前 JSON 节点
     * @param contextUrl 用于 {{baseUrl}} 替换的上下文 URL（目录章节场景为 tocUrl）
     */
    private fun renderJsonRule(rule: String, node: com.google.gson.JsonElement, contextUrl: String = baseUrl): String? {
        if (rule.isBlank()) return null

        // 拆分 ## 正则段（第一段是取值表达式，后续段是 正则[##替换]）
        val parts = rule.split("##")
        val expr = parts[0].trim()

        var value: String? = when {
            // 取值表达式为空（如 "##正则"）→ 取节点文本
            expr.isBlank() -> if (node.isJsonPrimitive) node.asString else null
            expr.startsWith("@json:") -> extractJsonPath(node, expr.substringAfter("@json:"))
            expr.contains("{{") -> renderJsonTemplate(expr, node, contextUrl)
            else -> extractJsonPath(node, expr)
        }

        // 应用 ## 正则净化/替换
        if (parts.size > 1 && value != null) {
            value = applyLegadoReplace(value, parts.drop(1))
        }
        return value
    }

    /** 应用 legado ## 正则替换（支持 正则##替换，替换中可用 $1 分组引用） */
    private fun applyLegadoReplace(value: String, parts: List<String>): String {
        return runCatching {
            val regex = parts[0]
            val replacement = parts.getOrElse(1) { "" }
            value.replace(Regex(regex), replacement)
        }.getOrDefault(value)
    }

    /**
     * 渲染含 {{...}} 的模板字符串，支持：
     * - {{$.字段.子字段}} → 从当前 JSON 节点取值
     * - {{baseUrl}} → 上下文 URL（默认源地址，目录章节场景为 tocUrl）
     * - {{baseUrl.replace('a','b')}} → 简单 JS 表达式（用 Rhino）
     */
    private fun renderJsonTemplate(template: String, node: com.google.gson.JsonElement, contextUrl: String = baseUrl): String? {
        return runCatching {
            var result = template
            // 逐段替换 {{...}} 表达式
            val regex = Regex("\\{\\{(.*?)\\}\\}")
            val matches = regex.findAll(template).toList()
            for (m in matches) {
                val expr = m.groupValues[1].trim()
                val value = evalTemplateExpr(expr, node, contextUrl)
                result = result.replace(m.value, value ?: "")
            }
            result
        }.getOrNull()
    }

    /** 求值单个 {{...}} 内的表达式 */
    private fun evalTemplateExpr(expr: String, node: com.google.gson.JsonElement, contextUrl: String = baseUrl): String? {
        // {{$.字段}} → JSON 取值
        if (expr.startsWith("$.")) {
            return extractJsonPath(node, expr.removePrefix("$."))
        }
        // {{baseUrl}} → 上下文 URL
        if (expr == "baseUrl") {
            return contextUrl
        }
        // {{baseUrl.replace('a','b')}} 等简单表达式 → 用 Rhino
        if (expr.contains("baseUrl") || expr.contains("$")) {
            return runCatching {
                val ctx = org.mozilla.javascript.Context.enter()
                try {
                    ctx.optimizationLevel = -1
                    val scope = ctx.initSafeStandardObjects()
                    // 注入 $ 节点（转成可序列化对象）
                    val jsonStr = node.toString()
                    val jsJson = ctx.evaluateString(scope, "($jsonStr)", "<json>", 1, null)
                    scope.put("$", scope, jsJson)
                    scope.put("baseUrl", scope, contextUrl)
                    val v = ctx.evaluateString(scope, "($expr)", "<expr>", 1, null)
                    org.mozilla.javascript.Context.toString(v)
                } finally {
                    org.mozilla.javascript.Context.exit()
                }
            }.getOrNull()
        }
        return null
    }

    /** 从 JSON 节点按路径（支持 a.b.c、a.b[0].c）取字符串 */
    private fun extractJsonPath(node: com.google.gson.JsonElement, path: String): String? {
        return runCatching {
            val cleaned = path.removePrefix("$").removePrefix(".").trim()
            if (cleaned.isEmpty()) {
                if (node.isJsonPrimitive) node.asString else null
            } else {
                var current: com.google.gson.JsonElement = node
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
                when {
                    current.isJsonPrimitive -> current.asString
                    current.isJsonNull -> null
                    else -> current.toString()
                }
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

        var cover = item.cover
        var author = item.author
        var desc = item.desc

        // 判断详情内容是否为 JSON
        val isJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")

        fun fieldValue(key: String): String? {
            val r = rule[key] ?: return null
            return if (isJson) {
                // JSON 详情：解析根节点（可能含 data 包装）后提取字段
                runCatching {
                    val root = com.google.gson.JsonParser.parseString(content)
                    val node = if (root.isJsonObject && root.asJsonObject.has("data")) {
                        root.asJsonObject.get("data")
                    } else root
                    renderJsonRule(r, node)
                }.getOrNull()
            } else {
                LegadoRuleEngine.extractString(content, r, baseUrl)
            }
        }

        cover = fieldValue("coverUrl") ?: cover
        author = fieldValue("author") ?: author
        desc = fieldValue("intro") ?: desc
        // 清理简介中的 HTML 标签
        desc = desc?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()

        return item.copy(cover = cover, author = author, desc = desc)
    }

    // ===== 目录 =====
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val rule = source.ruleToc
        if (rule.isEmpty()) return emptyList()

        val detailUrl = if (item.detailUrl.startsWith("http")) item.detailUrl
        else LegadoRuleEngine.toAbsoluteUrl(baseUrl, item.detailUrl) ?: return emptyList()

        val content = fetch(detailUrl) ?: return emptyList()

        // 目录可能有独立 URL：
        // 1. ruleToc.url
        // 2. ruleBookInfo.tocUrl（需渲染 {{baseUrl}}，此处的 baseUrl 指详情页 URL）
        val tocUrlRaw = rule["url"]
            ?: source.ruleBookInfo["tocUrl"]?.let { renderUrlTemplate(it, detailUrl) }
        val tocUrl = tocUrlRaw?.let { LegadoRuleEngine.toAbsoluteUrl(baseUrl, it) }
        val listContent = if (tocUrl != null && tocUrl != detailUrl) fetch(tocUrl) ?: content else content

        val listRule = rule["chapterList"] ?: rule["list"] ?: return emptyList()
        val fieldRules = rule

        val isJson = listRule.startsWith("@json:") ||
            listContent.trimStart().startsWith("{") || listContent.trimStart().startsWith("[")

        // JSON 目录时，{{baseUrl}} 指代目录实际请求 URL（tocUrl），供 chapterUrl 模板使用
        val jsonContextUrl = tocUrl ?: detailUrl

        val list = if (isJson) {
            extractJsonResults(listContent, rule, jsonContextUrl)
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

    /** 渲染仅含 {{baseUrl}} 等简单占位符的 URL 模板（baseUrl 用给定上下文替换） */
    private fun renderUrlTemplate(template: String, contextUrl: String): String {
        return template.replace("{{baseUrl}}", contextUrl)
            .replace("{{baseUrl.replace('?paging=0','')}}", contextUrl.replace("?paging=0", ""))
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
        if (contentRule.isBlank()) {
            // 无规则时取 body 文本
            return ContentResult.novel(Jsoup.parse(content).body()?.text() ?: "")
        }

        // JSON 正文（如 {"code":200,"data":{"content":"..."}}）
        // 注意：content 规则通常自带 data 前缀（如 "data.content"），相对根节点解析
        val isJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")
        val text = if (isJson) {
            runCatching {
                val root = com.google.gson.JsonParser.parseString(content)
                renderJsonRule(contentRule, root)
            }.getOrNull() ?: ""
        } else {
            // 正文规则可能是 @css:xxx 或常规选择器
            LegadoRuleEngine.extractString(content, contentRule, baseUrl) ?: ""
        }
        // ## 正则净化（非 JSON 路径下已由 renderJsonRule 处理；此处兜底 HTML 路径）
        val cleaned = if (!isJson) {
            rule["replaceRegex"]?.let { regex ->
                runCatching { text.replace(Regex(regex), "") }.getOrDefault(text)
            } ?: text
        } else text
        return ContentResult.novel(cleaned)
    }

    /** 提取漫画图片列表 */
    private fun extractComicImages(content: String, rule: Map<String, String>): ContentResult {
        val contentRule = rule["content"] ?: rule["images"] ?: ""
        if (contentRule.isBlank()) return ContentResult.comic(emptyList())

        // JSON 正文（如 {"data":{"images":[...]}}）：规则相对根节点解析
        val isJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")
        if (isJson) {
            return runCatching {
                val root = com.google.gson.JsonParser.parseString(content)
                val rendered = renderJsonRule(contentRule, root)
                // 渲染结果可能是 JSON 数组字符串或逗号分隔的 URL 列表
                val urls = parseImageList(rendered)
                ContentResult.comic(urls)
            }.getOrDefault(ContentResult.comic(emptyList()))
        }

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

    /** 解析渲染后的图片列表（JSON 数组字符串 / 换行或逗号分隔的 URL） */
    private fun parseImageList(rendered: String?): List<String> {
        if (rendered.isNullOrBlank()) return emptyList()
        val trimmed = rendered.trim()
        return runCatching {
            // 尝试作为 JSON 数组解析
            if (trimmed.startsWith("[")) {
                com.google.gson.JsonParser.parseString(trimmed).asJsonArray
                    .map { it.asString }
            } else emptyList()
        }.getOrElse {
            // 退化为按换行/逗号分隔
            trimmed.split("\n", ",")
                .map { it.trim() }
                .filter { it.startsWith("http") }
        }.map { LegadoRuleEngine.toAbsoluteUrl(baseUrl, it) ?: it }
    }

    /** 提取视频播放地址 */
    private fun extractVideoUrl(content: String, rule: Map<String, String>): ContentResult {
        val contentRule = rule["content"] ?: rule["videoUrl"] ?: ""
        val isJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")
        val videoUrl = when {
            contentRule.isBlank() -> null
            isJson -> runCatching {
                val root = com.google.gson.JsonParser.parseString(content)
                renderJsonRule(contentRule, root)
            }.getOrNull()
            else -> LegadoRuleEngine.extractString(content, contentRule, baseUrl)
        }
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
