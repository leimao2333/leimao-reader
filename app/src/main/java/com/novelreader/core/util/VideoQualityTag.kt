package com.novelreader.core.util

import kotlin.math.abs

/**
 * 视频清晰度偏好与线路名识别（v1.8.4.0）。
 *
 * 背景：采集站大多数只给**单码率** 1080P，播放器里那条「多码率 HLS 才显示」的清晰度
 * 入口平时根本用不上（见 `collectQualityTracks()`）。真正能拉开差距的是**线路**——
 * 「选集 / 换源」里常有 `高清` / `4K` / `BD` / `1080P` 这类名字，把用户想要的那条排到
 * 前面，比在播放器里切轨更实用。
 *
 * 这里只做两件纯逻辑的事，方便离线单测：
 * 1. 从线路名里认出清晰度（[heightOf]）；
 * 2. 按偏好给线路排序（[sortIndices]），不改变线路本身、只给一个下标顺序。
 *
 * 注意：识别结果只用于**排序与角标**，不作为「这条线路一定是多少 P」的承诺 ——
 * 名字是站方随便写的，真按高度锁轨还得看播放器报出来的轨道（[nearestHeight]）。
 */
object VideoQualityTag {

    /** 自动：不排序、不锁轨 */
    const val AUTO = 0

    /** 设置页可选档位（0 = 自动） */
    val CHOICES = listOf(0, 480, 720, 1080, 2160)

    /** 档位展示文案（设置页 / 清晰度面板共用） */
    fun label(height: Int): String = when {
        height <= 0 -> "自动"
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "1080P"
        height >= 720 -> "720P"
        height >= 480 -> "480P"
        else -> "360P"
    }

    /**
     * 线路名 → 清晰度高度（认不出返回 null）。
     *
     * 从高档往低档匹配，避免 `1080P高清` 被「高清」先截胡。
     * 拉丁词加边界，避免 `bd` 撞上 `bdrip` 之外的普通单词。
     */
    fun heightOf(lineName: String): Int? {
        if (lineName.isBlank()) return null
        return RULES.firstOrNull { (regex, _) -> regex.containsMatchIn(lineName) }?.second
    }

    /** 线路名上的角标文案（认不出返回 null，UI 就不画角标） */
    fun labelOf(lineName: String): String? = heightOf(lineName)?.let { label(it) }

    /**
     * 按清晰度偏好给线路排序，返回**下标顺序**（不修改原列表）。
     *
     * 规则（稳定排序，同档保持站方原有顺序）：
     * 1. 名字里就是偏好档位的排最前；
     * 2. 其余认得出清晰度的按「离偏好差多少」升序；
     * 3. 认不出清晰度的排最后（名字里没信息，不代表差，但没法当首选）。
     *
     * [preferred] <= 0（自动）时原样返回。
     */
    fun sortIndices(names: List<String>, preferred: Int): List<Int> {
        val indices = names.indices.toList()
        if (preferred <= AUTO || names.size <= 1) return indices
        return indices.sortedWith(
            compareBy(
                { i ->
                    val h = heightOf(names[i])
                    when {
                        h == null -> 2
                        h == preferred -> 0
                        else -> 1
                    }
                },
                { i -> heightOf(names[i])?.let { abs(it - preferred) } ?: Int.MAX_VALUE },
                { i -> i },
            ),
        )
    }

    /**
     * 播放器报出的多个档位里，挑最接近偏好的一档（用于多码率 HLS 自动锁轨）。
     *
     * 偏好 <= 0 或没有可用档位时返回 null（= 保持自动）。
     * 等距时取**更高**一档 —— 宁可清晰一点，也不要糊。
     */
    fun nearestHeight(heights: List<Int>, preferred: Int): Int? {
        if (preferred <= AUTO) return null
        val usable = heights.filter { it > 0 }
        if (usable.isEmpty()) return null
        return usable.minWithOrNull(
            compareBy({ abs(it - preferred) }, { -it }),
        )
    }

    /** 线路名识别规则：按清晰度从高到低，先命中先返回 */
    private val RULES: List<Pair<Regex, Int>> = listOf(
        Regex("""(?i)(?<![a-z0-9])(4k|uhd|2160p?)(?![a-z0-9])""") to 2160,
        Regex("""(?i)(?<![a-z0-9])(2k|1440p?)(?![a-z0-9])""") to 1440,
        Regex("""(?i)(?<![a-z0-9])(1080p?|fhd|fullhd|blu-?ray|bdrip|bd|remux)(?![a-z0-9])""") to 1080,
        Regex("""原画|超清|蓝光""") to 1080,
        Regex("""(?i)(?<![a-z0-9])(720p?|hd)(?![a-z0-9])""") to 720,
        Regex("""高清""") to 720,
        Regex("""(?i)(?<![a-z0-9])(480p?|sd)(?![a-z0-9])""") to 480,
        Regex("""标清""") to 480,
        Regex("""(?i)(?<![a-z0-9])(360p?)(?![a-z0-9])""") to 360,
        Regex("""流畅""") to 360,
    )
}
