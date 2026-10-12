package com.novelreader.core.text

/**
 * 单条正文替换 / 净化规则（对标开源阅读的「替换净化」）。
 *
 * @param pattern 待匹配内容
 * @param replacement 替换为（可空字符串表示删除）
 * @param isRegex 是否按正则匹配
 */
data class ReplaceRule(
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = false,
)

/**
 * 替换规则集合的编解码与执行。
 *
 * 持久化格式（存于 DataStore 的单个字符串）：
 * - 每条规则一段，段间用 [SEG] 分隔；
 * - 段内字段用 [FIELD] 分隔：`isRegex` / `pattern` / `replacement`。
 *
 * 之所以不用 JSON：规则常包含反斜杠、引号等正则字符，自定义分隔符编码更稳、无需转义。
 */
object TextReplaceRules {

    private const val SEG = "\u001E"
    private const val FIELD = "\u001F"

    /** 解析 */
    fun decode(raw: String?): List<ReplaceRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEG).mapNotNull { seg ->
            if (seg.isEmpty()) return@mapNotNull null
            val parts = seg.split(FIELD)
            if (parts.size < 2) return@mapNotNull null
            ReplaceRule(
                isRegex = parts[0] == "1",
                pattern = parts[1],
                replacement = parts.getOrElse(2) { "" },
            ).takeIf { it.pattern.isNotEmpty() }
        }
    }

    /** 序列化 */
    fun encode(rules: List<ReplaceRule>): String =
        rules.filter { it.pattern.isNotEmpty() }
            .joinToString(SEG) { r ->
                listOf(if (r.isRegex) "1" else "0", r.pattern, r.replacement).joinToString(FIELD)
            }

    /**
     * 对正文依次应用全部规则。
     *
     * 单条规则出错（如非法正则）只跳过该条，不影响其余规则与阅读。
     */
    fun apply(content: String, rules: List<ReplaceRule>): String {
        if (content.isEmpty() || rules.isEmpty()) return content
        var text = content
        for (rule in rules) {
            text = runCatching {
                if (rule.isRegex) {
                    Regex(rule.pattern).replace(text, rule.replacement)
                } else {
                    text.replace(rule.pattern, rule.replacement)
                }
            }.getOrDefault(text)
        }
        return text
    }
}
