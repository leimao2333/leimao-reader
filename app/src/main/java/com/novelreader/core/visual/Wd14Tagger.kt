package com.novelreader.core.visual

/**
 * WD14 tagger v2 的**纯逻辑**部分（v1.8.1.4「封面视觉打标」）。
 *
 * 模型：SmilingWolf/wd-v1-4-vit-tagger-v2（Danbooru 标签体系，ONNX，373MB），
 * 从 hf-mirror.com 按需下载 —— HuggingFace 主站直连不通，镜像可达。
 *
 * 这里只放**不依赖 Android / ONNX 运行时**的部分：标签表解析、阈值过滤、
 * Danbooru 名 → 受控词表（[com.novelreader.core.util.TagAlias]）的映射，
 * 方便 JVM 单测；位图预处理与推理在 [Wd14Inference]。
 */
object Wd14TaggerSpec {

    /** 模型 / 标签表的默认下载地址（镜像站，国内可达） */
    const val DEFAULT_MODEL_URL: String =
        "https://hf-mirror.com/SmilingWolf/wd-v1-4-vit-tagger-v2/resolve/main/model.onnx"
    const val DEFAULT_TAGS_URL: String =
        "https://hf-mirror.com/SmilingWolf/wd-v1-4-vit-tagger-v2/resolve/main/selected_tags.csv"

    /** 模型输入边长（wd v2 系列统一 448） */
    const val INPUT_SIZE = 448

    /** general 标签的判定阈值：低于它的概率当没看见（Danbooru 惯例 0.35，这里稍放宽） */
    const val GENERAL_THRESHOLD = 0.30f

    /** selected_tags.csv 的 category 列取值 */
    const val CATEGORY_GENERAL = 0
    const val CATEGORY_ARTIST = 1
    const val CATEGORY_CHARACTER = 3
    const val CATEGORY_META = 4
    const val CATEGORY_RATING = 9
}

/** selected_tags.csv 解析结果：第 i 行对应模型输出的第 i 个概率 */
class Wd14Labels(private val rows: List<Row>) {

    data class Row(val name: String, val category: Int)

    val size: Int get() = rows.size

    /** 参与打标的行下标：general（题材 / 属性）+ character（角色），artist / meta / rating 丢弃 */
    val usableIndices: List<Int> = rows.withIndex()
        .filter { it.value.category == Wd14TaggerSpec.CATEGORY_GENERAL || it.value.category == Wd14TaggerSpec.CATEGORY_CHARACTER }
        .map { it.index }

    /** 角色标签（category=3）的下标集合 —— 角色名不进受控词表，单独存 */
    private val characterIndices: Set<Int> =
        rows.withIndex().filter { it.value.category == Wd14TaggerSpec.CATEGORY_CHARACTER }.mapTo(HashSet()) { it.index }

    fun row(index: Int): Row = rows[index]

    fun isCharacter(index: Int): Boolean = index in characterIndices

    companion object {

        /**
         * 解析 selected_tags.csv（首行表头 `tag_id,name,category,count`）。
         *
         * 按 RFC 4180 处理引号 —— 标签名可能自带逗号（`"comic, strip"`），不能直接 split。
         * 容忍 CRLF 与结尾空行。
         */
        fun parse(csvText: String): Wd14Labels {
            val rows = ArrayList<Row>(4096)
            csvText.lineSequence().forEachIndexed { lineIndex, line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachIndexed
                if (lineIndex == 0 && trimmed.startsWith("tag_id")) return@forEachIndexed
                val parts = splitCsvLine(trimmed)
                if (parts.size < 3) return@forEachIndexed
                val name = parts[1].trim()
                val category = parts[2].trim().toIntOrNull() ?: return@forEachIndexed
                if (name.isNotEmpty()) rows += Row(name, category)
            }
            return Wd14Labels(rows)
        }

        /** 引号感知的 CSV 单行拆分：引号内的逗号不算分隔符 */
        private fun splitCsvLine(line: String): List<String> {
            val out = ArrayList<String>(4)
            val field = StringBuilder()
            var inQuotes = false
            for (ch in line) {
                when {
                    ch == '"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> {
                        out.add(field.toString())
                        field.setLength(0)
                    }
                    else -> field.append(ch)
                }
            }
            out.add(field.toString())
            return out
        }
    }
}

/**
 * Danbooru 标签 → 受控词表。
 *
 * 视觉模型输出的是 Danbooru 体系（`long_hair` / `yuri` / `monochrome`…），而本项目的
 * 标签层只认 [com.novelreader.core.util.TagAlias] 受控词表 —— 不做映射直接入库会让
 * 几千个 danbooru 词污染标签池，复合查询的「组」也会被冲散。
 *
 * 口径：**只收登记过的词**。`yuri → 百合`、`monochrome / greyscale → 黑白`、
 * `comic → 漫画` 这类命中受控词表的才落库；`1girl` / `long_hair` 这类未登记的丢弃
 * （后续把高频词补进 [com.novelreader.core.util.TagAlias.GROUPS] 即可自动生效）。
 */
object Wd14Mapper {

    /** Danbooru 写法 → 受控词表别名。`_` 转空格后按 [com.novelreader.core.util.TagAlias] 归一。 */
    fun toControlled(tagName: String): String? {
        val spaced = tagName.replace('_', ' ').trim()
        if (spaced.isEmpty()) return null
        if (!com.novelreader.core.util.TagAlias.isKnownAlias(spaced)) return null
        val canonical = com.novelreader.core.util.TagAlias.canonicalOf(spaced)
        return canonical.ifBlank { null }
    }
}
