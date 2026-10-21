package com.novelreader.core.media

import com.novelreader.core.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * 把 HLS（m3u8）整集取到本地，供离线识别用（v1.8.7.4）。
 *
 * **为什么需要它**：`MediaExtractor` 只认单一容器的音轨，m3u8 只是一张分片清单，
 * 直接喂过去必然 `UnrecognizedInputFormatException`。而采集站的视频绝大多数是 HLS ——
 * 这正是「好多视频用不了 AI 字幕」的根因。
 *
 * 做法：清单 → （主清单则挑**码率最低**的变体）→ 逐片下载并按顺序拼接成单个本地文件 →
 * 交给 [com.novelreader.core.asr.AudioPcmExtractor] 正常解码。
 *
 * 挑最低码率是因为字幕只要音轨：同一集 1080p 变体可能几百 MB，360p 只有几十 MB，
 * 而音轨对识别结果没有任何区别。
 *
 * 支持 `AES-128`（HLS 最常见的防盗链方式）；`SAMPLE-AES` 这类只加密部分字节的方案不支持，
 * 遇到直接失败 —— 解错了会得到一堆噪声，识别出来的字幕比没有更糟。
 */
object HlsDownloader {

    /** 单集体积上限，防止清单异常（比如把直播源当 VOD）时把磁盘写满 */
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** 清单本身很小，超时给短一点，别让死源把生成字幕卡住 */
    private const val PLAYLIST_TIMEOUT_MS = 15_000L

    private const val SEGMENT_TIMEOUT_MS = 30_000L

    /** 下载结果 */
    data class Result(
        val file: File,
        val bytes: Long,
        val durationMs: Long,
        /** 用的哪个变体（低码率时给用户看） */
        val variantUrl: String,
    )

    /**
     * 下载整集到 [target]（调用方负责清理）。
     *
     * @param onProgress `(0f~1f, 文案)`
     * @return 成功返回文件；取清单失败、加密方式不支持、被取消时返回 null
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result? = withContext(Dispatchers.IO) {
        val playlistText = fetchText(url, headers) ?: return@withContext null
        if (!HlsPlaylist.isPlaylist(playlistText)) return@withContext null

        // 主清单：先挑变体，再取它的媒体清单
        var mediaText = playlistText
        var base = url
        if (HlsPlaylist.isMaster(playlistText)) {
            val variant = HlsPlaylist.lowestBandwidthVariant(playlistText, base)
                ?: return@withContext null
            onProgress(0f, "已选最低码率线路，正在取分片清单…")
            mediaText = fetchText(variant, headers) ?: return@withContext null
            if (!HlsPlaylist.isPlaylist(mediaText)) return@withContext null
            base = variant
        }

        val segments = HlsPlaylist.uris(mediaText, base)
        if (segments.isEmpty()) return@withContext null
        val initSegment = HlsPlaylist.initSegment(mediaText, base)
        val totalMs = HlsPlaylist.totalDurationMs(mediaText)

        val key = HlsPlaylist.keyInfo(mediaText, base)
        val keyBytes = when {
            key == null || key.method == "NONE" -> null
            key.method == "AES-128" -> {
                val keyUrl = key.uri ?: return@withContext null
                fetchBytes(keyUrl, headers) ?: return@withContext null
            }
            // SAMPLE-AES / SAMPLE-AES-CTR 等：只加密部分字节，解不对就是噪声
            else -> return@withContext null
        }
        val sequence = HlsPlaylist.mediaSequence(mediaText)

        target.parentFile?.mkdirs()
        var written = 0L
        val total = segments.size + if (initSegment != null) 1 else 0
        var done = 0

        target.outputStream().buffered().use { out ->
            if (initSegment != null) {
                val init = fetchBytes(initSegment, headers) ?: return@withContext null
                out.write(init)
                written += init.size
                done++
            }
            for ((index, segmentUrl) in segments.withIndex()) {
                coroutineContext.ensureActive()

                var bytes = fetchBytes(segmentUrl, headers) ?: return@withContext null
                if (keyBytes != null) {
                    bytes = decrypt(bytes, keyBytes, key?.ivHex, sequence + index)
                        ?: return@withContext null
                }
                out.write(bytes)
                written += bytes.size
                if (written > MAX_BYTES) return@withContext null

                done++
                val fraction = done.toFloat() / total
                onProgress(fraction, "正在下载分片 $done / $total（${written / 1024 / 1024} MB）")
            }
        }

        if (written == 0L) return@withContext null
        onProgress(1f, "下载完成（${written / 1024 / 1024} MB），开始解码音轨…")
        Result(target, written, totalMs, base)
    }

    /** 是否 HLS 地址（扩展名粗判；调用方仍应以实际响应内容为准） */
    fun looksLikeHls(url: String): Boolean = HlsPlaylist.looksLikeHls(url)

    private fun fetchText(url: String, headers: Map<String, String>): String? =
        runCatching {
            HttpClient.get(url, headers, timeoutMs = PLAYLIST_TIMEOUT_MS)
        }.getOrNull()

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? = runCatching {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        HttpClient.executeRaw(builder.build(), SEGMENT_TIMEOUT_MS).use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.body?.bytes()
        }
    }.getOrNull()

    /**
     * AES-128-CBC 解密。
     *
     * IV 的取法按 HLS 规范：标签里写了 `IV=0x…` 就用它，否则用**媒体序号**
     * 转成 16 字节大端。用错 IV 不会报错，只会解出乱码 —— 所以这里任何一步不对就返回 null。
     */
    private fun decrypt(
        data: ByteArray,
        key: ByteArray,
        ivHex: String?,
        sequence: Long,
    ): ByteArray? = runCatching {
        if (key.size != 16) return@runCatching null
        val iv = if (!ivHex.isNullOrBlank()) {
            val padded = ivHex.padStart(32, '0')
            ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } else {
            // 媒体序号 → 16 字节大端
            ByteArray(16).also { arr ->
                var value = sequence
                for (i in 15 downTo 8) {
                    arr[i] = (value and 0xFF).toByte()
                    value = value shr 8
                }
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(data)
    }.getOrNull()
}
