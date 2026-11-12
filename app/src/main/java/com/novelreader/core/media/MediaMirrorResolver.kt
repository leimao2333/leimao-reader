package com.novelreader.core.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 同源 CDN 镜像探测（v1.9.5.5）。
 *
 * 采集站的播放域名到期 / 被停放后，`vod_play_url` 里的那条地址就成了死链，
 * 但**同一路径往往还挂在同源的其它 CDN 上**。这里拿播放失败的原地址，
 * 只把域名换成候选域名，保留路径与查询串，并发探测一小段响应：
 * 真正以 `#EXTM3U` 开头的才算命中。
 *
 * 只用 [MediaHostPool] 里出现过的域名：跨站乱猜既慢又会被运营商劫持页骗过。
 */
object MediaMirrorResolver {

    private const val TAG = "NRPlayUrl"

    /** 一次最多并发试几个域名（再多意义不大，池子里通常只有 3~8 个） */
    private const val MAX_CANDIDATES = 6

    private const val PROBE_TIMEOUT_MS = 6_000L

    /** 只读这么多字节就够判断是不是 m3u8 */
    private const val MAX_PROBE_BYTES = 1_024

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 在候选域名里找出同一路径仍可用的一条。
     *
     * @return 命中则返回替换域名后的完整地址，全部落空返回 null
     */
    suspend fun findMirror(
        url: String,
        candidateHosts: List<String>,
        headers: Map<String, String>,
    ): String? = withContext(Dispatchers.IO) {
        val currentHost = MediaHostPool.hostOf(url) ?: return@withContext null
        val targets = candidateHosts.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != currentHost }
            .distinct()
            .take(MAX_CANDIDATES)
            .toList()
        if (targets.isEmpty()) return@withContext null

        val candidates = targets.mapNotNull { host -> MediaHostPool.replaceHost(url, host) }
        val hit = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) { if (isStream(candidate, headers)) candidate else null }
            }.awaitAll().firstOrNull { it != null }
        }
        Log.d(TAG, "mirror $url -> $hit (tried=${targets.joinToString(",")})")
        hit
    }

    /** 该地址返回的是不是正常的 m3u8（只读开头一小段） */
    private fun isStream(url: String, headers: Map<String, String>): Boolean {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Range", "bytes=0-${MAX_PROBE_BYTES - 1}")
        headers.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true) && !k.equals("Host", ignoreCase = true)) {
                builder.header(k, v)
            }
        }
        val client = VideoTls.client().newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return false
                val stream = resp.body?.byteStream() ?: return false
                val buf = ByteArray(MAX_PROBE_BYTES)
                var total = 0
                while (total < buf.size) {
                    val n = stream.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                val text = String(buf, 0, total, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                text.startsWith("#EXTM3U")
            }
        }.getOrElse {
            Log.d(TAG, "mirror probe failed $url / ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /**
     * 这条线路是不是「域名死了」类失败（停放页 / 非媒体内容）。
     * 供 ViewModel 决定要不要先试镜像再嗅探。
     */
    fun looksLikeDeadHostFailure(errorText: String): Boolean =
        errorText.contains("EXTM3U", ignoreCase = true) ||
            errorText.contains("ParserException", ignoreCase = true) ||
            errorText.contains("UnrecognizedInputFormat", ignoreCase = true) ||
            errorText.contains("Unable to connect", ignoreCase = true) ||
            errorText.contains("Connection reset", ignoreCase = true) ||
            errorText.contains("UnknownHost", ignoreCase = true)
}
