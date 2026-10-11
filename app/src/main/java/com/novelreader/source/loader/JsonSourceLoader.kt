package com.novelreader.source.loader

import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.engine.JsonRuleParser
import java.util.UUID

/**
 * JSON 源加载器。
 *
 * 读取 JSON 源原始内容，解析为统一的 [SourceInfo]。
 */
class JsonSourceLoader : SourceLoader {

    override val format: SourceFormat = SourceFormat.JSON

    override fun load(rawContent: String, fileName: String?): SourceInfo {
        val ruleSet = JsonRuleParser.parse(rawContent)

        val type = SourceType.fromString(ruleSet.type)
            ?: throw IllegalArgumentException("无效的内容类型：${ruleSet.type}")

        // 用文件名或随机值生成稳定 ID
        val id = fileName?.substringBeforeLast(".")?.ifBlank { null }
            ?: "json_${UUID.randomUUID().toString().take(8)}"

        return SourceInfo(
            id = id,
            name = ruleSet.name,
            type = type,
            version = ruleSet.version,
            author = ruleSet.author,
            desc = ruleSet.desc,
            baseUrl = ruleSet.baseUrl,
            format = format,
        )
    }
}
