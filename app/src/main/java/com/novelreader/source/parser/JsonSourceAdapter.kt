package com.novelreader.source.parser

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.novelreader.core.network.HttpClient
import com.novelreader.core.util.CoverUrl
import com.novelreader.core.util.TextDecoding
import com.novelreader.core.util.UrlJoin
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.engine.HtmlSelectorEngine
import com.novelreader.source.engine.JsonRuleSet
import com.novelreader.source.engine.PlaceholderEngine
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * JSON 源适配器。
 *
 * 基于 [JsonRuleSet] 规则集，通过「占位符替换 + HTTP 请求 + CSS 选择器 / JSON 路径提取」
 * 完成 search / detail / chapterList / content 的标准化实现。
 *
 * 容错约定（v1.9.x）：
 * - 规则缺失 / 网络失败 / 响应非 2xx / JSON 语法错误 → 打日志并返回空结果，不抛出；
 * - 单条结果的单个字段解析失败只让该字段为空，**不丢弃整条结果**；
 * - 响应未声明 charset 时按 UTF-8 / GBK 探测解码（见 [TextDecoding]）。
 */
class JsonSourceAdapter(
    override val info: SourceInfo,
    private val ruleSet: JsonRuleSet,
) : SourceAdapter {

    private val type: SourceType = info.type

    // ===== 搜索 =====
    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        val rule = ruleSet.search ?: return missingRule("search", emptyList())
        val url = renderUrl(
            rule.url,
            mapOf("keyword" to keyword, "page" to page.toString(), "baseUrl" to ruleSet.baseUrl),
        ) ?: return emptyList()
        val body = fetch(url) ?: return emptyList()
        val rows = extractList(body, rule.list.orEmpty(), rule.fields)
        if (rows.isEmpty()) {
            Log.w(TAG, "源 ${info.id} 搜索无结果：$url（响应 ${body.length} 字符）")
            return emptyList()
        }

        return rows.mapNotNull { fields ->
            runCatching {
                val id = fields["id"].orEmpty().ifBlank { fields["url"].orEmpty() }
                MediaItem(
                    id = id,
                    title = fields["title"].orEmpty(),
                    // v1.8.3.0：清洗封面（JS undefined 拼出的假地址要清空）
                    cover = CoverUrl.sanitize(absoluteUrl(fields["cover"])),
                    author = fields["author"],
                    desc = fields["desc"],
                    type = type,
                    detailUrl = absoluteUrl(fields["id"]) ?: id,
                    sourceId = info.id,
                )
            }.onFailure { Log.w(TAG, "源 ${info.id} 搜索结果条目解析失败：${it.message}") }
                .getOrNull()
        }
    }

    // ===== 详情 =====
    override suspend fun detail(item: MediaItem): MediaItem {
        val rule = ruleSet.detail ?: return missingRule("detail", item)
        val url = renderUrl(
            rule.url,
            mapOf("itemUrl" to item.detailUrl, "baseUrl" to ruleSet.baseUrl),
        ) ?: return item
        val body = fetch(url) ?: return item

        var cover = item.cover
        var author = item.author
        var desc = item.desc
        rule.fields.forEach { (key, expr) ->
            // 单字段失败只跳过该字段，不影响整条详情
            val value = runCatching { extractSingle(body, expr) }
                .onFailure { Log.w(TAG, "源 ${info.id} 详情字段 $key 解析失败：${it.message}") }
                .getOrNull()
            if (value.isNullOrBlank()) return@forEach
            when (key) {
                "cover" -> CoverUrl.sanitize(absoluteUrl(value))?.let { cover = it }
                "author" -> author = value
                "desc" -> desc = value
                "title" -> Unit // 标题一般不变，忽略
                else -> Unit
            }
        }
        return item.copy(cover = cover, author = author, desc = desc)
    }

    // ===== 目录 =====
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val rule = ruleSet.chapterList ?: return missingRule("chapterList", emptyList())
        val url = renderUrl(
            rule.url,
            mapOf("detailUrl" to item.detailUrl, "baseUrl" to ruleSet.baseUrl),
        ) ?: return emptyList()
        val body = fetch(url) ?: return emptyList()
        val rows = extractList(body, rule.list.orEmpty(), rule.fields)
        if (rows.isEmpty()) {
            Log.w(TAG, "源 ${info.id} 目录为空：$url（响应 ${body.length} 字符）")
            return emptyList()
        }

        return rows.mapIndexed { index, fields ->
            runCatching {
                val rawUrl = fields["id"].orEmpty().ifBlank { fields["url"].orEmpty() }
                val chapterUrl = absoluteUrl(rawUrl) ?: rawUrl
                ChapterItem(
                    id = chapterUrl.ifBlank { fields["id"].orEmpty() },
                    title = fields["title"].orEmpty(),
                    url = chapterUrl,
                    index = index,
                    sourceId = info.id,
                )
            }.onFailure { Log.w(TAG, "源 ${info.id} 目录条目解析失败（#$index）：${it.message}") }
                .getOrNull()
        }.filterNotNull()
    }

    // ===== 正文 =====
    override suspend fun content(chapter: ChapterItem): ContentResult {
        val rule = ruleSet.content ?: return missingRule("content", ContentResult())
        val url = renderUrl(
            rule.url,
            mapOf("chapterUrl" to chapter.url, "baseUrl" to ruleSet.baseUrl),
        ) ?: return ContentResult()
        val body = fetch(url) ?: return ContentResult()

        return when {
            // 小说：提取正文文本
            rule.text != null -> {
                val raw = runCatching { extractSingle(body, rule.text) }
                    .onFailure { Log.w(TAG, "源 ${info.id} 正文规则解析失败：${it.message}") }
                    .getOrNull()
                    .orEmpty()
                if (raw.isBlank()) Log.w(TAG, "源 ${info.id} 正文为空：$url")
                val cleaned = runCatching { HtmlSelectorEngine.removeElements(raw, rule.remove) }
                    .getOrDefault(raw)
                ContentResult.novel(cleaned)
            }
            // 漫画：提取图片列表
            rule.images != null -> {
                val images = runCatching { extractImageList(body, rule.images) }
                    .onFailure { Log.w(TAG, "源 ${info.id} 图片列表解析失败：${it.message}") }
                    .getOrDefault(emptyList())
                if (images.isEmpty()) Log.w(TAG, "源 ${info.id} 图片列表为空：$url")
                ContentResult.comic(images)
            }
            // 视频：提取播放地址
            rule.videoUrl != null -> {
                val videoUrl = runCatching { extractSingle(body, rule.videoUrl) }.getOrNull().orEmpty()
                val absUrl = absoluteUrl(videoUrl) ?: videoUrl
                if (absUrl.isBlank()) Log.w(TAG, "源 ${info.id} 播放地址为空：$url")
                ContentResult.video(absUrl, rule.headers)
            }
            else -> {
                Log.w(TAG, "源 ${info.id} content 规则未配置 text/images/videoUrl，返回空内容")
                ContentResult()
            }
        }
    }

    // ===== 有效性自检 =====
    override suspend fun test(): Boolean {
        return runCatching {
            search("test", 1)
            true
        }.getOrDefault(false)
    }

    // ===== 网络 =====

    /**
     * 请求并解码文本。
     *
     * 走 [HttpClient.executeRaw] 是为了拿到 **HTTP 状态码 + Content-Type**：
     * 非 2xx 能与网络异常区分开，未声明 charset 时交给 [TextDecoding.detectAndDecode]
     * 按 UTF-8 / GBK 探测。各失败类型分别打日志，避免"静默空结果"。
     */
    private fun fetch(url: String): String? {
        if (url.isBlank()) {
            Log.w(TAG, "源 ${info.id} 请求地址为空，跳过")
            return null
        }
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", HttpClient.DEFAULT_USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "application/json;q=0.8,*/*;q=0.7",
                )
                .build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "源 ${info.id} 地址非法：$url（${e.message}）")
            return null
        }

        return try {
            HttpClient.executeRaw(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "源 ${info.id} HTTP 非 2xx（${response.code}）：$url")
                    return null
                }
                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "源 ${info.id} 响应体为空：$url")
                    return null
                }
                // 未声明 charset 时按 UTF-8 → GBK 探测；已声明仍可能是错标，做一次乱码修复
                TextDecoding.repairGarbled(
                    TextDecoding.detectAndDecode(bytes, response.header("Content-Type")),
                )
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "源 ${info.id} 请求超时：$url（${e.message}）")
            null
        } catch (e: InterruptedIOException) {
            Log.w(TAG, "源 ${info.id} 请求被中断/超时：$url（${e.message}）")
            null
        } catch (e: UnknownHostException) {
            Log.w(TAG, "源 ${info.id} 域名解析失败：$url")
            null
        } catch (e: ConnectException) {
            Log.w(TAG, "源 ${info.id} 连接失败：$url（${e.message}）")
            null
        } catch (e: SSLException) {
            Log.w(TAG, "源 ${info.id} TLS 握手失败：$url（${e.message}）")
            null
        } catch (e: IOException) {
            Log.w(TAG, "源 ${info.id} 网络异常：$url（${e.javaClass.simpleName}: ${e.message}）")
            null
        } catch (e: Exception) {
            Log.w(TAG, "源 ${info.id} 请求异常：$url（${e.javaClass.simpleName}: ${e.message}）")
            null
        }
    }

    // ===== 取数：HTML 选择器 / JSON 路径 =====

    /** 列表提取：`$` 开头按 JSON 路径；否则先 HTML，响应是 JSON 时回退 JSON */
    private fun extractList(
        content: String,
        listExpr: String,
        fields: Map<String, String>,
    ): List<Map<String, String>> {
        if (isJsonExpr(listExpr)) return extractJsonList(content, stripJsonPrefix(listExpr), fields)

        val htmlRows = runCatching { HtmlSelectorEngine.extractList(content, listExpr, fields) }
            .onFailure { Log.w(TAG, "源 ${info.id} 列表选择器「$listExpr」解析失败：${it.message}") }
            .getOrDefault(emptyList())
        if (htmlRows.isNotEmpty()) return htmlRows
        if (!looksLikeJson(content)) return htmlRows

        return extractJsonList(content, "$", fields)
    }

    /** 单值提取：`$` 开头按 JSON 路径；否则先 HTML，响应是 JSON 时回退 JSON */
    private fun extractSingle(content: String, expr: String): String? {
        if (isJsonExpr(expr)) {
            return jsonValueOf(content, stripJsonPrefix(expr))
        }
        val htmlValue = runCatching { HtmlSelectorEngine.extract(content, expr) }
            .onFailure { Log.w(TAG, "源 ${info.id} 选择器「$expr」解析失败：${it.message}") }
            .getOrNull()
        if (!htmlValue.isNullOrEmpty() || !looksLikeJson(content)) return htmlValue
        // 规则没写 `$` 但响应确实是 JSON：按同一表达式再当路径取一次
        return jsonValueOf(content, expr) ?: htmlValue
    }

    /** 漫画图片列表 */
    private fun extractImageList(content: String, imagesExpr: String): List<String> {
        if (isJsonExpr(imagesExpr)) {
            val root = parseJson(content) ?: return emptyList()
            return runCatching { resolveJson(root, tokenize(stripJsonPrefix(imagesExpr))) }
                .getOrDefault(emptyList())
                .flatMap { node -> if (node.isJsonArray) node.asJsonArray.toList() else listOf(node) }
                .mapNotNull { node -> absoluteUrl(jsonText(node)) }
        }

        val expression = HtmlSelectorEngine.parseExpression(imagesExpr)
        val doc = HtmlSelectorEngine.parse(content)
        val elements = if (expression.selector.isEmpty()) {
            doc.select("img")
        } else {
            doc.select(expression.selector)
        }
        val index = expression.index
        val picked = if (index == null) {
            elements
        } else {
            val actual = if (index < 0) elements.size + index else index
            listOfNotNull(elements.getOrNull(actual))
        }
        return picked.mapNotNull { element ->
            val attr = expression.attr.lowercase()
            val src = when (attr) {
                "src" -> element.attr("src")
                "data-src", "data-original" -> element.attr(attr).ifEmpty { element.attr("src") }
                else -> element.attr(expression.attr)
            }
            absoluteUrl(src)
        }
    }

    /** JSON 列表：路径命中的节点（数组会被摊平一层）逐项按 fields 取值 */
    private fun extractJsonList(
        content: String,
        path: String,
        fields: Map<String, String>,
    ): List<Map<String, String>> {
        val root = parseJson(content) ?: return emptyList()
        val nodes = runCatching { resolveJson(root, tokenize(path)) }
            .onFailure { Log.w(TAG, "源 ${info.id} JSON 路径「$path」解析失败：${it.message}") }
            .getOrDefault(emptyList())
        if (nodes.isEmpty()) {
            Log.w(TAG, "源 ${info.id} JSON 路径「$path」未命中列表（响应 ${content.length} 字符）")
            return emptyList()
        }
        val items = nodes.flatMap { node ->
            if (node.isJsonArray) node.asJsonArray.toList() else listOf(node)
        }
        val rows = items.map { item ->
            // 逐字段 runCatching：某个字段缺失/嵌套不对只留空串
            fields.mapValues { (_, expr) ->
                runCatching { jsonValueOf(item, expr) }
                    .onFailure { Log.d(TAG, "源 ${info.id} 字段规则「$expr」取值失败：${it.message}") }
                    .getOrNull()
                    .orEmpty()
            }
        }
        // 命中列表但所有字段都取不到值：说明字段规则与响应结构不匹配，返回空而不是一堆空条目
        if (rows.isNotEmpty() && rows.all { row -> row.values.all { it.isBlank() } }) {
            Log.w(TAG, "源 ${info.id} JSON 路径「$path」命中 ${rows.size} 项，但字段规则全部未命中")
            return emptyList()
        }
        return rows
    }

    /** 在单个节点上按规则取值：`$` 路径按 JSON，裸键（`title` / `data.title`）同样按路径 */
    private fun jsonValueOf(node: JsonElement, expr: String): String? {
        val path = stripJsonPrefix(expr).trim()
        if (path.isEmpty()) return jsonText(node)
        val values = runCatching { resolveJson(node, tokenize(path)) }.getOrDefault(emptyList())
        if (values.isEmpty()) return null
        return values.joinToString("\n") { jsonText(it) }
    }

    /** 从整段文本解析 JSON 并取单值（供 `$` 规则使用） */
    private fun jsonValueOf(content: String, path: String): String? {
        val root = parseJson(content) ?: return null
        val values = runCatching { resolveJson(root, tokenize(path)) }.getOrDefault(emptyList())
        if (values.isEmpty()) return null
        return values.joinToString("\n") { jsonText(it) }
    }

    // ===== JSON 路径 =====

    private fun parseJson(content: String): JsonElement? {
        val text = content.trim()
        if (!looksLikeJson(text)) return null
        return try {
            JsonParser.parseString(text)
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "源 ${info.id} JSON 解析失败：${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "源 ${info.id} JSON 解析失败：${e.message}")
            null
        }
    }

    /** 把 `$.a.b[0]` / `a.b[-1]` / `a[*].c` 切成 token（`$` 本身表示根，切成空列表） */
    private fun tokenize(path: String): List<String> {
        val body = stripJsonPrefix(path).trim().removePrefix("$").removePrefix(".")
        if (body.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens.add(buffer.toString())
                buffer.clear()
            }
        }
        var i = 0
        while (i < body.length) {
            when (val c = body[i]) {
                '.' -> {
                    flush()
                    i++
                }
                '[' -> {
                    flush()
                    val end = body.indexOf(']', i)
                    if (end < 0) return emptyList()
                    tokens.add(body.substring(i, end + 1))
                    i = end + 1
                }
                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
        return tokens
    }

    /** 沿 token 遍历 JSON，返回命中节点；`[*]` 会展开为多个 */
    private fun resolveJson(node: JsonElement, tokens: List<String>, start: Int = 0): List<JsonElement> {
        if (start >= tokens.size) return listOf(node)
        val token = tokens[start]
        return when {
            token.startsWith("[") -> {
                val inner = token.substring(1, token.length - 1).trim()
                val array = node as? JsonArray ?: return emptyList()
                if (inner == "*") {
                    array.flatMap { resolveJson(it, tokens, start + 1) }
                } else {
                    val n = inner.toIntOrNull() ?: return emptyList()
                    val index = if (n < 0) array.size() + n else n
                    if (index !in 0 until array.size()) return emptyList()
                    resolveJson(array.get(index), tokens, start + 1)
                }
            }
            node is JsonObject -> {
                node.get(token)?.let { resolveJson(it, tokens, start + 1) } ?: emptyList()
            }
            node is JsonArray -> {
                val n = token.toIntOrNull() ?: return emptyList()
                val index = if (n < 0) node.size() + n else n
                if (index !in 0 until node.size()) return emptyList()
                resolveJson(node.get(index), tokens, start + 1)
            }
            else -> emptyList()
        }
    }

    private fun jsonText(node: JsonElement): String = when {
        node.isJsonNull -> ""
        node.isJsonPrimitive -> node.asJsonPrimitive.asString
        node.isJsonArray -> node.asJsonArray.joinToString("\n") { jsonText(it) }
        else -> node.toString()
    }

    private fun looksLikeJson(content: String): Boolean {
        val head = content.trimStart()
        return head.startsWith("{") || head.startsWith("[")
    }

    private fun isJsonExpr(expr: String): Boolean =
        expr.startsWith("$") || expr.startsWith("@json:", ignoreCase = true)

    private fun stripJsonPrefix(expr: String): String =
        if (expr.startsWith("@json:", ignoreCase = true)) expr.substring(6).trim() else expr

    // ===== 工具 =====

    private fun <T> missingRule(name: String, fallback: T): T {
        Log.w(TAG, "源 ${info.id} 缺少 $name 规则，返回空结果")
        return fallback
    }

    private fun renderUrl(template: String, values: Map<String, String>): String? {
        if (template.isBlank()) {
            Log.w(TAG, "源 ${info.id} 规则地址模板为空")
            return null
        }
        val rendered = PlaceholderEngine.render(template, values)
        // 关键词可能带空格，OkHttp 的 url 解析会直接抛异常
        val url = absoluteUrl(rendered.replace(" ", "%20"))
        if (url == null) Log.w(TAG, "源 ${info.id} 规则地址无法补全：$template")
        return url
    }

    private fun absoluteUrl(url: String?): String? = UrlJoin.normalizeUrl(ruleSet.baseUrl, url)

    private companion object {
        const val TAG = "JsonSource"
    }
}
