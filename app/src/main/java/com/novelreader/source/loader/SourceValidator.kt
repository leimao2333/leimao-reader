package com.novelreader.source.loader

import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceType

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
    )

    /**
     * 校验源原始内容。
     */
    fun validate(rawContent: String, fileName: String? = null): Result {
        val content = rawContent.trim()
        if (content.isEmpty()) {
            return Result(false, "源内容为空")
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
        }
    }

    /** 检测源格式 */
    private fun detectFormat(content: String, fileName: String?): SourceFormat? {
        // 优先按文件扩展名判断
        fileName?.lowercase()?.let {
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
        // 其次按内容特征判断
        return when {
            looksLikeLegado(content) -> SourceFormat.LEGADO
            content.startsWith("{") || content.startsWith("[") -> SourceFormat.JSON
            content.contains("function") || content.contains("getMeta") -> SourceFormat.JS
            isUrlText(content) -> SourceFormat.LEGADO
            else -> null
        }
    }

    /** 是否为 legado 书源格式（含 bookSourceName 字段） */
    private fun looksLikeLegado(content: String): Boolean {
        if (!content.contains("bookSourceName")) return false
        // 尝试解析，判断是单个还是列表
        return runCatching {
            com.google.gson.JsonParser.parseString(content.trim())
            true
        }.getOrDefault(false)
    }

    /** 是否为网址型内容（txt 内是 URL） */
    private fun isUrlText(content: String): Boolean {
        val singleLine = content.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        return singleLine.startsWith("http://") || singleLine.startsWith("https://")
    }

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
