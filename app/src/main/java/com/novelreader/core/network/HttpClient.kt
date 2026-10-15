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

    /** 共享 Cookie 容器：两个客户端（带/不带重试）必须共用，否则会话 Cookie 会分裂 */
    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 20s：章节页 / 列表页的正常响应远小于此；此前 90s 会让死源把
            // 跨源搜索 / 换源 / 首页聚合拖到分钟级（v1.4.8.0 收紧）。
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .addInterceptor(RetryInterceptor(MAX_RETRY))
            .build()
    }

    /**
     * 不带自动重试的客户端（v1.7.1.0）。
     *
     * `execute()` 在 `timeoutMs == null` 时曾无条件走带 [RetryInterceptor] 的 [client]，
     * 于是 `retry = false` 被静默忽略 —— 跨源搜索逐源遍历，一个不可达的站点要跑
     * 3×(15~20s)+退避 ≈ 60~90s 才判定失败，把整轮搜索拖到分钟级（漫画"0/N 卡住"的直接成因之一）。
     * 现拆成两个客户端，让 `retry = false` 真正生效。
     */
    private val clientNoRetry: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
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
     * @param timeoutMs 自定义连接/读取超时（毫秒）；null 用全局默认（15s / 20s）。
     *        采集类接口（MoonTV / 苹果 CMS）动辄几十上百个站点，需要有界超时，
     *        避免个别死站点把整轮搜索拖到分钟级。
     * @param retry 是否启用自动重试；**默认 false** —— 跨源搜索 / 换源 / 首页聚合
     *        会遍历大量源，逐个重试 3 次会把「站点不可达」的确定性失败放大成数倍等待
     *        （v1.4.8.0 起默认关闭，确有需要的调用方显式传 true）。
     */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        charset: String? = null,
        timeoutMs: Long? = null,
        retry: Boolean = false,
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        // 注意：不要手动设置 Accept-Encoding，让 OkHttp 透明处理 gzip 解压
        headers.forEach { (k, v) -> builder.header(k, v) }

        return execute(builder.build(), charset, timeoutMs, retry)
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

    /**
     * 执行请求并返回**原始 [Response]**（不自动关连接、不校验 HTTP 状态）。
     *
     * 给需要自定义状态码 / 响应体处理的场景用（如 WebDav 的 PUT/DELETE/PROPFIND），
     * 调用方负责 `use { }` 关闭。
     *
     * **阻塞式调用，必须在 IO 线程执行。**
     */
    fun executeRaw(request: Request, timeoutMs: Long? = null): Response {
        val call = if (timeoutMs == null) {
            client.newCall(request)
        } else {
            clientWithTimeout(timeoutMs, retry = true).newCall(request)
        }
        return call.execute()
    }

    /** 统一执行请求并按字符集解码响应体 */
    private fun execute(
        request: Request,
        charset: String?,
        timeoutMs: Long? = null,
        retry: Boolean = false,
    ): String {
        val call = when {
            timeoutMs != null -> clientWithTimeout(timeoutMs, retry).newCall(request)
            // v1.7.1.0：timeoutMs == null 时也尊重 retry=false，避免死源被 3 次重试放大
            retry -> client.newCall(request)
            else -> clientNoRetry.newCall(request)
        }
        call.execute().use { response: Response ->
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
     * 带自定义超时的客户端（按「超时值 + 是否重试」缓存）。
     *
     * `newBuilder()` 复用同一个连接池与调度器，不会额外占用连接资源；
     * 缓存避免为每次请求新建客户端（OkHttp 官方建议共享实例）。
     *
     * @param retry false 时移除 [RetryInterceptor]，让「站点不可达」快速失败
     *        （采集类接口站点数量多，逐个重试 3 次会让整轮请求慢到不可用）。
     */
    private val timeoutClients = ConcurrentHashMap<String, OkHttpClient>()

    private fun clientWithTimeout(timeoutMs: Long, retry: Boolean = true): OkHttpClient =
        timeoutClients.getOrPut("$timeoutMs|$retry") {
            client.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .apply {
                    if (!retry) interceptors().removeAll { it is RetryInterceptor }
                }
                .build()
        }

    // ===== 播放地址测速（v1.3.6.0「测速择优」）=====

    /**
     * 探测播放地址的可达性与响应延迟（毫秒），失败返回 `null`。
     *
     * 为什么不用 [get]：播放地址动辄几百 MB，[get] 会把**整个文件**读进内存。
     * 这里只发 `Range: bytes=0-0` 拿**首字节**，拿到就立刻关连接 ——
     * 测的是「连接 + 首字节」耗时（用户感知的起播延迟），而不是下载带宽。
     *
     * 有界且不重试：测速是给用户看的对比数字，站点挂了就应该快速给个「-」，
     * 重试只会让整轮测速拖到分钟级。
     *
     * **注意：这是阻塞式调用，必须在 IO 线程上调用**（如 `withContext(Dispatchers.IO)`），
     * 否则会抛 `NetworkOnMainThreadException` —— 而它会被本方法的 `runCatching` 吞掉，
     * 表现为「所有线路都不可达」，非常难查（v1.3.6.0 踩过）。
     *
     * @param timeoutMs 单条线路的超时（连接 + 读取）
     */
    fun probe(url: String, timeoutMs: Long = PROBE_TIMEOUT_MS): Long? {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", DEFAULT_USER_AGENT)
                // 只要首字节；不支持的站点会忽略它开始推流，我们读完 1 字节即断开
                .header("Range", "bytes=0-0")
                .build()
        }.getOrNull() ?: return null

        val startedAt = System.nanoTime()
        return runCatching {
            clientWithTimeout(timeoutMs, retry = false).newCall(request).execute().use { response ->
                // 部分站点返回 200（忽略 Range）、部分返回 206（支持 Range），都算通
                if (!response.isSuccessful && response.code != HTTP_PARTIAL_CONTENT) {
                    return@runCatching null
                }
                response.body?.byteStream()?.read()
                (System.nanoTime() - startedAt) / 1_000_000L
            }
        }.getOrNull()
    }

    /** 测速默认超时：单条线路最长等多久 */
    const val PROBE_TIMEOUT_MS = 5_000L

    private const val HTTP_PARTIAL_CONTENT = 206

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
     *
     * **两段式**（v1.7.2.0）：先在**字节上**嗅探 `<meta charset>`，命中非 UTF-8 就直接
     * 按它解码。此前是「先无条件构造整串 UTF-8 → 发现乱码 → 再构造 meta 字符集的串 →
     * 还不行再构造 GBK 串」，GBK 站点一个 1MB 页面要同时存在三份字符串（UTF-16 下
     * 每份 ~2MB）。首页聚合并发几十个请求时，这个放大倍数是内存爆掉的直接推手。
     *
     * 字节级嗅探只看头部若干字节并用 ISO-8859-1（单字节 ↔ 单字符，恒定开销）解读，
     * 不产生额外的大对象。
     */
    private fun decode(bytes: ByteArray, charset: String?): String {
        if (charset != null) {
            return runCatching { String(bytes, java.nio.charset.Charset.forName(charset)) }
                .getOrDefault(String(bytes, Charsets.UTF_8))
        }
        // 第一段：字节级嗅探（只看头部，避免构造整串）
        val sniffed = sniffCharsetFromBytes(bytes)
        if (sniffed != null && sniffed.lowercase() != "utf-8") {
            runCatching { String(bytes, java.nio.charset.Charset.forName(sniffed)) }
                .getOrNull()
                ?.let { return it }
        }
        // 第二段：按 UTF-8 解一次；没有替换字符就说明本来就是 UTF-8
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) {
            return utf8
        }
        // 回退 GBK（中文站点常见）
        return runCatching { String(bytes, java.nio.charset.Charset.forName("GBK")) }
            .getOrDefault(utf8)
    }

    /**
     * 在**字节**上嗅探 HTML `meta` 声明的字符集。
     *
     * 只扫头部 [CHARSET_SNIFF_BYTES] 字节：`<meta charset>` 按规范必须出现在
     * `<head>` 开头 1024 字节内，留 4KB 已经很宽裕。
     * 用 ISO-8859-1 解读（单字节 → 单字符，长度的 1 倍，无替换歧义），
     * 这样 ASCII 的标签本身总能正确匹配，不受正文编码影响。
     */
    private fun sniffCharsetFromBytes(bytes: ByteArray): String? {
        val n = minOf(bytes.size, CHARSET_SNIFF_BYTES)
        if (n <= 0) return null
        val head = String(bytes, 0, n, Charsets.ISO_8859_1)
        return detectCharsetFromHtml(head)
    }

    /** 从 Content-Type 解析字符集 */
    private fun parseCharsetFromContentType(contentType: String): String? {
        val regex = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
        return regex.find(contentType)?.groupValues?.get(1)
    }

    /**
     * 从 HTML meta 标签检测字符集。
     *
     * `charset=` 既可能是 `<meta charset="gbk">`，也可能是
     * `<meta http-equiv="Content-Type" content="text/html; charset=gb2312">`，
     * 用一条正则同时覆盖两种写法的 `charset=` 出现位置即可。
     */
    private fun detectCharsetFromHtml(html: String): String? {
        val regex = Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
    }

    /** 字节级字符集嗅探的扫描窗口：`<meta charset>` 必在 head 开头，4KB 足够 */
    private const val CHARSET_SNIFF_BYTES = 4096

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
