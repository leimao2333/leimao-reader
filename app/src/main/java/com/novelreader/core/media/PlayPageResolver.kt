package com.novelreader.core.media

import android.util.Log
import com.novelreader.source.moontv.MacCmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 播放地址嗅探 / 播放页二次解析（v1.9.5.1）。
 *
 * 采集站给的播放地址不一定是真流：
 * - 有的是一张播放页（HTML + hls.js，真实 m3u8 写在内联脚本里）；
 * - 有的地址带 `.m3u8` 后缀，实际却返回 HTML；
 * - 旧版本落盘的「地址预取缓存」也可能存着上面这类坏地址，升级后仍会被直接命中。
 *
 * 播放器拿到网页只会报 `Input does not start with the #EXTM3U header`
 * / `UnrecognizedInputFormatException`，而这类源往往只有一条线路，自动换源无从可换。
 * 只看 URL 后缀判断不可靠（伪 m3u8 就是反例），所以这里在**播放已经失败**时
 * 直接向该地址发一次带 Range 的轻量请求，只读前 [MAX_SNIFF_BYTES] 字节：
 * - 是网页 → 从内联脚本里提取真实流地址（复用 [MacCmsParser.extractPlayUrl]）；
 * - 是正常的 m3u8 → 网络侧没问题，故障在播放器侧的磁盘缓存，清掉重播即可；
 * - 其它 / 请求失败 → 不干预，交回原有的换源与报错流程。
 *
 * 请求复用 [VideoTls.client()]：播放 CDN 证书过期时照样能取到页面。
 * 正常播放完全不经过这里，不会给能播的源增加一次请求。
 */
object PlayPageResolver {

    private const val TAG = "NRPlayUrl"
    private const val MAX_SNIFF_BYTES = 256 * 1024
    private const val PROBE_TIMEOUT_MS = 8_000L
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 探测结论 */
    sealed class Result {
        /** 网络返回的就是有效 m3u8，问题在播放器侧缓存 */
        object ValidMedia : Result()

        /** 返回的是网页；realUrl 为提取出的真实流，null = 没提取到 */
        data class PlayPage(val realUrl: String?) : Result()

        /** 请求失败 / 内容既不像流也不像网页，不干预 */
        object Unknown : Result()
    }

    /**
     * 域名停放 / 转卖占位页的特征词（中英）。
     *
     * 这类页面看起来是 HTML、里面还挂着统计跳转脚本，把原地址又编码进 `requrl=` 里，
     * 提取逻辑很容易「解析」出一个和原来一模一样的死地址 —— 白再播一次、多等两秒。
     * 命中这些特征就直接判定为死链，不再尝试提取。
     */
    private val PARKED_PAGE_MARKERS = listOf(
        "domain may be for sale",
        "domain is for sale",
        "buy this domain",
        "this domain is parked",
        "domain for sale",
        "此域名可出售",
        "域名出售",
        "该域名正在出售",
    )

    /**
     * 响应体是否为域名停放 / 转卖占位页（internal：单测直接喂页面样本）。
     */
    internal fun isParkedPage(body: String): Boolean =
        PARKED_PAGE_MARKERS.any { body.contains(it, ignoreCase = true) }

    /** 同一地址解析出的真实流，进程内只请求一次 */
    private val resolved = ConcurrentHashMap<String, String>()

    suspend fun probe(url: String, headers: Map<String, String>): Result = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext Result.Unknown
        resolved[url]?.let { return@withContext Result.PlayPage(it) }

        val body = runCatching { sniff(url, headers) }.getOrElse {
            Log.d(TAG, "sniff failed $url / ${it.javaClass.simpleName}: ${it.message}")
            return@withContext Result.Unknown
        }
        if (body == null) return@withContext Result.Unknown

        val text = body.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        Log.d(TAG, "sniff $url -> ${text.take(80).replace("\n", " ")}")
        when {
            text.startsWith("#EXTM3U") -> Result.ValidMedia
            // 域名停放页：不是播放页，也没有流可提，直接判死链
            isParkedPage(text) -> {
                Log.d(TAG, "parked page $url")
                Result.PlayPage(null)
            }
            text.startsWith("<") || text.contains("<script", ignoreCase = true) ||
                text.contains("<html", ignoreCase = true) -> {
                val found = MacCmsParser.extractPlayUrl(body, url)
                Log.d(TAG, "play page $url -> real=$found")
                if (found != null && found != url) resolved[url] = found
                Result.PlayPage(found)
            }
            else -> Result.Unknown
        }
    }

    /**
     * 只取响应开头一小段：用 Range 限制服务端返回量，本地也只读到 [MAX_SNIFF_BYTES]
     * 就关连接。播放地址偶尔是几百 MB 的 mp4，不能整段读进内存。
     */
    private fun sniff(url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Range", "bytes=0-${MAX_SNIFF_BYTES - 1}")
        headers.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true) && !k.equals("Host", ignoreCase = true)) {
                builder.header(k, v)
            }
        }
        val client = VideoTls.client().newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(builder.build()).execute().use { resp ->
            // 206 = 接受了 Range；200 = 服务端忽略 Range，本地读取量依然有上限
            if (!resp.isSuccessful && resp.code != 206) {
                Log.d(TAG, "sniff http ${resp.code} $url")
                return null
            }
            val stream = resp.body?.byteStream() ?: return null
            val buf = ByteArray(MAX_SNIFF_BYTES)
            var total = 0
            while (total < buf.size) {
                val n = stream.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            return String(buf, 0, total, Charsets.UTF_8)
        }
    }
}
