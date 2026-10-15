package com.novelreader.source.legado

import com.novelreader.core.network.HttpClient
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.ExploreCategory
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

    init {
        // 源级请求头注册进封面注册表：书架/搜索/详情的封面由 Coil 直接加载，
        // 拿不到源上下文，按 host 命中后由 CoverDefaultsInterceptor 补齐（防盗链）
        if (headers.isNotEmpty()) {
            runCatching {
                val host = java.net.URI(baseUrl).host
                if (!host.isNullOrBlank()) {
                    com.novelreader.core.image.CoverHeaderRegistry.register(host, headers)
                }
            }
        }
    }

    // ===== 搜索 =====
    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        if (source.searchUrl.isBlank()) return emptyList()

        // searchUrl 可能形如 "url,{请求配置JSON}"（含 method/body/charset/headers），
        // 或以 @js: 开头（执行 JS 得到 URL，可再带 ,{配置}）
        val spec = if (source.searchUrl.trimStart().startsWith("@js:")) {
            val js = source.searchUrl.trimStart().substringAfter("@js:")
            evalSearchUrlJs(js, keyword, page) ?: return emptyList()
        } else {
            source.searchUrl
        }
        return loadList(spec, keyword, page, source.ruleSearch)
    }

    /**
     * 从「URL（可带 ,{请求配置} 尾巴）」按规则拉取书籍列表。
     * 搜索（ruleSearch）与发现（ruleExplore）共用同一条抓取+解析链路。
     */
    private suspend fun loadList(
        rawSpec: String,
        keyword: String,
        page: Int,
        rule: Map<String, String>,
    ): List<MediaItem> {
        val (urlTemplate, requestConfig) = splitRequestConfig(rawSpec)

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

        val listRule = rule["bookList"] ?: rule["list"] ?: ""
        val fieldRules = rule

        // 每条搜索结果独立收集 `@put:{...}` 变量，随条目带出供详情/目录使用。
        // 外层换成空表，保证每次拉取从干净状态开始（不复用上一步残留的变量）。
        val varsSink = mutableListOf<Map<String, String>>()
        val list = LegadoRuleEngine.withVariables(mutableMapOf()) {
            if (isJson || listRule.startsWith("@json:")) {
                extractJsonResults(content, rule, varsSink = varsSink, listRule = listRule)
            } else {
                LegadoRuleEngine.extractList(content, listRule, fieldRules, baseUrl, varsSink)
            }
        }

        return list.mapIndexed { idx, fields ->
            val title = fields["name"] ?: fields["title"] ?: ""
            val bookUrlRaw = fields["bookUrl"] ?: fields["url"] ?: ""
            // 地址后可能跟一段请求配置：`url,{"method":"GET","headers":{...}}`（legado 约定）。
            // App 接口型源把接口签名放在这里（如 `m-request-id`），详情/目录/正文请求都得原样带上。
            val (bookUrl, reqOptions) = splitUrlOptions(bookUrlRaw)
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
                variableMap = varsSink.getOrNull(idx)?.takeIf { it.isNotEmpty() } ?: emptyMap(),
                requestOptions = reqOptions,
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

    // ===== 发现页（v1.2.3.0 对标 legado exploreUrl / ruleExplore） =====

    /**
     * 解析发现分类。支持三种格式（与 legado 一致）：
     * - JSON 数组：[{"title":"玄幻","url":"..."},...]
     * - 逐行 "分类名::地址"
     * - 逐行纯地址（标题即地址）
     * @js: 形式的发现暂不支持（返回空，UI 侧该源不展示）。
     */
    override suspend fun exploreCategories(): List<ExploreCategory> {
        val raw = source.exploreUrl.trim()
        if (raw.isBlank() || raw.startsWith("@js:")) return emptyList()
        return runCatching {
            if (raw.startsWith("[")) {
                val arr = com.google.gson.JsonParser.parseString(raw).asJsonArray
                arr.mapNotNull { el ->
                    val obj = el as? com.google.gson.JsonObject ?: return@mapNotNull null
                    val t = obj.get("title")?.asString?.trim() ?: return@mapNotNull null
                    val u = obj.get("url")?.asString?.trim() ?: return@mapNotNull null
                    if (t.isBlank() || u.isBlank()) null else ExploreCategory(t, u)
                }
            } else {
                raw.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { line ->
                        if (line.contains("::")) {
                            val t = line.substringBefore("::").trim()
                            val u = line.substringAfter("::").trim()
                            ExploreCategory(t.ifBlank { u }, u)
                        } else {
                            ExploreCategory(line, line)
                        }
                    }
            }
        }.getOrDefault(emptyList())
    }

    /** 按发现分类地址拉取列表：{{page}} 等占位符渲染后走与搜索相同的解析链路 */
    override suspend fun explore(url: String, page: Int): List<MediaItem> {
        if (source.ruleExplore.isEmpty() || url.isBlank()) return emptyList()
        return loadList(url, "", page, source.ruleExplore)
    }

    /** 处理 JSON 响应的搜索（@json 规则） */
    private fun extractJsonResults(
        content: String,
        rule: Map<String, String>,
        contextUrl: String = baseUrl,
        varsSink: MutableList<Map<String, String>>? = null,
        listRule: String? = null,
    ): List<Map<String, String>> {
        return runCatching {
            // 列表规则可能是「前置 <js> 块 + 后续 JSONPath」的链式写法：
            // 如目录规则 `<js>整体解密</js>\n$.chapters[*]`（部分 App 接口源）。
            // 先执行 JS 得到新的内容，再按剩余路径取数组。
            var json = content
            var effectiveRule = listRule
            LegadoRuleEngine.splitLeadingJs(listRule)?.let { (js, rest) ->
                LegadoRuleEngine.evalJsWithContent(js, content, contextUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { decoded ->
                        json = decoded
                        effectiveRule = rest
                    }
            }

            val root = com.google.gson.JsonParser.parseString(json)
            val (arr, reverse) = resolveJsonList(root, effectiveRule)
            val list = arr?.toList() ?: emptyList()

            val pairs = list.map { node ->
                // 每条记录用独立变量表：`@put:{bid:$.comic_id}` 之类必须按条目隔离。
                // 注意要以当前变量表为**初值**（目录阶段要能看到搜索/详情写入的变量），
                // 否则外层变量会被空表遮蔽，章节地址里的 `@get:` 就取不到了。
                val vars = LinkedHashMap(LegadoRuleEngine.variables)
                val fields = LegadoRuleEngine.withVariables(vars) {
                    rule.mapValues { (_, r) ->
                        renderJsonRule(r, node, contextUrl) ?: ""
                    }
                }
                fields to vars
            }
            // legado 语义：列表规则前置 `-` 表示结果倒置；变量表必须同步倒置才能与条目配对
            val ordered = if (reverse) pairs.reversed() else pairs
            varsSink?.addAll(ordered.map { it.second })
            ordered.map { it.first }
        }.getOrDefault(emptyList())
    }

    /**
     * 测试用：直接渲染 JSON 列表（含前置 `-` 倒置、`||` 备选路径与 `@put:` 变量收集）。
     * 与 [extractComicImages] 同为 internal，便于离线单测覆盖，不参与线上逻辑。
     */
    internal fun jsonListForTest(
        content: String,
        fields: Map<String, String>,
        listRule: String,
        contextUrl: String = baseUrl,
    ): List<Map<String, String>> = extractJsonResults(content, fields, contextUrl, listRule = listRule)

    /**
     * 测试用：按 JSON 字段规则渲染单个字段（含 `@js:` / `<js>` / `{{}}` 模板 / 变量机制）。
     * 与 [extractComicImages] 同为 internal，便于离线单测覆盖。
     */
    internal fun jsonFieldForTest(content: String, rule: String, contextUrl: String = baseUrl): String? =
        runCatching {
            val root = com.google.gson.JsonParser.parseString(content)
            renderJsonRule(rule, root, contextUrl)
        }.getOrNull()

    /**
     * 解析 JSON 列表规则，取「列表数组」与「是否倒置」。
     *
     * 支持：
     * - 前置 `-` → 列表倒置（legado 语义，很多站点目录本身是倒序的）
     * - `@json:` / `@JSon:` 前缀（大小写不敏感）
     * - `||` 备选路径（取第一个能解析出数组的）
     * - 尾部 `[*]` / `.*`（表示「数组全部元素」）
     * - 路径缺失时兜底取根对象的 `data` 字段
     */
    private fun resolveJsonList(
        root: com.google.gson.JsonElement,
        listRule: String?,
    ): Pair<com.google.gson.JsonArray?, Boolean> {
        var raw = listRule?.trim().orEmpty()

        // 前置 `-` = 列表倒置（`-` 后紧跟数字时是负索引，不倒置）
        var reverse = false
        if (raw.length > 1 && raw.startsWith("-") && !raw[1].isDigit()) {
            reverse = true
            raw = raw.substring(1).trim()
        }
        raw = raw.replaceFirst(Regex("^@json:", RegexOption.IGNORE_CASE), "").trim()

        val paths = raw.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        var arr: com.google.gson.JsonArray? = null
        for (p in paths) {
            val cleaned = p.removeSuffix("[*]").removeSuffix(".*").trim()
            val node = when {
                cleaned.isBlank() || cleaned == "$" -> root
                cleaned.startsWith("$") -> selectJsonNode(root, cleaned)
                else -> null
            }
            arr = when {
                node == null -> null
                node.isJsonArray -> node.asJsonArray
                node.isJsonObject && node.asJsonObject.has("data") ->
                    node.asJsonObject.get("data").takeIf { it.isJsonArray }?.asJsonArray
                else -> null
            }
            if (arr != null) break
        }
        // 兜底：根对象自带的 data 数组（不少接口直接返回 {"data":[...]}）
        if (arr == null && root.isJsonObject && root.asJsonObject.has("data")) {
            arr = root.asJsonObject.get("data").takeIf { it.isJsonArray }?.asJsonArray
        }
        return arr to reverse
    }

    /** 按简化 JSONPath 取节点（`$.a.b`、`$.a[0].b`、`$.a.*`） */
    private fun selectJsonNode(node: com.google.gson.JsonElement, path: String): com.google.gson.JsonElement? {        return runCatching {
            val cleaned = path.removePrefix("$").removePrefix(".").trim()
            if (cleaned.isEmpty()) return node
            var current: com.google.gson.JsonElement = node
            for (t in cleaned.split(Regex("[.\\[\\]]")).filter { it.isNotBlank() }) {
                // `[*]` / `.*`：数组全部元素，原样返回数组
                if (t == "*") {
                    if (current.isJsonArray) return current
                    continue
                }
                current = when {
                    current.isJsonObject -> current.asJsonObject.get(t) ?: return null
                    current.isJsonArray -> {
                        val idx = t.toIntOrNull() ?: return null
                        if (idx < 0 || idx >= current.asJsonArray.size()) return null
                        current.asJsonArray.get(idx)
                    }
                    else -> return null
                }
            }
            current
        }.getOrNull()
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

        // `@put:{k:v}` 写入变量、`@get:{k}` 回填（legado 变量机制）。
        // JSON API 源常用它把搜索结果里的 ID 传给后续的目录 / 正文规则。
        var normalized = rule
        if (normalized.contains("@get:") || normalized.contains("@put:")) {
            normalized = LegadoRuleEngine.resolveVariableRules(normalized) { v ->
                renderJsonRule(v, node, contextUrl)
            }
            if (normalized.isBlank()) return null
        }

        // 整条规则就是一段 JS（`@js:...` / `<js>...</js>`）：JSON 接口源大量使用，
        // 例如 `bookUrl` 里拼「带 md5 签名的地址,{请求配置}」、`chapterUrl` 里拼图片接口地址。
        // legado 语义：先替换规则中的 {{表达式}}（如 {{$.comic_id}}），再执行 JS。
        // 必须放在 `##` 拆分之前，避免 JS 代码里出现 `##` 被误当正则段切开。
        jsBodyOf(normalized)?.let { js ->
            val script = renderJsonTemplate(js, node, contextUrl) ?: js
            return LegadoRuleEngine.evalJsonJs(script, node, contextUrl)
        }

        // 拆分 ## 正则段（第一段是取值表达式，后续段是 正则[##替换]）
        val parts = normalized.split("##")
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

    /**
     * 判断整条规则是否就是一段 JS，是则返回 JS 代码。
     *
     * 支持 `@js:代码` 与 `<js>代码</js>` 两种写法；
     * `xxx@js:代码`（前段取值 + 尾部 JS）不属于此情形，由 selector 路径处理。
     */
    private fun jsBodyOf(rule: String): String? {
        val r = rule.trim()
        if (r.startsWith("@js:", ignoreCase = true)) return r.substringAfter("@js:")
        LegadoRuleEngine.splitLeadingJs(r)?.let { (js, rest) -> if (rest.isBlank()) return js }
        if (r.startsWith("<js>") && r.endsWith("</js>")) return r.substring(4, r.length - 5)
        return null
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
        // {{baseUrl.replace('a','b')}}、{{java.getString('$.x')}} 等表达式 → 用 Rhino
        if (expr.contains("baseUrl") || expr.contains("$") || expr.contains("java.")) {
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
                    // 注入 java 桥：{{java.getString('$.x')}} / {{java.timeFormat(...)}} 等
                    scope.put(
                        "java",
                        scope,
                        org.mozilla.javascript.Context.javaToJS(
                            LegadoJavaBridge(jsonStr, jsonStr, contextUrl),
                            scope,
                        ),
                    )
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

        // 详情地址也可能自带 `,{...}` 请求配置（如从书架读回的历史数据），先拆出来
        val (detailUrlClean, detailUrlOptions) = splitUrlOptions(item.detailUrl)
        val detailOptions = item.requestOptions.ifBlank { detailUrlOptions }
        val url = if (detailUrlClean.startsWith("http")) detailUrlClean
        else LegadoRuleEngine.toAbsoluteUrl(baseUrl, detailUrlClean) ?: return item

        var content = fetch(url, detailOptions) ?: return item
        // legado 的 init 规则：先对响应体做一次整体变换（很多 App 接口源用它做整体解密 / 去包装）
        content = applyInitRule(content, rule["init"], url)

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

        // 详情规则里也可能出现 `@put:{...}` / `@get:{...}`：
        // 用搜索阶段带出的变量表求值，并把详情阶段新写入的变量继续带下去（目录/正文要用）
        val vars = item.variableMap.toMutableMap()
        val extras = item.extras.toMutableMap()

        LegadoRuleEngine.withVariables(vars) {
            cover = fieldValue("coverUrl") ?: cover
            author = fieldValue("author") ?: author
            desc = fieldValue("intro") ?: desc
            // 详情页元信息（#27）：状态 / 字数 / 最新章节 / 更新时间
            // 搜索阶段未带出的字段，在此从详情规则补齐（缺失则保留搜索时的值）
            //
            // kind 是**一物两用**的字段：多数源写的是分类（「玄幻」「热血」），
            // 少数源混写状态（「连载中」）。v1.6.9.0 之前只落到 extras["status"]，
            // 结果详情页标签栏（ItemTags 读 extras["kind"]）在只由详情规则给出 kind
            // 的源上永远是空的 —— 小说 / 漫画详情页看不到标签就是这么来的。
            // 现在 extras["kind"] 一定写（状态词会被 ItemTags 过滤掉，不会当标签）；
            // extras["status"] 只在还是空的时候回填，免得把搜索阶段拿到的真实状态
            // （「连载中」）覆盖成分类名（v1.7.0.0）。
            fieldValue("kind")?.takeIf { it.isNotBlank() }?.let {
                extras["kind"] = it
                if (extras["status"].isNullOrBlank()) extras["status"] = it
            }
            fieldValue("wordCount")?.takeIf { it.isNotBlank() }?.let { extras["wordCount"] = it }
            fieldValue("lastUpdate")?.takeIf { it.isNotBlank() }?.let { extras["lastUpdate"] = it }
            fieldValue("lastChapter")?.takeIf { it.isNotBlank() }?.let { extras["lastChapter"] = it }
            // `@put:{k:取值规则}` 是「渲染字段时的副作用」，legado 只要渲染过该字段就会写入变量表。
            // 上面只渲染了 UI 用得到的字段，但变量可能挂在别的字段上：
            // 某源把 `@put:{cid:$.comic_id}` 挂在 `ruleBookInfo.name` 上，
            // 而详情页书名直接用搜索/书架的值（不渲染 name）→ cid 永远写不进变量表 →
            // 章节地址里 `@get:{cid}` 变成空 → 图片接口签名错误 → 正文空白。
            // 因此把所有带 `@put:` 的字段补渲染一遍，保证副作用完整。
            rule.forEach { (key, r) -> if (r.contains("@put:")) fieldValue(key) }
        }

        // 清理简介中的 HTML 标签
        desc = desc?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()

        return item.copy(cover = cover, author = author, desc = desc, extras = extras, variableMap = vars)
    }

    // ===== 目录 =====
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val rule = source.ruleToc
        if (rule.isEmpty()) return emptyList()

        val (detailUrlClean, detailUrlOptions) = splitUrlOptions(item.detailUrl)
        val detailOptions = item.requestOptions.ifBlank { detailUrlOptions }
        val detailUrl = if (detailUrlClean.startsWith("http")) detailUrlClean
        else LegadoRuleEngine.toAbsoluteUrl(baseUrl, detailUrlClean) ?: return emptyList()

        val rawContent = fetch(detailUrl, detailOptions) ?: return emptyList()
        // 注意：只应用 **目录规则自己的** init。ruleBookInfo.init 不能套到目录响应上——
        // 很多源的目录列表规则自身就带 `<js>` 解密（如某些 App 接口源），
        // 若先把响应体整体解密，规则里的 `java.getString("$.data")` 就取不到原文了。
        val content = applyInitRule(rawContent, rule["init"], detailUrl)

        // 目录阶段的 `@get:{...}` 变量来自搜索/详情（如 `&comic_id=@get:{cid}`）。
        // 注意：变量表按线程隔离，因此这里只在「不挂起」的求值块内使用，不跨 fetch 持有。
        var vars = item.variableMap.toMutableMap()
        // 变量表为空、但详情规则里确实有 `@put:` 时，先补跑一次详情取变量：
        // 从书架直接打开（条目来自数据库，不带变量表）时否则会取到空 ID → 目录空白。
        if (vars.isEmpty() && source.ruleBookInfo.values.any { it.contains("@put:") }) {
            vars = detail(item).variableMap.toMutableMap()
        }

        // 目录可能有独立 URL：
        // 1. ruleToc.url（URL 模板或选择器规则）
        // 2. ruleBookInfo.tocUrl（需从详情页渲染/提取，baseUrl 指详情页 URL）
        val tocUrlRaw = rule["url"]?.takeIf { it.isNotBlank() }
            ?: source.ruleBookInfo["tocUrl"]?.takeIf { it.isNotBlank() }
        val tocUrl = LegadoRuleEngine.withVariables(vars) {
            tocUrlRaw?.let { resolveTocUrl(it, content, detailUrl) }
        }?.let { LegadoRuleEngine.toAbsoluteUrl(detailUrl, it) }
        // 目录地址后可能跟请求配置（`url,{...}`）：地址用于取值与相对拼接，配置用于发请求
        val (tocUrlClean, tocOptions) = splitUrlOptions(tocUrl)
        val effectiveTocOptions = tocOptions.ifBlank { detailOptions }
        val usesOwnPage = tocUrlClean.isNotBlank() && tocUrlClean != detailUrl
        val firstPageUrl = if (usesOwnPage) tocUrlClean else detailUrl
        val listContent = if (usesOwnPage) {
            fetch(firstPageUrl, effectiveTocOptions) ?: content
        } else content

        val listRule = rule["chapterList"] ?: rule["list"] ?: return emptyList()
        val fieldRules = rule

        // 列表规则以 `<js>` 开头时同样是「JS 产出 JSON」的形态（先用 JS 整体解密）
        val isJson = listRule.startsWith("@json:") || listRule.trimStart().startsWith("<js>") ||
            listContent.trimStart().startsWith("{") || listContent.trimStart().startsWith("[")

        // JSON 目录时，{{baseUrl}} 指代目录实际请求 URL（tocUrl），供 chapterUrl 模板使用
        val jsonContextUrl = tocUrlClean.ifBlank { detailUrl }

        if (isJson) {
            val list = LegadoRuleEngine.withVariables(vars) {
                extractJsonResults(
                    listContent,
                    rule,
                    jsonContextUrl,
                    listRule = rule["chapterList"] ?: rule["list"],
                )
            }
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
                val nextRaw = LegadoRuleEngine.withVariables(vars) {
                    LegadoRuleEngine.extractString(last.content, nextRule, last.url)
                } ?: break
                val nextUrl = LegadoRuleEngine.toAbsoluteUrl(last.url, nextRaw) ?: break
                if (!visited.add(nextUrl)) break
                val nextContent = fetch(nextUrl, effectiveTocOptions) ?: break
                pages.add(TocPage(nextUrl, nextContent))
            }
        }

        var index = 0
        return pages.flatMap { page ->
            // {{baseUrl}} 在目录规则中指「目录页自身地址」（legado 语义），
            // 传 page.url 而非源根地址，否则相对章节链接会被拼错
            LegadoRuleEngine.withVariables(vars) {
                LegadoRuleEngine.extractList(page.content, listRule, fieldRules, page.url).map { fields ->
                    fieldsToChapter(fields, index++, page.url)
                }
            }
        }
    }

    /** 目录字段 -> ChapterItem（pageBase 为章节锚点所在页面 URL，相对地址按其解析） */
    private fun fieldsToChapter(fields: Map<String, String>, index: Int, pageBase: String): ChapterItem {
        val name = fields["chapterName"] ?: fields["name"] ?: fields["title"] ?: "第${index + 1}章"
        val chapterUrlRaw = fields["chapterUrl"] ?: fields["url"] ?: ""
        // 章节地址后可能跟请求配置（`url,{"method":...,"headers":{...}}`）：
        // 漫画 App 接口源把 access-token / m-request-id 签名放在这里，正文请求必须带上。
        // 注意：**地址保持原样（含请求配置）**，另外再单独解析出 requestOptions。
        // 这样 url 被写进进度 / 章节缓存 / 书签后，签名依旧随地址保留，
        // 「最近阅读续读」「离线缓存重放」不会因为配置丢失而请求失败；
        // 发请求时由 content() 统一拆分（见其中注释）。
        val (chapterUrlOnly, reqOptions) = splitUrlOptions(chapterUrlRaw)
        val absClean = LegadoRuleEngine.toAbsoluteUrl(pageBase, chapterUrlOnly) ?: chapterUrlOnly
        val chapterUrl = if (reqOptions.isBlank()) absClean else "$absClean,${reqOptions}"
        return ChapterItem(
            id = absClean.ifBlank { "$index" },
            title = name,
            url = chapterUrl,
            requestOptions = reqOptions,
            index = index,
            sourceId = info.id,
        )
    }

    /** 渲染仅含 {{baseUrl}} 等简单占位符的 URL 模板（baseUrl 用给定上下文替换） */
    private fun renderUrlTemplate(template: String, contextUrl: String): String {
        // `@get:{k}` → 变量回填（如目录接口的 `&comic_id=@get:{bid}`）
        val t = LegadoRuleEngine.resolveGetRules(template)
        return t.replace("{{baseUrl}}", contextUrl)
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

        // 章节地址后可能跟请求配置（access-token / m-request-id 等签名），先拆出来再发请求。
        // 注意：目录阶段（fieldsToChapter）已把这段配置剥离并存进 ChapterItem.requestOptions，
        // 因此这里必须优先用 requestOptions，否则 `url` 已是干净地址 → 后缀为空 →
        // 签名请求头全部丢失 → 部分 App 接口源返回错误数据 → 正文空白。
        val (chapterUrlClean, urlOptions) = splitUrlOptions(chapter.url)
        val reqOptions = chapter.requestOptions.ifBlank { urlOptions }
        val url = if (chapterUrlClean.startsWith("http")) chapterUrlClean
        else LegadoRuleEngine.toAbsoluteUrl(baseUrl, chapterUrlClean) ?: return ContentResult()

        val content = fetch(url, reqOptions) ?: return ContentResult()

        return when (info.type) {
            com.novelreader.domain.model.SourceType.COMIC -> {
                extractComicImages(content, rule, url)
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
            // 用 pageUrl（当前正文页地址）作上下文：相对链接与 {{baseUrl}} 语义与 legado 一致
            LegadoRuleEngine.extractString(c, contentRule, pageUrl) ?: ""
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

    /**
     * 提取漫画图片列表。
     *
     * 与小说正文不同，漫画图片有两点关键差异：
     * 1. 图片地址必须相对**章节页 URL**解析（源里大量使用相对路径 / `{{baseUrl}}`）；
     * 2. 多数漫画站有防盗链，必须带 `Referer`（源规则里常写 `header={"Referer":baseUrl}`），
     *    否则 CDN 返回 403 → 页面「无法加载」。这里把请求头一并带给图片加载器。
     *
     * 可见性为 internal：供单元测试直接注入 HTML + 规则做离线验证（无需联网）。
     */
    internal fun extractComicImages(content: String, rule: Map<String, String>, pageUrl: String): ContentResult {
        val contentRule = rule["content"] ?: rule["images"] ?: ""
        val baseHeaders = imageHeaders(pageUrl)
        if (contentRule.isBlank()) return ContentResult.comic(emptyList(), baseHeaders)

        // 单张图片地址可能带「按图请求头」后缀：`url,{"headers":{...}}`（legado 约定）
        val perImageHeaders = LinkedHashMap<String, String>()

        // JSON 正文（如 {"data":{"images":[...]}}）：规则相对根节点解析
        val isJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")
        if (isJson && !contentRule.contains("@js:") && !contentRule.contains("<js>")) {
            return runCatching {
                val root = com.google.gson.JsonParser.parseString(content)
                val rendered = renderJsonRule(contentRule, root)
                val urls = parseImageList(rendered, pageUrl, perImageHeaders)
                ContentResult.comic(urls, baseHeaders + perImageHeaders)
            }.getOrDefault(ContentResult.comic(emptyList(), baseHeaders))
        }

        // 尾部 @js:/<js> 规则：走兼容 legado 的求值（额外提供 src 与 java.*，并可读取 headers 变量）
        val (value, jsHeadersJson) = LegadoRuleEngine.evalContentRule(content, contentRule, pageUrl)
        val jsHeaders = parseHeaderJson(jsHeadersJson)

        val doc = Jsoup.parse(content)
        val images = mutableListOf<String>()

        fun addImage(rawSrc: String) {
            if (rawSrc.isBlank()) return
            val (url, hdr) = splitUrlAndHeaders(rawSrc)
            if (hdr.isNotEmpty()) perImageHeaders.putAll(hdr)
            LegadoRuleEngine.toAbsoluteUrl(pageUrl, url)?.let { images.add(it) }
        }

        // 支持 @css: 选择器提取多图
        if (contentRule.startsWith("@css:")) {
            val selector = contentRule.substringAfter("@css:").trim()
            val imgAttr = rule["imageStyle"] ?: "src"
            doc.select(selector).forEach { el ->
                val src = when (imgAttr.uppercase()) {
                    "FULL" -> el.attr("src").ifEmpty { el.attr("data-src").ifEmpty { el.attr("data-original") } }
                    else -> el.attr(imgAttr.lowercase()).ifEmpty { el.attr("src") }
                }
                addImage(src)
            }
        } else {
            // 常规选择器（如 class.xx@tag.img@html）或 JS 结果（可能是 HTML 片段 / 地址列表）
            val html = value ?: LegadoRuleEngine.extractString(content, contentRule, pageUrl)
            if (!html.isNullOrBlank()) {
                // (1) JS 拼出的 `<img src="url,{"headers":{...}}">`：src 内部含有引号，
                //     HTML 解析会在第一个引号处截断，导致请求头后缀丢失。
                //     这里按「一直取到 `">`」的方式抓 img 的 src/data-src，保住地址与请求头。
                //     （限定在 <img> 标签内，避免误抓 <script src> 之类的地址）
                if (html.contains("<img", ignoreCase = true)) {
                    Regex(
                        """<img\b[^>]*?\s(?:data-)?src\s*=\s*"([\s\S]*?)"(?=\s*/?>)""",
                        RegexOption.IGNORE_CASE,
                    ).findAll(html).forEach { m -> addImage(m.groupValues[1]) }
                }
                // (2) 结果可能本身就是图片地址列表（换行/逗号分隔，或 JSON 数组）
                if (images.isEmpty()) {
                    val direct = parseImageList(html, pageUrl, perImageHeaders)
                    if (direct.isNotEmpty()) images.addAll(direct)
                }
                // (3) 退化为「结果是 HTML 片段」
                if (images.isEmpty()) {
                    val subDoc = Jsoup.parse(html)
                    subDoc.select("img").forEach { img ->
                        addImage(
                            img.attr("src").ifEmpty {
                                img.attr("data-src").ifEmpty { img.attr("data-original") }
                            }
                        )
                    }
                }
                // (4) 兜底：结果里直接出现裸图片地址（如 JS 拼接的数组字符串）
                if (images.isEmpty()) {
                    Regex("https?://[^\\s\"'<>,\\[\\]{}]+").findAll(html).forEach { m ->
                        val u = m.value
                        if (Regex("\\.(jpg|jpeg|png|webp|gif|bmp|avif)(\\?|$)", RegexOption.IGNORE_CASE)
                                .containsMatchIn(u)
                        ) images.add(u)
                    }
                }
            }
        }
        return ContentResult.comic(images.distinct(), baseHeaders + jsHeaders + perImageHeaders)
    }

    /**
     * 判断一个片段是否「像图片地址」。
     *
     * 需要接受相对地址（如 `2.jpg`、`../img/3.png`）——它们要按章节页 URL 解析；
     * 同时排除规则结果里混进来的 HTML/JSON 残片。
     */
    private fun String.isUrlLike(): Boolean {
        if (isBlank()) return false
        if (startsWith("__HDR__")) return true
        if (any { it == '<' || it == '>' || it == '"' || it == '\'' || it == ' ' || it == '\t' }) return false
        if (startsWith("http://") || startsWith("https://") || startsWith("//") ||
            startsWith("/") || startsWith("data:image")
        ) return true
        // 相对路径：带扩展名（2.jpg / ../img/3.png?x=1）
        return contains('.') && !endsWith(".")
    }

    /** 拆分单图地址的「按图请求头」后缀：`url,{"headers":{...}}` → (url, headers) */
    private fun splitUrlAndHeaders(raw: String): Pair<String, Map<String, String>> {
        val idx = raw.indexOf(",{")
        if (idx <= 0) return raw.trim() to emptyMap()
        val url = raw.substring(0, idx).trim()
        val json = extractJsonObject(raw.substring(idx + 1)) ?: raw.substring(idx + 1).trim()
        return url to parseHeaderJson(json)
    }

    /**
     * 从一段文本中截出第一个「括号配对」的 JSON 对象。
     *
     * 漫画源的 JS 常把 `,{"headers":{...}}` 后缀拼在 `<img src="...">` 里，
     * 抓出来的串尾部会带 `">` 之类的残留，直接用 Gson 解析会失败——这里按括号配对截断。
     */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * 组装图片请求头：源自带 header + 默认 `Referer`（章节页，满足多数站防盗链）+ 规则内声明的头。
     */
    private fun imageHeaders(pageUrl: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h.putAll(headers)
        if (h.keys.none { it.equals("Referer", ignoreCase = true) }) {
            h["Referer"] = runCatching {
                val u = java.net.URI(pageUrl)
                "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}/"
            }.getOrDefault(pageUrl)
        }
        h.putAll(extra)
        return h
    }

    /** 解析 JS 中设置的 headers JSON（兼容 `{"Referer":x}`、`{"headers":{...}}`、`{"headers":"{...}"}` 三种写法） */
    private fun parseHeaderJson(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            var obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            val h = obj.get("headers")
            if (h != null) {
                obj = when {
                    h.isJsonObject -> h.asJsonObject
                    h.isJsonPrimitive && h.asJsonPrimitive.isString ->
                        com.google.gson.JsonParser.parseString(h.asString).asJsonObject
                    else -> obj
                }
            }
            obj.entrySet()
                .filter { it.value.isJsonPrimitive }
                .associate { it.key to it.value.asString }
        }.getOrDefault(emptyMap())
    }

    /** 解析渲染后的图片列表（JSON 数组字符串 / 换行、逗号分隔的 URL），相对地址按 pageUrl 解析 */
    private fun parseImageList(
        rendered: String?,
        pageUrl: String,
        headersOut: MutableMap<String, String> = mutableMapOf(),
    ): List<String> {
        if (rendered.isNullOrBlank()) return emptyList()
        val trimmed = rendered.trim()

        // 按换行/逗号切分（注意：地址可能带 `,{headers}` 后缀，按 ",{" 断开而不是按逗号断）
        fun splitPlain(text: String): List<String> = text.split("\n").flatMap { line ->
            val cut = line.indexOf(",{")
            if (cut > 0) {
                val rest = line.substring(cut)
                // 后缀本身可能还含有逗号（JSON 内部），这里不做二次切分
                listOf(line.substring(0, cut), "__HDR__$rest")
            } else {
                line.split(",")
            }
        }.map { it.trim() }
            .filter { it.isUrlLike() }

        // JSON 数组优先；不是 JSON 数组时走通用切分（此前 getOrElse 分支永远进不去，已修正）
        val raw: List<String> = if (trimmed.startsWith("[")) {
            runCatching { com.google.gson.JsonParser.parseString(trimmed).asJsonArray.map { it.asString } }
                .getOrElse { splitPlain(trimmed) }
        } else {
            splitPlain(trimmed)
        }

        // 收集按图请求头后缀，并返回清洗后的地址
        val urls = mutableListOf<String>()
        raw.forEach { item ->
            if (item.startsWith("__HDR__")) {
                val body = item.removePrefix("__HDR__").removePrefix(",")
                headersOut.putAll(parseHeaderJson(extractJsonObject(body) ?: body))
            } else {
                val (u, hdr) = splitUrlAndHeaders(item)
                if (hdr.isNotEmpty()) headersOut.putAll(hdr)
                urls.add(u)
            }
        }
        return urls.mapNotNull { LegadoRuleEngine.toAbsoluteUrl(pageUrl, it) ?: it }
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

    /**
     * 应用 legado 的 `init` 规则（整体变换响应体）。
     *
     * 很多 App 接口型源用它把响应整体解密 / 去掉外层包装，之后再按普通规则取值。
     * 规则求值失败（或结果为空）时返回原内容，保证不影响其他源。
     */
    private fun applyInitRule(content: String, initRule: String?, baseUrl: String): String {
        if (initRule.isNullOrBlank()) return content
        return runCatching {
            LegadoRuleEngine.evalContentRule(content, initRule, baseUrl).first
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: content
    }

    /**
     * 拆分「地址 + 请求配置」：`url,{"method":"GET","headers":{...}}` → (url, 配置 JSON)。
     *
     * legado 允许在任意地址后追加一段请求配置（搜索结果的 bookUrl、目录地址、章节地址都常见），
     * 配置里的 headers 往往是接口签名（m-request-id / access-token），不带就会拿到空数据。
     */
    internal fun splitUrlOptions(raw: String?): Pair<String, String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "" to ""
        val idx = text.indexOf(",{")
        if (idx <= 0) return text to ""
        val url = text.substring(0, idx).trim()
        val options = extractJsonObject(text.substring(idx + 1)) ?: text.substring(idx + 1).trim()
        return url to options
    }

    /** 解析请求配置 JSON → method / headers / body / charset */
    private data class RequestOptions(
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
        val charset: String? = null,
    )

    private fun parseRequestOptions(json: String?): RequestOptions {
        if (json.isNullOrBlank()) return RequestOptions()
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            val method = obj.get("method")?.takeIf { it.isJsonPrimitive }?.asString
                ?.uppercase()?.takeIf { it.isNotBlank() } ?: "GET"
            val headers = obj.get("headers")?.let { h ->
                when {
                    h.isJsonObject -> h.asJsonObject.entrySet()
                        .filter { it.value.isJsonPrimitive }
                        .associate { it.key to it.value.asString }
                    h.isJsonPrimitive && h.asJsonPrimitive.isString ->
                        parseHeaderJson(h.asString)
                    else -> emptyMap()
                }
            } ?: emptyMap()
            val body = obj.get("body")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val charset = obj.get("charset")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }
            RequestOptions(method, headers, body, charset)
        }.getOrDefault(RequestOptions())
    }

    /** 带请求头抓取（可带 `URL,{...}` 里的请求配置：method / headers / body / charset） */
    private suspend fun fetch(url: String, optionsJson: String? = null): String? {
        val opts = parseRequestOptions(optionsJson)
        val h = LinkedHashMap(headers)
        h.putAll(opts.headers)
        return runCatching {
            if (opts.method == "POST") {
                val contentType = if (opts.body.trimStart().startsWith("{")) {
                    "application/json; charset=utf-8"
                } else {
                    "application/x-www-form-urlencoded"
                }
                HttpClient.post(url, opts.body, h, opts.charset, contentType)
            } else {
                HttpClient.get(url, h, opts.charset)
            }
        }.getOrNull()
    }
}
