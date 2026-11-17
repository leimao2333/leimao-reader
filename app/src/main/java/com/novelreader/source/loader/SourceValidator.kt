package com.novelreader.source.loader

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceType
import java.io.StringReader

/**
 * 源导入校验器。
 *
 * 在源导入前校验原始内容是否合法，返回校验结果。
 * 校验项：
 * - 内容非空、格式可识别（JSON / JS / legado / txt 网址）
 * - 源名称（name）非空
 * - 内容类型（type）合法（novel/comic/video）
 */
object SourceValidator {

    /** 校验结果 */
    data class Result(
        val valid: Boolean,
        val message: String,
        val format: SourceFormat? = null,
        /** 是否为「网址型 txt」（内容是一个 URL，需要联网下载书源） */
        val isUrlContent: Boolean = false,
        /** 是否为 legado 多源列表（需要批量导入） */
        val isLegadoList: Boolean = false,
        /** 是否为 Venera 源索引（index.json，需要逐个下载 .js 再导入） */
        val isVeneraIndex: Boolean = false,
        /** 是否为 MoonTV / LunaTV 采集源配置（含 Base58 订阅，导入后得到多个视频源，v1.3.3.0） */
        val isMoonTvConfig: Boolean = false,
        /** 是否为聚合源包清单（leimaoPack，本身不是源，需逐个下载子包，v1.5.6.0） */
        val isSourcePack: Boolean = false,
    )

    /**
     * 严格 JSON 解析的长度上限（B4，v1.7.x）。
     * 超过 2MB 的内容直接跳过解析，只走启发式，避免导入大源时两次全量解析卡主线程。
     */
    private const val MAX_STRICT_JSON_CHARS = 2 * 1024 * 1024

    /**
     * 真实 JS 书源特征（B4）。
     *
     * 旧实现只要出现 `function` 就判 JS —— 畸形 JSON（字段值里带 "function"）或
     * 说明文档都会被误判成 JS 源，报「缺少必需函数」。
     * 这里只认真正的源写法。
     */
    private val JS_MARKERS = listOf(
        "var source =",
        "let source =",
        "const source =",
        "source.search",
        "source.getMeta",
        "source.chapterList",
        "source.content",
        "function search",
        "function getMeta",
        "function chapterList",
        "async function",
        "module.exports",
        "=> {",
    )

    /** 具名函数声明：`function detail(url)` —— 必须带左括号才算 JS */
    private val FUNCTION_DECL = Regex("""\bfunction\s+[A-Za-z_$][\w$]*\s*\(""")

    /**
     * 校验源原始内容。
     */
    fun validate(rawContent: String, fileName: String? = null): Result {
        // 前置规范化：去 BOM / 首尾空白 / 包裹引号，避免「看起来像 URL / JSON」被误判
        val content = normalize(rawContent)
        if (content.isEmpty()) {
            return Result(false, "源内容为空")
        }

        // 0.0 聚合源包清单（leimaoPack，v1.5.6.0）：
        //     内容是一串源包文件名，本身不是源，需逐个下载子包再合并候选。
        //     必须排在最前 —— 它是最「specific」的形态（魔数字段），
        //     否则会被当成普通 JSON 源报「缺少源名称」。
        com.novelreader.source.loader.SourcePack.parse(content)?.let { manifest ->
            return Result(
                true,
                "检测到聚合源包清单（共 ${manifest.files.size} 个源包）",
                null,
                isSourcePack = true,
            )
        }

        // 0. Venera 源索引（index.json）：内容是 [{name,fileName,key,version}]，
        //    本身不是源，需要按 fileName 逐个下载再导入
        com.novelreader.source.venera.VeneraIndex.parse(content)?.let { entries ->
            return Result(
                true,
                "检测到 Venera 源索引（共 ${entries.size} 个源）",
                SourceFormat.VENERA,
                isVeneraIndex = true,
            )
        }

        // 0.1 MoonTV / LunaTV 采集源配置（v1.3.3.0）
        //     订阅正文可能是 **Base58 编码的 JSON**，先解包再判断；
        //     单站点 JSON（只有 api 字段）同样归入本格式。
        if (com.novelreader.source.moontv.MoonTvConfig.isConfig(content)) {
            val sites = com.novelreader.source.moontv.MoonTvConfig.parseConfig(content)
            if (sites.isEmpty()) {
                return Result(false, "MoonTV/LunaTV 配置里没有可用的采集接口")
            }
            return Result(
                true,
                "检测到 MoonTV/LunaTV 配置（共 ${sites.size} 个视频源）",
                SourceFormat.MOONTV,
                isMoonTvConfig = true,
            )
        }
        if (com.novelreader.source.moontv.MoonTvConfig.looksLikeSite(content)) {
            return Result(true, "校验通过（MoonTV 采集源）", SourceFormat.MOONTV)
        }

        // 1. 判断格式
        val format = detectFormat(content, fileName)

        // 2. txt 内容为 URL → 联网下载型
        if (format == SourceFormat.JSON && isUrlText(content)) {
            return Result(true, "检测到书源网址，将联网下载", SourceFormat.LEGADO, isUrlContent = true)
        }

        if (format == null) {
            return Result(false, "无法识别源格式（支持 JSON / JS / 阅读书源）")
        }

        // 3. 按格式校验
        return when (format) {
            SourceFormat.JSON -> validateJsonOrLegado(content)
            SourceFormat.JS -> validateJs(content)
            SourceFormat.LEGADO -> validateJsonOrLegado(content)
            SourceFormat.VENERA -> validateVenera(content)
            SourceFormat.MOONTV -> Result(
                true,
                "校验通过（MoonTV 采集源）",
                SourceFormat.MOONTV,
            )
            // 本地书不走导入校验链路（独立入口），理论上不会到这里
            SourceFormat.LOCAL -> Result(false, "本地书请在书架页导入，不走书源导入")
        }
    }

    // ------------------------------------------------------------------
    // 内容规范化（B4）
    // ------------------------------------------------------------------

    /**
     * 去 BOM、去首尾空白、去包裹引号。
     *
     * 剪贴板 / 某些下载链路会把整个源文件当成一个 JSON 字符串传输
     * （`"{\"name\":...}"`），或带上 UTF-8 BOM，旧逻辑直接 startsWith("{") 判定失败。
     */
    private fun normalize(raw: String): String {
        var s = raw
        if (s.startsWith('\uFEFF')) s = s.substring(1)
        s = s.trim()
        if (s.startsWith('\uFEFF')) s = s.substring(1).trim()
        return stripWrappingQuotes(s)
    }

    /**
     * 去掉成对包裹的单/双引号，并展开最常见的转义。
     *
     * 只在内层「看起来确实是一份源」时才剥壳（JSON 对象/数组、URL、JS 源码开头），
     * 避免把 `"foo" and "bar"` 这类普通文本的两端引号误吃掉。
     */
    private fun stripWrappingQuotes(s: String): String {
        if (s.length < 2) return s
        val first = s.first()
        val last = s.last()
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            val inner = s.substring(1, s.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .trim()
            val looksLikeSource = inner.startsWith("{") ||
                inner.startsWith("[") ||
                inner.startsWith("http://") ||
                inner.startsWith("https://") ||
                inner.startsWith("var ") ||
                inner.startsWith("let ") ||
                inner.startsWith("const ") ||
                inner.startsWith("function ") ||
                inner.startsWith("class ")
            if (looksLikeSource) return inner
        }
        return s
    }

    // ------------------------------------------------------------------
    // 格式识别（B4：严格解析优先，启发式兜底）
    // ------------------------------------------------------------------

    /** 检测源格式 */
    private fun detectFormat(rawContent: String, fileName: String?): SourceFormat? {
        val content = normalize(rawContent)

        // Venera 源也是 .js，但依赖独立运行时（class / async-await），必须优先识别
        if (looksLikeVenera(content)) return SourceFormat.VENERA

        // 优先按文件扩展名判断
        fileName?.lowercase()?.let {
            if (it.endsWith(".venera")) return SourceFormat.VENERA
            if (it.endsWith(".moontv")) return SourceFormat.MOONTV
            if (it.endsWith(".txt")) {
                // txt 内容可能是网址，或 legado 书源 JSON
                return SourceFormat.LEGADO
            }
            if (it.endsWith(".json")) {
                // json 可能是自研格式或 legado 格式，需进一步判断
                return if (looksLikeLegado(content)) SourceFormat.LEGADO else SourceFormat.JSON
            }
            if (it.endsWith(".js")) return SourceFormat.JS
        }

        // 其次按内容特征判断：严格解析 → 真实 JS 特征 → 启发式兜底
        return when {
            looksLikeLegado(content) -> SourceFormat.LEGADO
            looksLikeStrictJson(content) -> SourceFormat.JSON
            looksLikeJsSource(content) -> SourceFormat.JS
            isUrlText(content) -> SourceFormat.LEGADO
            // 兜底：严格解析失败但形态上仍是 JSON（如带注释 / 尾逗号），交给 validateJsonOrLegado 报具体错误
            content.startsWith("{") || content.startsWith("[") -> SourceFormat.JSON
            else -> null
        }
    }

    /** 是否为 Venera 源（`class X extends ComicSource`） */
    private fun looksLikeVenera(content: String): Boolean =
        content.contains("extends ComicSource")

    /** 是否为 legado 书源格式（含 bookSourceName 字段） */
    private fun looksLikeLegado(content: String): Boolean {
        val text = normalize(content)
        if (!text.contains("bookSourceName")) return false
        // 尝试解析，判断是单个还是列表
        return runCatching {
            com.google.gson.JsonParser.parseString(text)
            true
        }.getOrDefault(false)
    }

    /**
     * 严格 JSON 判定（B4）。
     *
     * 用非 lenient 的 [JsonReader] 解析，并要求消费完整文档：
     * - `{"a":1}` → true
     * - `{"a":1} trailing` / `{a:1}` / JS 代码 → false（退回启发式）
     *
     * 快速排除：首字符不是 `{` / `[` 的内容（绝大多数 JS 源、txt）不做解析；
     * 超过 [MAX_STRICT_JSON_CHARS] 的大文件也不做解析，避免导入卡顿。
     */
    private fun looksLikeStrictJson(content: String): Boolean {
        if (content.isEmpty()) return false
        val head = content.first()
        if (head != '{' && head != '[') return false
        if (content.length > MAX_STRICT_JSON_CHARS) return false

        return runCatching {
            val reader = JsonReader(StringReader(content))
            reader.isLenient = false
            val element = com.google.gson.JsonParser.parseReader(reader)
            // JsonParser 内部会临时放开 lenient，这里再确认没有尾部多余内容
            val trailing = reader.peek()
            (element.isJsonObject || element.isJsonArray) &&
                trailing == JsonToken.END_DOCUMENT
        }.getOrDefault(false)
    }

    /** 是否为真实 JS 书源（B4：不再靠任意 `function` 关键字） */
    private fun looksLikeJsSource(content: String): Boolean {
        if (JS_MARKERS.any { content.contains(it, ignoreCase = true) }) return true
        // 连写的 `function xxx(` 也要认（如 `function detail(url)`），但必须带左括号，
        // 避免把 JSON 字段值里的 "function" 单词当成 JS
        return FUNCTION_DECL.containsMatchIn(content)
    }

    /** 是否为网址型内容（txt 内是 URL） */
    private fun isUrlText(content: String): Boolean {
        val singleLine = content.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        return singleLine.startsWith("http://") || singleLine.startsWith("https://")
    }

    // ------------------------------------------------------------------
    // 按格式校验
    // ------------------------------------------------------------------

    /** 校验 JSON / legado 源 */
    private fun validateJsonOrLegado(content: String): Result {
        return try {
            val element = com.google.gson.JsonParser.parseString(content)

            // legado 列表（数组）
            if (element.isJsonArray) {
                return Result(true, "校验通过（legado 书源列表）", SourceFormat.LEGADO, isLegadoList = true)
            }

            val json = element.asJsonObject

            // legado 单个书源
            if (json.has("bookSourceName")) {
                val name = json.get("bookSourceName")?.asString
                if (name.isNullOrBlank()) {
                    return Result(false, "缺少书源名称（bookSourceName）")
                }
                return Result(true, "校验通过", SourceFormat.LEGADO)
            }

            // 自研 JSON 格式
            val name = json.get("name")?.asString
            if (name.isNullOrBlank()) {
                return Result(false, "缺少源名称（name）")
            }
            val type = json.get("type")?.asString
            if (SourceType.fromString(type) == null) {
                return Result(false, "内容类型（type）无效，应为 novel/comic/video")
            }
            if (!json.has("search")) {
                return Result(false, "缺少搜索规则（search）")
            }
            Result(true, "校验通过", SourceFormat.JSON)
        } catch (e: Exception) {
            Result(false, "JSON 解析失败：${e.message}")
        }
    }

    /** 校验 Venera 源（`class Xxx extends ComicSource`） */
    private fun validateVenera(content: String): Result {
        if (!content.contains("extends ComicSource")) {
            return Result(false, "不是有效的 Venera 源（缺少 extends ComicSource）")
        }
        // 至少要有 comic.loadInfo，否则取不到详情与目录
        if (!content.contains("loadInfo")) {
            return Result(false, "Venera 源不完整：缺少 comic.loadInfo")
        }
        return Result(true, "校验通过（Venera 源）", SourceFormat.VENERA)
    }

    /** 校验 JS 源 */
    private fun validateJs(content: String): Result {
        val requiredFunctions = listOf("getMeta", "search", "chapterList", "content")
        val missing = requiredFunctions.filter { !content.contains(it) }

        return if (missing.isEmpty()) {
            Result(true, "校验通过", SourceFormat.JS)
        } else {
            Result(false, "JS 源缺少必需函数：${missing.joinToString(", ")}")
        }
    }
}
