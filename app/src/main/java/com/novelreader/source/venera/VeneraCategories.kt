package com.novelreader.source.venera

/**
 * 从 Venera 源 JS 代码里解析它**自己声明的分类**（v1.6.6.0）。
 *
 * **为什么需要它**：Venera 源协议里没有「按分类浏览」接口（只有 search / info /
 * chapters / content），所以这些源在标签体系里长期是**零贡献** —— 25 个 Venera
 * 漫画源一个标签都产不出来。但源代码里其实声明了完整的分类表：
 *
 * ```js
 * class Baozi extends ComicSource {
 *   category = {
 *     title: "包子漫画",
 *     parts: [
 *       { name: "题材", type: "fixed", categories: ["全部", "恋爱", "古风", "异能", ...] },
 *     ],
 *   }
 * }
 * ```
 *
 * 老式写法（hcomic 等）的分类是对象数组，名字在 `label` 里：
 *
 * ```js
 * categories: [ { label: "全彩", target: { page: "category", attributes: { category: "全彩" } } } ]
 * ```
 *
 * 两种写法都取到**名字**，交给调用方绑成「按关键词搜索」入口
 * （见 [com.novelreader.core.util.TagEntryUrl]）——Venera 没有分类页，
 * 但每个源都有 search，所以点标签 = 拿分类名去搜该源，链路依然通。
 *
 * 解析用「扫括号配对 + 只取顶层字符串 / `label` 值」而不是正则抓字符串，
 * 这样既不会漏掉多段 `parts`，也不会把对象里的键名（`category` / `page` 之类）
 * 误当成分类名。纯函数，可用真实源代码做单测。
 */
object VeneraCategories {

    /** 分类数组的开头：`categories: [` */
    private val CATEGORIES_KEY = Regex("""categories\s*:\s*\[""")

    /**
     * 解析全部分类名（按出现顺序去重）。
     *
     * 同一份代码里的多个 `parts` 会全部合并 —— 一个 Venera 源可以有
     * 「题材 / 地区 / 进度」多组分类，都是有效标签。
     */
    fun parse(code: String): List<String> {
        val out = LinkedHashSet<String>()
        var from = 0
        while (true) {
            val m = CATEGORIES_KEY.find(code, from) ?: break
            val (body, end) = arrayBody(code, m.range.last + 1)
            extractNames(body).forEach { name ->
                val n = name.trim()
                if (n.isNotEmpty()) out.add(n)
            }
            // 同一位置可能重叠，继续往后找
            from = if (end > m.range.last) end else m.range.last + 1
        }
        return out.toList()
    }

    /** 从 `[` 之后扫到配对的 `]`，返回数组体与结束下标（`]` 之后） */
    private fun arrayBody(code: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '[' -> depth++
                ']' -> depth--
            }
            if (depth > 0) sb.append(code[i])
            i++
        }
        return sb.toString() to i
    }

    /**
     * 取出数组里「算分类名」的字符串。
     *
     * - `{}` 深度 0（数组直接元素）→ 字符串就是分类名；
     * - `{}` 深度 >= 1（对象里）→ 只有紧跟在 `label:` 后面的字符串才算，
     *   其余（`category:` / `page:` / `param:` 的值与键名）全部跳过。
     */
    private fun extractNames(body: String): List<String> {
        val names = mutableListOf<String>()
        var depth = 0
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '{' -> { depth++; i++ }
                '}' -> { depth--; i++ }
                '"', '\'' -> {
                    val (literal, next) = readString(body, i)
                    if (literal != null) {
                        if (depth == 0 || precedingKey(body, i) == "label") names.add(literal)
                    }
                    i = next
                }
                else -> i++
            }
        }
        return names
    }

    /**
     * 读一个字符串字面量；返回内容与结束下标。
     *
     * 常见转义（`\n` / `\t` / `\r` / `\\` / `\"` / `\'`）按 JS 语义还原，
     * 未知转义原样保留 —— 分类名里的 `"\t校园\n"` 要能还原成真实空白，
     * 之后才能被 `trim()` 裁掉。
     */
    private fun readString(s: String, start: Int): Pair<String?, Int> {
        val quote = s[start]
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    when (val n = s[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        '\'' -> sb.append('\'')
                        else -> sb.append('\\').append(n)
                    }
                    i += 2
                }
                c == quote -> return sb.toString() to (i + 1)
                else -> { sb.append(c); i++ }
            }
        }
        return null to s.length
    }

    /**
     * 判断字符串前面的键名（如 `label: "全彩"` 里的 `label`）。
     *
     * 从引号位置往前跳过空白，应当先遇到 `:`，再往前跳过空白读一个标识符。
     * 不满足这个形状就返回 null（不是「键: 值」形式）。
     */
    private fun precedingKey(s: String, quoteIndex: Int): String? {
        var i = quoteIndex - 1
        while (i >= 0 && s[i].isWhitespace()) i--
        if (i < 0 || s[i] != ':') return null
        i--
        while (i >= 0 && s[i].isWhitespace()) i--
        val end = i
        while (i >= 0 && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) i--
        if (end < 0) return null
        return s.substring(i + 1, end + 1).takeIf { it.isNotEmpty() }
    }
}
