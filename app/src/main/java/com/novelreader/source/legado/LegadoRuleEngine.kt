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
        val trimmed = rule.trimStart()
        // 顶层 @json: 规则：从 JSON 内容提取（内容本身可能不是 JSON，先尝试）
        if (trimmed.startsWith("@json:", ignoreCase = true)) {
            val path = trimmed.replaceFirst(Regex("^@json:", RegexOption.IGNORE_CASE), "")
            return runCatching {
                extractJsonPath(com.google.gson.JsonParser.parseString(content), path)
            }.getOrNull()
        }
        // 裸 JSONPath（如 `$.data.list`、`$.a.b[0]`）：legado 中 JSON 源的规则常直接写 `$...`。
        // 内容确实是 JSON 时才按路径取；取不到再退回 HTML 解析（同一规则可能两用）。
        // 形如 `$.data.images@js:...` 的尾部 JS 规则，其前缀也依赖这里取到 result。
        if (trimmed.startsWith("$") && !trimmed.contains('@')) {
            val contentIsJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")
            if (contentIsJson) {
                extractJsonPathFromRoot(content, trimmed)?.let { return it }
            }
        }
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
        // 前段取值注入 result 变量，JS 返回值为最终结果）
        extractJsSuffix(expr)?.let { (pre, js) ->
            val preValue = if (pre.isBlank()) root.text() else selectAndExtract(root, pre, baseUrl)
            return evalFieldJs(js, preValue, baseUrl)
        }

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
    private fun evalFieldJs(code: String, result: String?, baseUrl: String): String? =
        evalJsRich(code, result, src = null, baseUrl = baseUrl).first

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
     */
    private fun evalJsRich(
        code: String,
        result: String?,
        src: String?,
        baseUrl: String,
    ): Pair<String?, String?> {
        return runCatching {
            val ctx = org.mozilla.javascript.Context.enter()
            try {
                ctx.optimizationLevel = -1
                val scope = ctx.initSafeStandardObjects()
                scope.put("result", scope, result ?: "")
                scope.put("baseUrl", scope, baseUrl)
                if (src != null) scope.put("src", scope, src)
                scope.put(
                    "java",
                    scope,
                    org.mozilla.javascript.Context.javaToJS(LegadoJavaBridge(result, src, baseUrl), scope),
                )
                val v = ctx.evaluateString(scope, code, "<rule-js>", 1, null)
                val value = org.mozilla.javascript.Context.toString(v).takeIf { it.isNotBlank() }
                value to readJsHeaders(scope)
            } finally {
                org.mozilla.javascript.Context.exit()
            }
        }.getOrNull() ?: (null to null)
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

    /** 供 JS 桥接使用：按规则选取元素列表（支持 @ 链、@css:、- 倒置） */
    fun selectElements(content: String, rule: String): List<Element> {
        val doc = Jsoup.parse(content)
        return selectListElements(doc, rule).toList()
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
        return sel.indexOf(' ') >= 0 || sel.indexOf('>') >= 0 || sel.indexOf('[') >= 0 ||
            sel.indexOf(':') >= 0 || sel.indexOf(',') >= 0
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

        // legado 语义：列表规则最前面的 `-` 表示「结果倒置」（很多站点目录是倒序的）。
        // 注意与负索引（-1 取最后一个）区分：`-` 后紧跟数字时不视为倒置标记。
        val (rule, reverse) = stripListReverse(resolvedListRule)

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

    /**
     * 解析列表规则的前置 `-` 倒置标记。
     *
     * legado 规则说明：「获取列表的最前面加上负号 `-` 可以使列表倒置」。
     * 仅当 `-` 之后不是数字时才视为倒置（否则是 `-1` 这类负索引）。
     *
     * @return (去掉标记后的规则, 是否需要倒置)
     */
    private fun stripListReverse(rule: String): Pair<String, Boolean> {
        val t = rule.trim()
        if (t.length > 1 && t.startsWith("-") && !t[1].isDigit()) {
            return t.substring(1).trim() to true
        }
        return t to false
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
    private fun selectListElementsSingle(doc: Document, rule: String): Elements {
        val excluded = parseExcludedIndices(rule)

        if (rule.startsWith("@css:")) {
            val els = runCatching { doc.select(rule.substringAfter("@css:").trim()) }.getOrDefault(Elements())
            return filterExcluded(els, excluded)
        }

        // 去掉位置排除标记（如 `li!0`、`class.item!0:1`），排除在结果上统一生效
        val sanitized = rule.replace(Regex("!-?\\d+(?::-?\\d+)?"), "")
        val segs = sanitized.split('@').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return Elements()

        val itemPart = if (segs.size > 1) segs.last() else ""
        val containerSegs = if (segs.size > 1) segs.dropLast(1) else segs

        var containers = walkSteps(listOf(doc.body() ?: doc), containerSegs.first())
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
        var current: com.google.gson.JsonElement = node
        val cleaned = path.removePrefix("$").removePrefix(".").trim()
        if (cleaned.isEmpty()) return current

        val tokens = cleaned.split(Regex("[.\\[\\]]")).filter { it.isNotBlank() }
        for (token in tokens) {
            current = when {
                current.isJsonObject -> current.asJsonObject.get(token) ?: return null
                current.isJsonArray -> {
                    val idx = token.toIntOrNull() ?: return null
                    val arr = current.asJsonArray
                    if (idx < 0 || idx >= arr.size()) return null
                    arr.get(idx)
                }
                else -> return null
            }
        }
        return current
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
        if (url.startsWith("http://") || url.startsWith("https://")) return sanitizeUrl(url)
        if (baseUrl.isBlank()) return sanitizeUrl(url)
        val resolved = runCatching {
            java.net.URL(java.net.URL(baseUrl), url).toString()
        }.getOrElse { url }
        return sanitizeUrl(resolved)
    }

    /**
     * 清洗 URL：编码空格、中文等非法字符，保留既有 `%XX` 转义。
     * 避免 `Illegal character in URL` 之类的异常导致整条链路失败。
     */
    private fun sanitizeUrl(url: String): String {
        val u = url.trim()
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
}

/**
 * legado 书源 `@js:` 规则中的 `java.*` 兼容桥。
 *
 * 开源阅读把一批 Java 能力暴露给 JS 规则，漫画源尤其依赖它（`java.getString`、
 * `java.ajax`、`java.t2s` 等）。这里实现最常用的一批；未实现的成员调用时抛错，
 * 由上层 `runCatching` 兜住（与「不支持」等效，不会中断整条书源解析）。
 *
 * 说明：本类实例通过 `Context.javaToJS` 注入，方法经反射调用。
 */
class LegadoJavaBridge(
    private val currentHtml: String?,
    private val src: String?,
    private val baseUrl: String,
) {
    /** 会话变量（java.get / java.put 共用；同一 JS 调用内有效） */
    private val vars = mutableMapOf<String, Any?>()

    fun log(msg: Any?) {
        android.util.Log.d("LegadoJs", msg?.toString() ?: "null")
    }

    fun get(key: String): Any? = vars[key]

    fun put(key: String, value: Any?) {
        vars[key] = value
    }

    /** 同步 HTTP GET；legado 中 java.ajax 亦支持带 headers 的重载 */
    fun ajax(url: String): String =
        runCatching { com.novelreader.core.network.HttpClient.get(url) }.getOrDefault("")

    fun ajax(url: String, headers: Map<*, *>?): String {
        val h = headers?.entries
            ?.mapNotNull { (k, v) -> if (k != null && v != null) k.toString() to v.toString() else null }
            ?.toMap()
            .orEmpty()
        return runCatching { com.novelreader.core.network.HttpClient.get(url, h) }.getOrDefault("")
    }

    /** 无参：当前规则结果原文；带规则：对结果再套一层 legado 规则 */
    fun getString(): String = currentHtml.orEmpty()

    fun getString(rule: String): String {
        val html = currentHtml ?: return ""
        return LegadoRuleEngine.extractString(html, rule, baseUrl).orEmpty()
    }

    /** 按规则取元素列表（返回 jsoup Element，供 JS 侧继续 .text()/.attr()） */
    fun getElements(rule: String): List<Element> {
        val html = currentHtml ?: return emptyList()
        return LegadoRuleEngine.selectElements(html, rule)
    }

    /** 原始页面文本（等价 legado 的 src 变量） */
    fun getSource(): String = src.orEmpty()

    fun t2s(text: String): String = com.novelreader.core.text.ChineseConverter.toSimplified(text)

    fun s2t(text: String): String = com.novelreader.core.text.ChineseConverter.toTraditional(text)

    fun base64Decode(text: String): String = runCatching {
        String(android.util.Base64.decode(text, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrDefault("")

    fun base64Encode(text: String): String = runCatching {
        android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }.getOrDefault("")

    fun hexDecodeToString(hex: String): String = runCatching {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        String(bytes, Charsets.UTF_8)
    }.getOrDefault("")

    fun urlEncode(text: String): String =
        runCatching { java.net.URLEncoder.encode(text, "UTF-8") }.getOrDefault(text)
}
