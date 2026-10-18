package com.novelreader.source.legado

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * legado 规则引擎。
 *
 * 解析并执行 legado 书源的规则表达式，从 HTML / JSON 中提取数据。
 *
 * 支持的核心语法：
 * - 默认规则（短式）：`.class`、`.class.0`（数字为索引，负数从末尾取）、`#id`
 * - 显式前缀：`class.xxx`、`id.xxx`、`tag.xxx`、`text.关键词`
 * - 链式：`选择器1@选择器2@...@提取`（`@` 逐级向下选择，最后一段为提取符）
 * - 提取符：`@text`、`@html`、`@href`、`@src`、`@data-src` 等；缺省为 text
 * - `@css:选择器` 直接使用 CSS 选择器
 * - `@json:路径` 从 JSON 结果中提取字段
 * - `##正则` 或 `##正则##替换` 净化 / 替换（替换中可用 $1 分组引用）
 * - `||` 多个选择器（取第一个非空结果）
 *
 * 参考阅读 App（legado）的默认规则语法实现核心子集。
 */
object LegadoRuleEngine {

    /** 常见 HTML 标签名（用于把无前缀的裸 token 判定为 tag） */
    private val KNOWN_TAGS = setOf(
        "a", "abbr", "article", "aside", "b", "body", "br", "button", "dd", "div", "dl", "dt",
        "em", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "i", "img",
        "input", "li", "main", "nav", "ol", "option", "p", "section", "select", "small",
        "span", "strong", "table", "tbody", "td", "textarea", "tfoot", "th", "thead", "tr", "u", "ul",
    )

    /** legado 提取符（属性关键字） */
    private val ATTR_KEYS = setOf(
        "text", "html", "all", "ownhtml", "owntext", "ownText", "textNodes", "textnodes",
        "href", "src", "data-src", "data-original", "content", "value", "title", "alt", "data",
    )

    /** legado 索引区间语法（如 `1:6`、`-3:-1`） */
    private val RANGE_REGEX = Regex("-?\\d+:-?\\d+")

    // ===== legado 变量表：`@put:{k:v}` 写入 / `@get:{k}` 读取 =====

    /**
     * 变量表按线程隔离（[withVariables] 的代码块内不做挂起，因此不会跨线程漂移）。
     * 用匿名子类而非 `ThreadLocal.withInitial`，以兼容 minSdk 24。
     */
    private val variablesHolder = object : ThreadLocal<MutableMap<String, String>>() {
        override fun initialValue(): MutableMap<String, String> = HashMap()
    }

    /**
     * 规则变量表（legado 语义：挂在书源 / 书对象上的 variableMap）。
     *
     * `@put:{key:取值规则}` 把取值写入此表；`@get:{key}` 在后续规则、URL 模板中把值填回。
     * 不少 JSON API 源依赖这套写法，例如目录规则：
     * - 搜索：`$.comic_name@put:{bid:$.comic_id}`
     * - 目录：`.../getchapterlist?...&comic_id=@get:{bid}`
     *
     * 缺失 `@get:` 时地址里会残留字面量 → 接口返回错误数据或空 → 目录空白。
     *
     * 适配器处理「单条搜索结果 / 详情 / 目录」时用 [withVariables] 换上独立的表，
     * 使变量随条目隔离（否则多条搜索结果会互相覆盖）。
     */
    var variables: MutableMap<String, String>
        get() = variablesHolder.get() ?: HashMap<String, String>()
        set(value) = variablesHolder.set(value)

    /** 替换变量表执行 [block]，结束后恢复原表（可安全嵌套） */
    fun <T> withVariables(vars: MutableMap<String, String>, block: () -> T): T {
        val prev = variables
        variables = vars
        return try {
            block()
        } finally {
            variables = prev
        }
    }

    private val GET_RULE_REGEX = Regex("@get:\\{([^}]*)\\}")

    /** 把规则里的 `@get:{key}` 替换为变量值（未命中替换为空串，避免地址里残留字面量） */
    fun resolveGetRules(rule: String): String {
        if (!rule.contains("@get:")) return rule
        return GET_RULE_REGEX.replace(rule) { m -> variables[m.groupValues[1].trim()] ?: "" }
    }

    /**
     * 执行规则里的 `@put:{key:取值规则}` 段并剥离它们，返回可直接求值的规则。
     *
     * @param evaluate 对内部取值规则求值的函数（HTML 场景传元素提取，JSON 场景传节点提取）
     */
    fun applyPutRules(rule: String, evaluate: (String) -> String?): String {
        if (!rule.contains("@put:")) return rule
        val sb = StringBuilder()
        var i = 0
        while (i < rule.length) {
            val idx = rule.indexOf("@put:", i)
            if (idx < 0) {
                sb.append(rule, i, rule.length)
                break
            }
            sb.append(rule, i, idx)
            val braceStart = idx + 5
            if (braceStart >= rule.length || rule[braceStart] != '{') {
                i = braceStart
                continue
            }
            // 按花括号配对找到 `{...}` 结尾（取值规则自身可能含 {} 或 \{ \} 转义）
            var j = braceStart
            var depth = 0
            var end = -1
            while (j < rule.length) {
                val c = rule[j]
                val escaped = j > braceStart && rule[j - 1] == '\\'
                when {
                    c == '{' && !escaped -> depth++
                    c == '}' && !escaped -> {
                        depth--
                        if (depth == 0) end = j
                    }
                }
                if (end >= 0) break
                j++
            }
            if (end < 0) {
                i = braceStart
                continue
            }
            val body = rule.substring(braceStart + 1, end)
            val sep = body.indexOf(':')
            if (sep > 0) {
                val key = body.substring(0, sep).trim()
                val valueRule = body.substring(sep + 1).trim()
                if (key.isNotEmpty() && valueRule.isNotEmpty()) {
                    runCatching { evaluate(valueRule) }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { variables[key] = it }
                }
            }
            i = end + 1
        }
        return sb.toString()
    }

    /** 一次完成 `@put:` 求值 + `@get:` 回填（供适配器 JSON 规则复用） */
    fun resolveVariableRules(rule: String, evaluate: (String) -> String?): String =
        resolveGetRules(applyPutRules(rule, evaluate))

    /** HTML 规则场景的变量处理：以整篇文档为求值上下文 */
    private fun prepareRule(content: String, rule: String, baseUrl: String): String {
        if (!rule.contains("@get:") && !rule.contains("@put:")) return rule
        return resolveVariableRules(rule) { v -> extractFromDocument(content, v, baseUrl) }
    }

    /** 选择器步骤 */
    private sealed class Step {
        data class Cls(val v: String) : Step()
        data class Id(val v: String) : Step()
        data class Tag(val v: String) : Step()
        data class Text(val v: String) : Step()
        data class Index(val i: Int) : Step()
        /** 索引区间（legado `a:b`，含首含尾，支持负数从末尾取） */
        data class Range(val from: Int, val to: Int) : Step()
    }

    // ===== 字符串提取 =====

    /**
     * 从 HTML 中按规则提取字符串值。
     *
     * @param content HTML 或 JSON 文本
     * @param rule 规则表达式
     * @param baseUrl 相对地址转换用的基础地址
     */
    fun extractString(content: String, rule: String, baseUrl: String = ""): String? {
        if (rule.isBlank()) return null
        // 支持 || 多规则（取第一个非空）；任何异常都不得向外抛出
        for (r in rule.split("||")) {
            // `@put:{k:v}` 先写入变量表，`@get:{k}` 再回填，之后才求值
            val prepared = prepareRule(content.trim(), r.trim(), baseUrl)
            if (prepared.isBlank()) continue
            val result = runCatching { extractFromDocument(content.trim(), prepared, baseUrl) }.getOrNull()
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    /** 从完整 HTML/JSON 文档按规则提取单个字符串 */
    private fun extractFromDocument(content: String, rule: String, baseUrl: String): String? {
        // 前置 `<js>` 块：先跑 JS 得到新内容，再对结果套用后续规则
        // （legado 常见写法：`<js>整体解密 / 去包装</js>\n$.data.list`）
        splitLeadingJs(rule)?.let { (js, rest) ->
            val decoded = evalJsWithContent(js, content, baseUrl)
            if (!decoded.isNullOrBlank()) {
                return if (rest.isBlank()) decoded else extractFromDocument(decoded, rest, baseUrl)
            }
        }
        val trimmed = rule.trimStart()
        // 顶层 @json: 规则：从 JSON 内容提取（内容本身可能不是 JSON，先尝试）
        if (trimmed.startsWith("@json:", ignoreCase = true)) {
            val path = trimmed.replaceFirst(Regex("^@json:", RegexOption.IGNORE_CASE), "")
            return runCatching {
                extractJsonPath(com.google.gson.JsonParser.parseString(content), path)
            }.getOrNull()
        }
        val contentIsJson = content.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        // 尾部 JS 规则 + JSON 内容（如 `$.data.list@js:...`）：前段按 JSONPath 取值后交给 JS。
        // HTML 内容的尾部 JS 由 extractFromElement 内的 extractJsSuffix 处理（前段是选择器），
        // 这里只补 JSON 内容这一路 —— legado 的 splitSourceRule 对两种内容都成立。
        if (contentIsJson) {
            splitJsSuffixLegado(trimmed)?.let { (pre, js) ->
                val preValue = if (pre.isBlank()) {
                    content
                } else {
                    val path = pre.substringBefore("##").trim()
                    val v = runCatching { extractJsonPathFromRoot(content, path) }.getOrNull()
                    if (v != null && pre.contains("##")) {
                        applyReplace(v, pre.substringAfter("##"))
                    } else {
                        v ?: ""
                    }
                }
                return evalFieldJs(js, preValue, baseUrl)
            }
            // JSON 内容优先：裸键（`turl`）、`$.a.b` 路径都按 JSON 取（legado 的 isJSON 语义：
            // 内容为 JSON 时 SourceRule 直接走 Mode.Json，不会再当 HTML 选择器）。
            // 取不到再退回 HTML 解析，保证「同一规则两用」的源不受影响。
            val path = trimmed.substringBefore("##").trim()
            val jsonValue = runCatching { extractJsonPathFromRoot(content, path) }.getOrNull()
            if (!jsonValue.isNullOrBlank()) {
                return if (trimmed.contains("##")) {
                    applyReplace(jsonValue, trimmed.substringAfter("##"))
                } else {
                    jsonValue
                }
            }
        }
        // 裸 JSONPath（如 `$.data.list`、`$.a.b[0]`）：legado 中 JSON 源的规则常直接写 `$...`。
        // 内容确实是 JSON 时才按路径取；取不到再退回 HTML 解析（同一规则可能两用）。
        // 形如 `$.data.images@js:...` 的尾部 JS 规则，其前缀也依赖这里取到 result。
        // （JSON 内容已在上面统一处理，这里只补 HTML 内容里嵌 `$` 规则的兼容。）
        val doc = Jsoup.parse(content)
        return extractFromElement(doc.body() ?: doc, rule, baseUrl)
    }

    /**
     * 从 JSON 文本根节点按路径取字符串。
     *
     * 数组按**换行**拼接（`$.a[*]` 的常见用法）：正文 JS 里普遍写
     * `result.split("\n")` 来逐条处理，若返回 JSON 数组字面量就会解析失败。
     */
    private fun extractJsonPathFromRoot(content: String, path: String): String? {
        return runCatching {
            val node = selectJsonPath(com.google.gson.JsonParser.parseString(content), path)
                ?: return null
            when {
                node.isJsonPrimitive -> node.asString
                node.isJsonNull -> null
                node.isJsonArray -> node.asJsonArray.joinToString("\n") { el ->
                    if (el.isJsonPrimitive) el.asString else el.toString()
                }
                else -> node.toString()
            }
        }.getOrNull()
    }

    /**
     * 从单个元素按规则提取值（统一入口，支持 ## 正则、@css:、@ 链式）。
     */
    private fun extractFromElement(element: Element, rule: String, baseUrl: String): String? {
        if (rule.isBlank()) return element.text()

        // `@put:{k:v}` 写入 / `@get:{k}` 回填（legado 变量机制，目录 URL 模板里常见）
        var raw = rule
        if (raw.contains("@get:") || raw.contains("@put:")) {
            raw = resolveVariableRules(raw) { v -> extractFromElement(element, v, baseUrl) }
            if (raw.isBlank()) return null
        }

        // 先剥离 ## 正则（作用于最终取值）
        var expr = raw
        var replacePart: String? = null
        val hashIndex = expr.indexOf("##")
        if (hashIndex >= 0) {
            replacePart = expr.substring(hashIndex + 2)
            expr = expr.substring(0, hashIndex)
        }

        val value = selectAndExtract(element, expr.trim(), baseUrl)
        return if (replacePart != null) applyReplace(value, replacePart) else value
    }

    /** 按 `@` 分段执行：前面各段逐级向下选择，最后一段若是属性关键字则取属性，否则作为选择器取文本 */
    private fun selectAndExtract(root: Element, expr: String, baseUrl: String): String? {
        if (expr.isBlank()) return root.text()

        // `&&` 组合规则：各子规则结果依次拼接（legado 语义）
        if (expr.contains("&&")) {
            val parts = expr.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val sb = StringBuilder()
                for (p in parts) {
                    val v = selectAndExtract(root, p, baseUrl)
                    if (!v.isNullOrEmpty()) sb.append(v)
                }
                return sb.toString()
            }
        }

        // 尾部 JS 规则：`xxx@js:代码` 或 `xxx<js>代码</js>`（legado 语义：
        // 前段取值注入 result 变量，JS 返回值为最终结果）。
        // 桥的当前内容 = 规则所在元素（legado 里字段规则就是 setContent(item) 后求值的，
        // JS 内的 java.getString('text') / java.getElements('class.x') 都读这个元素）。
        extractJsSuffix(expr)?.let { (pre, js) ->
            val preValue = if (pre.isBlank()) root.text() else selectAndExtract(root, pre, baseUrl)
            return evalFieldJs(js, preValue, baseUrl, root)
        }

        xpathValues(root, expr, baseUrl)?.firstOrNull()?.let { return it }

        // @css: 直接选择器，支持 `@css:选择器@属性`（如 @css:img@src）
        if (expr.startsWith("@css:")) {
            val rest = expr.substringAfter("@css:")
            val at = rest.lastIndexOf('@')
            val (sel, attr) = if (at > 0) {
                val a = rest.substring(at + 1).trim()
                if (a.isNotBlank() && (isAttrKey(a) || a.matches(Regex("[\\w-]+")))) {
                    rest.substring(0, at).trim() to a
                } else rest.trim() to null
            } else rest.trim() to null
            val el = runCatching { root.selectFirst(sel) }.getOrNull() ?: return null
            return if (attr != null) extractTextOrAttr(el, attr, baseUrl) else el.text()
        }
        // @json: 元素文本中解析 JSON
        if (expr.startsWith("@json:", ignoreCase = true)) {
            val path = expr.replaceFirst(Regex("^@json:", RegexOption.IGNORE_CASE), "")
            return runCatching {
                extractJsonPath(com.google.gson.JsonParser.parseString(root.text()), path)
            }.getOrNull()
        }

        val segs = expr.split('@').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return root.text()

        var cur: List<Element> = listOf(root)
        for (i in 0 until segs.size - 1) {
            cur = walkSteps(cur, segs[i])
            if (cur.isEmpty()) return null
        }

        val last = segs.last()
        val first = cur.firstOrNull()
        // 最后一段是属性关键字，或元素确有该属性（legado 语义：@后跟任意属性名，如 @onclick/@data-lazyload）
        if (first != null && (isAttrKey(last) || first.hasAttr(last))) {
            // legado 语义：「如不加位置会获取所有」——匹配到多个元素时逐个取值并用换行拼接。
            // 漫画正文规则强依赖此行为（如 `.list@img@data-src@js:result.split("\n")`：
            // 只有把每个 img 的地址都取出来，JS 才能拼出完整的图片列表）。
            return if (cur.size > 1) {
                cur.mapNotNull { el -> extractTextOrAttr(el, last, baseUrl)?.takeIf { it.isNotBlank() } }
                    .joinToString("\n")
                    .trim()
            } else {
                extractTextOrAttr(first, last, baseUrl)
            }
        }
        // 否则最后一段是选择器 → 取所有匹配元素的文本（legado 语义：不写位置即获取全部）
        val els = walkSteps(cur, last)
        return when {
            els.isEmpty() -> null
            els.size == 1 -> els.first().text()
            else -> els.mapNotNull { it.text()?.takeIf { t -> t.isNotBlank() } }.joinToString("\n")
        }
    }

    /**
     * 拆分尾部 JS 规则。
     * 返回 (前置规则, JS 代码)，无 JS 段时返回 null。
     * 支持 `...@js:代码` 与 `...<js>代码</js>` 两种写法。
     */
    private fun extractJsSuffix(expr: String): Pair<String, String>? {
        // <js>...</js> 块（通常在规则末尾）
        val blockMatch = Regex("<js>([\\s\\S]*?)</js>").find(expr)
        if (blockMatch != null && blockMatch.range.last >= expr.length - 8) {
            val pre = expr.removeRange(blockMatch.range).trim()
            return pre to blockMatch.groupValues[1]
        }
        val idx = expr.lastIndexOf("@js:")
        if (idx >= 0) {
            return expr.substring(0, idx).trim() to expr.substring(idx + 4)
        }
        return null
    }

    /** 用 Rhino 求值字段 JS：注入 result（前段取值）、baseUrl 与 legado 风格 java.* */
    private fun evalFieldJs(
        code: String,
        result: String?,
        baseUrl: String,
        element: Element? = null,
    ): String? = evalJsInternal(
        code,
        result,
        // legado 语义：字段规则求值时 content = 规则所在元素，故 src 也是该元素
        src = element?.outerHtml(),
        baseUrl = baseUrl,
        elementForBridge = element,
    ).text

    /**
     * 求值正文类规则（支持尾部 `@js:` / `<js>`），返回 (取值, JS 设置的 headers JSON)。
     *
     * 与字段规则相比，正文类 JS 额外可用 `src`（原始页面文本，legado 中很多正文规则
     * 直接对 `src` 做正则/JSON 解析），并会读取 JS 中赋值的 `headers` / `header` 变量——
     * legado 语义：作为后续资源请求头，漫画源常用它设置防盗链 Referer。
     */
    fun evalContentRule(content: String, rule: String, pageUrl: String): Pair<String?, String?> {
        extractJsSuffix(rule)?.let { (pre, js) ->
            val preValue = if (pre.isBlank()) content
            else (runCatching { extractFromDocument(content, pre, pageUrl) }.getOrNull() ?: "")
            return evalJsRich(js, preValue, content, pageUrl)
        }
        val v = runCatching { extractFromDocument(content, rule, pageUrl) }.getOrNull()
        return v to null
    }

    /**
     * Rhino 求值：注入 `result` / `src` / `baseUrl` / `java.*`，
     * 返回 (字符串结果, JS 设置的 headers JSON)。任何异常都不得抛出，避免中断整条书源解析。
     *
     * 结果转换规则（对齐 legado `AnalyzeRule.evalJS` 的消费方式）：
     * - `undefined` / `null` → null（legado 的 RhinoScriptEngine 把 Undefined 转 null，
     *   早期实现用 `Context.toString(Undefined)` 得到字面量 "undefined"，
     *   会让 `java.put("a",baseUrl)` 这类只写变量不返回值的 init 规则把整个响应体替换成 "undefined"）；
     * - 数字 → 整数去掉小数点（legado：`Double && %1.0==0.0 → "%.0f"`）；
     * - 对象 / 数组（Scriptable）→ `NativeJSON.stringify`（否则 `Context.toString` 得到
     *   "[object Object]"，安稳小说的 init 返回 `{'turl':...}` 后就再也取不到 tocUrl）；
     * - 其余 → toString。
     */
    private fun evalJsRich(
        code: String,
        result: String?,
        src: String?,
        baseUrl: String,
    ): Pair<String?, String?> {
        val r = evalJsInternal(code, result, src, baseUrl)
        return r.text to r.headers
    }

    /** JS 求值结果（原始值 + 字符串化值 + headers），字符串化必须在 Context 内完成 */
    private class JsResult(val raw: Any?, val text: String?, val headers: String?)

    /** Rhino 求值公共实现：raw / text / headers 一次算出 */
    private fun evalJsInternal(
        code: String,
        result: Any?,
        src: String?,
        baseUrl: String,
        contentForBridge: Any? = result,
        elementForBridge: Element? = null,
    ): JsResult {
        return runCatching {
            val ctx = org.mozilla.javascript.Context.enter()
            try {
                // legado 大量源规则使用 `let` 声明（如中文书城的目录 JS），
                // Rhino 1.7.15 只有在语言版本 >= 1.8 时才认识 `let`（默认版本会报
                // "missing ; before statement" 直接解析失败）。
                ctx.optimizationLevel = -1
                ctx.languageVersion = org.mozilla.javascript.Context.VERSION_1_8
                val scope = ctx.initSafeStandardObjects()
                scope.put("result", scope, result ?: "")
                scope.put("baseUrl", scope, baseUrl)
                if (src != null) scope.put("src", scope, src)
                // legado 在 JS 作用域暴露 `book` 对象：海棠书屋的目录地址 JS 用
                // `book.origin + "/mytool/getVerify/..."` 拼验证码接口。
                // 这里给最小可用集（origin = 上下文 URL 的 scheme://host）。
                val bookObj = ctx.newObject(scope)
                bookObj.put("origin", scope, originOf(baseUrl))
                bookObj.put("bookUrl", scope, baseUrl)
                bookObj.put("name", scope, LegadoRuleEngine.variables["bookName"].orEmpty())
                bookObj.put("author", scope, LegadoRuleEngine.variables["author"].orEmpty())
                scope.put("book", scope, bookObj)
                val bridge = if (elementForBridge != null) {
                    LegadoJavaBridge(elementForBridge, src, baseUrl)
                } else {
                    LegadoJavaBridge(contentForBridge as? String, src as? String, baseUrl)
                }
                scope.put(
                    "java",
                    scope,
                    org.mozilla.javascript.Context.javaToJS(bridge, scope),
                )
                val v = ctx.evaluateString(scope, code, "<rule-js>", 1, null)
                JsResult(v, jsValueToString(v, ctx, scope), readJsHeaders(scope))
            } finally {
                org.mozilla.javascript.Context.exit()
            }
        }.getOrNull() ?: JsResult(null, null, null)
    }

    /**
     * 求值一段 JS 并把结果归一为「列表项」集合（供列表规则使用）。
     *
     * JS 段的常见返回形态与处理：
     * - 元素列表（`List<Element>` / Rhino NativeArray 包元素）→ 原样返回，
     *   调用方按元素提取字段（海马读书 / 废纸文学的目录重排 JS 即此形态）；
     * - 对象数组（中文书城 / 轻菠萝包 / 繁星四月的目录 JS）→ 整体 JSON 化后逐项当 JSON 节点；
     * - JSON 文本（`[...]` / `{...}`）→ 解析成节点；
     * - 其它（含普通字符串）→ 单元素列表，由调用方决定如何提取。
     *
     * @param result 绑定到 JS `result` 变量的值（选择器结果 / JSON 文本 / 页面文本）
     * @param src 绑定到 JS `src` 的原始页面文本（同时作为 `java.*` 桥的当前内容）
     */
    fun evalJsListResult(code: String, result: Any?, src: String?, baseUrl: String): List<Any>? {
        return runCatching {
            val ctx = org.mozilla.javascript.Context.enter()
            try {
                ctx.optimizationLevel = -1
                ctx.languageVersion = org.mozilla.javascript.Context.VERSION_1_8
                val scope = ctx.initSafeStandardObjects()
                scope.put("result", scope, result ?: "")
                scope.put("baseUrl", scope, baseUrl)
                if (src != null) scope.put("src", scope, src)
                scope.put(
                    "java",
                    scope,
                    org.mozilla.javascript.Context.javaToJS(
                        LegadoJavaBridge(src, src, baseUrl),
                        scope,
                    ),
                )
                val v = ctx.evaluateString(scope, code, "<rule-js>", 1, null)
                jsValueToItems(v, ctx, scope)
            } finally {
                org.mozilla.javascript.Context.exit()
            }
        }.getOrNull()
    }

    /** 剥离 Rhino 的 Java 对象包装（NativeJavaObject 等） */
    private fun unwrapJs(v: Any?): Any? {
        var cur = v
        while (cur is org.mozilla.javascript.Wrapper) {
            cur = cur.unwrap()
        }
        return cur
    }

    /**
     * 把 JS 求值结果转成列表项集合。
     *
     * 必须在 Rhino Context 内调用（对象数组的 JSON 化依赖当前 Context / scope）。
     */
    private fun jsValueToItems(
        v: Any?,
        cx: org.mozilla.javascript.Context,
        scope: org.mozilla.javascript.Scriptable,
    ): List<Any>? {
        val raw = unwrapJs(v) ?: return null
        return when (raw) {
            is List<*> -> {
                val items: List<Any> = raw.mapNotNull { unwrapJs(it) }
                // 含元素 → 元素列表（字段规则按 HTML 元素提取）
                if (items.any { it is Element }) {
                    items
                } else if (items.any { it is com.google.gson.JsonElement }) {
                    // 已经是 JSON 节点（JSON 路径取出的节点经 JS 原样返回，
                    // 如安稳小说 `$.data.chapterList[0].volumeList<js>...result</js>`）
                    // → 直接当节点，不能再整体 JSON 化（NativeJavaObject 会被序列化成bean属性）
                    items
                } else {
                    // 纯 JS 值数组 → 整体 JSON 化，逐项当 JSON 节点
                    val json = runCatching {
                        org.mozilla.javascript.NativeJSON.stringify(cx, scope, v, null, null)?.toString()
                    }.getOrNull()
                    if (!json.isNullOrBlank()) {
                        val jsonItems: List<Any> = runCatching {
                            val el = com.google.gson.JsonParser.parseString(json)
                            if (el.isJsonArray) el.asJsonArray.toList() else listOf<Any>(el)
                        }.getOrElse { items }
                        jsonItems
                    } else {
                        items
                    }
                }
            }

            is String -> {
                val t = raw.trim()
                if (t.startsWith("[") || t.startsWith("{")) {
                    runCatching {
                        val el = com.google.gson.JsonParser.parseString(t)
                        if (el.isJsonArray) el.asJsonArray.toList() else listOf(el)
                    }.getOrElse { listOf(raw) }
                } else {
                    listOf(raw)
                }
            }

            is Map<*, *> -> runCatching {
                listOf(com.google.gson.JsonParser.parseString(com.google.gson.Gson().toJson(raw)))
            }.getOrElse { listOf(raw) }

            // JS 数组 / 对象（NativeArray / NativeObject，如中文万维目录 JS 返回的
            // `[{name,url},...]`）：JSON 化后逐项当 JSON 节点。
            is org.mozilla.javascript.Scriptable -> {
                if (raw is org.mozilla.javascript.NativeArray) {
                    // 数组里包 Java 对象（Element 等）时直接剥出，避免 JSON 化丢类型
                    val unwrapped = (0 until raw.length.toInt()).mapNotNull { i -> unwrapJs(raw.get(i, raw)) }
                    if (unwrapped.any { it is Element }) unwrapped else jsScriptableToItems(raw, cx, scope)
                } else {
                    jsScriptableToItems(raw, cx, scope)
                }
            }

            else -> listOf(raw)
        }
    }

    /** JS 数组/对象 → 列表项（JSON 化后逐项当节点；失败时原样包一项） */
    private fun jsScriptableToItems(
        raw: org.mozilla.javascript.Scriptable,
        cx: org.mozilla.javascript.Context,
        scope: org.mozilla.javascript.Scriptable,
    ): List<Any> = runCatching {
        val json = org.mozilla.javascript.NativeJSON.stringify(cx, scope, raw, null, null)?.toString()
        if (!json.isNullOrBlank()) {
            val el = com.google.gson.JsonParser.parseString(json)
            if (el.isJsonArray) el.asJsonArray.toList() else listOf<Any>(el)
        } else {
            listOf(raw)
        }
    }.getOrElse { listOf(raw) }

    /** JS 求值结果 → 字符串（供字段 / 正文规则使用）；必须在 Rhino Context 内调用 */
    private fun jsValueToString(
        v: Any?,
        cx: org.mozilla.javascript.Context,
        scope: org.mozilla.javascript.Scriptable,
    ): String? {
        val raw = unwrapJs(v) ?: return null
        return when (raw) {
            is String -> raw
            is Double -> if (raw % 1.0 == 0.0) String.format("%.0f", raw) else raw.toString()
            is Float -> if (raw % 1.0f == 0.0f) String.format("%.0f", raw) else raw.toString()
            is Int, is Long, is Boolean, is CharSequence -> raw.toString()
            is org.mozilla.javascript.Scriptable -> runCatching {
                org.mozilla.javascript.NativeJSON.stringify(cx, scope, raw, null, null)
                    ?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            }.getOrNull()

            is Map<*, *>, is Collection<*> -> runCatching {
                com.google.gson.Gson().toJson(raw)
            }.getOrElse { raw.toString() }

            else -> raw.toString()
        }
    }

    /** 读取 JS 作用域中 `headers` / `header` 变量（JSON 对象）为 JSON 字符串 */
    private fun readJsHeaders(scope: org.mozilla.javascript.Scriptable): String? {
        val cx = org.mozilla.javascript.Context.getCurrentContext() ?: return null
        for (name in listOf("headers", "header")) {
            val raw = runCatching { scope.get(name, scope) }.getOrNull() ?: continue
            if (raw === org.mozilla.javascript.Scriptable.NOT_FOUND ||
                raw is org.mozilla.javascript.Undefined
            ) continue
            val json = runCatching {
                org.mozilla.javascript.NativeJSON.stringify(cx, scope, raw, null, null)?.toString()
            }.getOrNull()
            if (!json.isNullOrBlank() && json.trimStart().startsWith("{")) return json
        }
        return null
    }

    /**
     * 拆出规则**前置**的 `<js>...</js>` 块。
     *
     * legado 里列表 / 取值规则允许「先跑一段 JS 得到新内容，再对结果套用后续规则」，
     * 例如 `<js>整体解密</js>\n$.chapters[*]`（部分漫画 App 接口源都用它）。
     * 返回 (JS 代码, 剩余规则)；没有前置 JS 块时返回 null。
     */
    fun splitLeadingJs(rule: String?): Pair<String, String>? {
        val r = rule?.trimStart() ?: return null
        if (!r.startsWith("<js>")) return null
        val end = r.indexOf("</js>")
        if (end < 0) return null
        return r.substring(4, end) to r.substring(end + 5).trim()
    }

    /** 以 [content] 为数据源求值一段 JS（JS 里 `java.getString(...)` 读的就是这份内容） */
    fun evalJsWithContent(code: String, content: String, baseUrl: String): String? =
        evalJsRich(code, content, content, baseUrl).first

    /**
     * 渲染 URL / 请求配置模板里的 `{{JS 表达式}}` 占位（legado 语义）。
     *
     * `{{baseUrl}}` 之类简单占位由调用方精确替换；这里处理其余表达式，如
     * 爱下电子书 POST body 里的 `{{baseUrl.match(/\/(\d+)/)[1]}}`（从详情地址取 bid）。
     * 以 baseUrl 为作用域求值；失败或为空的占位保留原样，保证不比修复前更差。
     */
    fun evalTemplateJs(template: String, contextUrl: String): String {
        val placeholder = Regex("\\{\\{([\\s\\S]*?)\\}\\}")
        return placeholder.replace(template) { m ->
            val expr = m.groupValues[1].trim()
            if (expr.isEmpty()) return@replace m.value
            runCatching { evalJsWithContent(expr, "", contextUrl) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: m.value
        }
    }

    /** 以 JSON 节点为数据源求值 JS（JSON 字段规则里的 `@js:` / `<js>`） */
    fun evalJsonJs(code: String, node: com.google.gson.JsonElement?, baseUrl: String): String? {
        val json = node?.toString()
        return evalJsRich(code, json, json, baseUrl).first
    }

    /**
     * 多段链式规则中的 JS 段求值（v1.7.6.0）。
     *
     * 与 [evalJsonJs] 的区别：`result` 变量绑定的是**上一段规则的结果**，
     * 而不是整个 JSON —— legado 的多段规则（`$.bid` / `<js>...</js>` / `{{result}}`）
     * 就是把上一段的值接力传下去。JSON 原文仍在 `src` 里可取。
     *
     * @param carry 上一段规则的结果；为 null 时退化为与 [evalJsonJs] 相同（result = 整个 JSON）
     */
    fun evalJsonJsCarry(
        code: String,
        node: com.google.gson.JsonElement?,
        baseUrl: String,
        carry: String?,
    ): String? {
        val json = node?.toString()
        return evalJsRich(code, carry ?: json, json, baseUrl).first
    }

    /** 供 JS 桥接使用：按规则选取元素列表（支持 @ 链、@css:、- 倒置） */
    fun selectElements(content: String, rule: String): List<Element> {
        val doc = Jsoup.parse(content)
        return selectListElements(doc, rule).toList()
    }

    /** 在指定元素内按规则选取元素列表（`java.setContent(el)` 后的 `java.getElements(rule)`） */
    fun selectElementsIn(element: Element, rule: String): List<Element> =
        selectListElementsSingle(element, rule).toList()

    /**
     * 按 legado 的 `JS_PATTERN`（`<js>([\w\W]*?)</js>|@js:([\w\W]*)`）语义拆出**尾部 JS 段。
     *
     * 与 [extractJsSuffix]（lastIndexOf）的差别：`@js:` 取**第一次出现**且贪婪到结尾 ——
     * 这是 legado 的真实语义（`splitSourceRule` 用 `matcher.find()` 取首个匹配）。
     * 返回 (前置规则, JS 代码)；没有 JS 段时返回 null。
     */
    fun splitJsSuffixLegado(expr: String): Pair<String, String>? {
        val block = Regex("<js>([\\s\\S]*?)</js>", RegexOption.IGNORE_CASE).find(expr)
        if (block != null) {
            val pre = expr.removeRange(block.range).trim()
            return pre to block.groupValues[1]
        }
        val idx = expr.indexOf("@js:", ignoreCase = true)
        if (idx >= 0) {
            return expr.substring(0, idx).trim() to expr.substring(idx + 4)
        }
        return null
    }

    /** 供 JS 桥接使用：对单个元素按规则提取字段值（含 ## 正则、@css:、@ 链、尾部 JS） */
    fun extractFieldFromElement(element: Element, rule: String, baseUrl: String = ""): String? =
        runCatching { extractFromElement(element, rule, baseUrl) }.getOrNull()

    /**
     * 供 JS 桥接使用：按 legado `getStringList` 语义取字符串列表。
     *
     * 支持 `##正则[##替换]`（对每项生效）、`&&` 拼接、`||` 备选、`@css:` / `@` 链 /
     * XPath（Jsoup 不支持 `/text()` 后缀，自动剥落后取元素文本）。
     */
    fun extractStringList(content: String, rule: String, baseUrl: String = ""): List<String> {
        val doc = Jsoup.parse(content)
        return extractStringListFromElement(doc.body() ?: doc, rule, baseUrl)
    }

    /** 在指定元素内按规则取字符串列表（[extractStringList] 的元素版） */
    fun extractStringListFromElement(root: Element, rule: String, baseUrl: String = ""): List<String> {
        if (rule.isBlank()) return emptyList()
        // `##正则[##替换]` 只作用于取值，先剥离
        val hashIndex = rule.indexOf("##")
        val expr = (if (hashIndex >= 0) rule.substring(0, hashIndex) else rule).trim()
        val replaceRule = if (hashIndex >= 0) rule.substring(hashIndex + 2) else null
        if (expr.isBlank()) return emptyList()

        val out = mutableListOf<String>()
        // `||` 备选：取第一个非空（legado 语义）
        for (alt in expr.split("||").map { it.trim() }.filter { it.isNotEmpty() }) {
            out.clear()
            collectStringList(root, alt, baseUrl, out)
            if (out.isNotEmpty()) break
        }
        // `&&` 拼接：各段结果依次拼接（legado 语义）
        if (out.isEmpty() && expr.contains("&&")) {
            val parts = expr.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val sb = StringBuilder()
                for (p in parts) {
                    val tmp = mutableListOf<String>()
                    collectStringList(root, p, baseUrl, tmp)
                    if (tmp.isNotEmpty()) sb.append(tmp.first())
                }
                if (sb.isNotEmpty()) out.add(sb.toString())
            }
        }
        // 无替换规则时原样返回（replaceRule 为空表示规则里没有 `##` 段）
        return if (replaceRule == null) out else out.map { applyReplace(it, replaceRule) ?: it }
    }

    /** 按单段规则收集字符串列表（属性去重、空值跳过，与 legado `getResultLast` 一致） */
    private fun collectStringList(root: Element, rule: String, baseUrl: String, out: MutableList<String>) {
        xpathValues(root, rule, baseUrl)?.let { values ->
            values.forEach { value -> if (value.isNotBlank() && value !in out) out.add(value) }
            return
        }
        val segs = rule.split('@').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) {
            val t = root.text().trim()
            if (t.isNotEmpty()) out.add(t)
            return
        }
        var cur: List<Element> = listOf(root)
        for (i in 0 until segs.size - 1) {
            cur = walkSteps(cur, segs[i])
            if (cur.isEmpty()) return
        }
        val last = segs.last()
        if (isAttrKey(last)) {
            cur.forEach { el ->
                val v = extractTextOrAttr(el, last, baseUrl).trim()
                if (v.isNotBlank() && v !in out) out.add(v)
            }
        } else {
            val els = walkSteps(cur, last)
            els.forEach { el ->
                val t = el.text().trim()
                if (t.isNotEmpty() && t !in out) out.add(t)
            }
        }
    }

    private fun xpathValues(root: Element, rule: String, baseUrl: String): List<String>? {
        val expression = rule.trim()
        if (!expression.startsWith("//") && !expression.startsWith("(/")) return null

        val attribute = Regex("/@([\\w:-]+)$").find(expression)?.groupValues?.get(1)
        val selector = when {
            attribute != null -> expression.removeSuffix("/@$attribute")
            expression.endsWith("/text()") -> expression.removeSuffix("/text()")
            else -> expression
        }
        val elements = runCatching { root.selectXpath(selector) }.getOrNull() ?: return emptyList()
        return elements.mapNotNull { element ->
            val value = if (attribute != null) {
                extractTextOrAttr(element, attribute, baseUrl)
            } else {
                element.text()
            }
            value.trim().takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 统一的「原始列表项」提取（legado `AnalyzeRule.getElements` 语义）。
     *
     * 按顺序处理规则中的各段（legado `splitSourceRule` 把规则按 JS_PATTERN 切段）：
     * - **选择器段**（HTML）：选出元素列表，成为下一段的 `result`；
     * - **JSON 段**（`@json:` / `$...`，内容为 JSON 时）：按路径取节点列表（`[*]` 中段投影会拍平）；
     * - **JS 段**（`<js>...</js>` / `@js:...`）：以当前列表为 `result` 求值，结果再归一为列表项；
     * - **正则段**（`:` 前缀，legado allInOne 模式）：按正则取匹配，每项为分组列表。
     *
     * 返回的列表项可能是 [Element]（按 HTML 字段规则提取）、
     * [com.google.gson.JsonElement]（按 JSON 字段规则提取）、`List<String>`（正则分组，
     * 字段规则里的 `$1` `$2` 引用分组）或普通字符串。
     *
     * @param listRule 列表规则（调用方负责剥离 `+` / `-` 前后缀）
     */
    fun extractListRaw(content: String, listRule: String, baseUrl: String = ""): List<Any>? {
        var rule = listRule.trim()
        if (rule.isBlank()) return null
        // `@put:{k:v}` 写入 / `@get:{k}` 回填
        if (rule.contains("@get:") || rule.contains("@put:")) {
            rule = resolveVariableRules(rule) { v -> extractString(content, v, baseUrl) }
            if (rule.isBlank()) return null
        }
        // 防御性剥离 `+`（倒置 `-` 由调用方处理）
        if (rule.startsWith("+")) rule = rule.substring(1).trim()

        val contentIsJson = content.trimStart().let { it.startsWith("{") || it.startsWith("[") }

        // 前置 `<js>` 块：先跑 JS 得到新内容，再对结果套用剩余规则
        splitLeadingJs(rule)?.let { (js, rest) ->
            val decoded = evalJsWithContent(js, content, baseUrl)
            if (!decoded.isNullOrBlank()) {
                return if (rest.isBlank()) jsValueStringToItems(decoded) else extractListRaw(decoded, rest, baseUrl)
            }
        }

        // legado allInOne 正则模式：`:(?s)(\d+)...`
        if (rule.startsWith(":")) {
            return extractRegexList(content, rule.substring(1))
        }

        // 按 JS_PATTERN 切段，逐段接力（legado splitSourceRule + getElements）
        val segments = splitRuleSegments(rule)
        if (segments.isEmpty()) return null
        var current: List<Any>? = null
        for (seg in segments) {
            current = when (seg.mode) {
                SegMode.JS -> {
                    val jsResult = evalJsListResult(seg.text, current ?: content, content, baseUrl)
                    jsResult ?: return null
                }

                SegMode.JSON -> {
                    val root = runCatching { com.google.gson.JsonParser.parseString(content) }.getOrNull()
                        ?: return null
                    selectJsonList(root, seg.text)
                }

                else -> {
                    // HTML 选择器（内容其实是 JSON 时也按 legado 语义走 JSON 分支）
                    if (contentIsJson && (seg.text.startsWith("$") || seg.text.startsWith("@json:"))) {
                        val root = runCatching { com.google.gson.JsonParser.parseString(content) }.getOrNull()
                            ?: return null
                        selectJsonList(root, seg.text)
                    } else {
                        selectListElements(Jsoup.parse(content), seg.text).toList()
                    }
                }
            }
            if (current.isNullOrEmpty()) return null
        }
        return current
    }

    /** JS 段返回的字符串 → 列表项（JSON 数组解析成节点，普通字符串原样） */
    private fun jsValueStringToItems(text: String): List<Any> {
        val t = text.trim()
        if (t.startsWith("[") || t.startsWith("{")) {
            return runCatching {
                val el = com.google.gson.JsonParser.parseString(t)
                if (el.isJsonArray) el.asJsonArray.toList() else listOf(el)
            }.getOrElse { listOf(text) }
        }
        return listOf(text)
    }

    private enum class SegMode { SELECTOR, JSON, JS }

    private class RuleSegment(val mode: SegMode, val text: String)

    /**
     * 按 legado `splitSourceRule` 语义把规则切段：JS_PATTERN（`<js>...</js>` 非贪婪 /
     * `@js:` 贪婪到结尾）切出 JS 段，其余为选择器 / JSON 段。
     */
    private fun splitRuleSegments(rule: String): List<RuleSegment> {
        val out = mutableListOf<RuleSegment>()
        var start = 0
        val matcher = Regex("<js>([\\s\\S]*?)</js>|@js:([\\w\\W]*)", RegexOption.IGNORE_CASE)
        for (m in matcher.findAll(rule)) {
            if (m.range.first > start) {
                val pre = rule.substring(start, m.range.first).trim()
                if (pre.isNotEmpty()) out.add(RuleSegment(classifySegment(pre), pre))
            }
            val js = m.groupValues[1].ifEmpty { m.groupValues[2] }
            out.add(RuleSegment(SegMode.JS, js))
            start = m.range.last + 1
        }
        if (rule.length > start) {
            val rest = rule.substring(start).trim()
            if (rest.isNotEmpty()) out.add(RuleSegment(classifySegment(rest), rest))
        }
        return out
    }

    private fun classifySegment(text: String): SegMode = when {
        text.startsWith("@json:", ignoreCase = true) -> SegMode.JSON
        text.startsWith("$") -> SegMode.JSON
        else -> SegMode.SELECTOR
    }

    /**
     * legado allInOne 正则列表（`AnalyzeByRegex.getElements`）：
     * 每段正则的所有匹配整体拼接后交给下一段；最后一段的每个匹配成为一项，
     * 项内容为「整匹配 + 各分组」的字符串列表（字段规则用 `$1` `$2` 引用分组）。
     */
    private fun extractRegexList(content: String, rule: String): List<Any>? {
        val parts = rule.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        fun recurse(text: String, index: Int): List<Any>? {
            val matcher = runCatching { Regex(parts[index]).findAll(text) }.getOrNull() ?: return null
            val matches = matcher.toList()
            if (matches.isEmpty()) return null
            if (index + 1 == parts.size) {
                return matches.map { m ->
                    val groups = mutableListOf<String>()
                    for (g in 0..m.groupValues.size - 1) groups.add(m.groupValues.getOrNull(g).orEmpty())
                    groups
                }
            }
            val joined = matches.joinToString("") { it.value }
            return recurse(joined, index + 1)
        }
        return recurse(content, 0)
    }

    /**
     * 按 JSONPath 取「列表节点」（jayway 语义：中段 / 尾段 `[*]` 投影都会**拍平**）。
     *
     * 安轻小说的目录规则 `$.data.volumeList[*].chapterList[*]` 就依赖拍平 ——
     * 两层卷各自带 chapterList，最终要的是所有章节的平铺列表。
     */
    fun selectJsonList(root: com.google.gson.JsonElement, path: String): List<com.google.gson.JsonElement> {
        val cleaned = Regex("^@json:", RegexOption.IGNORE_CASE).replace(path.trim(), "").trim()
        val tokens = tokenizeJsonPath(cleaned) ?: return emptyList()
        if (tokens.isEmpty()) return listOf(root)
        return walkJsonPath(root, tokens, 0)
    }

    private fun tokenizeJsonPath(path: String): List<String>? {
        val tokens = mutableListOf<String>()
        var i = if (path.startsWith("$")) 1 else 0
        while (i < path.length) {
            when (path[i]) {
                '.' -> i++
                '[' -> {
                    i++
                    while (i < path.length && path[i].isWhitespace()) i++
                    if (i >= path.length) return null
                    val quote = path[i].takeIf { it == '\'' || it == '"' }
                    if (quote != null) {
                        i++
                        val key = StringBuilder()
                        var closed = false
                        while (i < path.length) {
                            val char = path[i++]
                            when {
                                char == '\\' -> {
                                    if (i >= path.length) return null
                                    val escaped = path[i++]
                                    if (escaped == 'u') {
                                        if (i + 4 > path.length) return null
                                        val code = path.substring(i, i + 4).toIntOrNull(16) ?: return null
                                        key.append(code.toChar())
                                        i += 4
                                    } else {
                                        key.append(
                                            when (escaped) {
                                                'b' -> '\b'
                                                'f' -> '\u000C'
                                                'n' -> '\n'
                                                'r' -> '\r'
                                                't' -> '\t'
                                                else -> escaped
                                            },
                                        )
                                    }
                                }
                                char == quote -> {
                                    closed = true
                                    break
                                }
                                else -> key.append(char)
                            }
                        }
                        if (!closed) return null
                        while (i < path.length && path[i].isWhitespace()) i++
                        if (i >= path.length || path[i] != ']') return null
                        i++
                        tokens += key.toString()
                    } else {
                        val end = path.indexOf(']', i)
                        if (end < 0) return null
                        val token = path.substring(i, end).trim()
                        if (token != "*" && token.toIntOrNull() == null) return null
                        tokens += token
                        i = end + 1
                    }
                }
                '$' -> return null
                else -> {
                    val start = i
                    while (i < path.length && path[i] != '.' && path[i] != '[') {
                        if (path[i] == ']') return null
                        i++
                    }
                    if (start == i) return null
                    tokens += path.substring(start, i)
                }
            }
        }
        return tokens
    }

    private fun walkJsonPath(
        node: com.google.gson.JsonElement,
        tokens: List<String>,
        i: Int,
    ): List<com.google.gson.JsonElement> {
        if (i >= tokens.size) return listOf(node)
        val t = tokens[i]
        if (t == "*") {
            val out = mutableListOf<com.google.gson.JsonElement>()
            when (node) {
                is com.google.gson.JsonArray -> node.forEach { out.addAll(walkJsonPath(it, tokens, i + 1)) }
                is com.google.gson.JsonObject -> node.entrySet()
                    .forEach { out.addAll(walkJsonPath(it.value, tokens, i + 1)) }

                else -> Unit
            }
            return out
        }
        val next = when (node) {
            is com.google.gson.JsonObject -> node.get(t) ?: return emptyList()
            is com.google.gson.JsonArray -> {
                val idx = t.toIntOrNull() ?: return emptyList()
                if (idx < 0 || idx >= node.size()) return emptyList()
                node.get(idx)
            }

            else -> return emptyList()
        }
        return walkJsonPath(next, tokens, i + 1)
    }

    private fun isAttrKey(s: String): Boolean {
        val key = s.trim().lowercase()
        return key in ATTR_KEYS.map { it.lowercase() }
    }

    /**
     * 在一组元素内按 legado 选择器继续向下选择。
     *
     * 若选择器是纯 CSS（含空格 / `>` / `[` / `:` / `,`），直接用 Jsoup 选择；
     * 否则按 legado 默认规则解析（`.class`、`#id`、`class.xx`、`tag.xx`、`text.xx`、数字索引）。
     *
     * 健壮性：任何非法选择器都不得抛出异常（否则会中断整条书源的解析），
     * 统一返回空列表；CSS 解析失败时回退到 legado 语法。
     */
    private fun walkSteps(roots: List<Element>, selector: String): List<Element> {
        val sel = selector.trim()
        if (sel.isEmpty()) return roots

        // 暂不支持：JS 规则（@js: / <js>...</js>）→ 返回空，避免当作 CSS 抛异常
        if (sel.startsWith("@js:") || sel.startsWith("<js>") || sel.contains("<js>")) return emptyList()

        // 显式 CSS
        if (sel.startsWith("@css:")) {
            val css = sel.substringAfter("@css:").trim()
            return safeSelect(roots) { it.select(css) }
        }

        // XPath（legado 语法：以 // 或 (/ 开头）
        if (sel.startsWith("//") || sel.startsWith("(/")) {
            return safeSelect(roots) { it.selectXpath(sel) }
        }

        // 纯 CSS 选择器（失败则回退到 legado 语法）
        if (looksLikeCss(sel)) {
            val cssResult = safeSelectOrNull(roots) { it.select(sel) }
            if (cssResult != null) return cssResult
        }

        val steps = parseLegadoSelector(sel)
        if (steps.isEmpty()) return roots

        var cur: List<Element> = roots
        for (step in steps) {
            cur = when (step) {
                is Step.Cls -> {
                    val v = step.v.trim()
                    if (v.isEmpty()) cur
                    // 支持复合类名（class.xxx yyy → .xxx.yyy）
                    else safeSelect(cur) { el ->
                        val css = if (v.contains(' ')) {
                            v.split(Regex("\\s+")).joinToString("") { ".$it" }
                        } else ".$v"
                        el.select(css)
                    }
                }
                is Step.Id -> step.v.trim().takeIf { it.isNotEmpty() }
                    ?.let { id -> safeSelect(cur) { it.select("#$id") } } ?: cur
                is Step.Tag -> step.v.trim().takeIf { it.isNotEmpty() }
                    ?.let { tag -> safeSelect(cur) { it.select(tag) } } ?: cur
                is Step.Text -> safeSelect(cur) { el ->
                    Elements(el.select("*").filter { c -> c.ownText().contains(step.v) })
                }
                is Step.Index -> {
                    val list = cur
                    val idx = if (step.i < 0) list.size + step.i else step.i
                    if (idx in list.indices) listOf(list[idx]) else emptyList()
                }
                is Step.Range -> {
                    val list = cur
                    val size = list.size
                    val from = if (step.from < 0) size + step.from else step.from
                    val to = if (step.to < 0) size + step.to else step.to
                    if (size == 0) emptyList()
                    else {
                        val f = from.coerceIn(0, size - 1)
                        val t = to.coerceIn(0, size - 1)
                        if (f <= t) list.subList(f, t + 1).toList() else emptyList()
                    }
                }
            }
            if (cur.isEmpty()) break
        }
        return cur
    }

    /** 逐个元素执行选择，忽略个别元素上的解析异常 */
    private inline fun safeSelect(roots: List<Element>, crossinline f: (Element) -> Elements): List<Element> {
        val out = mutableListOf<Element>()
        for (r in roots) {
            val els = runCatching { f(r) }.getOrNull() ?: continue
            out.addAll(els)
        }
        return out
    }

    /** 同上，但任一异常返回 null（用于判断「CSS 解析失败需回退」） */
    private inline fun safeSelectOrNull(roots: List<Element>, crossinline f: (Element) -> Elements): List<Element>? {
        val out = mutableListOf<Element>()
        for (r in roots) {
            val els = runCatching { f(r) }.getOrNull() ?: return null
            out.addAll(els)
        }
        return out
    }

    private fun looksLikeCss(sel: String): Boolean {
        // 显式带 legado 前缀 → 一律走 legado 语法（即使类名里含空格）
        if (sel.startsWith("class.") || sel.startsWith("id.") ||
            sel.startsWith("tag.") || sel.startsWith("text.")
        ) {
            return false
        }
        // `#` 只出现在 CSS 的 id 选择器里（legado 语法的 id 写作 `id.xxx`），
        // 因此带 `#` 一律按 CSS 解析。反例会把 `div#content` 拆成类名 `.div#content`
        // 一类的非法选择器 → 匹配不到元素 → 正文/字段静默为空（何以生肖、笔趣阁系）。
        return sel.indexOf(' ') >= 0 || sel.indexOf('>') >= 0 || sel.indexOf('[') >= 0 ||
            sel.indexOf(':') >= 0 || sel.indexOf(',') >= 0 || sel.indexOf('#') >= 0
    }

    /**
     * 解析 legado 默认规则选择器为步骤序列。
     *
     * 例：
     * - `.section-list.1`   → [Cls("section-list"), Index(1)]
     * - `.s2`               → [Cls("s2")]
     * - `class.book-list.0` → [Cls("book-list"), Index(0)]
     * - `tag.div.2`         → [Tag("div"), Index(2)]
     * - `p.0`               → [Tag("p"), Index(0)]
     * - `#content`          → [Id("content")]
     */
    private fun parseLegadoSelector(sel: String): List<Step> {
        val leadingDot = sel.startsWith(".")
        val parts = sel.split('.')
        val steps = mutableListOf<Step>()
        var i = 0
        var first = true
        while (i < parts.size) {
            val p = parts[i]
            if (p.isEmpty()) { i++; continue }
            when {
                // 显式前缀：class./id./tag./text. → 绑定下一个 token 作为值
                (p == "class" || p == "id" || p == "tag" || p == "text") && i + 1 < parts.size -> {
                    val v = parts[i + 1]
                    // 值带区间后缀（如 `tag.span[0:1]`）→ 选择后取区间
                    val rangeMatch = Regex("^(.*)\\[(-?\\d+:-?\\d+)]$").find(v)
                    if (rangeMatch != null) {
                        val name = rangeMatch.groupValues[1]
                        val r = rangeMatch.groupValues[2].split(':').map { it.toInt() }
                        when (p) {
                            "class" -> steps.add(Step.Cls(name))
                            "id" -> steps.add(Step.Id(name))
                            "tag" -> steps.add(Step.Tag(name))
                            else -> steps.add(Step.Text(name))
                        }
                        steps.add(Step.Range(r[0], r[1]))
                    } else {
                        when (p) {
                            "class" -> steps.add(Step.Cls(v))
                            "id" -> steps.add(Step.Id(v))
                            "tag" -> steps.add(Step.Tag(v))
                            else -> steps.add(Step.Text(v))
                        }
                    }
                    i += 2
                    first = false
                    continue
                }
                p.startsWith("#") -> steps.add(Step.Id(p.substring(1)))
                RANGE_REGEX.matches(p) -> {
                    val a = p.substringBefore(':').toIntOrNull() ?: 0
                    val b = p.substringAfter(':').toIntOrNull() ?: 0
                    steps.add(Step.Range(a, b))
                }
                p.toIntOrNull() != null -> steps.add(Step.Index(p.toInt()))
                // 标签/类名带区间后缀（如 `span[0:1]`、`li[1:5]`）→ 选择后取区间
                p.contains('[') && p.endsWith("]") &&
                    RANGE_REGEX.matches(p.substringAfterLast('[').removeSuffix("]")) -> {
                    val name = p.substringBeforeLast('[')
                    val r = p.substringAfterLast('[').removeSuffix("]")
                    val a = r.substringBefore(':').toIntOrNull() ?: 0
                    val b = r.substringAfter(':').toIntOrNull() ?: 0
                    when {
                        name.lowercase() in KNOWN_TAGS -> steps.add(Step.Tag(name))
                        else -> steps.add(Step.Cls(name))
                    }
                    steps.add(Step.Range(a, b))
                }
                // 无前缀裸 token：首段且在无前导点、且是常见标签名 → tag；否则按 class
                first && !leadingDot && p.lowercase() in KNOWN_TAGS -> steps.add(Step.Tag(p))
                else -> steps.add(Step.Cls(p))
            }
            first = false
            i++
        }
        return steps
    }

    // ===== 列表提取 =====

    /**
     * 从 HTML 中按规则提取列表。
     *
     * @param content HTML/JSON 文本
     * @param listRule 列表项选择器（如 `class.book-list@li`、`.section-list.1@li a` 或 `@json:$.data`）
     * @param fieldRules 字段名 -> 字段规则
     */
    fun extractList(
        content: String,
        listRule: String,
        fieldRules: Map<String, String>,
        baseUrl: String = "",
        varsSink: MutableList<Map<String, String>>? = null,
    ): List<Map<String, String>> {
        if (listRule.isBlank()) return emptyList()

        // `@put:` / `@get:`（legado 变量机制）：列表规则里也可能用到变量
        val resolvedListRule =
            if (listRule.contains("@get:") || listRule.contains("@put:")) {
                resolveVariableRules(listRule) { v -> extractString(content, v, baseUrl) }
            } else listRule
        if (resolvedListRule.isBlank()) return emptyList()

        // legado 语义（BookChapterList）：先判 `-` 前缀 = 结果倒置，再剥 `+` 前缀（仅剥离）。
        // 注意与负索引（-1 取最后一个）区分：`-` 后紧跟数字时不视为倒置标记。
        var listRuleResolved = resolvedListRule
        var reverse = false
        if (listRuleResolved.length > 1 && listRuleResolved.startsWith("-") && !listRuleResolved[1].isDigit()) {
            reverse = true
            listRuleResolved = listRuleResolved.substring(1).trim()
        }
        if (listRuleResolved.startsWith("+")) {
            listRuleResolved = listRuleResolved.substring(1).trim()
        }
        val rule = listRuleResolved

        // 处理 @json: 列表（大小写不敏感：@json: / @JSon: 都有源在用）
        if (rule.startsWith("@json:", ignoreCase = true)) {
            val base = varsSink?.size ?: 0
            val list = extractJsonList(content, rule, fieldRules, varsSink)
            if (!reverse) return list
            // 倒置时变量表顺序也要跟着倒过来（两者必须同序，调用方按下标配对）
            varsSink?.let { sink ->
                for (i in 0 until list.size / 2) {
                    val a = base + i
                    val b = base + list.size - 1 - i
                    val t = sink[a]; sink[a] = sink[b]; sink[b] = t
                }
            }
            return list.reversed()
        }

        val doc = Jsoup.parse(content)
        val items = selectListElements(doc, rule)
        if (items.isEmpty()) return emptyList()

        // 每条记录用独立变量表求值：`@put:{bid:...}` 必须按条目隔离，
        // 否则多条搜索结果会互相覆盖，点开任意一条都拿到最后一条的 ID。
        // 以当前变量表为初值，保证外层（搜索/详情阶段）写入的变量仍可见。
        val pairs = items.map { el ->
            val vars = LinkedHashMap(variables)
            val fields = withVariables(vars) {
                fieldRules.mapValues { (_, r) ->
                    runCatching { extractFromElement(el, r, baseUrl) }.getOrNull() ?: ""
                }
            }
            fields to vars
        }
        val ordered = if (reverse) pairs.reversed() else pairs
        varsSink?.addAll(ordered.map { it.second })
        return ordered.map { it.first }
    }

    /** 在一段规则上选取列表元素（支持 || 备选） */
    private fun selectListElements(doc: Document, listRule: String): Elements {
        for (r in listRule.split("||")) {
            val trimmed = r.trim()
            if (trimmed.isEmpty()) continue
            val elements = selectListElementsSingle(doc, trimmed)
            if (elements.isNotEmpty()) return elements
        }
        return Elements()
    }

    /**
     * 解析 legado 位置排除标记：`!idx` 或 `!a:b`（负数为倒数序号）。
     * 文档：「! 是排除，后面的序号用 : 隔开，0 是第 1 个，负数为倒数序号」。
     */
    private fun parseExcludedIndices(rule: String): Set<Int> {
        if (!rule.contains('!')) return emptySet()
        val out = mutableSetOf<Int>()
        Regex("!(-?\\d+)(?::(-?\\d+))?").findAll(rule).forEach { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@forEach
            val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (b == null) {
                out.add(a)
            } else if (a >= 0 && b >= 0) {
                for (i in minOf(a, b)..maxOf(a, b)) out.add(i)
            } else {
                out.add(a); out.add(b)
            }
        }
        return out
    }

    /** 按排除索引集合过滤（同时按正索引与负索引匹配） */
    private fun filterExcluded(els: Elements, excluded: Set<Int>): Elements {
        if (excluded.isEmpty()) return els
        val out = Elements()
        els.forEachIndexed { i, el ->
            if (i !in excluded && (i - els.size) !in excluded) out.add(el)
        }
        return out
    }

    /** 选取单个列表规则的元素：`容器1@容器2@...@子选择器`（多段 @ 逐级向下） */
    private fun selectListElementsSingle(doc: Document, rule: String): Elements =
        selectListElementsSingle(doc.body() ?: doc, rule)

    /**
     * 选取单个列表规则的元素（[root] 为起点，可以是文档体也可以是某个元素 ——
     * `java.getElements(rule)` 在 `java.setContent(element)` 之后就要在元素内选取）。
     */
    private fun selectListElementsSingle(root: Element, rule: String): Elements {
        val excluded = parseExcludedIndices(rule)

        if (rule.startsWith("@css:")) {
            val els = runCatching { root.select(rule.substringAfter("@css:").trim()) }.getOrDefault(Elements())
            return filterExcluded(els, excluded)
        }

        // 去掉位置排除标记（如 `li!0`、`class.item!0:1`），排除在结果上统一生效
        val sanitized = rule.replace(Regex("!-?\\d+(?::-?\\d+)?"), "")
        val segs = sanitized.split('@').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return Elements()

        val itemPart = if (segs.size > 1) segs.last() else ""
        val containerSegs = if (segs.size > 1) segs.dropLast(1) else segs

        var containers = walkSteps(listOf(root), containerSegs.first())
        for (i in 1 until containerSegs.size) {
            containers = walkSteps(containers, containerSegs[i])
            if (containers.isEmpty()) break
        }
        if (containers.isEmpty()) return Elements()

        val out = Elements()
        if (itemPart.isBlank()) {
            containers.forEach { out.add(it) }
        } else {
            containers.forEach { c ->
                if (looksLikeCss(itemPart)) {
                    val els = runCatching { c.select(itemPart) }.getOrNull()
                    if (els != null) out.addAll(els) else walkSteps(listOf(c), itemPart).forEach { out.add(it) }
                } else {
                    walkSteps(listOf(c), itemPart).forEach { out.add(it) }
                }
            }
        }
        return filterExcluded(out, excluded)
    }

    /** 从 JSON 内容按 @json: 规则提取列表 */
    private fun extractJsonList(
        json: String,
        listRule: String,
        fieldRules: Map<String, String>,
        varsSink: MutableList<Map<String, String>>? = null,
    ): List<Map<String, String>> {
        // 前缀大小写不敏感（@json: / @JSon:）
        val jsonPath = listRule.replaceFirst(Regex("^@json:", RegexOption.IGNORE_CASE), "").trim()
        return runCatching {
            val root = com.google.gson.JsonParser.parseString(json)
            val arr = selectJsonArray(root, jsonPath)
            arr.map { node ->
                // 每条记录独立变量表（`@put:{bid:...}` 需按条目隔离）。
                // 以当前变量表为初值：目录阶段的外层变量（搜索/详情写入）必须仍可见，
                // 否则章节地址里的 `@get:` 会被空表遮蔽而取到空串。
                val vars = LinkedHashMap(variables)
                val fields = withVariables(vars) {
                    fieldRules.mapValues { (_, rule) ->
                        extractFromJsonNode(node, rule) ?: ""
                    }
                }
                varsSink?.add(vars)
                fields
            }
        }.getOrDefault(emptyList())
    }

    /** 从元素按属性类型提取值 */
    private fun extractTextOrAttr(element: Element, attr: String, baseUrl: String): String {
        return when (attr.trim().lowercase()) {
            "text" -> element.text()
            "textnodes" -> element.text()
            "html", "all" -> element.html()
            "ownhtml", "owntext" -> element.ownText()
            "href" -> element.attr("href")
            "src" -> element.attr("src").ifEmpty { element.attr("data-src") }.ifEmpty { element.attr("data-original") }
            "data-src" -> element.attr("data-src").ifEmpty { element.attr("src") }
            "data-original" -> element.attr("data-original").ifEmpty { element.attr("src") }
            else -> element.attr(attr.trim())
        }
    }

    /** 正则替换（支持 `##正则##替换` 或 `##正则` 简写） */
    private fun applyReplace(value: String?, replaceRule: String): String? {
        if (value == null) return null
        return runCatching {
            if (replaceRule.contains("##")) {
                val parts = replaceRule.split("##", limit = 2)
                value.replace(Regex(parts[0]), parts.getOrElse(1) { "" })
            } else {
                value.replace(Regex(replaceRule), "")
            }
        }.getOrDefault(value)
    }

    // ===== JSON 提取 =====

    private fun extractFromJsonNode(node: com.google.gson.JsonElement, rule: String): String? {
        // `@put:{k:v}` / `@get:{k}`（legado 变量机制）
        var normalized = rule
        if (normalized.contains("@get:") || normalized.contains("@put:")) {
            normalized = resolveVariableRules(normalized) { v -> extractFromJsonNode(node, v) }
            if (normalized.isBlank()) return null
        }
        // 尾部 JS（`$.x@js:...` / `$.x<js>...</js>`）：前段按路径取值后交给 JS（legado splitSourceRule 语义）。
        // 桥的当前内容 = 整个节点（`java.getString('$.x')` 读的是节点），result = 前段取值。
        splitJsSuffixLegado(normalized.trim())?.let { (pre, js) ->
            val path = pre.substringBefore("##").trim().removePrefix("@json:")
            val preValue = if (path.isBlank()) {
                node.toString()
            } else {
                val v = runCatching { extractJsonPath(node, path) }.getOrNull()
                if (v != null && pre.contains("##")) applyReplace(v, pre.substringAfter("##")) else v
            }
            return evalJsonJsCarry(js, node, "", preValue)
        }
        val path = if (normalized.startsWith("@json:")) normalized.substringAfter("@json:") else normalized
        return extractJsonPath(node, path)
    }

    private fun extractJsonPath(node: com.google.gson.JsonElement, path: String): String? {
        return runCatching {
            val target = selectJsonPath(node, path) ?: return null
            when {
                target.isJsonPrimitive -> target.asString
                target.isJsonNull -> null
                else -> target.toString()
            }
        }.getOrNull()
    }

    /** 按 JSONPath 子集选择节点 */
    private fun selectJsonPath(node: com.google.gson.JsonElement, path: String): com.google.gson.JsonElement? {
        val values = runCatching { selectJsonList(node, path) }.getOrNull() ?: return null
        return when (values.size) {
            0 -> null
            1 -> values[0]
            // 多值（`[*]` 投影）→ 收成数组，由调用方决定拼接方式
            else -> com.google.gson.JsonArray().also { arr -> values.forEach { arr.add(it) } }
        }
    }

    private fun selectJsonArray(root: com.google.gson.JsonElement, path: String): List<com.google.gson.JsonElement> {
        val target = selectJsonPath(root, path)
        return when {
            target == null -> emptyList()
            target.isJsonArray -> target.asJsonArray.toList()
            else -> listOf(target)
        }
    }

    // ===== URL 工具 =====

    /** 相对 URL 转绝对 URL */
    private fun absUrl(baseUrl: String, url: String): String {
        // 先拆多匹配拼接串（见 sanitizeUrl 注释）：相对地址也要按首行解析，
        // 否则 `new URL(base, "/a\n/b")` 会把整串当路径解析出垃圾地址。
        val first = firstUrlLine(url) ?: return ""
        if (first.startsWith("http://") || first.startsWith("https://")) return sanitizeUrl(first)
        if (baseUrl.isBlank()) return sanitizeUrl(first)
        val resolved = runCatching {
            java.net.URL(java.net.URL(baseUrl), first).toString()
        }.getOrElse { first }
        return sanitizeUrl(resolved)
    }

    /**
     * 取 URL 串的首个非空行。
     *
     * `tag.a@href` 这类不带位置的规则命中多个元素时，legado 语义是「全部获取、
     * 换行拼接」（漫画正文取多图强依赖此行为）。但 URL 字段（bookUrl / tocUrl /
     * chapterUrl / 图片地址）拿到拼接串就会把后续地址一起编码进请求
     * （实测 `...html%0Ahttp://...` → 服务端返回 1 字节错误页，链路静默失败）。
     * URL 不可能合法包含换行 —— 取首个非空行，与源里写 `.0` 位置的取法等效。
     */
    private fun firstUrlLine(url: String): String? {
        if (!url.contains('\n') && !url.contains('\r')) return url.trim().ifEmpty { null }
        return url.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    /**
     * 清洗 URL：编码空格、中文等非法字符，保留既有 `%XX` 转义。
     * 避免 `Illegal character in URL` 之类的异常导致整条链路失败。
     */
    private fun sanitizeUrl(url: String): String {
        val u = firstUrlLine(url) ?: ""
        if (u.isEmpty()) return u
        // 已经合法则原样返回
        runCatching { java.net.URI(u) }.onSuccess { return u }
        var pre = u
        if (pre.contains(' ')) pre = pre.replace(" ", "%20")
        runCatching { java.net.URI(pre) }.onSuccess { return it.toASCIIString() }
        // 退化为逐字节百分号编码（保留 URL 结构字符）
        val keep = "-._~:/?#[]@!$&'()*+,;=%"
        return buildString {
            for (b in pre.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 128 && (ch.isLetterOrDigit() || keep.indexOf(ch) >= 0)) append(ch)
                else append('%').append(String.format("%02X", c))
            }
        }
    }

    /** 公开的 URL 转换工具 */
    fun toAbsoluteUrl(baseUrl: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        return absUrl(baseUrl, url)
    }

    /** 取 URL 的 origin（scheme://host[:port]）；解析失败时原样返回 */
    internal fun originOf(url: String): String = runCatching {
        val u = java.net.URI(url)
        if (u.scheme.isNullOrBlank()) url else "${u.scheme}://${u.authority}"
    }.getOrElse { url }
}

/**
 * legado 书源 `@js:` 规则中的 `java.*` 兼容桥。
 *
 * 开源阅读把一批 Java 能力暴露给 JS 规则，漫画 / 小说源尤其依赖它（`java.getString`、
 * `java.getStringList`、`java.getElements`、`java.setContent`、`java.get/put` 等）。
 * 这里实现最常用的一批；未实现的成员调用时抛错，由上层 `runCatching` 兜住
 * （与「不支持」等效，不会中断整条书源解析）。
 *
 * 与 legado `AnalyzeRule`（JS 里 `java` 就是它本身）的关键对齐点：
 * - `java.getString(rule)` / `java.getElements(rule)` 读的是**当前内容**，
 *   `java.setContent(x)` 可把当前内容切成某个元素 / 文本（轻菠萝包的目录 JS 全靠它）；
 * - `java.put(k, v)` 写入**书源变量表**（跨 搜索→详情→目录→正文 存活），
 *   并返回 v（legado 的 put 返回 value，JS 里常写 `java.put('bid',result);'...'+result`）；
 * - `java.get(k)` 读变量表。
 *
 * 说明：本类实例通过 `Context.javaToJS` 注入，方法经反射调用 —— 桥类与 public 方法必须 public。
 */
class LegadoJavaBridge(
    private var currentHtml: String?,
    private val src: String?,
    private val baseUrl: String,
) {
    /** `java.setContent(element)` 时保留元素引用，`getString`/`getElements` 直接在元素上求值 */
    private var currentElement: Element? = null

    /** 字段 JS 专用：桥的当前内容就是规则所在的元素（legado `setContent(item)` 语义） */
    constructor(element: Element, src: String?, baseUrl: String) : this(
        element.outerHtml(),
        src,
        baseUrl,
    ) {
        currentElement = element
    }

    fun log(msg: Any?) {
        // 源自己打出的日志内容不可控（常直接带站点名 / 接口地址），只记录长度
        android.util.Log.d("LegadoJs", "(log ${msg?.toString()?.length ?: 0} chars)")
    }

    /**
     * 切换当前内容（legado `AnalyzeRule.setContent`）。
     *
     * 传元素时同时保留元素引用与 outerHtml：`java.getString('text')` 读元素文本、
     * `java.getElements('class.x')` 在元素内选取；传文本 / null 时退回纯文本内容。
     */
    fun setContent(content: Any?) {
        val raw = unwrapJsValue(content)
        when (raw) {
            is Element -> {
                currentElement = raw
                currentHtml = raw.outerHtml()
            }

            null -> {
                currentElement = null
                currentHtml = null
            }

            else -> {
                currentElement = null
                currentHtml = raw.toString()
            }
        }
    }

    /** 读变量表（线程隔离，搜索→详情→目录 同链路可见） */
    fun get(key: String): Any? {
        val v = LegadoRuleEngine.variables[key]
        if (v != null) return v
        return when (key) {
            "bookName", "title" -> ""
            else -> null
        }
    }

    /** 写变量表并返回 value（legado 语义：`put` 返回 value） */
    fun put(key: String, value: Any?): Any? {
        LegadoRuleEngine.variables[key] = unwrapJsValue(value)?.toString() ?: ""
        return value
    }

    /** 同步 HTTP GET；legado 中 java.ajax 亦支持带 headers 的重载 */
    fun ajax(url: String): String =
        runCatching { com.novelreader.core.network.HttpClient.get(url) }.getOrDefault("")

    /**
     * 取验证码（legado `java.getVerificationCode`）：对验证码接口发一次同步 GET。
     * 海棠书屋的目录地址 JS 用它拿 verify 值再 POST 取全量目录。
     */
    fun getVerificationCode(url: String): String = ajax(url)

    fun ajax(url: String, headers: Map<*, *>?): String {
        val h = headers?.entries
            ?.mapNotNull { (k, v) -> if (k != null && v != null) k.toString() to v.toString() else null }
            ?.toMap()
            .orEmpty()
        return runCatching { com.novelreader.core.network.HttpClient.get(url, h) }.getOrDefault("")
    }

    /** 无参：当前内容原文；带规则：对当前内容再套一层 legado 规则 */
    fun getString(): String = currentElement?.text() ?: currentHtml.orEmpty()

    fun getString(rule: String): String {
        val el = currentElement
        if (el != null) return LegadoRuleEngine.extractFieldFromElement(el, rule, baseUrl).orEmpty()
        val html = currentHtml ?: return ""
        return LegadoRuleEngine.extractString(html, rule, baseUrl).orEmpty()
    }

    /** 按规则取字符串列表（legado `java.getStringList`，轻菠萝包目录 JS 取卷名用它） */
    fun getStringList(rule: String): List<String> {
        val el = currentElement
        if (el != null) return LegadoRuleEngine.extractStringListFromElement(el, rule, baseUrl)
        val html = currentHtml ?: return emptyList()
        return LegadoRuleEngine.extractStringList(html, rule, baseUrl)
    }

    /** 按规则取元素列表（返回 jsoup Element，供 JS 侧继续 .text()/.attr()） */
    fun getElements(rule: String): List<Element> {
        val el = currentElement
        if (el != null) return LegadoRuleEngine.selectElementsIn(el, rule)
        val html = currentHtml ?: return emptyList()
        return LegadoRuleEngine.selectElements(html, rule)
    }

    /**
     * 按规则取**单个**元素（legado 部分源写 `java.getElement("a")`，
     * 如海棠书屋的「请输入验证码」分支把反序拼接的 HTML 重新拆出链接）。
     * 取不到返回 null（JS 侧 `result = java.getElement(...)` 后由上层兜底）。
     */
    fun getElement(rule: String): Element? = getElements(rule).firstOrNull()

    /** 原始页面文本（等价 legado 的 src 变量） */
    fun getSource(): String = src.orEmpty()

    /** 剥离 Rhino 的 Java 对象包装 */
    private fun unwrapJsValue(v: Any?): Any? {
        var cur = v
        while (cur is org.mozilla.javascript.Wrapper) cur = cur.unwrap()
        return cur
    }

    fun t2s(text: String): String = com.novelreader.core.text.ChineseConverter.toSimplified(text)

    fun s2t(text: String): String = com.novelreader.core.text.ChineseConverter.toTraditional(text)

    fun base64Decode(text: String): String =
        runCatching { String(Base64Codec.decode(text), Charsets.UTF_8) }.getOrDefault("")

    fun base64Encode(text: String): String =
        runCatching { Base64Codec.encode(text.toByteArray(Charsets.UTF_8)) }.getOrDefault("")

    /**
     * MD5（小写十六进制）。
     *
     * 大量「App 接口型」漫画 / 小说源用它做接口签名：
     * `sign=java.md5Encode("/comic-api/v2/...&client-version=1.4.8erciyuan2020")`，
     * 再把 sign 放进请求头 `m-request-id`，缺失时服务端直接返回空数据。
     */
    fun md5Encode(text: String): String = runCatching {
        val md = java.security.MessageDigest.getInstance("MD5")
        md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** MD5 短码（取中间 16 位，部分源用此写法） */
    fun md5Encode16(text: String): String {
        val full = md5Encode(text)
        return if (full.length == 32) full.substring(8, 24) else full
    }

    /** SHA-1（十六进制，小写） */
    fun digestHex(text: String): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /**
     * AES 解密（Base64 输入 → 明文字符串）。
     *
     * 对应 legado 的 `java.aesBase64DecodeToString(data, key, transformation, iv)`。
     * 与 legado 一致：key / iv 都按 UTF-8 字节直接使用（16 字节即 AES-128）。
     * 参数不合法时返回空串，由上层 `runCatching` 兜住，不中断整条书源解析。
     */
    fun aesBase64DecodeToString(
        data: String,
        key: String,
        transformation: String = "AES/CBC/PKCS5Padding",
        iv: String = "",
    ): String = runCatching {
        String(aesDoFinal(Base64Codec.decode(data), key, iv, transformation, false), Charsets.UTF_8)
    }.getOrDefault("")

    /** 同 [aesBase64DecodeToString]，兼容部分源使用的别名 */
    fun aesDecodeToString(
        data: String,
        key: String,
        transformation: String = "AES/CBC/PKCS5Padding",
        iv: String = "",
    ): String = aesBase64DecodeToString(data, key, transformation, iv)

    /** AES 加密（明文 → Base64），对应 legado 的 `java.aesBase64EncodeToString` */
    fun aesBase64EncodeToString(
        data: String,
        key: String,
        transformation: String = "AES/CBC/PKCS5Padding",
        iv: String = "",
    ): String = runCatching {
        Base64Codec.encode(aesDoFinal(data.toByteArray(Charsets.UTF_8), key, iv, transformation, true))
    }.getOrDefault("")

    /** 十六进制字符串 → 文本 */
    fun hexDecodeToString(hex: String): String = runCatching {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        String(bytes, Charsets.UTF_8)
    }.getOrDefault("")

    /**
     * 时间格式化（毫秒时间戳 → 文本）。
     *
     * legado 的 `java.timeFormat(time)` 默认格式为 `yyyy/MM/dd HH:mm`；
     * 源里常写 `java.timeFormat($.update_time).replace(/\//g,"-")`。
     */
    fun timeFormat(time: Long): String = timeFormat(time, "yyyy/MM/dd HH:mm")

    fun timeFormat(time: String): String = timeFormat(time.toLongOrNull() ?: 0L, "yyyy/MM/dd HH:mm")

    fun timeFormat(time: Long, format: String): String = runCatching {
        java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
            .format(java.util.Date(time))
    }.getOrDefault("")

    fun timeFormat(time: String, format: String): String =
        timeFormat(time.toLongOrNull() ?: 0L, format)

    fun urlEncode(text: String): String =
        runCatching { java.net.URLEncoder.encode(text, "UTF-8") }.getOrDefault(text)

    fun urlDecode(text: String): String =
        runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    /** 与 legado 一致的空实现，避免源里的提示调用直接报错中断 JS */
    fun toast(msg: Any?) {
        // 源 toast 文本与日志同源，内容不可控（常带站点名），只记录长度
        android.util.Log.d("LegadoJs", "toast: ${com.novelreader.core.util.LogSanitizer.lengthOnly(msg?.toString())}")
    }

    fun longToast(msg: Any?) = toast(msg)

    fun getStringOrNull(key: String): String? = LegadoRuleEngine.variables[key]

    /**
     * AES 加解密的公共实现。
     *
     * @param encrypt true=加密，false=解密
     * @param keyText / ivText 按 UTF-8 字节使用；iv 为空时退化为 ECB
     */
    private fun aesDoFinal(
        input: ByteArray,
        keyText: String,
        ivText: String,
        transformation: String,
        encrypt: Boolean,
    ): ByteArray {
        val t = transformation.uppercase()
        val mode = when {
            t.contains("ECB") -> "ECB"
            t.contains("CBC") -> "CBC"
            ivText.isEmpty() -> "ECB"
            else -> "CBC"
        }
        val padding = if (t.contains("NOPADDING")) "NoPadding" else "PKCS5Padding"
        val cipher = javax.crypto.Cipher.getInstance("AES/$mode/$padding")
        val keySpec = javax.crypto.spec.SecretKeySpec(keyText.toByteArray(Charsets.UTF_8), "AES")
        val mode2 = if (encrypt) javax.crypto.Cipher.ENCRYPT_MODE else javax.crypto.Cipher.DECRYPT_MODE
        if (mode == "CBC") {
            cipher.init(
                mode2,
                keySpec,
                javax.crypto.spec.IvParameterSpec(ivText.toByteArray(Charsets.UTF_8)),
            )
        } else {
            cipher.init(mode2, keySpec)
        }
        return cipher.doFinal(input)
    }
}

/**
 * 纯 Kotlin 的 Base64 编解码。
 *
 * 不使用 `android.util.Base64`（单元测试的 JVM 环境里不可用，无法离线回归），
 * 也不使用 `java.util.Base64`（API 26 起才有，项目 minSdk 24）。
 */
internal object Base64Codec {

    private const val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val REVERSE: IntArray = IntArray(128) { -1 }.also { map ->
        TABLE.forEachIndexed { i, c -> map[c.code] = i }
    }

    /** 解码（忽略换行、空格等空白字符与 URL-safe 的 `-` `_`） */
    fun decode(text: String): ByteArray {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '-' -> sb.append('+')
                ch == '_' -> sb.append('/')
                ch == '=' || ch.isWhitespace() -> {}
                else -> sb.append(ch)
            }
        }
        val s = sb.toString()
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in s) {
            val v = if (ch.code < 128) REVERSE[ch.code] else -1
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** 编码（标准 Base64，无换行） */
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(TABLE[b0 shr 2])
            sb.append(TABLE[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) sb.append(TABLE[((b1 and 0x0F) shl 2) or (b2 shr 6)]) else sb.append('=')
            if (i + 2 < bytes.size) sb.append(TABLE[b2 and 0x3F]) else sb.append('=')
            i += 3
        }
        return sb.toString()
    }
}
