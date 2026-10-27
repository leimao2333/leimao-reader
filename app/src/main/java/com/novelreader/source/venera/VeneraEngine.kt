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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * 关键点：HTTP 全部走原生 OkHttp，因此完全不受 WebView 同源策略 / CORS 约束。
 *
 * v1.8.8.8 起网络改走**异步桥**（[Bridge.httpAsync] / [Bridge.httpTake]）：
 * 旧实现里 `@JavascriptInterface` 的 `http()` 是同步阻塞调用，一次 HTTP 就把
 * WebView 的 JS 线程占满 6~12 秒，于是无论把并发开到多大，所有源实际上仍是
 * 一个接一个地跑（[callMutex] 那套串行锁正是为这种「占线」兜底）。
 * 现在原生在自己的 IO 线程池里发请求，完成后用 `evaluateJavascript` 回调
 * `window.__veneraHttpDone(reqId)`，JS 侧 `Network.get()` 变成返回 Promise ——
 * 源码里的 `await Network.get(...)` 写法完全不用改，等待期间 JS 线程即刻空出来
 * 去跑别的源。这也是调用闸门能从「串行锁」换成「双通道并发闸门」的前提。
 *
 * 少数源不走 `await`、直接同步取 `Network.get().body`，这类写法在异步桥下会拿到
 * Promise。JS 侧对此做了自愈：一旦某个源因这类错误失败，会整体回落同步桥重试一次，
 * 成功则把该源永久标记为 sync-only。
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
     * 调用并发闸门（v1.8.8.8，取代 v1.7.5.0 的串行 `callMutex`）。
     *
     * 旧实现为什么必须串行：同步 HTTP 桥把 JS 线程占满整个网络往返，同一时刻
     * 放进第二个调用也只会排在 JS 线程后面，反而让「排队」不可控（一个卡死的源
     * 能把整条队列拖到 60s，实测整页推荐 0 条）。
     *
     * 异步桥把网络等待从 JS 线程挪走之后，一次调用只在与 JS 本身打交道时才占用
     * JS 线程，多个源可以真正并行。现在闸门只负责限制「同时在飞的调用数」，
     * 避免几十个源同时冲击同一个渲染进程。排不上队的调用最多等
     * [ENGINE_QUEUE_WAIT_MS]（或调用方下发的 `RequestBudget.queueWaitMs`），
     * 超时快速失败，把机会让给下一个源。
     *
     * **分道（v1.8.8.8）**：闸门拆成「交互」和「后台」两条互不相交的通道。
     * 用户搜索 / 打开详情时，最怕的是首屏聚合（explore、categories）或后台预热
     * 已经把槽位占满 —— 表现为「搜了十秒什么都没出来」。现在交互通道有固定额度，
     * 永远不必等后台任务，后台任务也挤不进交互通道。
     */
    private val interactiveGate = Semaphore(MAX_CONCURRENT_INTERACTIVE)
    private val backgroundGate = Semaphore(MAX_CONCURRENT_BACKGROUND)
    private val callSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 正在飞的**交互**调用数（v1.8.9.4）。
     *
     * 后台任务看到它 > 0 就主动让路：用户正在等某次点击时，不再往共享 JS 线程里
     * 塞新的召回请求。这是「点进 Venera 源一直转圈」的正面修法 ——
     * 只调小后台并发仍然会让已排队的请求继续挤在前面。
     */
    private val interactiveInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 派发锁：只保护「注册 + evaluateJavascript」这一小段，不覆盖网络等待。
     *
     * 为什么需要：同一批并发调用里，A 的脚本带 `__veneraRegister`、B 的不带，
     * 若两者的 `evaluateJavascript` 落地顺序被打乱，B 会先于注册执行而报「源未注册」。
     * 锁住派发顺序即可保证 JS 侧的注册先于调用。
     */
    private val dispatchMutex = Mutex()

    /** 异步 HTTP：请求 id 序号 */
    private val httpSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 异步 HTTP：reqId -> 已完成的响应 JSON，等待 JS 侧 `httpTake` 取走 */
    private val httpResults = ConcurrentHashMap<String, String>()

    /** 异步 HTTP 的并发闸门（独立于调用闸门：一个调用里可能连发多个请求） */
    private val httpGate = Semaphore(MAX_CONCURRENT_HTTP)

    /** 异步 HTTP 的执行作用域：IO 线程池，随进程存活 */
    private val httpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 把「请求已完成」这件事送回 JS。
     *
     * 只送 reqId、不送载荷：真正的大字符串由 JS 侧再调 [Bridge.httpTake] 同步取，
     * 避免再经 `evaluateJavascript` 的脚本串做一次转义与二次拷贝（24MB 的响应体会
     * 被放大成几十 MB 的脚本文本）。
     *
     * webView 已经没了（崩溃 / 重建）就直接丢弃结果，不掉进内存里等一个不会来的取件。
     */
    private fun deliverHttp(reqId: String) {
        val wv = webView
        if (wv == null) {
            httpResults.remove(reqId)
            return
        }
        // 未被取走的结果兜底清理：线程池里可能还有几十个在飞，谁都不许长期驻留
        if (httpResults.size > MAX_PENDING_HTTP_RESULTS) {
            val stale = httpResults.keys.take(httpResults.size - MAX_PENDING_HTTP_RESULTS / 2)
            stale.forEach { httpResults.remove(it) }
        }
        val postAt = System.currentTimeMillis()
        wv.post {
            val waitedMs = System.currentTimeMillis() - postAt
            if (waitedMs > 500) {
                // 应用主线程被占住时，所有「请求已完成」的通知都会排在队列里 ——
                // JS 侧那些 Promise 一个都结不了（真机实测 pending 一路涨到 14）。
                // JS 侧已补了轮询取件兜底，这条日志用来确认它到底有没有被卡。
                android.util.Log.w("VeneraNet", "投递延迟 ${waitedMs}ms")
            }
            runCatching {
                wv.evaluateJavascript(
                    "window.__veneraHttpDone&&window.__veneraHttpDone(${jsString(reqId)});",
                    null,
                )
            }
        }
    }

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
    suspend fun call(sourceKey: String, op: String, vararg args: Any?): String {
        val budget = currentCoroutineContext()[RequestBudget]
        return callInternal(
            sourceKey,
            op,
            // v1.8.8.4：标签搜索在协程上下文里下发短排队预算（2s）。
            // 引擎是全应用一个 JS 线程，几十个源一起冲进来时，按默认 25s 排队意味着
            // 排在第 5 位之后的源光等锁就超过外层 8s 超时 —— 既拿不到结果，还一直占着队列。
            // 短预算下拿不到锁就立刻失败，把机会让给下一个源。未下发时行为不变。
            budget?.queueWaitMs ?: ENGINE_QUEUE_WAIT_MS,
            budget?.background == true,
            *args,
        )
    }

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
        background: Boolean = false,
        vararg args: Any?,
    ): String {
        try {
            return callOnce(sourceKey, op, queueWaitMs, background, *args)
        } catch (e: Exception) {
            if (e.message?.contains("渲染进程已终止") != true) throw e
            android.util.Log.w(LOG_TAG, "渲染进程崩溃，重建 WebView 后重试一次 op=$op")
            return callOnce(sourceKey, op, queueWaitMs, background, *args)
        }
    }

    private suspend fun callOnce(
        sourceKey: String,
        op: String,
        queueWaitMs: Long,
        background: Boolean = false,
        vararg args: Any?,
    ): String {
        // 分道：用户主动发起的操作走交互通道，首屏聚合 / 预热 / 标签召回走后台通道
        val interactive = !background && isInteractive(op)
        val gate = if (interactive) interactiveGate else backgroundGate
        // 后台任务主动让路（v1.8.9.4）：用户正在等某次点击（详情 / 目录 / 正文）时，
        // 不再往那条**全应用共用**的 JS 线程里塞新的召回请求。否则召回会把线程排满，
        // 交互请求即使拿到了许可也只能排在后面 —— 实测顶到 30 秒上界，表现就是
        // 「点进 Venera 源一直转圈」。等待有上界（[YIELD_TO_INTERACTIVE_MS]），
        // 用户离开后后台工作照常继续，不会被永久饿死。
        if (!interactive && interactiveInFlight.get() > 0) {
            val deadline = System.currentTimeMillis() + YIELD_TO_INTERACTIVE_MS
            while (interactiveInFlight.get() > 0 && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(YIELD_POLL_MS)
            }
        }
        if (interactive) interactiveInFlight.incrementAndGet()
        try {
            // 并发闸门准入：等不上就快速失败，不参与无限期排队（见 gate 字段注释）。
            val acquired = if (gate.tryAcquire()) {
                true
            } else {
                withTimeoutOrNull(queueWaitMs) {
                    gate.acquire()
                    true
                } ?: false
            }
            if (!acquired) {
                throw IllegalStateException("Venera 引擎繁忙（并发调用已满，稍后重试）")
            }
            return awaitCall(sourceKey, op, background, gate, *args)
        } finally {
            if (interactive) interactiveInFlight.decrementAndGet()
        }
    }

    private suspend fun awaitCall(
        sourceKey: String,
        op: String,
        background: Boolean,
        gate: Semaphore,
        vararg args: Any?,
    ): String {
        val callId = "c" + callSeq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[callId] = deferred
        val startedAt = System.currentTimeMillis()
        // 预热 ping 的预算单独收紧（v1.8.8.8）：它只是「顺手把 init 跑掉」，
        // 卡住的源不该占着后台通道整整 30 秒 —— 首页推荐还在等这些额度。
        val callTimeoutMs = when {
            op == "ping" -> PING_TIMEOUT_MS
            // v1.8.9.5：读章节单独放宽。有些源的 loadEp 是「一页一次请求」，
            // 一章几十页 = 几十次往返，30 秒必然不够 —— 用户侧就是「章节加载不出内容」。
            // 放宽到 60s 是安全的：超时后 [abortJsCall] 会把 JS 侧的循环打断，
            // 不会再出现「放弃了还在后台狂发请求」。
            op == "content" -> CONTENT_TIMEOUT_MS
            background -> BACKGROUND_CALL_TIMEOUT_MS
            else -> CALL_TIMEOUT_MS
        }
        try {
            try {
                dispatch(sourceKey, op, callId, deferred, *args)
                return withTimeout(callTimeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                // 可能是本调用的超时到点，也可能是父协程（首页单源预算）取消顺带
                // 触发的 —— 两者异常类型相同，用实际耗时区分，日志才不至于误导
                val elapsed = System.currentTimeMillis() - startedAt
                android.util.Log.w(LOG_TAG, "call(op=$op) 超时（实际 ${elapsed}ms）")
                throw IllegalStateException("Venera 源执行超时（${callTimeoutMs / 1000}s）")
            } catch (e: CancellationException) {
                // 父协程取消：不是源的错，照常抛出让 withTimeout 收场
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
            // v1.8.8.8：网络等待已从 JS 线程挪走，不再需要「等孤儿工作落地再放锁」
            // （旧实现的 ABANDON_GRACE_MS 等待）。
            //
            // v1.8.9.5：但**必须通知 JS 侧取消**。实测某些源（逐页取图那类）的
            // `loadEp` 是「一页一次请求」的循环，Kotlin 这边 30 秒超时放弃之后，
            // JS 里的循环仍在继续 —— 退出阅读器后网络请求还在一路发（实测 10 秒
            // 35 次），把共享 JS 线程和 HTTP 闸门长期占住，于是**别的什么都加载不出来**。
            // 这里给 JS 打一个取消标记，JS 侧的下一次网络调用会直接抛错，把循环打断。
            if (!deferred.isCompleted) {
                abortJsCall(callId)
            }
            pending.remove(callId)
            gate.release()
        }
    }

    /**
     * 通知 JS 侧放弃某次调用（v1.8.9.5）。
     *
     * 只是「打个标记」：JS 无法真正中断一个正在跑的源函数，但它在下一次
     * `Network.*` 时会看到标记并抛错，从而把源的循环 / 重试链打断。
     */
    private fun abortJsCall(callId: String) {
        val wv = webView ?: return
        wv.post {
            runCatching {
                wv.evaluateJavascript(
                    "window.__veneraAbort&&window.__veneraAbort(${jsString(callId)});",
                    null,
                )
            }
        }
    }

    /**
     * 该 op 是否属于「用户在等结果」的操作。
     *
     * - 交互：search（搜索）、info / chapters / content（打开详情与阅读）、
     *   login / accountInfo / retryImage（账号与图片重试）—— 用户盯着屏幕，
     *   任何一秒的等待都会被感知。
     * - 后台：ping（预热）、categories / explore / categoryComics（首屏聚合）——
     *   用户没在看具体结果，慢一点无感知，但不该抢走交互通道。
     */
    private fun isInteractive(op: String): Boolean = when (op) {
        "search", "info", "chapters", "content", "login", "accountInfo", "retryImage" -> true
        else -> false
    }

    /**
     * 派发一次调用（注册源 + evaluateJavascript）。
     *
     * 与等待分离：派发失败时要把 [deferred] 立即判失败，好让 [callOnce] 的
     * 收尾逻辑不必白等。
     *
     * [dispatchMutex] 只覆盖「拼脚本 + 投递」这一小段（不含任何网络等待），
     * 保证同源并发调用时注册语句一定先于调用语句在 JS 线程落地。
     */
    private suspend fun dispatch(
        sourceKey: String,
        op: String,
        callId: String,
        deferred: CompletableDeferred<String>,
        vararg args: Any?,
    ) {
        val dispatchStartedAt = System.currentTimeMillis()
        try {
            dispatchMutex.withLock {
                val lockedAt = System.currentTimeMillis()
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
                // 派发耗时（v1.8.9.5）：交互调用卡 30 秒时，必须先分清是卡在
                // 「等锁 / 等主线程派发」还是「JS 里真的跑不完」——
                // 以前这两种情况在日志里长得一模一样，只能靠猜。
                val dispatchMs = System.currentTimeMillis() - dispatchStartedAt
                if (dispatchMs > DISPATCH_SLOW_MS) {
                    android.util.Log.w(
                        LOG_TAG,
                        "dispatch 偏慢 ${dispatchMs}ms（等锁 ${lockedAt - dispatchStartedAt}ms）op=$op",
                    )
                }
            }
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
                    // 在飞的异步 HTTP 结果无人认领了，一并丢弃
                    httpResults.clear()
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
            // 只保留级别与长度，避免日志里出现具体的源与站点。
            // 例外：我们自己的 PROBE 诊断（v1.8.9.5）只含操作名、耗时、init 状态与
            // 已抹掉 URL 的栈帧，原样输出 —— 详情/目录/正文失败时靠它一眼分清
            // 「卡在等 init」「卡在发请求」还是「源自己的代码抛了」。
            if (message.contains("PROBE")) {
                android.util.Log.d("VeneraProbe", message)
                return
            }
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

        /**
         * 异步 HTTP：立刻返回 reqId，请求在原生 IO 线程池里跑，完成后回调 JS。
         *
         * 这是 v1.8.8.8 并行的核心 —— 见类注释。JS 侧拿到 reqId 后挂一个 Promise，
         * 原生完成时 `evaluateJavascript("__veneraHttpDone(reqId)")`，JS 再同步调
         * [httpTake] 把结果取走（取走是一次 map 查表，不再占着 JS 线程等网络）。
         *
         * 所有异常都在这里收成 `{"error": "..."}` JSON，与同步桥的返回形状一致，
         * 源侧 `if (res.error) throw` 的逻辑不用改。
         */
        @JavascriptInterface
        fun httpAsync(
            method: String,
            url: String,
            headersJson: String?,
            data: String?,
            binary: Boolean,
        ): String {
            val reqId = "h" + httpSeq.incrementAndGet()
            val startedAt = System.currentTimeMillis()
            try {
                httpScope.launch {
                    try {
                        val gateAt = System.currentTimeMillis()
                        httpGate.withPermit {
                            val gateWait = System.currentTimeMillis() - gateAt
                            if (gateWait > 1_000) {
                                android.util.Log.w("VeneraNet", "HTTP 闸门等了 ${gateWait}ms")
                            }
                            var failed = false
                            val json = runCatching {
                                doHttp(method, url, headersJson, data, binary)
                            }.getOrElse {
                                failed = true
                                gson.toJson(mapOf("error" to friendly(it)))
                            }
                            // 诊断日志：不打 URL / 域名（源与站点信息不进日志），
                            // 只留耗时与成败 —— 「并行到底有没有生效」全靠它判断
                            android.util.Log.d(
                                "VeneraNet",
                                "$method ${System.currentTimeMillis() - startedAt}ms " +
                                    "${json.length} 字符" + if (failed) " [失败]" else "",
                            )
                            // 结果体积上限与同步桥一致；超限直接替换成错误 JSON，
                            // 免得把超大串留在内存里等一个可能永远不会来的取件
                            if (json.length > MAX_RESULT_CHARS) {
                                httpResults[reqId] = gson.toJson(
                                    mapOf("error" to "响应体过大（${json.length} 字符）"),
                                )
                            } else {
                                httpResults[reqId] = json
                            }
                        }
                    } finally {
                        deliverHttp(reqId)
                    }
                }
            } catch (e: Throwable) {
                // 线程池都排不进（几乎不会发生）：立刻给出错误，别让 JS 侧永久等待
                httpResults[reqId] = gson.toJson(mapOf("error" to friendly(e)))
                deliverHttp(reqId)
            }
            return reqId
        }

        /**
         * JS 侧取走异步 HTTP 的结果（同步、O(1)）。
         *
         * @return 响应 JSON；已被取走 / 从未存在时返回 null（JS 侧据此判失败）
         */
        @JavascriptInterface
        fun httpTake(reqId: String): String? = httpResults.remove(reqId)

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
         * 读章节（`content`）的执行上界（v1.8.9.5）。
         *
         * 比普通调用宽一倍：部分源的 `loadEp` 是「一页一次请求」的循环，
         * 一章几十页就是几十次往返，30 秒必然取不完 —— 用户侧看到的就是
         * 「章节一直转圈 / 加载不出内容」。放宽后这些源能真正打开。
         *
         * 放宽不会回到「卡死源堵住引擎」的老问题：超时后 [abortJsCall] 会通知
         * JS 打断源的循环，不再让它在后台继续发请求。
         */
        private const val CONTENT_TIMEOUT_MS = 60_000L

        /** 预热 ping 的执行上界：只是「顺手跑掉 init」，不值得占着通道太久 */
        private const val PING_TIMEOUT_MS = 15_000L

        /**
         * 后台批量调用（标签召回等）的执行上界（v1.8.9.2）。
         *
         * 召回本来就是「能出多少算多少」，硬截止也只有 30s；单个源卡满 30s 毫无收益，
         * 却会长时间占着后台通道、连带把共享 JS 线程排满，让用户的点击排在后面。
         * 压到 15s 与一次 HTTP 的 callTimeout 同量级，卡死的源尽快让位。
         */
        private const val BACKGROUND_CALL_TIMEOUT_MS = 15_000L

        /**
         * 后台任务为交互调用让路的最长等待（v1.8.9.4）。
         *
         * 有上界才不会把后台工作永久饿死：用户点开详情看完就退，或点开之后一直停在
         * 那一页，后台召回最多让这么久就继续跑（那时用户已经在看内容，不再感知召回）。
         */
        private const val YIELD_TO_INTERACTIVE_MS = 3_000L

        /** 让路期间的轮询间隔：够快跟上交互调用的起落，又不至于空转 */
        private const val YIELD_POLL_MS = 50L

        /**
         * 交互通道并发数（v1.8.8.8）。
         *
         * 旧实现是"一次只能跑一个"，因为同步 HTTP 桥会占满 JS 线程。异步桥之后
         * 一次调用只在「读写 JS 的那一小段」占用 JS 线程，剩下的时间都在等网络，
         * 所以并发才有意义。12 是按上层标签搜索的 10 路并发（TagSearchViewModel
         * 的 `VENERA_TAG_CONCURRENCY`）留出余量定的：搜索占满时，用户点开一本书
         * 仍然有 2 个槽可以立刻用上。
         */
        private const val MAX_CONCURRENT_INTERACTIVE = 12

        /**
         * 后台通道并发数：首屏聚合 + 预热共用。
         *
         * 6 = 上层首屏聚合的 8 路并发里绝大多数都能立刻进场，又不至于在冷启动
         * 时一次性打出几十个请求。预热是串行的（同一时刻只占 1 个），剩下的
         * 5 个额度留给 explore —— 首页推荐不必等预热跑完。
         */
        private const val MAX_CONCURRENT_BACKGROUND = 6

        /** 异步 HTTP 的原生并发数：要能容纳「12 路调用各自并行发 2 个请求」 */
        private const val MAX_CONCURRENT_HTTP = 24

        /** 异步 HTTP 结果表的软上限，超出时清理最旧的一半（见 [deliverHttp]） */
        private const val MAX_PENDING_HTTP_RESULTS = 64

        /** 引擎闸门的排队上界：等不上就快速失败，调用方按普通错误处理 */
        private const val ENGINE_QUEUE_WAIT_MS = 25_000L

        /** 派发超过这个时长就记一条警告（见 [dispatch] 里的耗时埋点） */
        private const val DISPATCH_SLOW_MS = 1_000L

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
