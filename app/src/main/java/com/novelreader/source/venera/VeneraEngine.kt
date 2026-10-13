package com.novelreader.source.venera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Venera 源运行时宿主。
 *
 * Venera 的源是「可执行 JS」，重度使用 class 字段 / async-await / Map。
 * 本项目原有的 Rhino（1.7.15）连 `class` 都不支持，因此这里用系统 WebView（V8）
 * 作为 JS 引擎 —— 零额外依赖、语法完整，且能直接跑官方运行时。
 *
 * 职责分工：
 * - **JS 侧**（assets/venera/runtime.js）：提供 ComicSource / Network / HtmlDocument /
 *   Convert / ComicDetails 等 Venera API，并负责把源返回值整理成 JSON；
 * - **原生侧**（本类）：隐藏 WebView 的创建与调度、HTTP、摘要/加解密、设置与数据存取。
 *
 * 关键点：`addJavascriptInterface` 的方法在 JS 侧是**同步**调用，所以 Venera 源码里
 * `Network.get()` 这种同步取值写法可以原样保留；同时 HTTP 全部走原生 OkHttp，
 * 因此完全不受 WebView 同源策略 / CORS 约束。
 */
class VeneraEngine(private val context: Context) {

    private val gson = Gson()

    /** key -> 源 JS 源码 */
    private val sources = ConcurrentHashMap<String, String>()

    /** 已在本轮 WebView 实例里注册过的 key（重建 WebView 后需重新注册） */
    private val registered = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** callId -> 结果等待者 */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private var webView: WebView? = null
    private var readySignal: CompletableDeferred<Unit>? = null
    private val bootMutex = Mutex()
    private val callSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 独立 Cookie Jar：与 legado 源隔离，避免互相污染；登录态 Cookie 持久化到本地 */
    private val cookieJar = SimpleCookieJar()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .followRedirects(true)
            .build()
    }

    /** 注册（或覆盖）一个源的源码。调用方：导入 / 启动恢复 */
    fun putSource(key: String, code: String) {
        val old = sources.put(key, code)
        // 源码变了（编辑 / 更新）→ 清掉注册标记，下次调用时重新求值
        if (old != code) registered.remove(key)
    }

    fun removeSource(key: String) {
        sources.remove(key)
        registered.remove(key)
    }

    /** 执行一个操作：op ∈ ping / search / info / chapters / content */
    suspend fun call(sourceKey: String, op: String, vararg args: Any?): String {
        val code = sources[sourceKey] ?: throw IllegalStateException("源未注册：$sourceKey")
        ensureStarted(code, sourceKey)

        val callId = "c" + callSeq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[callId] = deferred

        val argsJson = gson.toJson(args.toList())
        val script = buildString {
            if (registered.add(sourceKey)) {
                // 首次调用（或 WebView 重建后）先把源注册进 JS 侧
                append("__veneraRegister(").append(jsString(sourceKey)).append(");")
            }
            append("__veneraCall(")
                .append(jsString(callId)).append(',')
                .append(jsString(sourceKey)).append(',')
                .append(jsString(op)).append(',')
                .append(jsString(argsJson))
                .append(");true;")
        }

        withContext(Dispatchers.Main) { webView?.evaluateJavascript(script, null) }

        return try {
            withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // 日志里不出现源标识与地址（见 core/util/LogSanitizer 的说明）
            android.util.Log.w(LOG_TAG, "call(op=$op) 超时")
            throw IllegalStateException("Venera 源执行超时（${CALL_TIMEOUT_MS / 1000}s）")
        } catch (e: Exception) {
            // 源内部异常（JS 抛错 / 网络失败）在这里落地，方便排查；消息本身会透给 UI。
            // 消息里常常带接口地址，先脱敏再写日志。
            android.util.Log.w(
                LOG_TAG,
                "call(op=$op) 失败：${com.novelreader.core.util.LogSanitizer.sanitize(e.message)}",
            )
            throw e
        } finally {
            pending.remove(callId)
        }
    }

    // ==================== 启动 ====================

    private suspend fun ensureStarted(code: String, key: String) = bootMutex.withLock {
        // 源码可能刚被覆盖：这里只负责把「本次的源」放进 map
        sources[key] = code
        if (webView != null) return@withLock

        withContext(Dispatchers.Main) {
            val signal = CompletableDeferred<Unit>()
            readySignal = signal

            val wv = WebView(context)
            @SuppressLint("SetJavaScriptEnabled")
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // 运行时全部走原生 HTTP，这里不需要放开 file:// 跨域
                loadsImagesAutomatically = false
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = USER_AGENT
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            var pageLoaded = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (pageLoaded) return
                    pageLoaded = true
                    val runtime = runCatching {
                        context.assets.open("venera/runtime.js").bufferedReader().use { it.readText() }
                    }.getOrElse {
                        signal.completeExceptionally(it)
                        return
                    }
                    view?.evaluateJavascript(runtime) { result ->
                        // 运行时脚本末尾会调用 NativeBridge.ready()；这里兜底一次
                        if (result != null && result.contains("error", true)) {
                            signal.completeExceptionally(IllegalStateException("运行时加载失败：$result"))
                        } else if (!signal.isCompleted) {
                            signal.complete(Unit)
                        }
                    }
                }
            }
            wv.addJavascriptInterface(Bridge(), "NativeBridge")
            webView = wv
            wv.loadDataWithBaseURL(null, "<html><head><meta charset=\"utf-8\"></head><body></body></html>", "text/html", "utf-8", null)

            try {
                withTimeout(START_TIMEOUT_MS) { signal.await() }
            } catch (e: Exception) {
                webView = null
                readySignal = null
                registered.clear()
                wv.destroy()
                throw IllegalStateException("Venera 运行时启动失败：${e.message}")
            }
        }
    }

    // ==================== 原生桥（JS 同步调用）====================

    inner class Bridge {

        @JavascriptInterface
        fun ready() {
            readySignal?.let { if (!it.isCompleted) it.complete(Unit) }
        }

        @JavascriptInterface
        fun getSource(key: String): String? = sources[key]

        @JavascriptInterface
        fun postResult(callId: String, json: String) {
            pending.remove(callId)?.complete(json)
        }

        @JavascriptInterface
        fun postError(callId: String, message: String) {
            pending.remove(callId)?.completeExceptionally(IllegalStateException(message))
        }

        @JavascriptInterface
        fun log(level: String, message: String) {
            // 源自己打出的日志内容不可控（常含站点名 / 图片地址 / 接口参数），
            // 只保留级别与长度，避免日志里出现具体的源与站点
            android.util.Log.d("VeneraJs", "[$level] (${message.length} chars)")
        }

        @JavascriptInterface
        fun http(method: String, url: String, headersJson: String?, data: String?): String =
            runCatching { doHttp(method, url, headersJson, data, binary = false) }
                .getOrElse { gson.toJson(mapOf("error" to friendly(it))) }

        @JavascriptInterface
        fun httpBinary(method: String, url: String, headersJson: String?, data: String?): String =
            runCatching { doHttp(method, url, headersJson, data, binary = true) }
                .getOrElse { gson.toJson(mapOf("error" to friendly(it))) }

        @JavascriptInterface
        fun setCookie(url: String, lines: String) {
            val httpUrl = runCatching { url.toHttpUrl() }.getOrNull() ?: return
            lines.split('\n').forEach { line ->
                runCatching {
                    Cookie.parse(httpUrl, line)?.let { cookieJar.saveFromResponse(httpUrl, listOf(it)) }
                }
            }
        }

        @JavascriptInterface
        fun getCookie(url: String): String {
            val httpUrl = runCatching { url.toHttpUrl() }.getOrNull() ?: return ""
            return cookieJar.loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
        }

        @JavascriptInterface
        fun deleteCookies(url: String) {
            val httpUrl = runCatching { url.toHttpUrl() }.getOrNull() ?: return
            cookieJar.clear(httpUrl)
        }

        @JavascriptInterface
        fun storageGet(key: String): String? = prefs().getString(key, null)

        @JavascriptInterface
        fun storageSet(key: String, value: String) {
            prefs().edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun storageDelete(key: String) {
            prefs().edit().remove(key).apply()
        }

        @JavascriptInterface
        fun digest(algo: String, dataB64: String): String {
            val md = MessageDigest.getInstance(algo.uppercase())
            return md.digest(Base64.decode(dataB64, Base64.DEFAULT)).toHex()
        }

        @JavascriptInterface
        fun hmac(algo: String, keyB64: String, dataB64: String): String {
            val macAlgo = "Hmac" + algo.uppercase()
            val mac = Mac.getInstance(macAlgo)
            mac.init(SecretKeySpec(Base64.decode(keyB64, Base64.DEFAULT), macAlgo))
            return mac.doFinal(Base64.decode(dataB64, Base64.DEFAULT)).toHex()
        }

        @JavascriptInterface
        fun aesDecrypt(mode: String, dataB64: String, keyB64: String, ivB64: String, blockSize: Int): String {
            val transformation = when (mode.lowercase()) {
                "ecb" -> "AES/ECB/PKCS5Padding"
                "cbc" -> "AES/CBC/PKCS5Padding"
                "cfb" -> "AES/CFB/NoPadding"
                "ofb" -> "AES/OFB/NoPadding"
                else -> "AES/CBC/PKCS5Padding"
            }
            val cipher = Cipher.getInstance(transformation)
            val key = SecretKeySpec(Base64.decode(keyB64, Base64.DEFAULT), "AES")
            val iv = Base64.decode(ivB64, Base64.DEFAULT)
            val spec = if (iv.isEmpty()) null else IvParameterSpec(iv)
            if (spec != null) cipher.init(Cipher.DECRYPT_MODE, key, spec) else cipher.init(Cipher.DECRYPT_MODE, key)
            val out = cipher.doFinal(Base64.decode(dataB64, Base64.DEFAULT))
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }
    }

    // ==================== HTTP ====================

    private fun doHttp(method: String, url: String, headersJson: String?, data: String?, binary: Boolean): String {
        val headers = parseHeaders(headersJson)
        val builder = Request.Builder().url(url)
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value

        headers.forEach { (k, v) ->
            // 以下交给 OkHttp 统一处理，避免重复 / 冲突：
            // - User-Agent：缺省时补默认 UA
            // - Content-Type：由下面的 mediaType 统一设置
            // - Content-Length：OkHttp 自己算
            // - Accept-Encoding：**必须剥掉**。很多源会写
            //   `Accept-Encoding: gzip, deflate, br, zstd`，OkHttp 只有在调用方
            //   没有自己设置该头时才会自动解压；一旦原样透传，响应体会以压缩字节
            //   直接返回，源里 JSON.parse 就会炸（实测报 "Unexpected token '\u001f'"，
            //   即 gzip 魔数）。剥掉后 OkHttp 会协商 gzip 并透明解压。
            val lower = k.lowercase()
            if (lower == "user-agent" || lower == "content-type" ||
                lower == "content-length" || lower == "accept-encoding"
            ) {
                return@forEach
            }
            builder.header(k, v)
        }
        if (headers.none { it.key.equals("user-agent", true) }) builder.header("User-Agent", USER_AGENT)

        val m = method.uppercase()
        val mediaType = (contentType ?: "application/x-www-form-urlencoded; charset=utf-8").toMediaTypeOrNull()
        val body = when {
            m == "GET" || m == "HEAD" -> null
            data == null -> ByteArray(0).toRequestBody(mediaType)
            else -> data.toByteArray(Charsets.UTF_8).toRequestBody(mediaType)
        }
        builder.method(m, body)

        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val respHeaders = linkedMapOf<String, String>()
            response.headers.names().forEach { name ->
                respHeaders[name] = response.header(name).orEmpty()
            }
            return if (binary) {
                gson.toJson(
                    mapOf(
                        "status" to response.code,
                        "headers" to respHeaders,
                        "bodyBase64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                        "url" to response.request.url.toString(),
                    )
                )
            } else {
                gson.toJson(
                    mapOf(
                        "status" to response.code,
                        "headers" to respHeaders,
                        "body" to decodeBody(bytes, contentType ?: response.header("Content-Type")),
                        "url" to response.request.url.toString(),
                    )
                )
            }
        }
    }

    private fun parseHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            obj.entrySet().associate { (k, v) -> k to v.asString }
        }.getOrDefault(emptyMap())
    }

    /** 响应体解码：优先 Content-Type 的 charset，UTF-8 解出替换符再回落 GBK */
    private fun decodeBody(bytes: ByteArray, contentType: String?): String {
        val declared = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(contentType ?: "")?.groupValues?.getOrNull(1)?.lowercase()
        if (declared != null) {
            runCatching {
                val cs = when (declared) {
                    "gb2312", "gbk", "gb18030" -> "GBK"
                    "big5" -> "Big5"
                    else -> declared
                }
                return String(bytes, java.nio.charset.Charset.forName(cs))
            }
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.indexOf('\uFFFD') < 0) return utf8
        return runCatching { String(bytes, java.nio.charset.Charset.forName("GBK")) }.getOrDefault(utf8)
    }

    private fun prefs() = context.getSharedPreferences("venera_store", Context.MODE_PRIVATE)

    // ==================== 账号登录（v1.3.0.0）====================

    /** 源登录态标记的 prefs key */
    private fun loginMarkKey(key: String) = "login_ok_$key"

    /**
     * 该源此前是否成功登录过（本地标记，供 UI 展示「已登录」徽标）。
     *
     * 只读 prefs，不启动 WebView——UI 列表刷新时可以放心调用。
     * 真实登录态以源自己的 checkLogin / 服务端会话为准。
     */
    fun isLoginMarked(key: String): Boolean = prefs().getString(loginMarkKey(key), null) == "1"

    /**
     * 查询源的账号能力与登录态。
     *
     * @return (是否支持登录, 是否已登录)——后者依赖源实现 checkLogin；未实现时回退本地标记
     */
    suspend fun accountInfo(sourceKey: String): Pair<Boolean, Boolean> {
        val json = call(sourceKey, "accountInfo")
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }
            .getOrElse { return Pair(false, isLoginMarked(sourceKey)) }
        val supported = obj.get("supported")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val loggedInRemote = obj.get("loggedIn")?.takeIf { it.isJsonPrimitive }?.asBoolean
        return Pair(supported, loggedInRemote ?: isLoginMarked(sourceKey))
    }

    /** 账号密码登录：成功后写本地登录标记（Cookie 由 saveFromResponse 自动持久化） */
    suspend fun login(sourceKey: String, account: String, password: String) {
        call(sourceKey, "login", account, password)
        prefs().edit().putString(loginMarkKey(sourceKey), "1").apply()
    }

    /** 退出登录：清登录标记 + 清该源 Cookie */
    suspend fun logout(sourceKey: String) {
        runCatching { call(sourceKey, "logout") }
        prefs().edit().remove(loginMarkKey(sourceKey)).apply()
        cookieJar.clearAll()
    }

    private fun jsString(text: String): String = gson.toJson(text)

    private fun friendly(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * 简易 CookieJar：内存存取 + 登录 Cookie 持久化。
     *
     * v1.3.0.0：登录后的会话 Cookie 必须在应用重启后仍有效（否则每次启动都要重新登录），
     * 因此按 host 写入 SharedPreferences，引擎创建时回灌。
     */
    private inner class SimpleCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        init {
            // 回灌持久化的 Cookie（按请求 host 分组存储）
            val all = prefs().all
            all.entries.filter { it.key.startsWith(COOKIE_PREF_PREFIX) }.forEach { (prefKey, value) ->
                val host = prefKey.removePrefix(COOKIE_PREF_PREFIX)
                val httpUrl = runCatching { "https://$host/".toHttpUrl() }.getOrNull() ?: return@forEach
                (value as? String)?.split('\n')?.forEach { line ->
                    val cookie = runCatching { Cookie.parse(httpUrl, line) }.getOrNull()
                    if (cookie != null) store.getOrPut(host) { mutableListOf() }.add(cookie)
                }
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val key = url.host
            val list = store.getOrPut(key) { mutableListOf() }
            synchronized(list) {
                cookies.forEach { c ->
                    list.removeAll { it.name == c.name && it.path == c.path }
                    if (c.expiresAt > System.currentTimeMillis()) list.add(c)
                }
                persistHost(key, list)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            return synchronized(list) {
                list.removeAll { it.expiresAt < now }
                list.filter { it.matches(url) }
            }
        }

        fun clear(url: HttpUrl) {
            store.remove(url.host)
            prefs().edit().remove(COOKIE_PREF_PREFIX + url.host).apply()
        }

        /** 退出登录：清空全部 Cookie（登录态是全站性的，逐 host 清理意义不大） */
        fun clearAll() {
            store.keys.toList().forEach { host ->
                store.remove(host)
                prefs().edit().remove(COOKIE_PREF_PREFIX + host).apply()
            }
        }

        private fun persistHost(host: String, cookies: List<Cookie>) {
            // Cookie.toString() 产出 Set-Cookie 风格行，Cookie.parse 可无损还原
            val text = synchronized(cookies) { cookies.joinToString("\n") { it.toString() } }
            prefs().edit().putString(COOKIE_PREF_PREFIX + host, text).apply()
        }
    }

    companion object {
        private const val LOG_TAG = "VeneraEngine"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val START_TIMEOUT_MS = 20_000L
        private const val CALL_TIMEOUT_MS = 60_000L

        /** Cookie 持久化 prefs key 前缀：venera_store 里 "ck_<host>" → 多行 Set-Cookie 文本 */
        private const val COOKIE_PREF_PREFIX = "ck_"
    }
}
