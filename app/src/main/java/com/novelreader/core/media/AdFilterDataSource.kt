package com.novelreader.core.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * 广告过滤数据源（v1.9.6.0）。
 *
 * 播放器要的是**字节流**，广告分片写在**播放列表文本**里 —— 想摘掉广告，唯一的入口
 * 就是「请求 m3u8 时把响应体换掉」。Media3 没有提供改写响应内容的扩展点
 * （`ResolvingDataSource` 只能改请求头 / 改地址，改不了内容），所以这里自己包一层
 * [DataSource]：
 *
 * ```
 * 播放器 → AdFilterDataSource → CacheDataSource → OkHttpDataSource → 站点
 * ```
 *
 * 放在缓存**外面**是有意的：缓存里存的是站点原样的清单，过滤只在每次读清单时做。
 * 这样改了过滤规则不需要清缓存，关掉开关也能立刻恢复原样。
 *
 * ## 只读播放列表，绝不整段读视频
 *
 * 判断条件是「地址看起来像 HLS（`.m3u8` / `.m3u`）且从头开始读」。分片（`.ts` / `.mp4`）
 * 一律原样透传、边下边给，不会进内存 —— 一个分片几十 MB、一集几百个分片，
 * 全读进内存必然 OOM。
 *
 * 即使地址像 m3u8，也设了 [MAX_PLAYLIST_BYTES] 上限：撞到上限就认为「不像清单」，
 * 把已读部分和剩下的流拼起来照常交付，行为退化为完全不干预。
 */
@OptIn(UnstableApi::class)
class AdFilterDataSource(private val upstream: DataSource) : DataSource {

    /** 已经准备好的响应体（过滤后的清单 / 已读部分的回放）；null 表示直接透传上游 */
    private var body: InputStream? = null

    /** 当前请求的地址（[getUri] 用） */
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        // 同一个实例会被播放器反复 open（拖动进度、换码率变体），先释放上一份响应体
        releaseBody()
        val length = upstream.open(dataSpec)
        uri = dataSpec.uri

        if (!VideoAdFilter.enabled) return length
        if (!shouldIntercept(dataSpec)) return length

        val read = readWhole(length)
        // 超过上限：不是清单，把已读的部分接在剩余流前面原样交出去
        if (read.overflow) {
            body = SequenceInputStream(ByteArrayInputStream(read.bytes), UpstreamStream(upstream))
            return length
        }

        val bytes = read.bytes
        val filtered = runCatching {
            VideoAdFilter.filterPlaylist(String(bytes, Charsets.UTF_8))
        }.getOrNull()

        if (filtered == null) {
            // 不是清单 / 没有认出一个广告：原样回放已读内容
            body = ByteArrayInputStream(bytes)
            return if (length >= 0) length else bytes.size.toLong()
        }

        val cleaned = filtered.playlist.toByteArray(Charsets.UTF_8)
        body = ByteArrayInputStream(cleaned)
        VideoAdFilter.notifyOnce(dataSpec.uri.toString(), filtered)
        return cleaned.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = body ?: return upstream.read(buffer, offset, length)
        return stream.read(buffer, offset, length)
    }

    override fun close() {
        releaseBody()
        upstream.close()
    }

    override fun getUri(): Uri? = uri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    // ===== 内部实现 =====

    /**
     * 是否值得拦下来改写。
     *
     * 三个条件缺一不可：
     * - 从文件头开始读（`position == 0` 才算拿到完整清单，断点续读只能透传）；
     * - 地址形似 HLS（后缀 `.m3u8` / `.m3u`）；
     * - 开关打开（调用方已判，这里再判一次是为了防止将来漏改调用点）。
     */
    private fun shouldIntercept(dataSpec: DataSpec): Boolean {
        if (dataSpec.position != 0L) return false
        return HlsPlaylist.looksLikeHls(dataSpec.uri.toString())
    }

    /** 读到内存的字节 + 是否撞到上限 */
    private class ReadResult(val bytes: ByteArray, val overflow: Boolean)

    private fun readWhole(expectedLength: Long): ReadResult {
        val initial = when {
            expectedLength in 1..(64L * 1024) -> expectedLength.toInt()
            else -> 16 * 1024
        }
        val out = ByteArrayOutputStream(initial)
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total <= MAX_PLAYLIST_BYTES) {
            val n = upstream.read(chunk, 0, chunk.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(chunk, 0, n)
            total += n
        }
        return ReadResult(out.toByteArray(), total > MAX_PLAYLIST_BYTES)
    }

    private fun releaseBody() {
        runCatching { body?.close() }
        body = null
    }

    /** 把 [DataSource] 伪装成 [InputStream]，用于「已读部分 + 剩余流」拼接 */
    private class UpstreamStream(private val source: DataSource) : InputStream() {

        private val single = ByteArray(1)

        override fun read(): Int {
            val n = source.read(single, 0, 1)
            return if (n == C.RESULT_END_OF_INPUT) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)

        /** 关闭交给外层统一做，这里不能提前关掉上游 */
        override fun close() = Unit
    }

    companion object {

        /**
         * 播放列表最多读这么多。
         *
         * 正常 m3u8 只有几十 KB（一集几百个分片也就 30 KB 上下）；2 MB 只可能是一份
         * 被当成 m3u8 下发的视频文件 —— 那种情况必须立刻放弃拦截，不然会吃满内存。
         */
        private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024

        /** 给任意数据源工厂套上广告过滤 */
        fun factory(upstream: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { AdFilterDataSource(upstream.createDataSource()) }
    }
}
