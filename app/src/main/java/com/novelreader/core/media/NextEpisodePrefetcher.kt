package com.novelreader.core.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import com.novelreader.core.network.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 尾段预缓冲下一集的开头（v1.8.4.3）。
 *
 * 场景：一集快播完时，用户要么等「自动连播」，要么手点下一集 —— 两种情况都要经历
 * 「解析地址 → 连上 → 下第一个分片 → 起播」，弱网下就是 3~10 秒黑屏。
 * 这里在当前集剩最后二十来秒时，**悄悄**把下一集开头的几 MB 拉进磁盘缓存，
 * 等真正切过去时播放器直接从 [VideoMediaCache] 命中，起播只要几十毫秒。
 *
 * 两个关键设计：
 * 1. **只下开头**：预算默认 6MB（约几秒到十几秒的正片），不是整集。整集会吃掉
 *    用户流量，也和「预缓冲」的意图不符。
 * 2. **HLS 要下分片，不是下清单**：采集站大多是 `.m3u8`，清单本身只有几百字节，
 *    缓存它没有任何加速作用。所以先取清单、解析出前几个分片，再逐个写进缓存
 *    （key 用分片 URL，和播放器自己算的一致，才能命中）。
 *
 * 全程静默：任何一步失败都只是「没有预缓冲」，绝不影响当前播放。
 */
@OptIn(UnstableApi::class)
object NextEpisodePrefetcher {

    /** 默认预算：6MB（按 2Mbps 算约 24 秒正片，足够撑到网络跟上） */
    const val DEFAULT_BUDGET_BYTES = 6L * 1024 * 1024

    /** HLS 最多下几个分片（分片通常 4~10s 一个，8 个已远超预算意义） */
    private const val MAX_HLS_SEGMENTS = 8

    /** 取播放清单的超时（清单很小，慢就没必要等） */
    private const val PLAYLIST_TIMEOUT_MS = 8_000L

    /** 读缓存的临时缓冲区（128KB，和 media3 内部默认值一致） */
    private const val READ_BUFFER_BYTES = 128 * 1024

    private const val TAG = "NextPrefetch"

    /**
     * 预缓冲 [url] 的开头到磁盘缓存。
     *
     * @return 实际写进缓存的字节数（0 = 没下成，调用方不用管）
     */
    suspend fun prefetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    ): Long = withContext(Dispatchers.IO) {
        if (url.isBlank() || budgetBytes <= 0L) return@withContext 0L
        val source = VideoMediaCache.cacheDataSource(headers) ?: return@withContext 0L
        val written = try {
            if (looksLikeHls(url)) prefetchHls(url, headers, budgetBytes, source)
            else prefetchRange(url, headers, budgetBytes, source)
        } catch (e: CancellationException) {
            // 换集 / 退出播放页：把任务停掉，别和「正在播的这一集」抢带宽
            android.util.Log.d(TAG, "预缓冲已取消（已写 $url）")
            throw e
        } catch (e: Exception) {
            android.util.Log.d(TAG, "预缓冲失败：${e.javaClass.simpleName} ${e.message} @ $url")
            0L
        }
        // 一集只打一行：出问题时能直接从日志判断「预缓冲到底有没有跑、下了多少」，
        // 否则这个功能失败是完全静默的，用户只会说「下一集还是要转圈」
        android.util.Log.d(TAG, "预缓冲下一集：${if (looksLikeHls(url)) "hls" else "range"} $written 字节 @ $url")
        written
    }

    /**
     * 扩展名判 HLS（去掉查询串再比，忽略大小写）。
     *
     * 采集站偶尔给不带后缀的地址（如 `/vod/play?id=1`），那种按直链字节处理即可 ——
     * 猜错只是预缓冲效果差一点，不会影响播放。
     */
    fun looksLikeHls(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /**
     * 直链（mp4 等）：从 0 开始下 [budget] 字节。
     *
     * 用 `DataSpec` 的 position/length 限定范围，`CacheWriter` 只写这一段。
     */
    private suspend fun prefetchRange(
        url: String,
        headers: Map<String, String>,
        budget: Long,
        source: CacheDataSource,
    ): Long = write(source, url, headers, budget)

    /**
     * HLS：清单 → 变体清单（如果是主清单）→ 初始化分片 → 前 N 个分片。
     */
    private suspend fun prefetchHls(
        url: String,
        headers: Map<String, String>,
        budget: Long,
        source: CacheDataSource,
    ): Long {
        val playlist = fetchText(url, headers) ?: run {
            android.util.Log.d(TAG, "取清单失败 @ $url")
            return 0L
        }
        android.util.Log.d(TAG, "清单 ${playlist.length} 字 / master=${HlsPlaylist.isMaster(playlist)} / 列表=${HlsPlaylist.isPlaylist(playlist)}")
        if (!HlsPlaylist.isPlaylist(playlist)) return 0L

        var text = playlist
        var base = url
        if (HlsPlaylist.isMaster(text)) {
            // 多码率主清单：取第一个变体（播放器默认也是挑带宽合适的那条，
            // 预缓冲不必纠结 —— 下错了顶多是白花几 MB，不影响正确性）
            val variant = HlsPlaylist.uris(text, base, 1).firstOrNull() ?: return 0L
            text = fetchText(variant, headers) ?: return 0L
            base = variant
        }
        android.util.Log.d(TAG, "分片 ${HlsPlaylist.uris(text, base, MAX_HLS_SEGMENTS).size} 个，base=$base")

        var written = 0L
        HlsPlaylist.initSegment(text, base)?.let { init ->
            written += write(source, init, headers, budget)
        }
        for (segment in HlsPlaylist.uris(text, base, MAX_HLS_SEGMENTS)) {
            if (written >= budget) break
            written += write(source, segment, headers, budget - written)
        }
        return written
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? =
        runCatching { HttpClient.get(url, headers, timeoutMs = PLAYLIST_TIMEOUT_MS) }.getOrNull()

    /**
     * 把一个地址的 [length] 字节读一遍并落进缓存，返回实际读到的字节数。
     *
     * 为什么不用 `CacheWriter`：它内部对「缓存里已有这段」的判断非常黑盒 ——
     * 实测它对从没下过的分片也会直接返回、不报错、不读网络（0 字节），
     * 完全查不出原因。这里改成自己 `open` + `read` 循环：
     * `CacheDataSource` 读的时候会把字节 tee 进 `CacheDataSink`，
     * 效果与 `CacheWriter` 相同，但每一步的返回长度都能打日志。
     *
     * **可取消**（v1.8.4.4）：`source.read()` 是阻塞调用，协程 `cancel()` 打不断它，
     * 于是切集后旧任务还会继续把 6MB 拉完 —— 正好和「让新一集起播更快」的目标相反。
     * 所以每读一块就查一次协程状态，取消时立刻收手（最坏多读 128KB）。
     */
    private suspend fun write(
        source: CacheDataSource,
        uri: String,
        headers: Map<String, String>,
        length: Long,
    ): Long {
        if (length <= 0L) return 0L
        val spec = DataSpec.Builder()
            .setUri(uri)
            // key 必须和播放器读同一个分片时算出来的一致（默认就是 URL 字符串），
            // 显式写出来是为了防止以后 media3 改默认值
            .setKey(uri)
            .setPosition(0L)
            .setLength(length)
            .setHttpRequestHeaders(headers)
            .build()
        var total = 0L
        try {
            val resolved = source.open(spec)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (total < length) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer, 0, buffer.size)
                if (read == -1) break
                total += read
            }
            android.util.Log.d(TAG, "读入 $total/$resolved 字节 @ $uri")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 预缓冲失败不影响播放，但要留痕：否则「下一集还是要转圈」时无从下手
            android.util.Log.d(TAG, "读入失败 ${e.javaClass.simpleName}: ${e.message} @ $uri")
        } finally {
            runCatching { source.close() }
        }
        return total
    }
}
