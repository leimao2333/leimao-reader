package com.novelreader.source.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * JSON 源规则解析器。
 *
 * 将 JSON 源原始文本解析为结构化的 [JsonRuleSet]。
 * 供 JsonSourceLoader / JsonSourceParser 使用。
 */
object JsonRuleParser {

    /**
     * 解析 JSON 源文本为规则集。
     *
     * @throws IllegalArgumentException 当 JSON 格式错误或缺少必要字段时
     */
    fun parse(rawContent: String): JsonRuleSet {
        val root = try {
            JsonParser.parseString(rawContent).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON 解析失败：${e.message}")
        }

        return JsonRuleSet(
            name = root.str("name") ?: throw IllegalArgumentException("缺少 name 字段"),
            type = root.str("type") ?: throw IllegalArgumentException("缺少 type 字段"),
            version = root.str("version") ?: "1.0.0",
            author = root.str("author") ?: "",
            desc = root.str("desc") ?: "",
            baseUrl = root.str("baseUrl") ?: "",
            search = root.obj("search")?.toRule(),
            detail = root.obj("detail")?.toRule(),
            chapterList = root.obj("chapterList")?.toRule(),
            content = root.obj("content")?.toRule(),
        )
    }

    /** 将 JSON 对象解析为单条 Rule */
    private fun JsonObject.toRule(): Rule? {
        val url = str("url") ?: return null
        return Rule(
            url = url,
            method = str("method") ?: "GET",
            list = str("list"),
            fields = obj("fields")?.toStringMap() ?: emptyMap(),
            text = str("text"),
            images = str("images"),
            videoUrl = str("videoUrl"),
            headers = obj("headers")?.toStringMap() ?: emptyMap(),
            remove = arr("remove")?.map { it.asString } ?: emptyList(),
        )
    }

    private fun JsonObject.str(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.obj(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.arr(key: String): List<com.google.gson.JsonElement>? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray.toList() else null

    private fun JsonObject.toStringMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        entrySet().forEach { (k, v) ->
            if (v.isJsonPrimitive) map[k] = v.asString
        }
        return map
    }
}
