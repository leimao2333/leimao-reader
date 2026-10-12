package com.novelreader.core.text

/**
 * 正文净化（对标开源阅读的「净化」流程）。
 *
 * 目标：把各书源五花八门的正文统一成规整的段落文本，再做替换规则处理，
 * 供阅读页做缩进 / 段距排版。
 *
 * 处理顺序：
 * 1. 统一换行符（\r\n / \r → \n）
 * 2. 应用用户自定义替换规则（正则 / 字面）
 * 3. 去掉每行行尾空白
 * 4. 折叠连续空行（3 行以上空行压缩为 1 个空行）
 * 5. 去掉整体首尾空白
 */
object TextCleaner {

    private val MULTI_BLANK = Regex("\n{3,}")

    fun clean(raw: String, rules: List<ReplaceRule> = emptyList()): String {
        if (raw.isEmpty()) return raw
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = TextReplaceRules.apply(text, rules)
        text = text.lineSequence().joinToString("\n") { it.trimEnd() }
        text = MULTI_BLANK.replace(text, "\n\n")
        return text.trim()
    }

    /** 按空行 / 换行切分为段落列表（过滤纯空白段） */
    fun paragraphs(text: String): List<String> =
        text.split('\n').filter { it.isNotBlank() }

    /** 估算字数（去掉空白后的字符数） */
    fun charCount(text: String): Int = text.count { !it.isWhitespace() }
}
