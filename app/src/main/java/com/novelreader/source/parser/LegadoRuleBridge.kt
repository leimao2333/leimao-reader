package com.novelreader.source.parser

import com.novelreader.core.network.HttpClient
import com.novelreader.core.util.CoverUrl
import com.novelreader.core.util.TextDecoding
import com.novelreader.core.util.UrlJoin
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.source.engine.HtmlSelectorEngine
import com.novelreader.source.engine.JsonRuleSet
import com.novelreader.source.engine.PlaceholderEngine
import com.novelreader.source.legado.LegadoRuleEngine
import okhttp3.Request

/**
 * legado 规则语法桥接（B2）。
 *
 * 为什么需要这一层：项目里只有 [LegadoRuleEngine] 实现了 legado 全套语法，自研 JSON 源的
 * `HtmlSelectorEngine` 只支持「单 @ + 属性」，JSON 路径也只是简化版，顶层 `##` / `{{}}` /
 * `||` / 多级 `@` 链 / `%%` 正则 / JS `java.*` 全部缺失。直接改 legado 引擎或改 JSON 适配器
 * 风险大（它们还支撑着 legado 源），因此这里做两件事：
 *
 * 1. **纯逻辑语法层**：把规则串解析成可单测的结构（[analyze] / [splitFallback] /
 *    [parseReplace] / [renderTemplate] / [splitChain] / [applyReplace]），不依赖 Android、
 *    不依赖网络，规则解析/匹配单独可测；
 * 2. **委托层**：把真正的求值交给成熟的 [LegadoRuleEngine]（[extract] / [extractList] /
 *    [evalJs]），避免重复造轮子、也保证与 legado 源的解释口径一致。
 *
 * 接线策略：[com.novelreader.source.parser.JsonSourceParser] 仅在规则集**确实含高级语法**时
 * 才改用 [LegadoBridgeJsonAdapter]；纯简单规则仍走原来的 JsonSourceAdapter，既有行为不回退。
 */
object LegadoRuleBridge {

    // ==================================================================
    // 一、规则语法特征（纯逻辑，可单测）
    // ==================================================================

    /** 一条规则里出现的 legado 语法特性。用于判定是否需要走桥接 adapter。 */
    data class Features(
        val hasFallback: Boolean,      // `||` 多选一回退
        val hasReplace: Boolean,       // `##正则##替换` / `%%正则%%替换`
        val hasBrace: Boolean,         // `{{}}` 求值模板
        val hasChain: Boolean,         // 多级 `@` 链（>=2 段且末段是选择器/属性）
        val hasJs: Boolean,            // `@js:` / `<js>...</js>`
        val hasJsBridge: Boolean,      // JS 里的 `java.` 调用
        val hasGetPut: Boolean,        // `@get:{}` / `@put:{}` 变量机制
    ) {
        /** 是否含「自研 JsonSourceAdapter 无法正确解释」的高级语法 */
        val hasAdvanced: Boolean
            // hasChain 必须计入：多级 @ 链（a@img@src）自研引擎只认单 @，不桥接会被错误解释
            get() = hasFallback || hasReplace || hasBrace || hasChain || hasJs || hasJsBridge || hasGetPut
    }

    /**
     * 分析一条规则串包含哪些 legado 语法。
     *
     * 为什么不直接正则一把梭：各语法有嵌套（如 `@js:` 里的字符串含 `##`、`{{}}`），
     * 这里只做「是否存在」的保守探测——宁可多判为高级语法（走桥接，功能更全），
     * 也不要漏判导致规则被旧引擎错误解释。
     */
    fun analyze(rule: String?): Features {
        if (rule.isNullOrBlank()) return Features(false, false, false, false, false, false, false)
        return Features(
            hasFallback = rule.contains("||"),
            hasReplace = rule.contains("##") || rule.contains("%%"),
            hasBrace = rule.contains("{{") && rule.contains("}}"),
            hasChain = hasMultiLevelChain(rule),
            hasJs = rule.contains("@js:", ignoreCase = true) ||
                (rule.contains("<js>") && rule.contains("</js>")),
            hasJsBridge = JAVA_CALL.containsMatchIn(rule),
            hasGetPut = rule.contains("@get:") || rule.contains("@put:"),
        )
    }

    /** 规则集里是否存在需要桥接的高级语法。 */
    fun hasAdvancedSyntax(rules: Collection<String?>): Boolean = rules.any { analyze(it).hasAdvanced }

    /** 多级 `@` 链探测：`a.b@img@src` 有 2 段以上（排除单 `@` 的 `a@href`）。 */
    private fun hasMultiLevelChain(rule: String): Boolean {
        val head = rule.substringBefore("##").substringBefore("@js:").substringBefore("<js>")
        val segments = head.split('@').map { it.trim() }.filter { it.isNotEmpty() }
        return segments.size >= 3
    }

    private val JAVA_CALL = Regex("""java\.[A-Za-z_$][\w$]*\s*\(""")

    // ==================================================================
    // 二、语法拆分 / 规范化（纯逻辑，可单测）
    // ==================================================================

    /**
     * `||` 多选一回退：拆成候选列表，取第一个非空的语义由调用方（委托 legado 引擎）完成。
     *
     * legado 的语义就是简单 `split("||")`，这里保持一致——不试图在 `##` / `@js:` 内部
     * 做转义解析，因为 legado 源本身也没那么写，过度解析反而偏离口径。
     */
    fun splitFallback(rule: String): List<String> =
        rule.split("||").map { it.trim() }.filter { it.isNotEmpty() }

    /** 正则替换规格：模式 + 替换模板（模板可为 null，表示只做「删除匹配」）。 */
    data class ReplaceSpec(val pattern: String, val template: String?)

    /**
     * 拆出 `##正则##替换` 或 `%%正则%%替换`（`%%` 是任务要求的兼容写法，见 [normalizePercent]）。
     *
     * 只拆第一处 `##` / `%%`：后半段整体当作「正则##模板」，与 legado 的
     * `applyReplace` 口径一致（模板里可含 `##` 字面量）。
     *
     * @return (主规则, 替换规格)；无替换时规格为 null
     */
    fun parseReplace(rule: String): Pair<String, ReplaceSpec?> {
        val idx = rule.indexOf("##").let { if (it >= 0) it else rule.indexOf("%%") }
        if (idx < 0) return rule.trim() to null
        val main = rule.substring(0, idx).trim()
        val rest = rule.substring(idx + 2)
        val sep = rest.indexOf(if (rule[idx] == '#') "##" else "%%")
        val spec = if (sep < 0) {
            ReplaceSpec(rest.trim(), null)
        } else {
            ReplaceSpec(rest.substring(0, sep).trim(), rest.substring(sep + 2))
        }
        return main to spec
    }

    /**
     * 把 `%%正则%%替换` 规范化为 legado 的 `##正则##替换`。
     *
     * 为什么：`%%` 是任务要求补齐的写法，但 legado 引擎实际只认 `##`；在解析阶段统一
     * 转换后，就能原封不动复用 legado 的正则替换实现，不必维护第二套解释器。
     * 纯简单规则（无 `%%`）原样返回，保证不回退。
     */
    fun normalizePercent(rule: String): String {
        if (!rule.contains("%%")) return rule
        return rule.replace("%%", "##")
    }

    /**
     * 综合规范化：把规则里的 `%%` 正则写法收敛到 legado 口径。
     * 由 [JsonRuleParser] 在解析阶段调用，让自研 JSON 源也能用正则语法。
     */
    fun normalize(rule: String?): String? = rule?.let { normalizePercent(it) }

    /** 多级 `@` 链拆分：`div.list@img@src` → [div.list, img, src]，供逐段选择。 */
    fun splitChain(rule: String): List<String> =
        rule.split('@').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * `{{}}` 求值模板渲染。
     *
     * 每个占位交给 [resolve] 解析：`{{baseUrl}}` 由调用方直接给出上下文地址，其余按
     * legado 语义当作「规则」求值（见 legado 的 renderBraceTemplate）。解析不出来时
     * 保留原占位串，避免静默丢失信息。
     */
    fun renderTemplate(template: String, resolve: (String) -> String?): String {
        if (!template.contains("{{")) return template
        val regex = Regex("\\{\\{([^{}]+)\\}\\}")
        return regex.replace(template) { m ->
            val inner = m.groupValues[1].trim()
            resolve(inner) ?: m.value
        }
    }

    /**
     * 正则替换（含 `$1` 分组引用），纯逻辑。
     *
     * 为什么自己实现而委托 legado：`LegadoRuleEngine.applyReplace` 是 private，且这里要
     * 同时服务于「纯逻辑单测」和「桥接 adapter 的字段后处理」；实现口径与 legado 一致：
     * - `模板为 null` = 删除匹配的文本；
     * - `##` 分隔模式与模板；`$1`..`$9` 为分组引用，`\` 为转义；
     * - 跳过「串尾伪空匹配」，避免 `(.*)` 把替换做两遍。
     */
    fun applyReplace(value: String?, spec: ReplaceSpec): String? {
        if (value == null) return null
        return runCatching {
            val regex = Regex(spec.pattern)
            val template = spec.template.orEmpty()
            val matches = regex.findAll(value).toList()
            if (matches.isEmpty()) return value
            val sb = StringBuilder()
            var cursor = 0
            for (i in matches.indices) {
                val m = matches[i]
                val start = m.range.first
                val end = m.range.last + 1
                // 串尾伪空匹配：`(.*)` 命中整串后还会在末尾再来一次零长匹配
                if (start == end && start == value.length && i > 0 &&
                    matches[i - 1].range.last + 1 == value.length
                ) continue
                if (start < cursor) continue
                sb.append(value, cursor, start)
                if (spec.template != null) expandGroups(m, template, sb)
                cursor = end
            }
            sb.append(value, cursor, value.length)
            sb.toString()
        }.getOrDefault(value)
    }

    /** 展开替换模板里的 `$1`..`$9` / `\1`（复用 legado 的分组口径）。 */
    private fun expandGroups(m: MatchResult, template: String, sb: StringBuilder) {
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '\\' && i + 1 < template.length) {
                sb.append(template[i + 1]); i += 2; continue
            }
            if (c == '$' && i + 1 < template.length && template[i + 1].isDigit()) {
                var j = i + 1
                val num = StringBuilder()
                while (j < template.length && template[j].isDigit() && num.length < 2) {
                    num.append(template[j]); j++
                }
                sb.append(m.groupValues.getOrNull(num.toString().toIntOrNull() ?: -1).orEmpty())
                i = j; continue
            }
            sb.append(c); i++
        }
    }

    // ==================================================================
    // 三、委托 legado 引擎求值（复用成熟实现，口径与 legado 源一致）
    // ==================================================================

    /** 单值提取：支持多级 `@` 链、`##` / `%%`、`{{}}`、`||`、`@js:` / `java.*`、`@json:`。 */
    fun extract(content: String, rule: String, baseUrl: String = ""): String? =
        LegadoRuleEngine.extractString(content, normalizePercent(rule), baseUrl)

    /** 列表提取：字段规则同样走 legado 全套语法。 */
    fun extractList(
        content: String,
        listRule: String,
        fields: Map<String, String>,
        baseUrl: String = "",
    ): List<Map<String, String>> = LegadoRuleEngine.extractList(
        content,
        normalizePercent(listRule),
        fields.mapValues { normalizePercent(it.value) ?: "" },
        baseUrl,
    )

    /** 字符串列表（漫画图片等）：支持 `##` 替换与 `@js:` 拆分。 */
    fun extractStringList(content: String, rule: String, baseUrl: String = ""): List<String> =
        LegadoRuleEngine.extractStringList(content, normalizePercent(rule), baseUrl)

    /** JS 求值：注入 `result` / `src` / `baseUrl` 与 legado 风格 `java.*` 桥。 */
    fun evalJs(code: String, result: Any?, src: String?, baseUrl: String): String? =
        LegadoRuleEngine.evalContentRule(src ?: "", code, baseUrl).first
            ?: LegadoRuleEngine.evalJsWithContent(code, result?.toString().orEmpty(), baseUrl)

    /** 相对地址补全，委托 legado（与源规则口径一致）。 */
    fun toAbsolute(baseUrl: String, url: String?): String? = LegadoRuleEngine.toAbsoluteUrl(baseUrl, url)
}

/**
 * JSON 源的 legado 语法增强适配器（B2）。
 *
 * 只在 [JsonSourceParser] 探测到高级语法时启用；简单规则仍走 [JsonSourceAdapter]，
 * 因此这是纯增量能力，不回退既有行为。规则求值全部委托 [LegadoRuleBridge]（即 legado 引擎），
 * 网络与编码处理沿用项目既有的 HttpClient / TextDecoding。
 */
internal class LegadoBridgeJsonAdapter(
    override val info: SourceInfo,
    private val ruleSet: JsonRuleSet,
) : SourceAdapter {

    private val type = info.type

    /** legado 变量表：`@put:` 写入、`@get:` 读取，跨 搜索→详情→目录 存活。 */
    private val variables = LinkedHashMap<String, String>()

    private fun <T> withVars(block: () -> T): T = LegadoRuleEngine.withVariables(variables, block)

    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        val rule = ruleSet.search ?: return emptyList()
        val url = renderUrl(rule.url, mapOf("keyword" to keyword, "page" to page.toString()))
            ?: return emptyList()
        val body = fetch(url) ?: return emptyList()
        val rows = withVars { LegadoRuleBridge.extractList(body, rule.list.orEmpty(), rule.fields, ruleSet.baseUrl) }
        return rows.mapNotNull { fields ->
            runCatching {
                val id = fields["id"].orEmpty().ifBlank { fields["url"].orEmpty() }
                MediaItem(
                    id = id,
                    title = fields["title"].orEmpty(),
                    cover = CoverUrl.sanitize(abs(fields["cover"])),
                    author = fields["author"],
                    desc = fields["desc"],
                    type = type,
                    detailUrl = abs(fields["id"]) ?: id,
                    sourceId = info.id,
                    variableMap = LinkedHashMap(variables),
                )
            }.getOrNull()
        }
    }

    override suspend fun detail(item: MediaItem): MediaItem {
        val rule = ruleSet.detail ?: return item
        val url = renderUrl(rule.url, mapOf("itemUrl" to item.detailUrl)) ?: return item
        val body = fetch(url) ?: return item
        var cover = item.cover
        var author = item.author
        var desc = item.desc
        withVars {
            rule.fields.forEach { (key, expr) ->
                val value = runCatching { LegadoRuleBridge.extract(body, expr, ruleSet.baseUrl) }.getOrNull()
                if (value.isNullOrBlank()) return@forEach
                when (key) {
                    "cover" -> CoverUrl.sanitize(abs(value))?.let { cover = it }
                    "author" -> author = value
                    "desc" -> desc = value
                }
            }
        }
        return item.copy(cover = cover, author = author, desc = desc)
    }

    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val rule = ruleSet.chapterList ?: return emptyList()
        val url = renderUrl(rule.url, mapOf("detailUrl" to item.detailUrl)) ?: return emptyList()
        val body = fetch(url) ?: return emptyList()
        val rows = withVars { LegadoRuleBridge.extractList(body, rule.list.orEmpty(), rule.fields, ruleSet.baseUrl) }
        return rows.mapIndexed { index, fields ->
            val raw = fields["id"].orEmpty().ifBlank { fields["url"].orEmpty() }
            val chapterUrl = abs(raw) ?: raw
            ChapterItem(
                id = chapterUrl.ifBlank { raw },
                title = fields["title"].orEmpty(),
                url = chapterUrl,
                index = index,
                sourceId = info.id,
                requestOptions = fields["requestOptions"].orEmpty(),
            )
        }
    }

    override suspend fun content(chapter: ChapterItem): ContentResult {
        val rule = ruleSet.content ?: return ContentResult()
        val url = renderUrl(rule.url, mapOf("chapterUrl" to chapter.url)) ?: return ContentResult()
        val body = fetch(url) ?: return ContentResult()
        return withVars {
            when {
                rule.text != null -> {
                    // extract 内部已按 legado 口径处理 `##` / `{{}}` / `||` / `@js:` / `java.*`；
                    // 正文里若还嵌 JS 需拿到 headers，用 evalContentRule 取第二遍（仅当含 @js:）。
                    val raw = if (rule.text.contains("@js:", ignoreCase = true) ||
                        rule.text.contains("<js>")
                    ) {
                        LegadoRuleEngine.evalContentRule(body, rule.text, ruleSet.baseUrl).first ?: ""
                    } else {
                        LegadoRuleBridge.extract(body, rule.text, ruleSet.baseUrl).orEmpty()
                    }
                    ContentResult.novel(HtmlSelectorEngine.removeElements(raw, rule.remove))
                }
                rule.images != null -> ContentResult.comic(
                    LegadoRuleBridge.extractStringList(body, rule.images, ruleSet.baseUrl).mapNotNull { abs(it) },
                )
                rule.videoUrl != null -> {
                    val video = LegadoRuleBridge.extract(body, rule.videoUrl, ruleSet.baseUrl).orEmpty()
                    ContentResult.video(abs(video) ?: video, rule.headers)
                }
                else -> ContentResult()
            }
        }
    }

    override suspend fun test(): Boolean = runCatching { search("test", 1); true }.getOrDefault(false)

    /** 占位符渲染：先替换已知占位，再把剩余 `{{JS表达式}}` 交给 legado 引擎求值。 */
    private fun renderUrl(template: String, values: Map<String, String>): String? {
        if (template.isBlank()) return null
        val withBase = values + ("baseUrl" to ruleSet.baseUrl)
        val rendered = PlaceholderEngine.render(template, withBase)
        val evaluated = if (rendered.contains("{{")) {
            LegadoRuleEngine.evalTemplateJs(rendered, ruleSet.baseUrl)
        } else rendered
        return abs(evaluated.replace(" ", "%20"))
    }

    private fun abs(url: String?): String? = UrlJoin.normalizeUrl(ruleSet.baseUrl, url)

    /** 请求并解码文本：非 2xx / 编码异常一律吞掉返回 null，交由上层降级为空结果。 */
    private fun fetch(url: String): String? {
        if (url.isBlank()) return null
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", HttpClient.DEFAULT_USER_AGENT)
                .build()
        }.getOrNull() ?: return null
        return runCatching {
            HttpClient.executeRaw(request).use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                TextDecoding.repairGarbled(
                    TextDecoding.detectAndDecode(bytes, response.header("Content-Type")),
                )
            }
        }.getOrNull()
    }
}
