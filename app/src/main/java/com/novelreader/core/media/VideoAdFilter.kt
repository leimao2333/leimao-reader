package com.novelreader.core.media

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 视频广告过滤（v1.9.6.0）。
 *
 * 采集站的广告几乎都不是「播放器里的弹窗」，而是**拼进 HLS 播放列表的分片**：
 * 一集正片被切成几百个 `.ts`，广告商塞进来几个自己的分片（贴片在前、中插在中），
 * 播放器完全分不清哪段是正片 —— 于是用户被迫连看 15 秒广告，进度条还照样走。
 *
 * 这个对象只做一件事：**把播放列表里的广告分片摘掉**，输出一份干净的清单。
 * 分片本身不动（正片的 `.ts` 一个字节都没改），所以不影响画质、不重编码、不耗流量。
 *
 * ## 怎么认广告
 *
 * 两条互相独立的判据，命中任一条即为广告段：
 *
 * 1. **HLS 官方广告标记**：`#EXT-X-CUE-OUT` 到 `#EXT-X-CUE-IN` 之间的分片。
 *    这是 HLS 给「插播广告」定的标准标签（SCTE-35 走的就是它），凡是用正规工具
 *    打包的广告都带这对标记，识别零误伤。
 * 2. **分片地址的广告特征**：路径里带 `/ad/` `/ads/` `/gg/` `/guanggao/` 这类目录，
 *    或文件名整串就是 `ad` / `ad1` / `preroll` / `midroll` 之类。
 *
 * 第 2 条必须**整串匹配**：`load` / `download` / `header` / `shadow` 里都含 `ad`，
 * 早先图省事用 `contains("ad")` 的写法会把正常分片删光 —— 这是这类过滤最容易翻车的地方。
 *
 * ## 两道安全阀
 *
 * 认错就要么看不成、要么看半截，所以宁可少删：
 *
 * - 删完一个分片都不剩 → 放弃，交回原始清单；
 * - 删掉的时长超过整集一半 → 放弃。正片被「广告规则」吃掉一半，只可能是规则误伤。
 *
 * ## 删中间那段为什么要补 DISCONTINUITY
 *
 * HLS 的时间轴由分片自带的 PTS 决定，中间挖掉一段后前后 PTS 不连续。按规范必须用
 * `#EXT-X-DISCONTINUITY` 声明「这里时间戳重置」，否则播放器会从挖掉的位置开始音画错位，
 * 甚至直接判流损坏。所以每处删除点都补一个（原本就有就不重复加）。
 *
 * ## 已知覆盖不到的情况
 *
 * 广告**烧在正片分片内部**（同一份 `.ts` 里前 15 秒是广告画面）时，这一层无能为力 ——
 * 那需要重新转码，不是改清单能解决的。
 */
object VideoAdFilter {

    /** 过滤后的播放列表 */
    data class Filtered(
        /** 干净清单的全文 */
        val playlist: String,
        /** 摘掉的分片数 */
        val removedSegments: Int,
        /** 摘掉的时长（毫秒），按被摘分片的 EXTINF 累加 */
        val removedMs: Long,
        /** 原清单的分片总数 */
        val totalSegments: Int,
    )

    /** 一次过滤的播报（供播放页提示「已跳过 N 个广告片段」） */
    data class Report(
        val url: String,
        val removedSegments: Int,
        val removedMs: Long,
        val totalSegments: Int,
    ) {
        val removedSeconds: Int get() = (removedMs / 1000L).toInt()
    }

    /**
     * 总开关。
     *
     * 初值 true（设置项的默认值也是开），DataStore 读完再回灌真实值 ——
     * 数据源是同步构建的，等不到异步设置，只能先按默认走。
     */
    @Volatile
    var enabled: Boolean = true

    private val _events = MutableSharedFlow<Report>(extraBufferCapacity = 8)

    /** 过滤播报：同一集每次重新缓冲都会重新过滤一遍，播放页按地址去重后提示一次 */
    val events: SharedFlow<Report> = _events.asSharedFlow()

    /** 已经播报过的清单地址（避免重缓冲 / 拖动进度时反复弹提示） */
    @Volatile
    private var notifiedUrl: String? = null

    /**
     * 播报一次过滤结果。
     *
     * `@Synchronized` 是必要的：过滤发生在播放器的下载线程，可能是多个分片
     * 并行触发，两个线程同时通过「没播报过」的检查就会弹两次。
     */
    @Synchronized
    fun notifyOnce(url: String, filtered: Filtered) {
        if (notifiedUrl == url) return
        notifiedUrl = url
        _events.tryEmit(
            Report(url, filtered.removedSegments, filtered.removedMs, filtered.totalSegments),
        )
    }

    // ===== 广告分片识别 =====

    /**
     * 命中即认为是广告目录的路径段。
     *
     * 只认**整段**相等，不认包含：`/ad/` 是广告，`/advertise-cover/` 不是（可能是正片封面）。
     */
    private val AD_DIRS = setOf(
        "ad", "ads", "adv", "advert", "adverts", "advertisement",
        "gg", "guanggao", "preroll", "midroll", "postroll", "spot",
    )

    /**
     * 分片文件名（去掉扩展名）的广告形状，整串匹配。
     *
     * 允许带序号：`ad1`、`ad_2`、`advert-3`。不允许任何多余字符 ——
     * `load` 含 `ad` 但不是广告，`download_1` 同理，整串锚定才能把它们排除掉。
     */
    private val AD_FILE = Regex(
        "^(?:ad|ads|adv|advert|adverts|advertisement|gg|guanggao|preroll|midroll|postroll|spot)" +
            "(?:[-_]?\\d+)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 播放列表级标签：出现在第一个分片之前时归入「头部」。
     *
     * 为什么要单独挑出来：清理时整段删除（第一段可能就是贴片广告），若 `#EXTM3U`
     * 被算作那一段的前缀，删广告就会连文件头一起删掉 —— 播放器直接判「不是 m3u8」。
     */
    private val HEAD_TAGS = listOf(
        "#EXTM3U",
        "#EXT-X-VERSION",
        "#EXT-X-TARGETDURATION",
        "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-DISCONTINUITY-SEQUENCE",
        "#EXT-X-PLAYLIST-TYPE",
        "#EXT-X-I-FRAMES-ONLY",
        "#EXT-X-INDEPENDENT-SEGMENTS",
        "#EXT-X-START",
        "#EXT-X-ALLOW-CACHE",
    )

    /**
     * 地址是否像广告分片。`uri` 可以是相对地址（清单里常见），只看路径形状。
     */
    fun isAdUri(uri: String): Boolean {
        val path = uri.trim().substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        // 目录段：除最后一段（文件名）之外
        for (i in 0 until parts.size - 1) {
            if (parts[i].lowercase() in AD_DIRS) return true
        }
        return AD_FILE.matches(parts.last().substringBeforeLast('.'))
    }

    /**
     * 过滤一份媒体播放列表。
     *
     * @return 过滤结果；`null` 表示**不要动**（不是媒体清单 / 没认出一个广告 /
     *         认出的广告多到不可能 / 删完就空了）
     */
    fun filterPlaylist(text: String): Filtered? {
        val trimmed = text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (!trimmed.startsWith("#EXTM3U")) return null
        // 主清单只有码率变体、没有分片，过滤它没有意义（也不该动，删了变体等于没法自适应）
        if (text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return null

        val head = ArrayList<String>()
        val segments = ArrayList<Segment>()
        val tail = ArrayList<String>()
        var pending = ArrayList<String>()
        var sawSegment = false
        var inCueAd = false

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val upper = line.uppercase()
                if (!sawSegment && HEAD_TAGS.any { upper.startsWith(it) }) {
                    head += line
                    continue
                }
                // 顺序很关键：先看标记、再收进 pending，段落下时才知道自己属于广告区间
                if (upper.startsWith("#EXT-X-CUE-OUT") || upper.startsWith("#EXT-OATCLS-SCTE35")) {
                    inCueAd = true
                } else if (upper.startsWith("#EXT-X-CUE-IN")) {
                    inCueAd = false
                }
                pending += line
                continue
            }
            segments += Segment(pending, line, durationMsOf(pending), inCueAd)
            pending = ArrayList()
            sawSegment = true
        }
        // 没有分片之前剩下的标签属于头部；有分片则剩下的都是尾部（ENDLIST 之类）
        if (sawSegment) tail += pending else head += pending

        val total = segments.size
        if (total == 0) return null

        var removedCount = 0
        var removedMs = 0L
        var totalMs = 0L
        for (seg in segments) {
            totalMs += seg.durationMs
            if (seg.isAd) {
                removedCount++
                removedMs += seg.durationMs
            }
        }
        if (removedCount == 0) return null
        // 安全阀 1：全被判成广告（删完就空了）—— 一定是认错了
        if (removedCount == total) return null
        // 安全阀 2：正片被「广告」吃掉一半 —— 同样是认错了，宁可不过滤
        val keptMs = totalMs - removedMs
        if (totalMs > 0 && keptMs * 2 < totalMs) return null

        val out = StringBuilder(text.length)
        head.forEach { out.append(it).append('\n') }

        var emitted = false
        var removedBefore = false
        for (seg in segments) {
            if (seg.isAd) {
                removedBefore = true
                continue
            }
            if (removedBefore) {
                // 中间挖掉分片 → 时间戳断层，得声明一下；开头那段不用（后面就是时间轴起点）
                if (emitted && seg.prefix.none { it.trim().uppercase().startsWith("#EXT-X-DISCONTINUITY") }) {
                    out.append("#EXT-X-DISCONTINUITY\n")
                }
                removedBefore = false
            }
            // 保留整段前缀（EXTINF / BYTERANGE / KEY / MAP / DISCONTINUITY 都要留着），
            // 只丢掉已经失去意义的 CUE 标记
            seg.prefix.forEach { if (!isCueTag(it)) out.append(it).append('\n') }
            out.append(seg.uri).append('\n')
            emitted = true
        }
        tail.forEach { out.append(it).append('\n') }

        return Filtered(
            playlist = out.toString(),
            removedSegments = removedCount,
            removedMs = removedMs,
            totalSegments = total,
        )
    }

    // ===== 内部结构 =====

    /** 清单里的一个分片：段级标签 + 地址 + 时长 */
    private class Segment(
        val prefix: List<String>,
        val uri: String,
        val durationMs: Long,
        /** 是否落在 `#EXT-X-CUE-OUT` ~ `#EXT-X-CUE-IN` 之间 */
        val cueAd: Boolean,
    ) {
        val isAd: Boolean get() = cueAd || isAdUri(uri)
    }

    /** 段前缀里的 `#EXTINF:9.009,` → 9009 毫秒 */
    private fun durationMsOf(prefix: List<String>): Long {
        prefix.forEach { line ->
            val t = line.trim()
            if (!t.startsWith("#EXTINF:", ignoreCase = true)) return@forEach
            val seconds = t.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
            return (seconds * 1000).toLong()
        }
        return 0L
    }

    /** 广告区间标记，过滤后已无意义 */
    private fun isCueTag(line: String): Boolean {
        val u = line.trim().uppercase()
        return u.startsWith("#EXT-X-CUE-OUT") || u.startsWith("#EXT-X-CUE-IN") ||
            u.startsWith("#EXT-OATCLS-SCTE35") || u.startsWith("#EXT-X-SCTE35")
    }
}
