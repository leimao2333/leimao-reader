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

        // searchUrl 可能形如 "url,{请求配置JSON}"（含 method/body/charset/headers），
        // 或以 @js: 开头（执行 JS 得到 URL，可再带 ,{配置}）
        val (urlTemplate, requestConfig) = if (source.searchUrl.trimStart().startsWith("@js:")) {
            val js = source.searchUrl.trimStart().substringAfter("@js:")
            val jsResult = evalSearchUrlJs(js, keyword, page) ?: return emptyList()
            splitRequestConfig(jsResult)
        } else {
            splitRequestConfig(source.searchUrl)
        }

        val charset = requestConfig?.get("charset")?.asString?.takeIf { it.isNotBlank() }
        var url = renderUrl(urlTemplate, keyword, page, charset)
        if (!url.startsWith("http")) {
            url = LegadoRuleEngine.toAbsoluteUrl(baseUrl, url) ?: url
        }

        // 合并请求头：源级 headers + 请求配置中的 headers（UA/Referer 等，防盗链）
        val reqHeaders = buildMap {
            putAll(headers)
            requestConfig?.get("headers")?.let { h ->
                if (h.isJsonObject) {
                    h.asJsonObject.entrySet().forEach { (k, v) ->
                        if (v.isJsonPrimitive) put(k, v.asString)
                    }
                }
            }
        }

        val content = runCatching {
            val method = requestConfig?.get("method")?.asString?.uppercase() ?: "GET"
            if (method == "POST") {
                val bodyTemplate = requestConfig?.get("body")?.asString ?: ""
                // body 中的 {{key}} 按请求字符集编码（GBK 源语义，与 legado 一致）
                val body = renderUrl(bodyTemplate, keyword, page, charset)
                val contentType = if (bodyTemplate.trimStart().startsWith("{")) {
                    "application/json; charset=utf-8"
                } else {
                    "application/x-www-form-urlencoded"
                }
                HttpClient.post(url, body, reqHeaders, charset, contentType)
            } else {
                HttpClient.get(url, reqHeaders, charset)
            }
        }.getOrNull()
        if (content.isNullOrBlank()) return emptyList()

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
                extras = buildMap {
                    fields["kind"]?.let { put("kind", it) }
                    fields["lastChapter"]?.let { put("lastChapter", it) }
                    // 搜索元数据（#23/#25）：state/wordCount/lastUpdate 一并带出，供结果卡片展示
                    fields["status"]?.let { put("status", it) }
                    fields["wordCount"]?.let { put("wordCount", it) }
                    fields["lastUpdate"]?.let { put("lastUpdate", it) }
                    fields["totalChapter"]?.let { put("totalChapter", it) }
                },
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

        // 详情页元信息（#27）：状态 / 字数 / 最新章节 / 更新时间
        // 搜索阶段未带出的字段，在此从详情规则补齐（缺失则保留搜索时的值）
        val extras = item.extras.toMutableMap()
        fieldValue("kind")?.takeIf { it.isNotBlank() }?.let { extras["status"] = it }
        fieldValue("wordCount")?.takeIf { it.isNotBlank() }?.let { extras["wordCount"] = it }
        fieldValue("lastUpdate")?.takeIf { it.isNotBlank() }?.let { extras["lastUpdate"] = it }
        fieldValue("lastChapter")?.takeIf { it.isNotBlank() }?.let { extras["lastChapter"] = it }

        return item.copy(cover = cover, author = author, desc = desc, extras = extras)
    }

    // ===== 目录 =====
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val rule = source.ruleToc
        if (rule.isEmpty()) return emptyList()

        val detailUrl = if (item.detailUrl.startsWith("http")) item.detailUrl
        else LegadoRuleEngine.toAbsoluteUrl(baseUrl, item.detailUrl) ?: return emptyList()

        val content = fetch(detailUrl) ?: return emptyList()

        // 目录可能有独立 URL：
        // 1. ruleToc.url（URL 模板或选择器规则）
        // 2. ruleBookInfo.tocUrl（需从详情页渲染/提取，baseUrl 指详情页 URL）
        val tocUrlRaw = rule["url"]?.takeIf { it.isNotBlank() }
            ?: source.ruleBookInfo["tocUrl"]?.takeIf { it.isNotBlank() }
        val tocUrl = tocUrlRaw?.let { resolveTocUrl(it, content, detailUrl) }
            ?.let { LegadoRuleEngine.toAbsoluteUrl(detailUrl, it) }
        val firstPageUrl = tocUrl?.takeIf { it.isNotBlank() && it != detailUrl } ?: detailUrl
        val listContent = if (firstPageUrl != detailUrl) fetch(firstPageUrl) ?: content else content

        val listRule = rule["chapterList"] ?: rule["list"] ?: return emptyList()
        val fieldRules = rule

        val isJson = listRule.startsWith("@json:") ||
            listContent.trimStart().startsWith("{") || listContent.trimStart().startsWith("[")

        // JSON 目录时，{{baseUrl}} 指代目录实际请求 URL（tocUrl），供 chapterUrl 模板使用
        val jsonContextUrl = tocUrl ?: detailUrl

        if (isJson) {
            val list = extractJsonResults(listContent, rule, jsonContextUrl)
            return list.mapIndexed { index, fields ->
                fieldsToChapter(fields, index, jsonContextUrl)
            }
        }

        // HTML 目录：支持 nextTocUrl 翻页（legado 语义），循环抓取下一页并追加章节
        data class TocPage(val url: String, val content: String)
        val pages = mutableListOf(TocPage(firstPageUrl, listContent))
        val nextRule = rule["nextTocUrl"]?.takeIf { it.isNotBlank() }
        if (nextRule != null) {
            val visited = mutableSetOf(firstPageUrl)
            var guard = 0
            while (guard++ < 50) {
                val last = pages.last()
                val nextRaw = LegadoRuleEngine.extractString(last.content, nextRule, last.url) ?: break
                val nextUrl = LegadoRuleEngine.toAbsoluteUrl(last.url, nextRaw) ?: break
                if (!visited.add(nextUrl)) break
                val nextContent = fetch(nextUrl) ?: break
                pages.add(TocPage(nextUrl, nextContent))
            }
        }

        var index = 0
        return pages.flatMap { page ->
            LegadoRuleEngine.extractList(page.content, listRule, fieldRules, baseUrl).map { fields ->
                fieldsToChapter(fields, index++, page.url)
            }
        }
    }

    /** 目录字段 -> ChapterItem（pageBase 为章节锚点所在页面 URL，相对地址按其解析） */
    private fun fieldsToChapter(fields: Map<String, String>, index: Int, pageBase: String): ChapterItem {
        val name = fields["chapterName"] ?: fields["name"] ?: fields["title"] ?: "第${index + 1}章"
        val chapterUrlRaw = fields["chapterUrl"] ?: fields["url"] ?: ""
        val chapterUrl = LegadoRuleEngine.toAbsoluteUrl(pageBase, chapterUrlRaw) ?: chapterUrlRaw
        return ChapterItem(
            id = chapterUrl.ifBlank { "$index" },
            title = name,
            url = chapterUrl,
            index = index,
            sourceId = info.id,
        )
    }

    /** 渲染仅含 {{baseUrl}} 等简单占位符的 URL 模板（baseUrl 用给定上下文替换） */
    private fun renderUrlTemplate(template: String, contextUrl: String): String {
        return template.replace("{{baseUrl}}", contextUrl)
            .replace("{{baseUrl.replace('?paging=0','')}}", contextUrl.replace("?paging=0", ""))
    }

    /**
     * 解析目录地址。
     * - 含 `http` 或 `{{占位}}` → 视为 URL 模板，直接渲染；
     * - 否则视为「选择器规则」，从详情页中提取（如 `.yuedu@a@href##...`）。
     */
    private fun resolveTocUrl(raw: String, detailContent: String, detailUrl: String): String? {
        val r = raw.trim()
        if (r.isBlank()) return null
        return if (r.startsWith("http") || r.contains("{{")) {
            renderUrlTemplate(r, detailUrl)
        } else {
            LegadoRuleEngine.extractString(detailContent, r, detailUrl)
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
                extractNovelText(content, rule, url)
            }
        }
    }

    /** 提取小说正文 */
    private suspend fun extractNovelText(content: String, rule: Map<String, String>, pageUrl: String): ContentResult {
        val contentRule = rule["content"] ?: rule["text"] ?: ""
        if (contentRule.isBlank()) {
            // 无规则时取 body 文本
            return ContentResult.novel(normalizePlainText(Jsoup.parse(content).body()?.wholeText() ?: ""))
        }

        // JSON 正文（如 {"code":200,"data":{"content":"..."}}）
        // 注意：content 规则通常自带 data 前缀（如 "data.content"），相对根节点解析
        val isJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")

        // 单页正文提取（原始 HTML 片段或纯文本）
        fun extractPage(c: String): String = if (isJson) {
            runCatching {
                val root = com.google.gson.JsonParser.parseString(c)
                renderJsonRule(contentRule, root)
            }.getOrNull() ?: ""
        } else {
            LegadoRuleEngine.extractString(c, contentRule, baseUrl) ?: ""
        }

        var text = extractPage(content)

        // 正文翻页：nextContentUrl（legado 语义），循环抓取下一页正文并拼接
        val nextRule = rule["nextContentUrl"]?.takeIf { it.isNotBlank() }
        if (nextRule != null && !isJson) {
            val visited = mutableSetOf(pageUrl)
            var curUrl = pageUrl
            var curContent = content
            var guard = 0
            while (guard++ < 30) {
                val nextRaw = LegadoRuleEngine.extractString(curContent, nextRule, curUrl) ?: break
                val nextUrl = LegadoRuleEngine.toAbsoluteUrl(curUrl, nextRaw) ?: break
                if (!visited.add(nextUrl)) break
                val nextContent = fetch(nextUrl) ?: break
                text += "\n" + extractPage(nextContent)
                curContent = nextContent
                curUrl = nextUrl
            }
        }

        // ## 正则净化（非 JSON 路径下已由 renderJsonRule 处理；此处兜底 HTML 路径）
        val cleaned = if (!isJson) {
            val afterRegex = applySourceReplaceRegex(text, rule["replaceRegex"])
            // 正文规则常带 @html 提取（如 xxx@html），结果是 HTML 片段，
            // 必须转成纯文本（按块级标签保留段落换行），否则 div/br/&nbsp; 直接显示给用户
            htmlToPlainText(afterRegex)
        } else {
            // JSON 正文可能是含 <br>/&nbsp; 的 HTML 片段，同样归一化并保留缩进
            normalizePlainText(applyHtmlEntities(text))
        }
        return ContentResult.novel(cleaned)
    }

    /**
     * 应用书源级 replaceRegex 净化规则（legado 语义）：
     * - `##正则` → 删除匹配
     * - `##正则##替换` → 替换（支持 $1 分组）
     * - 无 ## 前缀 → 整串视为正则删除
     */
    private fun applySourceReplaceRegex(text: String, raw: String?): String {
        if (raw.isNullOrBlank()) return text
        val body = if (raw.startsWith("##")) raw.substring(2) else raw
        if (body.isBlank()) return text
        val regex = body.substringBefore("##")
        val replacement = body.substringAfter("##", "")
        return runCatching { text.replace(Regex(regex), replacement) }.getOrDefault(text)
    }

    /** 解码 JSON 正文里常见的 HTML 实体与 <br> 标签（保留段落与缩进） */
    private fun applyHtmlEntities(text: String): String {
        if (text.isBlank()) return text
        var t = text
        if (t.contains('<')) {
            t = runCatching {
                val doc = Jsoup.parse(t)
                doc.outputSettings().prettyPrint(false)
                doc.select("br").forEach { it.appendText("\n") }
                doc.select("p, div").forEach { it.appendText("\n") }
                doc.wholeText()
            }.getOrDefault(t)
        }
        return t.replace("&nbsp;", "\u3000")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    /**
     * HTML 片段转正文纯文本：
     * - br 视为换行；p/div 等块级标签边界视为段落换行；
     * - 其余标签剥除；HTML 实体（&nbsp; 等）由 Jsoup 自动解码。
     * 已经是纯文本（不含标签）时原样返回（避免误伤含 < 的正文）。
     *
     * 注意：**不要 trim 掉段首的全角空格**——中文小说源常用 `\u3000\u3000` 做首行缩进，
     * 旧实现对每行做了 `trim()`，把缩进一并删除，导致「段落没有首行缩进」。
     * 这里只压缩「行尾空白与行内连续空白」，保留行首缩进。
     */
    private fun htmlToPlainText(text: String): String {
        if (!text.contains('<')) return normalizePlainText(text)
        return runCatching {
            val doc = Jsoup.parse(text)
            doc.outputSettings().prettyPrint(false)
            // Jsoup 的 text() 会丢失段落边界，先用 br 换行占位再转
            doc.select("br").forEach { it.appendText("\n") }
            // 块级标签结束处补换行，让 <p>/<div> 之间形成分段
            doc.select("p, div").forEach { it.appendText("\n") }
            val raw = doc.wholeText()
            normalizePlainText(raw)
        }.getOrDefault(text)
    }

    /**
     * 规范化纯文本：保留每行行首缩进（全角/半角空格），仅压缩行尾与行内多余空白，
     * 并把连续空行折叠为单空行。
     */
    private fun normalizePlainText(raw: String): String {
        if (!raw.contains('\n')) return raw.trimEnd()
        return raw.split("\n")
            .joinToString("\n") { line ->
                // 1) 保留行首空白（缩进）；2) 行内多空格压缩为一个半角空格；3) 去掉行尾空白
                val leading = line.takeWhile { it == ' ' || it == '\t' || it == '\u3000' }
                val rest = line.substring(leading.length)
                    .replace(Regex("[ \\t\\u00A0]{2,}"), " ")
                    .trimEnd()
                leading + rest
            }
            // 连续 3 个以上换行折叠为 1 个空行
            .replace(Regex("\n{3,}"), "\n\n")
            .trim('\n')
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
        // 无搜索规则时无法联网自检，直接视为可用（避免误判）
        if (source.searchUrl.isBlank()) return true
        // 超时保护：个别源网络极慢，避免测试卡死；
        // 捕获 Throwable（含 Rhino/正则可能抛出的 Error），runCatching 只拦 Exception 拦不住 Error。
        return kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            runCatching {
                search("测试", 1).isNotEmpty()
            }.getOrDefault(false)
        } ?: false
    }

    /** 渲染 URL（处理 {{key}}/{{keyword}}/{{page}} 占位；关键词按 charset 编码，其余 {{表达式}} 用 Rhino 求值） */
    private fun renderUrl(template: String, keyword: String, page: Int, charset: String? = null): String {
        val enc = charset?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val simple = mapOf(
            "key" to java.net.URLEncoder.encode(keyword, enc.name()),
            "keyword" to java.net.URLEncoder.encode(keyword, enc.name()),
            "searchKey" to java.net.URLEncoder.encode(keyword, enc.name()),
            "searchPage" to page.toString(),
            "page" to page.toString(),
        )
        var result = template
        simple.forEach { (k, v) -> result = result.replace("{{$k}}", v) }
        // 其余 {{表达式}}（如 {{(page-1)*50}}）→ Rhino 求值
        if (result.contains("{{")) {
            result = Regex("\\{\\{(.*?)\\}\\}").replace(result) { m ->
                evalUrlExpr(m.groupValues[1], keyword, page).orEmpty()
            }
        }
        return result
    }

    /** 求值 URL 中的 {{表达式}}：注入 key/searchKey/page/searchPage/baseUrl */
    private fun evalUrlExpr(expr: String, keyword: String, page: Int): String? = runCatching {
        val ctx = org.mozilla.javascript.Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initSafeStandardObjects()
            scope.put("key", scope, keyword)
            scope.put("searchKey", scope, keyword)
            scope.put("page", scope, page)
            scope.put("searchPage", scope, page)
            scope.put("baseUrl", scope, baseUrl)
            val v = ctx.evaluateString(scope, expr, "<url-expr>", 1, null)
            org.mozilla.javascript.Context.toString(v)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }.getOrNull()

    /**
     * 执行 @js: 搜索地址脚本，返回 URL 字符串（可含 `,{配置}` 尾巴）。
     * 注入变量：key / searchKey / page / searchPage / baseUrl / bookSourceUrl，
     * 并提供 java 桩（put/get/toast 等常用方法 no-op，避免脚本直接失败）。
     * 取值优先级：脚本返回值 > result 变量。
     */
    private fun evalSearchUrlJs(js: String, keyword: String, page: Int): String? = runCatching {
        val ctx = org.mozilla.javascript.Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initSafeStandardObjects()
            scope.put("key", scope, keyword)
            scope.put("searchKey", scope, keyword)
            scope.put("page", scope, page)
            scope.put("searchPage", scope, page)
            scope.put("baseUrl", scope, baseUrl)
            scope.put("bookSourceUrl", scope, baseUrl)
            // java 桩：脚本里常见的 java.put / java.toast 等在这里只做记录，不产生副作用
            ctx.evaluateString(
                scope,
                "var java = { put: function(k,v){ this['_p_'+k]=v; }, " +
                    "get: function(k){ return this['_p_'+k] || ''; }, " +
                    "toast: function(){}, ajax: function(){ return ''; }, " +
                    "startBrowserAwait: function(){ return null; }, " +
                    "getElement: function(){ return null; } };",
                "<java-stub>", 1, null,
            )
            // 先渲染脚本里的 {{key}}/{{page}} 占位（legado 语义：先替换再执行）
            val script = renderUrl(js, keyword, page)
            val out = ctx.evaluateString(scope, script, "<search-url>", 1, null)
            val s = org.mozilla.javascript.Context.toString(out)
            if (s.isNotBlank() && s != "undefined" && s != "null") {
                s
            } else {
                val rv = scope.get("result", scope)
                if (rv != null && rv != org.mozilla.javascript.Scriptable.NOT_FOUND) {
                    org.mozilla.javascript.Context.toString(rv)
                } else null
            }
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 拆分 `"url,{请求配置JSON}"` 形式的 searchUrl。
     * 返回 (url 模板, 配置对象或 null)。配置含 method / body / charset 等字段。
     */
    private fun splitRequestConfig(raw: String): Pair<String, com.google.gson.JsonObject?> {
        var i = raw.indexOf(',')
        while (i >= 0) {
            val rest = raw.substring(i + 1).trim()
            if (rest.startsWith("{")) {
                val cfg = runCatching {
                    com.google.gson.JsonParser.parseString(rest).asJsonObject
                }.getOrNull()
                if (cfg != null) return raw.substring(0, i).trim() to cfg
            }
            i = raw.indexOf(',', i + 1)
        }
        return raw.trim() to null
    }

    /** 带请求头抓取 */
    private suspend fun fetch(url: String): String? {
        return runCatching {
            HttpClient.get(url, headers)
        }.getOrNull()
    }
}
