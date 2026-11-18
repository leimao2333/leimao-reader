package com.novelreader.core.image

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片下载进度（D16）。
 *
 * Coil 2.x 的 [coil.compose.AsyncImagePainter.State] 只有 Empty/Loading/Success/Error，
 * `listener` 也只有三个时间点，拿不到字节量 —— 想要「这一张下到 40%」只能在 OkHttp 层
 * 包一层 [ResponseBody] 统计 `source()` 的读取量。
 *
 * 设计取舍：
 * - 只对 `content-type` 为 image 类型的响应生效，JSON / 视频流不占用表项；
 * - 每个 URL 一个 [MutableStateFlow]，读取方（Compose）`collectAsState` 即可；
 * - 表项有上限（[MAX_ENTRIES]），超出后按插入顺序淘汰，避免边刷边泄漏；
 * - 命中磁盘 / 内存缓存时拦截器根本不会跑，此时 flow 为 null（UI 显示普通转圈）。
 */
object ImageDownloadProgress {

    private const val MAX_ENTRIES = 64

    /** url -> 0f..1f；null 表示总长度未知（gzip / chunked）或未开始 */
    private val states = ConcurrentHashMap<String, MutableStateFlow<Float?>>()

    /** 取某张图的进度流（不存在则创建） */
    fun flow(url: String): StateFlow<Float?> = mutableFlow(url)

    private fun mutableFlow(url: String): MutableStateFlow<Float?> {
        states[url]?.let { return it }
        if (states.size >= MAX_ENTRIES) {
            // 简单的插入序淘汰：拿不到 LRU 顺序，但够用（同时下载的图不会超过这个量级）
            states.keys.firstOrNull()?.let { states.remove(it) }
        }
        return states.getOrPut(url) { MutableStateFlow(null) }
    }

    internal fun start(url: String, total: Long) {
        mutableFlow(url).value = if (total > 0) 0f else null
    }

    internal fun update(url: String, read: Long, total: Long) {
        if (total <= 0) return
        mutableFlow(url).value = (read.toFloat() / total).coerceIn(0f, 1f)
    }

    /** 加载成功 / 失败后调用，避免残留一个停在 99% 的旧进度 */
    fun clear(url: String) {
        states[url]?.value = null
    }
}

/** 统计读取字节数的响应体包装（只透传，不改内容） */
internal class ProgressResponseBody(
    private val delegate: ResponseBody,
    private val onRead: (read: Long) -> Unit,
) : ResponseBody() {

    private val source = object : ForwardingSource(delegate.source()) {
        private var read = 0L

        override fun read(sink: Buffer, byteCount: Long): Long =
            super.read(sink, byteCount).also { n ->
                if (n > 0) {
                    read += n
                    onRead(read)
                }
            }
    }.buffer()

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun source() = source
}

/**
 * 图片下载进度拦截器。
 *
 * 挂在 Coil 专属的 OkHttpClient 上（见 [com.novelreader.NovelReaderApp.newImageLoader]），
 * 并且**放在解密拦截器之后**（更靠近网络）：解密拦截器会一次性 `bytes()` 读完，
 * 读取动作正好驱动这里的 source 计数。
 */
class ImageDownloadProgressInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response

        val body = response.body ?: return response
        if (body.contentType()?.type != "image") return response

        val url = chain.request().url.toString()
        val total = body.contentLength()
        ImageDownloadProgress.start(url, total)

        val wrapped = ProgressResponseBody(body) { read ->
            ImageDownloadProgress.update(url, read, total)
        }
        return response.newBuilder().body(wrapped).build()
    }
}
