package com.novelreader.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.novelreader.core.util.CoverUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 缩略图（预览图 / 样张）专用加载通道（TODO 第十六章 D15 + D17）。
 *
 * ## 为什么要单独开一条通道
 *
 * 缩略图和正文大图是两类**完全不同的资源**：
 *
 * | | 正文大图 | 缩略图 / 样张 |
 * |---|---|---|
 * | 数量 | 一话几十页 | 一个列表页几百张 |
 * | 单张体积 | 数百 KB ~ 数 MB | 几 KB ~ 几十 KB |
 * | 超时容忍度 | 慢一点可以接受（用户正盯着这一页） | 必须**快速失败**，否则整屏滚动被拖住 |
 * | 缓存期望 | 尽量多留（翻页回看要秒开） | 少量留一点即可，别把磁盘撑爆 |
 *
 * 此前两者共用 `NovelReaderApp.newImageLoader()` 那一条链路，于是：
 *
 * 1. **超时被正文绑架** —— 正文图读超时 30s，缩略图也拿到 30s。
 *    一个失效的图床会让列表滚过十几张占位块，每张都等满超时。
 * 2. **缓存互相挤占** —— 正文大图把内存缓存占满后，缩略图反复被挤出，
 *    列表来回滑就是一次「重新解码」的循环。
 * 3. **磁盘目录混在一起** —— 几百张缩略图会把真正想留的正文页缓存挤掉。
 *
 * 所以这里给缩略图一条**完全独立**的链路：
 *
 * - 独立 `OkHttpClient`：连接/读超时都比正文**短**（快速失败 → 降级到占位图），
 *   连接池与并发上限也比正文小（缩略图数量多但单张小，不值得占满正文的名额）；
 * - 独立磁盘目录 `cacheDir/thumbnails` 与独立内存缓存（容量比正文小一个量级）；
 * - **缓存 key 带用途标识**：缩略图与正文图的 key 绝不相同，
 *   就算将来两个通道共用同一个缓存目录，也不会「拿大图当缩略图」或反之；
 * - **按 sourceId 隔离**：不同源的缩略图质量与体积差异很大，
 *   允许按源设置磁盘缓存条目上限、或干脆禁止落盘（见 [ThumbnailPolicies]）。
 *
 * ## 分层
 *
 * 为了能离线单测，本文件把「策略」与「IO」拆成两层：
 *
 * - **纯逻辑层**（无 android/OkHttp 依赖，可直接 JUnit 测）：
 *   [ThumbnailUrlResolver]（地址解析与候选排序）、[ThumbnailCacheKey]（缓存 key）、
 *   [ThumbnailPolicies]（超时/配额策略）、[ThumbnailSampling]（采样倍数）、
 *   [ThumbnailCancelHandle]（取消句柄）、[ThumbnailMemoryCache] / [ThumbnailDiskCache]。
 * - **IO 层**（本文件下半部分）：[ThumbnailChannel] 负责真正的取图、编解码与缓存落地。
 *
 * ## 与 D16 的关系
 *
 * D16 要的是「大图可中断」。这里顺带把**可取消**的能力做出来：
 * [ThumbnailChannel.enqueue] 返回一个 [ThumbnailCancelHandle]，
 * 取消后正在飞行的 HTTP 请求会被 `Call.cancel()` 打断（而不是等它超时）。
 */

/** 缩略图地址的来源类型；数值越小优先级越高。 */
enum class ThumbnailKind(val priority: Int) {
    /** 源显式声明的独立缩略图 / 章节样张地址（D15 `loadComicThumbnail` 的产物）。 */
    THUMBNAIL(0),

    /** 条目封面地址：缩略图缺失、或缩略图加载失败时的回退。 */
    COVER(1),
}

/**
 * 一条可尝试的缩略图地址。
 *
 * 之所以是「列表」而不是「单个字符串」：缩略图的可用性天然不稳定
 * （样张地址会过期、CDN 会 403、有的源只给了封面），
 * 加载失败时需要**换下一个候选**，而不是直接显示占位。
 */
data class ThumbnailCandidate(
    /** 已补全为绝对地址的候选 URL。 */
    val url: String,
    /** 该地址的来源类型，决定尝试顺序与诊断信息。 */
    val kind: ThumbnailKind,
)

/**
 * 漫画源返回的、跟缩略图有关的那部分字段。
 *
 * 这里刻意只声明「跟缩略图有关」的字段，而不是直接依赖某个源的条目模型：
 * 章节样张（D15）和列表封面来源各异，但落到通道上只需要
 * 「一个优先用的地址 + 一个兜底地址 + 一个站点基址」。
 */
data class ThumbnailEntry(
    /** 源独立提供的缩略图 / 章节样张地址（可能是相对路径，也可能是脏值）。 */
    val thumbUrl: String? = null,

    /** 条目封面地址：缩略图缺失时的兜底。 */
    val coverUrl: String? = null,

    /** 站点基址（如 `https://example.com`），用于把相对地址补全成绝对地址。 */
    val baseUrl: String? = null,
)

// ============================================================================
// A. 缩略图地址解析（D15）
// ============================================================================

/**
 * 缩略图地址解析与候选排序（纯逻辑，无 android 依赖）。
 *
 * ## 为什么要自己再解析一遍
 *
 * 源脚本取不到值时，引擎会把 JS 的 `undefined` **当成普通对象**再拼进站点地址，
 * 得到一串看似合法、实际永远 404 的地址：
 *
 * ```
 * https://ubook.reader.qq.com/org.mozilla.javascript.Undefined@dc3369d
 * ```
 *
 * 这类脏串一旦进入候选列表，就等于「白等一次超时」——
 * 而缩略图恰恰是**最经不起等**的资源。所以这里沿用
 * [com.novelreader.core.util.CoverUrl] 的防御口径（同一套 `sanitize` / `resolve`），
 * 保证缩略图地址的干净程度与封面地址完全一致，不给两套规则留分叉的口子。
 *
 * 额外加了两条缩略图专属的约束：
 *
 * - **长度上限**（[MAX_URL_LENGTH]）：超长地址基本是模板拼错或脏串拼接的产物，
 *   直接丢弃比「留着等超时」划算。
 * - **去重**：缩略图与封面解析出同一个地址时只保留一条（按优先级高的那条），
 *   避免同一个请求白跑两遍。
 */
object ThumbnailUrlResolver {

    /**
     * 缩略图地址长度上限。
     *
     * 取 4096：常见的 CDN 签名 URL 在几百字节量级，
     * 而「相对路径被拼成超长串」这类脏值轻易就能超过 4K。
     */
    const val MAX_URL_LENGTH = 4096

    /**
     * 解析漫画源条目里的缩略图候选地址，按优先级排序。
     *
     * 规则：
     * 1. `thumbUrl` 优先（源真的给了独立预览图）；
     * 2. `coverUrl` 作为兜底（thumbUrl 为空 / 是脏值时才有意义）；
     * 3. 相对地址按 `baseUrl` 补全，绝对地址原样保留；
     * 4. 脏值（`undefined` / `null` / 空串 / JS undefined 痕迹）直接丢弃；
     * 5. 同一个绝对地址只保留优先级最高的那一条。
     *
     * @return 有序候选列表；没有任何可用地址时返回空列表（调用方据此跳过加载）。
     */
    fun resolve(entry: ThumbnailEntry): List<ThumbnailCandidate> {
        val picked = LinkedHashMap<String, ThumbnailCandidate>()

        addCandidate(picked, entry.thumbUrl, ThumbnailKind.THUMBNAIL, entry.baseUrl)
        addCandidate(picked, entry.coverUrl, ThumbnailKind.COVER, entry.baseUrl)

        // 按优先级排序（稳定排序：同优先级保持插入顺序）
        return picked.values.sortedBy { it.kind.priority }
    }

    /**
     * 便捷入口：直接按「基址 + 缩略图 + 封面」三个参数解析。
     */
    fun resolve(baseUrl: String?, thumbUrl: String? = null, coverUrl: String? = null): List<ThumbnailCandidate> =
        resolve(ThumbnailEntry(thumbUrl = thumbUrl, coverUrl = coverUrl, baseUrl = baseUrl))

    /**
     * 把一个原始地址变成可尝试的候选；无效时返回 null（不入列表）。
     */
    private fun addCandidate(
        sink: MutableMap<String, ThumbnailCandidate>,
        raw: String?,
        kind: ThumbnailKind,
        baseUrl: String?,
    ) {
        val url = resolveOne(raw, baseUrl) ?: return
        // 同一个地址只保留先出现的那条（先出现的优先级更高）
        if (sink.containsKey(url)) return
        sink[url] = ThumbnailCandidate(url, kind)
    }

    /**
     * 单个地址的清洗 + 补全。
     *
     * 复用 [CoverUrl.resolve]：它已经处理了脏值字面量（`undefined` / `null` / `nan` …）、
     * JS undefined 痕迹、协议相对地址（`//host/path`）、根相对地址（`/path`）
     * 与普通相对路径（`cover/1.jpg`），并把结果限制在受支持的协议白名单内。
     */
    fun resolveOne(raw: String?, baseUrl: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        // 超长地址几乎必然是脏串：留着只会白等一次超时
        if (value.length > MAX_URL_LENGTH) return null
        return CoverUrl.resolve(value, baseUrl)
    }
}

// ============================================================================
// B. 缓存 key（用途隔离 + 按源隔离）
// ============================================================================

/**
 * 图片缓存的用途标识。
 *
 * 缩略图与正文图**必须**用不同的 key：同一个 URL 在两个通道里可能对应完全不同的
 * 目标（缩略图要小图、正文图要原图），一旦 key 撞车，就会出现
 * 「列表里拿到大图」或「正文页拿到缩略图」这类张冠李戴的错位。
 */
enum class ImagePurpose(val token: String) {
    /** 正文大图：漫画内页、封面原图等。 */
    CONTENT("content"),

    /** 缩略图 / 样张预览图。 */
    THUMBNAIL("thumbnail"),
}

/**
 * 图片缓存 key 生成（纯逻辑）。
 *
 * key 的三段结构：`版本 | 用途 | 源 | URL`。
 *
 * - **用途**是必须的：解决缩略图与正文图互相污染的问题；
 * - **源**（sourceId）是必须的：不同源的同一条 URL 可能是完全不同的内容
 *   （图床 + 鉴权 + 请求头都不同），混在一起缓存等于张冠李戴；
 * - **版本**是为了将来改 key 结构时能自然失效旧缓存，而不是把新旧语义搅在一起。
 */
object ThumbnailCacheKey {

    /** key 结构版本号；结构变更时 +1，旧缓存自然失效。 */
    const val KEY_VERSION = "v1"

    /** 磁盘文件名前缀，便于人工排查缓存目录时一眼认出是缩略图。 */
    const val FILE_PREFIX = "t_"

    /** 磁盘文件扩展名。 */
    const val FILE_SUFFIX = ".img"

    /**
     * 生成缓存 key。
     *
     * @param purpose 用途标识（缩略图 / 正文图）
     * @param sourceId 所属源 ID；不同源的同 URL 不共享缓存条目
     * @param url 图片地址
     */
    fun key(purpose: ImagePurpose, sourceId: String, url: String): String =
        "$KEY_VERSION|${purpose.token}|${sourceId.trim()}|$url"

    /** 缩略图缓存 key。 */
    fun thumbnailKey(sourceId: String, url: String): String = key(ImagePurpose.THUMBNAIL, sourceId, url)

    /** 正文图缓存 key。 */
    fun contentKey(sourceId: String, url: String): String = key(ImagePurpose.CONTENT, sourceId, url)

    /**
     * 由缓存 key 推导磁盘文件名。
     *
     * 用 key 的 SHA-1 而不是直接用 URL 当文件名：URL 里可能有 `/`、`?`、`:`，
     * 长度也不受控，直接当文件名在各平台上都可能出问题。
     */
    fun fileName(key: String): String = "$FILE_PREFIX${sha1Hex(key)}$FILE_SUFFIX"

    /** 计算字符串的 SHA-1 十六进制摘要（缓存文件名用，非安全用途）。 */
    fun sha1Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

// ============================================================================
// C. 超时 / 配额策略（按 sourceId 分级）
// ============================================================================

/**
 * 缩略图分级。
 *
 * 档位描述的是「这个源的缩略图有多大、值不值得留」：
 *
 * - [LIGHT] 轻量站点的普通小图（几十 KB）：传输本来就快，超时可以卡得很紧
 *   （早失败早降级），但因为单张便宜，可以多留一些；
 * - [STANDARD] 默认档；
 * - [STRICT] 样张体积大、或源方明确不希望缓存的站：传输本身慢，
 *   超时要给得比小图宽裕（否则永远加载不出来），
 *   但**配额最小且禁止落盘**（大文件落盘很快就把磁盘吃满）。
 *
 * 两条轴是同向的：**图越大 → 超时越宽、缓存配额越小**。
 */
enum class ThumbnailTier {
    LIGHT,
    STANDARD,
    STRICT,
}

/**
 * 某个源在缩略图通道上的策略。
 *
 * @param tier 所属档位
 * @param connectTimeoutMs 连接超时（毫秒）
 * @param readTimeoutMs 读超时（毫秒）
 * @param callTimeoutMs 整个调用（含重定向等）的总超时（毫秒）
 * @param diskCacheMaxEntries 该源在磁盘缓存里允许占用的最大条目数
 * @param diskCacheMaxBytes 该源在磁盘缓存里允许占用的最大字节数
 * @param allowDiskCache 该源是否允许落磁盘
 */
data class ThumbnailPolicy(
    val tier: ThumbnailTier,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val callTimeoutMs: Long,
    val diskCacheMaxEntries: Int,
    val diskCacheMaxBytes: Long,
    val allowDiskCache: Boolean,
)

/**
 * 缩略图策略的档位基线与按源注册表（纯逻辑）。
 *
 * ## 为什么要按源分级
 *
 * 「缩略图」这个词在不同源身上的含义差得很远：
 * 有的源的缩略图是 40KB 的 WebP，有的源直接把整页大图塞进缩略图位
 * （几百 KB ~ 几 MB）。用同一套配额对待它们，结果是：
 * 大图源把磁盘和内存吃光，小图源连一个条目都留不下。
 *
 * 所以这里提供两级控制：
 *
 * 1. **档位基线**（[forTier]）：同一档位的源共享一组超时与配额；
 * 2. **按源注册**（[register] / [registerTier]）：个别源可以单独调，
 *    典型用途是把某个超大图的源直接设成 [ThumbnailTier.STRICT]（禁止落盘）。
 *
 * 超时基线刻意比正文图**短**（正文图连接 10s / 读 30s），
 * 即使是最宽裕的档位也不例外：缩略图失败就该降级成占位图，
 * 而不是把列表滚动卡住。
 */
object ThumbnailPolicies {

    /** 正文图通道的连接超时（与 `NovelReaderApp.newImageLoader()` 保持一致）。 */
    const val CONTENT_CONNECT_TIMEOUT_MS = 10_000L

    /** 正文图通道的读超时（与 `NovelReaderApp.newImageLoader()` 保持一致）。 */
    const val CONTENT_READ_TIMEOUT_MS = 30_000L

    /** 未注册时的默认档位。 */
    val DEFAULT_TIER: ThumbnailTier = ThumbnailTier.STANDARD

    private val registered = ConcurrentHashMap<String, ThumbnailPolicy>()

    /**
     * 某档位的基线策略。
     *
     * 两条轴同向：**图越大 → 超时越宽、缓存配额越小**。
     *
     * 但即便最宽裕的 [ThumbnailTier.STRICT]，超时也严格小于正文图通道
     * （正文图连接 10s / 读 30s）：缩略图的价值在于「快」，
     * 等 30s 才能知道失败，这段时间够用户把列表滑过去好几屏了。
     */
    fun forTier(tier: ThumbnailTier): ThumbnailPolicy = when (tier) {
        ThumbnailTier.LIGHT -> ThumbnailPolicy(
            tier = tier,
            connectTimeoutMs = 3_000L,
            readTimeoutMs = 6_000L,
            callTimeoutMs = 8_000L,
            diskCacheMaxEntries = 400,
            diskCacheMaxBytes = 64L * 1024 * 1024,
            allowDiskCache = true,
        )

        ThumbnailTier.STANDARD -> ThumbnailPolicy(
            tier = tier,
            connectTimeoutMs = 5_000L,
            readTimeoutMs = 10_000L,
            callTimeoutMs = 14_000L,
            diskCacheMaxEntries = 200,
            diskCacheMaxBytes = 32L * 1024 * 1024,
            allowDiskCache = true,
        )

        ThumbnailTier.STRICT -> ThumbnailPolicy(
            tier = tier,
            connectTimeoutMs = 8_000L,
            readTimeoutMs = 18_000L,
            callTimeoutMs = 24_000L,
            diskCacheMaxEntries = 0,
            diskCacheMaxBytes = 0L,
            allowDiskCache = false,
        )
    }

    /** 为某个源注册自定义策略。 */
    fun register(sourceId: String, policy: ThumbnailPolicy) {
        val id = sourceId.trim()
        if (id.isEmpty()) return
        registered[id] = policy
    }

    /** 为某个源注册档位（用该档位的基线策略）。 */
    fun registerTier(sourceId: String, tier: ThumbnailTier) = register(sourceId, forTier(tier))

    /** 注销某个源的定制策略，回到默认档。 */
    fun unregister(sourceId: String) {
        registered.remove(sourceId.trim())
    }

    /** 某个源当前的策略（未注册则用默认档基线）。 */
    fun policyFor(sourceId: String): ThumbnailPolicy =
        registered[sourceId.trim()] ?: forTier(DEFAULT_TIER)

    /** 某个源当前的档位。 */
    fun tierOf(sourceId: String): ThumbnailTier = policyFor(sourceId).tier

    /** 清空注册表（测试用）。 */
    fun clear() = registered.clear()
}

// ============================================================================
// D. 解码采样（纯逻辑）
// ============================================================================

/**
 * 缩略图解码时的采样倍数计算（纯逻辑）。
 *
 * 缩略图要的是「小」，所以按目标边长反推一个 2 的幂次采样倍数，
 * 让解码器直接产出接近目标尺寸的位图，而不是先解出整张大图再缩小
 * （后者在长列表里是内存杀手）。
 */
object ThumbnailSampling {

    /**
     * 计算 `inSampleSize`。
     *
     * @param width 原图宽
     * @param height 原图高
     * @param targetPx 目标边长（像素）
     */
    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var halfW = width / 2
        var halfH = height / 2
        var sample = 1
        while (halfW >= targetPx && halfH >= targetPx) {
            halfW /= 2
            halfH /= 2
            sample *= 2
        }
        return sample
    }
}

// ============================================================================
// E. 取消句柄（纯逻辑）
// ============================================================================

/**
 * 缩略图加载的可取消句柄（纯逻辑）。
 *
 * 语义要求：
 *
 * - **重复取消不抛异常**：UI 侧常见「滑出屏幕时取消」与「切页面时取消」叠加触发，
 *   句柄必须幂等；
 * - **取消后状态正确**：[isCancelled] 为 true，且已注册的取消动作**只执行一次**
 *   （否则会重复 `Call.cancel()`，虽然无害但会掩盖真实的状态问题）；
 * - **取消后再注册动作要立即执行**：存在「取消」与「注册」竞态时
 *   （加载刚起步就被取消），动作不能被丢掉，否则请求会一直挂着。
 */
class ThumbnailCancelHandle {

    private val lock = Any()
    private var cancelled = false
    private val actions = mutableListOf<() -> Unit>()

    /** 是否已被取消。 */
    val isCancelled: Boolean
        get() = synchronized(lock) { cancelled }

    /**
     * 取消加载：执行已注册的取消动作（每个动作只执行一次）。
     *
     * 重复调用是安全的——第二次直接返回，不会重复执行动作。
     */
    fun cancel() {
        val pending = synchronized(lock) {
            if (cancelled) emptyList() else {
                cancelled = true
                actions.toList().also { actions.clear() }
            }
        }
        pending.forEach { runCatching { it() } }
    }

    /**
     * 注册一个「加载被取消时要做的事」（通常是 `Call.cancel()`）。
     *
     * 若句柄已被取消，action 会被**立即执行**（而不是被丢弃）。
     */
    fun onCancel(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (cancelled) true else {
                actions += action
                false
            }
        }
        if (runNow) runCatching { action() }
    }
}

// ============================================================================
// F. 缓存实现（内存 / 磁盘）
// ============================================================================

/**
 * 缩略图内存缓存（纯逻辑，按字节计的 LRU）。
 *
 * ## 缓存的是「原始字节」而不是「解码后的位图」
 *
 * 一张 320×320 的 ARGB 位图解码后约 400KB，而对应的压缩字节往往只有十几 KB。
 * 8MB 的配额下：
 *
 * - 存字节 → 能留几百张，命中后重新解码（几毫秒）；
 * - 存位图 → 只能留二十来张，命中直接用。
 *
 * 缩略图的特点是「多而小」，列表来回滑时同一张图会被反复请求，
 * 所以选**字节**：宁可多留几十张换更高的命中率，也不愿意因为配额小而反复走网络。
 * 解码成本（一次 `BitmapFactory` + `inSampleSize` 采样）远低于一次网络往返。
 *
 * 与正文图的内存缓存完全独立：正文图走的是 Coil 全局 `MemoryCache`（占堆 25%），
 * 这里另起一份小得多的配额，缩略图再多也挤不掉正文页的缓存。
 */
class ThumbnailMemoryCache(private val maxBytes: Long) {

    private val lock = Any()

    // accessOrder = true：按访问顺序维护，淘汰时从头淘汰（真正的 LRU）
    private val entries = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {}

    private var currentBytes = 0L

    /** 当前占用的字节数。 */
    val sizeBytes: Long
        get() = synchronized(lock) { currentBytes }

    /** 读取；命中返回原始字节（不可变副本语义：调用方不应修改）。 */
    fun get(key: String): ByteArray? = synchronized(lock) { entries[key] }

    /** 写入；超出配额时按 LRU 淘汰最旧的条目。 */
    fun put(key: String, bytes: ByteArray) {
        synchronized(lock) {
            entries.remove(key)?.let { currentBytes -= it.size }
            entries[key] = bytes
            currentBytes += bytes.size

            val it = entries.entries.iterator()
            while (currentBytes > maxBytes && it.hasNext()) {
                val eldest = it.next()
                it.remove()
                currentBytes -= eldest.value.size
            }
        }
    }

    /** 清空。 */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            currentBytes = 0L
        }
    }
}

/**
 * 缩略图磁盘缓存（纯逻辑，文件落在独立目录、按 sourceId 分桶）。
 *
 * 目录结构：`<root>/<sourceId 派生目录>/<key 的 SHA-1>.img`。
 *
 * 按源分桶有两个好处：
 *
 * 1. **配额可按源执行**——某个源条目超限时，只清理它自己桶里的老文件，
 *    不会把别的源的缓存一起清掉；
 * 2. **互不干扰**——同 URL 在不同源下是两个文件，不会张冠李戴。
 *
 * 淘汰策略是「先写先淘汰」：写入新条目后若超出该源的条目数 / 字节数上限，
 * 按最后修改时间从旧到新删到满足配额为止。
 */
class ThumbnailDiskCache(private val root: File) {

    /** 缓存根目录。 */
    val directory: File get() = root

    /**
     * 某个源在磁盘上的子目录。
     *
     * 目录名 = 可读化后的 sourceId + 原始 id 的哈希短摘要：
     * 纯可读化会有碰撞风险（`a/b` 与 `a_b` 撞车），加一段哈希即可区分，
     * 同时保留人工排查时「一眼看出是哪个源」的可读性。
     */
    fun sourceDir(sourceId: String): File {
        val raw = sourceId.trim()
        val readable = buildString {
            for (c in raw) {
                append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
            }
        }.ifEmpty { "source" }.take(32)
        return File(root, "${readable}_${ThumbnailCacheKey.sha1Hex(raw).take(8)}")
    }

    /** 读取缓存字节；未命中返回 null。 */
    fun read(sourceId: String, key: String): ByteArray? {
        val file = File(sourceDir(sourceId), ThumbnailCacheKey.fileName(key))
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * 写入缓存字节，并按该源的配额淘汰旧条目。
     *
     * @param policy 该源的策略；[ThumbnailPolicy.allowDiskCache] 为 false 时直接跳过。
     */
    fun write(sourceId: String, key: String, bytes: ByteArray, policy: ThumbnailPolicy) {
        if (!policy.allowDiskCache) return
        if (policy.diskCacheMaxEntries <= 0 || policy.diskCacheMaxBytes <= 0) return

        val dir = sourceDir(sourceId)
        if (!dir.isDirectory && !dir.mkdirs()) return

        val target = File(dir, ThumbnailCacheKey.fileName(key))
        val written = runCatching { target.writeBytes(bytes) }.isSuccess
        if (!written) return

        evict(dir, policy)
    }

    /**
     * 按配额淘汰旧条目：先按最后修改时间从旧到新删除，
     * 直到条目数与字节数都回到配额内。
     */
    private fun evict(dir: File, policy: ThumbnailPolicy) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = 0L
        files.forEach { total += it.length() }

        var index = 0
        while (
            index < files.size &&
            (files.size - index > policy.diskCacheMaxEntries || total > policy.diskCacheMaxBytes)
        ) {
            val victim = files[index]
            total -= victim.length()
            victim.delete()
            index++
        }
    }

    /** 清空某个源的缓存。 */
    fun clearSource(sourceId: String) {
        sourceDir(sourceId).deleteRecursively()
    }

    /** 清空全部缓存。 */
    fun clearAll() {
        root.deleteRecursively()
    }
}

// ============================================================================
// G. 加载结果
// ============================================================================

/** 缩略图加载结果。 */
sealed class ThumbnailOutcome {

    /** 加载成功。 */
    data class Success(
        /** 解码好的位图（已按目标尺寸采样）。 */
        val bitmap: Bitmap,
        /** 是否命中缓存（内存或磁盘）。 */
        val fromCache: Boolean,
    ) : ThumbnailOutcome()

    /** 加载失败：所有候选地址都没能拿到可解码的图片。 */
    data class Failure(
        /** 失败原因（用于日志与诊断，不直接展示给用户）。 */
        val reason: String,
        /** 原始异常，可能为 null（例如「没有可用地址」）。 */
        val cause: Throwable? = null,
    ) : ThumbnailOutcome()
}

// ============================================================================
// H. 缩略图加载通道（IO 层）
// ============================================================================

/**
 * 缩略图加载通道。
 *
 * 与正文图通道的隔离点一览：
 *
 * | 隔离项 | 正文图（Coil 全局） | 本通道 |
 * |---|---|---|
 * | OkHttp 客户端 | 全局那一个 | [clientFor] 每个档位一个独立实例 |
 * | 连接 / 读超时 | 10s / 30s | 3~8s / 6~18s（按档位，全部短于正文图） |
 * | 并发上限 | 64 / 每 host 16 | 16 / 每 host 4 |
 * | 连接池 | 默认（5 空闲 / 5 分钟） | 4 空闲 / 1 分钟 |
 * | 内存缓存 | Coil 全局（堆 25%） | 独立 LRU（[MEMORY_CACHE_MAX_BYTES]） |
 * | 磁盘目录 | `cacheDir/image_cache`（512MB） | `cacheDir/thumbnails`（按源分桶 + 按源配额） |
 * | 缓存 key | URL 本身 | `版本\|用途\|源\|URL`（缩略图与正文图绝不相同） |
 *
 * 用法：
 *
 * ```kotlin
 * val channel = ThumbnailChannel(context.cacheDir)
 * val handle = channel.enqueue(sourceId, candidates) { outcome -> … }
 * // 列表滑走时
 * handle.cancel()
 * ```
 *
 * @param cacheRoot 应用缓存根目录（一般是 `context.cacheDir`）；
 *        缩略图会落在它下面的 `thumbnails/` 子目录里。
 */
class ThumbnailChannel(
    cacheRoot: File,
    /** 策略来源；默认走 [ThumbnailPolicies] 的全局注册表。 */
    private val policyProvider: (String) -> ThumbnailPolicy = { ThumbnailPolicies.policyFor(it) },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 独立内存缓存（与正文图的 Coil 内存缓存完全分开）。 */
    private val memoryCache = ThumbnailMemoryCache(MEMORY_CACHE_MAX_BYTES)

    /** 独立磁盘缓存（独立目录 `cacheDir/thumbnails`）。 */
    private val diskCache = ThumbnailDiskCache(File(cacheRoot, DISK_DIR_NAME))

    /** 每个档位一个独立客户端：超时与连接池按档位配置，互不牵连。 */
    private val clients = ConcurrentHashMap<ThumbnailTier, OkHttpClient>()

    /** 当前通道的内存缓存（便于 UI 侧在内存吃紧时主动清空）。 */
    val memory: ThumbnailMemoryCache get() = memoryCache

    /** 当前通道的磁盘缓存。 */
    val disk: ThumbnailDiskCache get() = diskCache

    /**
     * 异步加载缩略图，返回**可取消**的句柄。
     *
     * 取消后：正在飞行的 HTTP 请求立刻被打断（`Call.cancel()`），
     * 不会继续占用连接池等超时——这正是 D16 想要的「大图可中断」。
     *
     * @param sourceId 所属源 ID（用于缓存隔离与配额）
     * @param candidates 有序候选地址（见 [ThumbnailUrlResolver.resolve]）
     * @param targetPx 目标边长（像素），解码时按它采样
     * @param onResult 回调；取消后不会再回调
     */
    fun enqueue(
        sourceId: String,
        candidates: List<ThumbnailCandidate>,
        targetPx: Int = DEFAULT_TARGET_PX,
        onResult: (ThumbnailOutcome) -> Unit,
    ): ThumbnailCancelHandle {
        val handle = ThumbnailCancelHandle()
        val job = scope.launch {
            val outcome = load(sourceId, candidates, targetPx, handle)
            if (!handle.isCancelled) onResult(outcome)
        }
        handle.onCancel { job.cancel() }
        return handle
    }

    /**
     * 挂起式加载（供已经在协程里的调用方使用）。
     *
     * 协程被取消时同样会打断在飞的 HTTP 请求。
     */
    suspend fun load(
        sourceId: String,
        candidates: List<ThumbnailCandidate>,
        targetPx: Int = DEFAULT_TARGET_PX,
    ): ThumbnailOutcome = load(sourceId, candidates, targetPx, null)

    /** 加载单个地址（便捷入口）。 */
    suspend fun load(sourceId: String, url: String, targetPx: Int = DEFAULT_TARGET_PX): ThumbnailOutcome =
        load(sourceId, listOf(ThumbnailCandidate(url, ThumbnailKind.THUMBNAIL)), targetPx)

    /**
     * 真正的加载流程：内存 → 磁盘 → 网络，逐级回退；
     * 网络失败时按候选顺序换下一个地址。
     */
    private suspend fun load(
        sourceId: String,
        candidates: List<ThumbnailCandidate>,
        targetPx: Int,
        handle: ThumbnailCancelHandle?,
    ): ThumbnailOutcome {
        if (candidates.isEmpty()) {
            return ThumbnailOutcome.Failure("没有可用的缩略图地址")
        }

        val policy = policyProvider(sourceId)

        for (candidate in candidates) {
            if (handle?.isCancelled == true) return ThumbnailOutcome.Failure("已取消")
            coroutineContext.ensureActive()

            val key = ThumbnailCacheKey.thumbnailKey(sourceId, candidate.url)

            // 1) 内存缓存
            memoryCache.get(key)?.let { bytes ->
                decode(bytes, targetPx)?.let { return ThumbnailOutcome.Success(it, fromCache = true) }
            }

            // 2) 磁盘缓存
            val diskBytes = diskCache.read(sourceId, key)
            if (diskBytes != null) {
                memoryCache.put(key, diskBytes)
                decode(diskBytes, targetPx)?.let { return ThumbnailOutcome.Success(it, fromCache = true) }
            }

            // 3) 网络
            val bytes = fetch(candidate.url, policy, handle) ?: continue
            if (policy.allowDiskCache) {
                diskCache.write(sourceId, key, bytes, policy)
            }
            memoryCache.put(key, bytes)
            decode(bytes, targetPx)?.let { return ThumbnailOutcome.Success(it, fromCache = false) }
        }

        return ThumbnailOutcome.Failure("所有候选地址都加载失败")
    }

    /**
     * 发起一次 HTTP 请求并读回字节。
     *
     * 取消的处理是这一段的重点：
     *
     * - 句柄被取消（[ThumbnailCancelHandle.cancel]）→ 立刻 `Call.cancel()`，
     *   socket 读写直接中断，不用等到超时；
     * - 协程被取消 → 同样 `Call.cancel()`（通过 job 的完成回调注册），
     *   否则挂起的线程会一直占着连接池直到超时。
     *
     * 两种取消都只影响当前这一次请求：换下一个候选时会重新建一个 Call。
     */
    private suspend fun fetch(
        url: String,
        policy: ThumbnailPolicy,
        handle: ThumbnailCancelHandle?,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val call = clientFor(policy.tier).newCall(Request.Builder().url(url).get().build())

        // 句柄取消 → 打断请求
        handle?.onCancel { call.cancel() }
        // 协程取消 → 打断请求（否则会一直占着连接池直到超时）
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) null
                else runCatching { response.body?.bytes() }.getOrNull()
            }
        } catch (e: IOException) {
            // 取消 / 网络错误都走这里：返回 null 让上层换下一个候选
            null
        }
    }

    /**
     * 解码字节为位图；解码失败返回 null（交给上层换候选或降级）。
     */
    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ThumbnailSampling.sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    /**
     * 取（或创建）某个档位专用的 OkHttp 客户端。
     *
     * 与正文图客户端的差别：
     *
     * - 超时**比正文图更短**（各档位都短于正文图的 10s / 30s）：
     *   缩略图要快速失败并降级，不能让一张死链图拖住整屏滚动；
     * - 并发与连接池**更小**：缩略图数量多但单张小，不该占着正文的名额；
     * - 复用同一套请求头 / 解密 / 备用域名拦截器：
     *   缩略图与正文图往往来自同一个图床，防盗链 Referer、Cookie 兜底、
     *   整包 AES 解密、图床备用域名这几件事对缩略图同样成立，
     *   没必要写第二套（写第二套的真正风险是两边规则漂移）。
     */
    private fun clientFor(tier: ThumbnailTier): OkHttpClient = clients.getOrPut(tier) {
        val policy = ThumbnailPolicies.forTier(tier)
        OkHttpClient.Builder()
            .connectTimeout(policy.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(policy.readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(policy.callTimeoutMs, TimeUnit.MILLISECONDS)
            .dispatcher(
                Dispatcher().apply {
                    // 缩略图请求多、生命周期短，压低并发避免挤掉正文图
                    maxRequests = 16
                    maxRequestsPerHost = 4
                },
            )
            // 连接池也比正文小：空闲连接少、存活时间短，不留僵尸连接占资源
            .connectionPool(ConnectionPool(4, 1, TimeUnit.MINUTES))
            .addInterceptor(ImageHostFallbackInterceptor())
            .addInterceptor(ImageDecryptInterceptor())
            .addInterceptor(CoverDefaultsInterceptor())
            .build()
    }

    /** 取消所有在飞的加载并释放后台协程。 */
    fun shutdown() {
        scope.cancel()
        clients.values.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
        clients.clear()
    }

    companion object {
        /** 缩略图磁盘缓存目录名（位于 `cacheDir` 之下）。 */
        const val DISK_DIR_NAME = "thumbnails"

        /**
         * 缩略图内存缓存上限：8MB。
         *
         * 正文图那边是 Coil 全局缓存的「堆 25%」（真机上往往上百 MB），
         * 缩略图给它 8MB 已经能存下上千张 8KB 的小图，
         * 且不至于把正文页的缓存挤掉。
         */
        const val MEMORY_CACHE_MAX_BYTES = 8L * 1024 * 1024

        /** 默认目标边长（像素）：列表缩略图按 320px 足够清晰。 */
        const val DEFAULT_TARGET_PX = 320
    }
}
