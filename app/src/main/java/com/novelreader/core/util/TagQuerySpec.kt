package com.novelreader.core.util

/**
 * 面向用户的标签查询语法（v1.8.1.3）。
 *
 * 输入框里除了「空格分隔 = 组间 AND」之外，还支持三种修饰：
 *
 * | 写法 | 含义 | 示例 |
 * |------|------|------|
 * | `-标签` / `!标签` | **排除**（对应嵌套查询的 must_not） | `无码 -有码` |
 * | `大类:标签` | 限定标签大类（题材 / 画风 / 受众 / 成人） | `题材:热血`、`成人:无码` |
 * | `前缀*` / `*后缀` | **通配**，按受控词表展开成一组标签 | `魔法*`、`*触手*` |
 *
 * 解析只负责把输入拆成 [Clause]（纯函数），展开通配与别名、拼嵌套查询由调用方完成 ——
 * 这样语法本身可以脱离数据库单测。
 *
 * 未识别的大类前缀（例如 `https://x`）会**整体当作标签名**，不会吞掉用户的输入。
 */
object TagQuerySpec {

    /** 受支持的标签大类（v1.8.1.2 的 [TagTagger] 大类口径） */
    val CATEGORIES: Set<String> = setOf("题材", "画风", "受众", "成人")

    /** 大类的英文 / 别名写法 → 标准大类 */
    private val CATEGORY_ALIASES: Map<String, String> = mapOf(
        "genre" to "题材", "tag" to "题材", "type" to "题材", "分类" to "题材",
        "style" to "画风", "art" to "画风", "画风" to "画风",
        "audience" to "受众", "target" to "受众", "读者" to "受众",
        "adult" to "成人", "r18" to "成人", "18" to "成人", "分级" to "成人",
    )

    /** 解析结果：一个逻辑标签条件 */
    data class Clause(
        /** 原始写法（已去掉 `-` / `!` 前缀与大类前缀，保留 `*`） */
        val raw: String,
        /** 标签名（通配时含 `*`） */
        val name: String,
        /** 限定大类；null = 不限 */
        val category: String? = null,
        /** 是否排除条件 */
        val exclude: Boolean = false,
        /** 是否含通配符 */
        val wildcard: Boolean = false,
    )

    /**
     * 解析查询串：按空白拆分（全角空格、`%20`、`+` 同样是分隔符，复用 [TagQuery.parse]）。
     *
     * 去重口径：同一 (name, category, exclude) 只保留第一次出现，保持输入顺序。
     */
    fun parse(query: String): List<Clause> {
        val out = LinkedHashSet<Clause>()
        TagQuery.parse(query).forEach { token ->
            val clause = parseToken(token) ?: return@forEach
            out.add(clause)
        }
        return out.toList()
    }

    private fun parseToken(token: String): Clause? {
        var body = token.trim()
        if (body.isEmpty()) return null

        // 1) 排除前缀：-xxx / !xxx / －xxx（全角减号）
        var exclude = false
        if (body.length > 1 && (body[0] == '-' || body[0] == '!' || body[0] == '－')) {
            exclude = true
            body = body.substring(1).trim()
        }
        if (body.isEmpty()) return null
        // 剩下的只有分隔符本身（如单独一个「-」）时也不构成条件
        if (body == "-" || body == "!" || body == "－") return null

        // 2) 大类前缀：只在左侧是已知大类时生效，否则整体当标签
        var category: String? = null
        val sep = body.indexOfFirst { it == ':' || it == '：' }
        if (sep > 0) {
            val head = body.substring(0, sep).trim()
            val tail = body.substring(sep + 1).trim()
            val mapped = CATEGORIES.firstOrNull { it.equals(head, ignoreCase = true) }
                ?: CATEGORY_ALIASES[head.lowercase()]
            if (mapped != null && tail.isNotEmpty()) {
                category = mapped
                body = tail
            }
        }

        val wildcard = body.contains('*') || body.contains('？') || body.contains('?')
        return Clause(
            raw = body,
            name = body,
            category = category,
            exclude = exclude,
            wildcard = wildcard,
        )
    }

    /**
     * 通配展开：[pattern] 里的 `*` 匹配任意串（`?` / `？` 匹配单个字符），大小写不敏感。
     *
     * @param vocabulary 受控词表（标签池 / 别名组），返回其中命中的**原始写法**
     */
    fun expandWildcard(pattern: String, vocabulary: Collection<String>): List<String> {
        val normalized = pattern.filterNot { it.isWhitespace() }.lowercase()
        if (normalized.isEmpty()) return emptyList()
        val regex = wildcardRegex(normalized)
        return vocabulary
            .filter { candidate -> regex.matches(candidate.filterNot { it.isWhitespace() }.lowercase()) }
            .distinct()
    }

    private fun wildcardRegex(normalized: String): Regex {
        val sb = StringBuilder()
        normalized.forEach { ch ->
            when (ch) {
                '*' -> sb.append(".*")
                '?', '？' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(sb.toString())
    }

    /** 从 [Clause] 生成可读说明（用于结果上方的提示）。 */
    fun describe(clause: Clause): String = buildString {
        if (clause.exclude) append("排除 ")
        if (clause.category != null) append(clause.category).append(':')
        append(clause.name)
    }
}
