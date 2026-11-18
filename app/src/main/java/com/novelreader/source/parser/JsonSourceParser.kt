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
        // B2：规则含 legado 高级语法（多级 @ 链 / ## / {{}} / || / @js: / java.* 等）时，
        // 改走桥接 adapter 复用 legado 引擎能力；纯简单规则仍用原适配器，行为不回退。
        val advanced = listOf(
            ruleSet.search, ruleSet.detail, ruleSet.chapterList, ruleSet.content,
        ).any { rule ->
            rule != null && LegadoRuleBridge.hasAdvancedSyntax(
                listOf(rule.list, rule.text, rule.images, rule.videoUrl) + rule.fields.values,
            )
        }
        return if (advanced) {
            LegadoBridgeJsonAdapter(info, ruleSet)
        } else {
            JsonSourceAdapter(info, ruleSet)
        }
    }
}
