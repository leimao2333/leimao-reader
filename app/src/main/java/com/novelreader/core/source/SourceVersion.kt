package com.novelreader.core.source

/**
 * 源协议版本比较（D13，对齐 venera `parser.dart` 的 `compareVersion`）。
 *
 * Venera 源的 JS 里会声明自己的版本（如 `version: "1.0.3"`）。本地补丁
 * （[com.novelreader.source.venera.SourceRepairs]）是针对**某个旧版本**写的，
 * 上游一旦修好并升版本，补丁就不该再往上打 —— 靠字符串匹配「碰巧还能对上」
 * 只会改坏新逻辑。这里提供版本解析与比较，供补丁层做版本闸。
 */
object SourceVersion {

    /**
     * 比较两个版本号：a < b 返回负数，相等返回 0，a > b 返回正数。
     *
     * 规则与 venera 一致：按 `.` 切段逐段比较数字；段数不同时短的那个按 0 补齐。
     * 非数字段（`1.0.0-beta` 里的 `0-beta`）取前导数字部分，取不到按 0 处理。
     */
    fun compare(a: String?, b: String?): Int {
        val pa = parse(a)
        val pb = parse(b)
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return if (va < vb) -1 else 1
        }
        return 0
    }

    /** [version] 是否 >= [min]（任一为空时视为「无法判定」→ 返回 true，保持旧行为） */
    fun isAtLeast(version: String?, min: String?): Boolean {
        if (version.isNullOrBlank() || min.isNullOrBlank()) return true
        return compare(version, min) >= 0
    }

    /** [version] 是否 <= [max]（任一为空时视为「无法判定」→ 返回 true，保持旧行为） */
    fun isAtMost(version: String?, max: String?): Boolean {
        if (version.isNullOrBlank() || max.isNullOrBlank()) return true
        return compare(version, max) <= 0
    }

    /**
     * 从源 JS 源码里抠出版本号。
     *
     * 兼容两种常见写法：`version: "1.0.0"` 与 `"version": "1.0.0"`。
     * 找不到就返回 null（补丁层按「无法判定」处理，仍然应用补丁）。
     */
    fun fromCode(code: String?): String? {
        if (code.isNullOrBlank()) return null
        return VERSION_PATTERN.find(code)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun parse(version: String?): List<Int> =
        version.orEmpty()
            .trim()
            .removePrefix("v")
            .split('.')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private val VERSION_PATTERN = Regex("""["']?version["']?\s*:\s*["']([0-9][0-9.]*)["']""")
}
