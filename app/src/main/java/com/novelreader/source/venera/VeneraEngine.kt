package com.novelreader.source.venera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.novelreader.core.network.RequestBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
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

    @Volatile
    private var webView: WebView? = null
    private var readySignal: CompletableDeferred<Unit>? = null
    private val bootMutex = Mutex()

    /**
     * 调用串行锁（v1.7.5.0）。
     *
     * 隐藏 WebView 的 JS 线程与 JavaBridge 线程都是**单线程**：任何在飞的 Venera 调用
     * （含源自己 `init()` 里的联网、dynamic loader）都会阻塞其它调用。冷启动时枚举层
     * 会并发派出一二十个 `categories`，旧代码里它们无条件排队，一个卡死的源就能把
     * 整条队列拖到 60s 超时（实测整页推荐 0 条）。
     *
     * 现在同一时刻只放一个调用真正执行；排不上队的调用最多等
     * [ENGINE_QUEUE_WAIT_MS]，超时直接快速失败（调用方按普通错误处理），
     * 把「无限期饿死」变成「快速失败 + 下一轮再来」。
     */
    private val callMutex = Mutex()
    private val callSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 独立 Cookie Jar：与 legado 源隔离，避免互相污染；登录态 Cookie 持久化到本地 */
    private val cookieJar = SimpleCookieJar()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // v1.7.5.0：超时全面收紧。旧值 connect 20s / read 60s，遇上「运营商丢包」
            // 型不可达站点（手机 WiFi/数据网最常见）时，单个请求能占用共享的
            // JavaBridge / JS 线程 20~80 秒 —— 而 Venera 引擎是**全应用一个 WebView**，
            // 一个卡死的 init()/loader 会把后面所有源全部堵到 60s 超时（实测冷启动
            // 9 个 categories 同时超时、整页推荐 0 条）。现在：
            // - connect 6s：不可达站点快速失败
            // - read 10s + callTimeout 12s：连上但拖数据的请求也有硬上界。
            //   read 15s→10s 是为了让「源顺序发多个请求」时总耗时仍压在
            //   CALL_TIMEOUT_MS(30s) 以内，否则这类源永远等不到结果。
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
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

    /**
     * 源设置修复入口（v1.7.5.0，见 [SourceRepairs]）。
     *
     * 仅当该设置**用户没有显式设过**时写入默认值；用户设过（含设为空串以外的
     * 任何值）则保持原样。写入格式与运行时的 `saveSetting` 一致（JSON 字符串），
     * `loadSetting` 会 `JSON.parse` 后返回。
     */
    fun ensureSetting(sourceKey: String, name: String, value: String) {
        val prefKey = "venera:setting:$sourceKey:$name"
        if (!prefs().getString(prefKey, null).isNullOrBlank()) return
        prefs().edit().putString(prefKey, gson.toJson(value)).apply()
    }

    fun removeSource(key: String) {
        sources.remove(key)
        registered.remove(key)
    }

    /**
     * 执行一个操作：op ∈ ping / search / info / chapters / content。
     *
     * v1.7.2.0 起**渲染进程崩溃自动重试一次**：WebView 渲染进程被杀时，
     * [onRenderProcessGone] 会把当前排队中的所有调用一起判失败并丢弃 WebView 实例，
     * 用户侧表现为弹「WebView 渲染进程已经终止」。而下次调用本来就会重建实例，
     * 所以这里对这一类失败重试一次即可无感恢复（search/info/chapters/content 都是
     * 幂等读，重试无副作用）。
     */
    suspend fun call(sourceKey: String, op: String, vararg args: Any?): String =
        callInternal(
            sourceKey,
            op,
            // v1.8.8.4：标签搜索在协程上下文里下发短排队预算（2s）。
            // 引擎是全应用一个 JS 线程，几十个源一起冲进来时，按默认 25s 排队意味着
            // 排在第 5 位之后的源光等锁就超过外层 8s 超时 —— 既拿不到结果，还一直占着队列。
            // 短预算下拿不到锁就立刻失败，把机会让给下一个源。未下发时行为不变。
            currentCoroutineContext()[RequestBudget]?.queueWaitMs ?: ENGINE_QUEUE_WAIT_MS,
            *args,
        )

    /**
     * 预热：串行 ping 一批源，触发各自的 `init()`（v1.7.5.0）。
     *
     * 为什么需要：引擎是全应用一个 JS 线程，源的 `init()` 常联网（拷贝漫画拉接口
     * 清单、少年Jump+ 拉 2MB 页面、JM 拉域名表）。若不在后台提前付掉这笔一次性
     * 成本，首页聚合的每次 explore 都要现场跑 init，单源耗时翻几倍，整页推荐
     * 直接被 init 风暴拖空（实测冷启动整页 0 条）。
     *
     * 与首页抢引擎的处理：ping 的排队等待取 [PREWARM_QUEUE_WAIT_MS]（远小于正常
     * 调用的 25s）—— 引擎闲就顺手预热一个，忙就立刻让位，绝不让首页的 explore
     * 等在我们后面。单个源失败（真死 / 超时）不影响后面的源。
     */
    suspend fun prewarm(keys: List<String>) {
        keys.forEach { key ->
            android.util.Log.i("VeneraPrewarm", "ping $key ...")
            runCatching { callInternal(key, "ping", PREWARM_QUEUE_WAIT_MS) }
                .onFailure { android.util.Log.w("VeneraPrewarm", "ping $key 失败：${it.message}") }
            // 让位间隔：刚放锁的瞬间，排队的首页协程要先够得着锁
            kotlinx.coroutines.delay(PREWARM_GAP_MS)
        }
        android.util.Log.i("VeneraPrewarm", "预热完成")
    }

    private suspend fun callInternal(
        sourceKey: String,
        op: String,
        queueWaitMs: Long,
        vararg args: Any?,
    ): String {
        try {
            return callOnce(sourceKey, op, queueWaitMs, *args)
        } catch (e: Exception) {
            if (e.message?.contains("渲染进程已终止") != true) throw e
            android.util.Log.w(LOG_TAG, "渲染进程崩溃，重建 WebView 后重试一次 op=$op")
            return callOnce(sourceKey, op, queueWaitMs, *args)
        }
    }

    private suspend fun callOnce(sourceKey: String, op: String, queueWaitMs: Long, vararg args: Any?): String {
        // 引擎串行 + 准入：等不上就快速失败，不参与无限期排队（见 callMutex 注释）。
        // 注意拿锁与 try/finally 必须紧邻：超时未拿到锁时直接抛错，不能进 finally 解锁。
        val locked = if (callMutex.tryLock()) {
            true
        } else {
            withTimeoutOrNull(queueWaitMs) {
                callMutex.lock()
                true
            }
        }
        if (locked == null) {
            throw IllegalStateException("Venera 引擎繁忙（已有调用执行中，稍后重试）")
        }
        val callId = "c" + callSeq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[callId] = deferred
        val startedAt = System.currentTimeMillis()
        try {
            try {
                dispatchLocked(sourceKey, op, callId, deferred, *args)
                return withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                // 可能是本调用的 30s 到点，也可能是父协程（首页单源预算）取消顺带
                // 触发的 —— 两者异常类型相同，用实际耗时区分，日志才不至于误导
                val elapsed = System.currentTimeMillis() - startedAt
                android.util.Log.w(LOG_TAG, "call(op=$op) 超时（实际 ${elapsed}ms）")
                throw IllegalStateException("Venera 源执行超时（${CALL_TIMEOUT_MS / 1000}s）")
            } catch (e: CancellationException) {
                // 父协程取消：不是源的错，照常抛出让 withTimeout 收场；
                // JS 侧善后在下面的 finally 里等落地
                throw e
            } catch (e: Exception) {
                // 源内部异常（JS 抛错 / 网络失败 / 派发失败）在这里落地，方便排查；
                // 消息本身会透给 UI，且常带接口地址，先脱敏再写日志。
                android.util.Log.w(
                    LOG_TAG,
                    "call(op=$op) 失败：${com.novelreader.core.util.LogSanitizer.sanitize(e.message)}",
                )
                throw e
            }
        } finally {
            // **关键**：无论成功 / 自身超时 / 被父协程取消，都要等 JS 侧这段工作
            // 真正落地才放锁。`@JavascriptInterface` 是同步调用，占着 JS 线程；
            // 放任不管的话，下一个调用会排在这段「没人要但还在跑」的工作后面
            // （实测冷启动整页推荐 0 条、漫画首页成批 30s 超时的根因）。
            // NonCancellable 保证父协程已取消时也等得完；宽限时间兜底防永久挂死。
            // runCatching：postError 会让 await 抛错，不能把原始异常顶掉。
            if (!deferred.isCompleted) {
                withContext(NonCancellable) {
                    runCatching { withTimeoutOrNull(ABANDON_GRACE_MS) { deferred.await() } }
                }
            }
            pending.remove(callId)
            callMutex.unlock()
        }
    }

    /**
     * 派发一次调用（注册源 + evaluateJavascript）。
     *
     * 与等待分离：派发失败时要把 [deferred] 立即判失败，好让 [callOnce] 的
     * 收尾逻辑不必白等宽限时间。
     */
    private suspend fun dispatchLocked(
        sourceKey: String,
        op: String,
        callId: String,
        deferred: CompletableDeferred<String>,
        vararg args: Any?,
    ) {
        try {
            val code = sources[sourceKey] ?: throw IllegalStateException("源未注册：$sourceKey")
            ensureStarted(code, sourceKey)

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
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
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
                /**
                 * 渲染进程崩溃 / 被系统回收时的兜底（API 26+）。
                 *
                 * **必须返回 true。** 不覆盖这个方法时默认实现返回 false，WebView 会认为
                 * 「没有任何人处理这次崩溃」，于是直接把宿主 App 进程 abort 掉 ——
                 * 实测 tombstone（2026-10-15）：
                 * ```
                 * Fatal signal 5 (SIGTRAP) in tid ... (com.novelreader)
                 * Abort message: [FATAL:crashpad_client_linux.cc(732)] Render process
                 *   (10349)'s crash wasn't handled by all associated webviews,
                 *   triggering application crash.
                 * ```
                 * 用户看到的就是毫无征兆的闪退，而且发生在任何一个 Tab ——
                 * 因为 Venera 引擎的隐藏 WebView 一直挂在后台。
                 *
                 * 这里返回 true 并自己回收实例：本次调用以异常收场（调用方本来就有
                 * 超时 / runCatching 兜底），下次 `call()` 会重建一个 WebView
                 * （见 [ensureStarted] 的 `webView == null` 分支），最坏只是慢一次。
                 */
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    Log.w(
                        LOG_TAG,
                        "Venera WebView 渲染进程已终止（didCrash=${detail?.didCrash()}），" +
                            "回收实例，下次调用时重建",
                    )
                    webView = null
                    readySignal?.let {
                        if (!it.isCompleted) {
                            it.completeExceptionally(
                                IllegalStateException("WebView 渲染进程已终止"),
                            )
                        }
                    }
                    readySignal = null
                    registered.clear()
                    // 正在等的调用全部异常返回，避免永久挂起
                    val gone = IllegalStateException("WebView 渲染进程已终止")
                    pending.values.forEach { if (!it.isCompleted) it.completeExceptionally(gone) }
                    pending.clear()
                    runCatching { view?.destroy() }
                    return true
                }

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
        fun getSource(key: String): String? =
            runCatching { sources[key] }.getOrElse { null }

        @JavascriptInterface
        fun postResult(callId: String, json: String) {
            // 结果载荷上限：字符串参数在进入 Java 前已完成转换（防不住转换本身），
            // 这里至少不再把它复制 / 透传给等待方，避免数百 MB 的串在堆里流转。
            val deferred = pending.remove(callId) ?: return
            if (json.length > MAX_RESULT_CHARS) {
                deferred.completeExceptionally(IllegalStateException("结果载荷超过 ${MAX_RESULT_CHARS / 1024 / 1024}M 字符上限"))
                return
            }
            deferred.complete(json)
        }

        @JavascriptInterface
        fun postError(callId: String, message: String) {
            val deferred = pending.remove(callId) ?: return
            deferred.completeExceptionally(
                IllegalStateException(message.take(MAX_ERROR_CHARS)),
            )
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
            runCatching {
                val httpUrl = url.toHttpUrlOrNull() ?: return@runCatching
                lines.split('\n').forEach { line ->
                    runCatching {
                        Cookie.parse(httpUrl, line)?.let { cookieJar.saveFromResponse(httpUrl, listOf(it)) }
                    }
                }
            }
        }

        @JavascriptInterface
        fun getCookie(url: String): String = runCatching {
            val httpUrl = url.toHttpUrlOrNull() ?: return@runCatching ""
            cookieJar.loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
        }.getOrDefault("")

        @JavascriptInterface
        fun deleteCookies(url: String) {
            runCatching {
                val httpUrl = url.toHttpUrlOrNull() ?: return@runCatching
                cookieJar.clear(httpUrl)
            }
        }

        @JavascriptInterface
        fun storageGet(key: String): String? = runCatching {
            prefs().getString(key, null)
        }.getOrElse { null }

        @JavascriptInterface
        fun storageSet(key: String, value: String) {
            runCatching { prefs().edit().putString(key, value).apply() }
        }

        @JavascriptInterface
        fun storageDelete(key: String) {
            runCatching { prefs().edit().remove(key).apply() }
        }

        @JavascriptInterface
        fun digest(algo: String, dataB64: String): String = bridgeCrypto(dataB64) {
            val md = MessageDigest.getInstance(algo.uppercase())
            md.digest(Base64.decode(dataB64, Base64.DEFAULT)).toHex()
        }

        @JavascriptInterface
        fun hmac(algo: String, keyB64: String, dataB64: String): String = bridgeCrypto(keyB64, dataB64) {
            val macAlgo = "Hmac" + algo.uppercase()
            val mac = Mac.getInstance(macAlgo)
            mac.init(SecretKeySpec(Base64.decode(keyB64, Base64.DEFAULT), macAlgo))
            mac.doFinal(Base64.decode(dataB64, Base64.DEFAULT)).toHex()
        }

        @JavascriptInterface
        fun aesDecrypt(mode: String, dataB64: String, keyB64: String, ivB64: String, blockSize: Int): String =
            bridgeCrypto(dataB64, keyB64, ivB64) {
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
                Base64.encodeToString(out, Base64.NO_WRAP)
            }
    }

    /**
     * 加解密桥的统一兜底：输入限长 + 异常不出线程。
     *
     * 这三个方法以前没有任何保护：源一旦传入超大 Base64（或算法名非法），
     * 异常会直接在 JavaBridge 线程爆掉、整个进程陪葬。现在超限返回空串
     * （源侧表现为该次取数失败，由 UI 层的错误提示兜底），异常一律吞掉。
     */
    private inline fun bridgeCrypto(vararg b64Inputs: String, block: () -> String): String {
        if (b64Inputs.any { it.length > MAX_CRYPTO_B64_CHARS }) return ""
        return runCatching { block() }.getOrElse { e ->
            android.util.Log.w(LOG_TAG, "桥加解密失败：${friendly(e)}")
            ""
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
            val bytes = readCapped(response.body)
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

    /**
     * 限长读取响应体。
     *
     * 背景（2026-10-16 真机闪退）：某个源取回的响应体巨大，旧代码直接用
     * `ResponseBody.bytes()` 按全长分配 ByteArray，随后 Base64 / String / Gson
     * 再放大数倍，在 WebView 的 JavaBridge 线程触发 7.78 亿字节的单次分配，
     * 而应用堆上限只有 384MB —— ART 在分配前就抛 OOM，线程直接死亡、进程闪退。
     *
     * 这里分块读、超限即抛错（由桥方法的 runCatching 收成 error JSON 返回给源），
     * 把下游所有分配都压死在 24MB 量级：文本 String ≤ 48MB、Base64 ≤ 32MB、
     * Gson JSON ≤ 64MB，峰值也远低于堆上限。
     */
    private fun readCapped(body: ResponseBody?): ByteArray {
        if (body == null) return ByteArray(0)
        val sink = Buffer()
        val source = body.source()
        while (true) {
            if (source.read(sink, READ_CHUNK_BYTES) == -1L) break
            if (sink.size > MAX_HTTP_BODY_BYTES) {
                throw IllegalStateException(
                    "响应体超过 ${MAX_HTTP_BODY_BYTES / 1024 / 1024}MB 上限",
                )
            }
        }
        return sink.readByteArray()
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

        /**
         * 单次调用（含 init / dynamic loader）的执行上界。
         *
         * v1.7.5.0 从 60s 降到 30s：内部每次 HTTP 已有 15s callTimeout 兜底，
         * 30s 对「一次操作里顺序发多个请求」的源仍够用；而 60s 的旧值意味着
         * 一个卡死的源能堵死共享引擎整整一分钟，整页推荐全部 0 条。
         */
        private const val CALL_TIMEOUT_MS = 30_000L

        /**
         * 引擎锁的排队上界：等不上就快速失败，调用方按普通错误处理
         * （首页推荐排会放弃该源，用户搜索则看到「引擎繁忙」）。
         *
         * v1.7.5.0 从 15s 提到 25s：首页每源预算提到 30s 后，6 路并发探一个
         * 约 5s 的调用时，排在第 6 位的要等 ~25s；15s 会把它误杀成「繁忙」，
         * 而它本来是能在预算内跑完的。
         */
        private const val ENGINE_QUEUE_WAIT_MS = 25_000L

        /**
         * 调用被放弃（自身超时 / 父协程取消）后，等 JS 侧工作落地的宽限时间。
         *
         * 同步桥调用占着 JS 线程，不等它就放锁，下一个调用只会排在这段孤儿
         * 工作后面 —— 实测是「整页推荐 0 条」的直接成因。15s 足够覆盖
         * 「一次慢请求（≤12s）+ 收尾」。
         */
        private const val ABANDON_GRACE_MS = 15_000L

        /**
         * 预热 ping 的排队上界：引擎忙（首页正在聚合）立刻让位，不跟首页抢。
         */
        private const val PREWARM_QUEUE_WAIT_MS = 4_000L

        /** 预热相邻两次 ping 的间隔，给排队中的首页协程留出抢锁窗口 */
        private const val PREWARM_GAP_MS = 350L

        /** Cookie 持久化 prefs key 前缀：venera_store 里 "ck_<host>" → 多行 Set-Cookie 文本 */
        private const val COOKIE_PREF_PREFIX = "ck_"

        /** 单次 HTTP 响应体硬上限（24MB），详见 [readCapped] 的崩溃背景说明 */
        private const val MAX_HTTP_BODY_BYTES = 24L * 1024 * 1024

        /** 响应体分块读取的块大小 */
        private const val READ_CHUNK_BYTES = 64L * 1024

        /** 加解密桥的 Base64 输入长度上限（8M 字符 ≈ 解码后 6MB），超限直接返回空串 */
        private const val MAX_CRYPTO_B64_CHARS = 8 * 1024 * 1024

        /** JS 回传结果载荷上限（16M 字符 ≈ 32MB String） */
        private const val MAX_RESULT_CHARS = 16 * 1024 * 1024

        /** JS 回传错误消息长度上限（防异常消息里带进整页响应体） */
        private const val MAX_ERROR_CHARS = 2_000
    }
}
