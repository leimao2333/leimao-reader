package com.novelreader.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一网络客户端。
 *
 * 供源解析引擎使用，支持：
 * - 自定义 User-Agent（模拟浏览器，规避反爬）
 * - 字符集自动识别（Content-Type / HTML meta）
 * - Cookie 持久化（内存实现，会话内有效）
 * - 请求重试（网络错误自动重试）
 * - gzip 自动解压（OkHttp 默认支持）
 */
object HttpClient {

    private const val MAX_RETRY = 3

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(MemoryCookieJar())
            .addInterceptor(RetryInterceptor(MAX_RETRY))
            .build()
    }

    /** 默认 UA（模拟主流浏览器） */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * GET 请求，返回字符串。
     *
     * @param url 目标地址
     * @param headers 额外请求头（如 Referer、Cookie）
     * @param charset 指定字符集，null 则自动检测
     */
    fun get(url: String, headers: Map<String, String> = emptyMap(), charset: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        // 注意：不要手动设置 Accept-Encoding，让 OkHttp 透明处理 gzip 解压
        headers.forEach { (k, v) -> builder.header(k, v) }

        return execute(builder.build(), charset)
    }

    /**
     * POST 请求，返回字符串。
     *
     * @param url 目标地址
     * @param body 请求体（通常为表单或 JSON 文本）
     * @param headers 额外请求头
     * @param charset 请求体与响应的字符集（如 GB2312），null 则自动检测响应
     * @param contentType 请求体 MIME 类型
     */
    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        charset: String? = null,
        contentType: String = "application/x-www-form-urlencoded",
    ): String {
        val reqCharset = charset?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
        val requestBody = body.toByteArray(reqCharset).toRequestBody(contentType.toMediaTypeOrNull())

        val builder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        headers.forEach { (k, v) -> builder.header(k, v) }

        return execute(builder.build(), charset)
    }

    /** 统一执行请求并按字符集解码响应体 */
    private fun execute(request: Request, charset: String?): String {
        client.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) {
                throw NetworkException("请求失败: ${response.code} ${request.url}")
            }
            val body = response.body ?: throw NetworkException("空响应: ${request.url}")
            val bytes = body.bytes()

            // 优先从 Content-Type 获取字符集
            val contentTypeCharset = response.header("Content-Type")
                ?.let { parseCharsetFromContentType(it) }
            return decode(bytes, charset ?: contentTypeCharset)
        }
    }

    /**
     * 下载文件到本地，带进度回调。
     *
     * @param url 下载地址
     * @param target 目标文件
     * @param headers 额外请求头
     * @param byteTransform 整包字节处理（如加密图床的解密）；非空时改为「先读全量 → 转换 → 一次写盘」
     * @param onProgress 进度回调（已下载字节数, 总字节数，未知时为 -1），运行在下载线程
     */
    fun downloadFile(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        byteTransform: ((ByteArray) -> ByteArray)? = null,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }

        val request = builder.build()
        client.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) {
                throw NetworkException("下载失败: ${response.code} $url")
            }
            val body: ResponseBody = response.body ?: throw NetworkException("空响应: $url")
            val contentLength = body.contentLength()
            val total = if (contentLength > 0) contentLength else -1L

            target.parentFile?.mkdirs()

            if (byteTransform != null) {
                val raw = body.bytes()
                onProgress?.invoke(raw.size.toLong(), total)
                val fixed = byteTransform(raw)
                target.writeBytes(fixed)
                onProgress?.invoke(fixed.size.toLong(), fixed.size.toLong())
                return
            }

            val input = body.byteStream()
            val output = FileOutputStream(target)
            var downloaded = 0L
            val buffer = ByteArray(8 * 1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress?.invoke(downloaded, total)
                }
                output.flush()
            } finally {
                output.close()
                input.close()
            }
        }
    }

    /**
     * 字节解码。自动检测字符集。
     * 优先级：指定 charset > Content-Type > HTML meta > 默认 UTF-8 > 回退 GBK
     */
    private fun decode(bytes: ByteArray, charset: String?): String {
        if (charset != null) {
            return runCatching { String(bytes, java.nio.charset.Charset.forName(charset)) }
                .getOrDefault(String(bytes, Charsets.UTF_8))
        }
        // 先用 UTF-8 尝试，检测是否有乱码（含替换字符）
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) {
            return utf8
        }
        // 尝试从 HTML meta 中检测字符集
        val metaCharset = detectCharsetFromHtml(utf8)
        if (metaCharset != null && metaCharset.lowercase() != "utf-8") {
            return runCatching { String(bytes, java.nio.charset.Charset.forName(metaCharset)) }
                .getOrDefault(utf8)
        }
        // 回退 GBK（中文站点常见）
        return runCatching { String(bytes, java.nio.charset.Charset.forName("GBK")) }
            .getOrDefault(utf8)
    }

    /** 从 Content-Type 解析字符集 */
    private fun parseCharsetFromContentType(contentType: String): String? {
        val regex = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
        return regex.find(contentType)?.groupValues?.get(1)
    }

    /** 从 HTML meta 标签检测字符集 */
    private fun detectCharsetFromHtml(html: String): String? {
        val regex = Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
    }

    /**
     * 内存 Cookie 持久化（会话内跨请求共享）。
     * 用于需要登录态或会话保持的源。
     */
    private class MemoryCookieJar : CookieJar {
        private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies.toMutableList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    /**
     * 重试拦截器。
     * 网络错误（IOException，含 SSL 握手失败）自动重试，最多 [maxRetry] 次。
     * 每次重试前短暂等待，避免对 CDN 节点造成连续冲击。
     */
    private class RetryInterceptor(private val maxRetry: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var response: Response? = null
            var exception: Exception? = null
            var retryCount = 0

            while (retryCount <= maxRetry) {
                try {
                    response = chain.proceed(request)
                    if (response.isSuccessful) {
                        return response
                    }
                    // 5xx 服务端错误也可重试
                    if (response.code < 500) {
                        return response
                    }
                    response.close()
                    response = null
                    retryCount++
                    Thread.sleep(500L * retryCount)
                } catch (e: Exception) {
                    exception = e
                    // SSL 握手失败/连接重置/超时均可重试
                    retryCount++
                    Thread.sleep(800L * retryCount)
                }
            }
            if (response != null) return response
            val reason = exception?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "未知错误"
            throw NetworkException("网络请求失败：$reason")
        }
    }
}

/** 网络异常 */
class NetworkException(message: String) : Exception(message)
