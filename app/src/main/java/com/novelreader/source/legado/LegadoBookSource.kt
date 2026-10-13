package com.novelreader.source.legado

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * legado（阅读 App）书源数据模型。
 *
 * 对应阅读 App 的标准书源 JSON 格式，字段命名与 legado 保持一致。
 * 详见 index.json 等书源文件的真实结构。
 */
data class LegadoBookSource(
    val name: String,                 // bookSourceName
    val url: String,                  // bookSourceUrl
    val type: Int,                    // bookSourceType: 0=文本/小说 1=有声 2=漫画 3=视频
    val group: String = "",           // bookSourceGroup
    val comment: String = "",         // bookSourceComment
    val searchUrl: String = "",       // 搜索地址（含 {{key}}/{{page}} 占位）
    val ruleSearch: Map<String, String> = emptyMap(),   // 搜索规则
    val ruleBookInfo: Map<String, String> = emptyMap(), // 详情规则
    val ruleToc: Map<String, String> = emptyMap(),      // 目录规则
    val ruleContent: Map<String, String> = emptyMap(),  // 正文规则
    val ruleExplore: Map<String, String> = emptyMap(),  // 发现规则
    val exploreUrl: String = "",      // 发现地址
    val header: String = "",          // 请求头 JSON 字符串
    val enabled: Boolean = true,
) {
    /** 解析请求头 JSON 字符串为 Map */
    fun parseHeaders(): Map<String, String> {
        if (header.isBlank()) return emptyMap()
        return runCatching {
            val obj = JsonParser.parseString(header).asJsonObject
            obj.entrySet().associate { (k, v) -> k to v.asString }
        }.getOrDefault(emptyMap())
    }

    /** 是否为漫画类型（bookSourceType == 2） */
    val isComic: Boolean get() = type == 2

    /** 是否为视频类型（bookSourceType == 3） */
    val isVideo: Boolean get() = type == 3

    companion object {
        /**
         * 解析 legado 书源列表 JSON。
         * 支持：
         * - 单个书源对象 {...}
         * - 书源数组 [{...}, {...}]
         * - 带包装字段（如 {"data": [...]}）
         */
        fun parseList(raw: String): List<LegadoBookSource> {
            val element: JsonElement = runCatching {
                JsonParser.parseString(raw.trim())
            }.getOrElse { throw IllegalArgumentException("JSON 解析失败") }

            val array = when {
                element.isJsonArray -> element.asJsonArray
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    // 单个书源
                    if (obj.has("bookSourceName")) {
                        return listOf(parseOne(obj.toString()))
                    }
                    // 带包装字段
                    val candidates = listOf("data", "bookSources", "sources", "list", "result")
                    val found = candidates.firstOrNull { key ->
                        obj.has(key) && obj.get(key).isJsonArray
                    }
                    if (found != null) obj.getAsJsonArray(found)
                    else throw IllegalArgumentException("无法识别书源列表结构")
                }
                else -> throw IllegalArgumentException("无法识别书源结构")
            }

            return array.mapNotNull { item ->
                runCatching { parseOne(item.toString()) }.getOrNull()
            }
        }

        /**
         * 解析 legado 书源列表，返回 (书源对象, 原始 JSON 字符串) 对。
         * 用于导入时保留完整规则以供持久化。
         */
        fun parseListWithRaw(raw: String): List<Pair<LegadoBookSource, String>> =
            parseListWithRawDetailed(raw).pairs

        /**
         * 解析 legado 书源列表并报告解析失败数。
         *
         * @property pairs 成功解析的 (书源对象, 原始 JSON) 列表
         * @property skipped 解析失败被跳过的条目数（v1.3.0：导入时向用户报告，不再静默丢弃）
         */
        data class Detailed(val pairs: List<Pair<LegadoBookSource, String>>, val skipped: Int)

        fun parseListWithRawDetailed(raw: String): Detailed {
            val element: JsonElement = runCatching {
                JsonParser.parseString(raw.trim())
            }.getOrElse { throw IllegalArgumentException("JSON 解析失败") }

            var skipped = 0
            val array = when {
                element.isJsonArray -> element.asJsonArray
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    if (obj.has("bookSourceName")) {
                        // 单对象路径：解析失败直接抛给上层（明确报错优于静默）
                        return Detailed(listOf(parseOne(obj.toString()) to obj.toString()), 0)
                    }
                    val candidates = listOf("data", "bookSources", "sources", "list", "result")
                    val found = candidates.firstOrNull { key ->
                        obj.has(key) && obj.get(key).isJsonArray
                    }
                    if (found != null) obj.getAsJsonArray(found)
                    else throw IllegalArgumentException("无法识别书源列表结构")
                }
                else -> throw IllegalArgumentException("无法识别书源结构")
            }

            val pairs = array.mapNotNull { item ->
                runCatching { parseOne(item.toString()) to item.toString() }
                    .getOrElse { skipped++; null }
            }
            return Detailed(pairs, skipped)
        }

        /** 解析单个 legado 书源对象 */
        fun parseOne(json: String): LegadoBookSource {
            val obj = JsonParser.parseString(json).asJsonObject
            val name = obj.str("bookSourceName")
                ?: throw IllegalArgumentException("缺少 bookSourceName")
            val url = obj.str("bookSourceUrl") ?: ""
            return LegadoBookSource(
                name = name,
                url = url,
                type = obj.int("bookSourceType"),
                group = obj.str("bookSourceGroup") ?: "",
                comment = obj.str("bookSourceComment") ?: "",
                searchUrl = obj.str("searchUrl") ?: "",
                ruleSearch = obj.strMap("ruleSearch"),
                ruleBookInfo = obj.strMap("ruleBookInfo"),
                ruleToc = obj.strMap("ruleToc"),
                ruleContent = obj.strMap("ruleContent"),
                ruleExplore = obj.strMap("ruleExplore"),
                exploreUrl = obj.str("exploreUrl") ?: "",
                header = obj.str("header") ?: "",
                enabled = obj.bool("enabled", true),
            )
        }
    }
}

/** JsonObject 便捷取值扩展 */
private fun com.google.gson.JsonObject.str(key: String): String? = when {
    // 字段缺失或为 null
    !has(key) || get(key).isJsonNull -> null
    // 常规字符串
    get(key).isJsonPrimitive -> get(key).asString
    // 对象/数组：原样序列化为字符串，而不是抛异常。
    // 真实书源里 header / searchUrl 偶尔会写成 JSON 对象（如 "header": {"User-Agent": "..."}），
    // 旧实现直接 get(key).asString 抛 IllegalStateException → parseOne 失败 →
    // parseList 的 runCatching 把整个源静默丢弃（v1.3.0 排查「部分小说源不加载」的根因之一）。
    else -> get(key).toString()
}

private fun com.google.gson.JsonObject.int(key: String): Int =
    if (has(key) && get(key).isJsonPrimitive) get(key).asInt else 0

private fun com.google.gson.JsonObject.bool(key: String, default: Boolean): Boolean =
    if (has(key) && get(key).isJsonPrimitive) get(key).asBoolean else default

/** 解析值为 Map<String,String>，兼容嵌套 JSON 字符串 */
private fun com.google.gson.JsonObject.strMap(key: String): Map<String, String> {
    if (!has(key)) return emptyMap()
    val el = get(key)
    return when {
        el.isJsonObject -> el.asJsonObject.entrySet().associate { (k, v) ->
            k to (if (v.isJsonPrimitive) v.asString else v.toString())
        }
        el.isJsonPrimitive && el.asJsonPrimitive.isString -> {
            runCatching {
                JsonParser.parseString(el.asString).asJsonObject.entrySet()
                    .associate { (k, v) -> k to v.asString }
            }.getOrDefault(emptyMap())
        }
        else -> emptyMap()
    }
}
