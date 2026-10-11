package com.novelreader.source.parser

import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.source.engine.JsonRuleParser

/**
 * JSON 源解析器（工厂）。
 *
 * 根据 [SourceInfo] 和原始内容构建 [JsonSourceAdapter]。
 */
class JsonSourceParser : SourceParser {

    override val format: SourceFormat = SourceFormat.JSON

    override fun createAdapter(info: SourceInfo, rawContent: String): SourceAdapter {
        val ruleSet = JsonRuleParser.parse(rawContent)
        return JsonSourceAdapter(info, ruleSet)
    }
}
