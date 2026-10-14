package com.novelreader.ui.screens.comic

import com.novelreader.domain.model.ChapterItem

/**
 * 漫画目录分卷（C4）。
 *
 * 源适配器的 [ChapterItem] 没有结构化「卷」字段，卷信息只存在于章节标题里
 * （如「第1卷 第5话」「第一卷」「Vol.3 Ch.2」）。这里从标题解析卷号，用于目录分卷展示。
 *
 * 纯 UI 层渐进增强：解析不出卷号时整体退回单组（「未分卷」），不影响原有扁平目录。
 */
object ComicChapterVolume {

    /** 从标题解析卷号；解析不出返回 -1 */
    fun volumeOf(title: String): Int {
        // 阿拉伯数字卷：「第1卷」「Vol.3」「第12卷」
        Regex("第\\s*([0-9０-９]+)\\s*卷").find(title)?.let { return cnNumToInt(it.groupValues[1]) }
        Regex("(?i)vol\\.?\\s*([0-9０-９]+)").find(title)?.let { return cnNumToInt(it.groupValues[1]) }
        // 中文数字卷：「第一卷」「第二卷」
        Regex("第\\s*([零一二三四五六七八九十百千万两]+)\\s*卷").find(title)?.let {
            return chineseNumToInt(it.groupValues[1])
        }
        // 「卷1」「卷2」（中文/数字混合，卷在前）
        Regex("卷\\s*([0-9０-９]+)").find(title)?.let { return cnNumToInt(it.groupValues[1]) }
        return -1
    }

    /** 目录是否「看起来分卷」：至少有两个不同卷号，且卷号递增 */
    fun isVolumeStructured(chapters: List<ChapterItem>): Boolean {
        val vols = chapters.mapNotNull { volumeOf(it.title).takeIf { v -> v >= 0 } }
        if (vols.size < chapters.size / 2) return false
        if (vols.toSet().size < 2) return false
        return true
    }

    /** 把目录按卷分组，返回（卷名, 该卷章节）列表；卷内保持源顺序 */
    fun groupByVolume(chapters: List<ChapterItem>): List<Pair<String, List<ChapterItem>>> {
        if (chapters.isEmpty()) return emptyList()
        if (!isVolumeStructured(chapters)) {
            return listOf("全部章节" to chapters)
        }
        val result = mutableListOf<Pair<String, List<ChapterItem>>>()
        var curVol = -1
        var curName = ""
        var curList = mutableListOf<ChapterItem>()
        for (ch in chapters) {
            val v = volumeOf(ch.title)
            if (v >= 0 && v != curVol) {
                if (curList.isNotEmpty()) result.add(curName to curList)
                curVol = v
                curName = "第 ${v} 卷"
                curList = mutableListOf()
            }
            curList.add(ch)
        }
        if (curList.isNotEmpty()) result.add(curName to curList)
        return result
    }

    /** 全角/中文数字转 Int（支持 0-9 及简单中文数字） */
    private fun cnNumToInt(s: String): Int {
        val normalized = s.map { ch ->
            when (ch) {
                '０' -> '0'; '１' -> '1'; '２' -> '2'; '３' -> '3'; '４' -> '4'
                '５' -> '5'; '６' -> '6'; '７' -> '7'; '８' -> '8'; '９' -> '9'
                else -> ch
            }
        }.joinToString("")
        return normalized.toIntOrNull() ?: -1
    }

    private val cnDigits = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /**
     * 简单中文数字转 Int（如「三」「十二」「二十」「一百二十三」）。
     *
     * 从左到右累加，`十`/`百`/`千` 作为「单位」：
     * 遇到数字先记入 current，遇到单位则 current（空则 1）×单位 累加。
     * 例如「十二」= 1×10 + 2 = 12，「二十」= 2×10 = 20。
     */
    private fun chineseNumToInt(s: String): Int {
        if (s.isEmpty()) return -1
        var result = 0
        var current = 0
        var hasDigit = false
        for (ch in s) {
            when (ch) {
                '十' -> {
                    result += (if (current == 0) 1 else current) * 10
                    current = 0
                    hasDigit = true
                }
                '百' -> {
                    result += (if (current == 0) 1 else current) * 100
                    current = 0
                    hasDigit = true
                }
                '千' -> {
                    result += (if (current == 0) 1 else current) * 1000
                    current = 0
                    hasDigit = true
                }
                else -> {
                    val d = cnDigits[ch] ?: return -1
                    current = d
                    hasDigit = true
                }
            }
        }
        result += current
        return if (hasDigit) result else -1
    }
}
