package com.novelreader.core.js

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * JS 执行超时。
 *
 * 由 [SourceJsEngine.evaluateWithTimeout] / [SourceJsEngine.callFunctionWithTimeout] 抛出，
 * 调用方可用 `runCatching { ... }` 统一处理。
 */
class JsTimeoutException(message: String) : RuntimeException(message)

/**
 * Rhino JS 引擎封装。
 *
 * 用于执行 JS 源。提供：
 * - 隔离的脚本上下文（每个源一个作用域，互不干扰）
 * - 执行源码、调用作用域内函数、Rhino 返回值解包
 * - 带超时看门狗的执行入口（B3，v1.7.x）：源里出现 `while(true){}` 时不会拖死调用方
 *
 * 注：内置辅助函数（fetch / parseHtml / log）由 [SourceJsBridge] 注入，
 * 通过 [JsSourceLoader] / [JsSourceAdapter] 完成端到端调用。
 */
object SourceJsEngine {

    /**
     * 全局执行锁。
     *
     * Rhino 的 ScriptableObject 作用域**不是线程安全的**：多个线程并发对同一 scope 调用
     * 函数（如跨源并行搜索时），会破坏 Rhino 内部状态，抛出不可预料的 Error（如
     * StackOverflowError / ClassCastException），而 runCatching 只能捕获 Exception、
     * 捕获不到 Error，最终表现为「测试源闪退」。
     *
     * 这里用一把全局互斥锁，串行化所有 JS 执行，从根本上消除并发崩溃。
     * JS 源执行本身耗时极短，全局锁对性能影响可忽略。
     */
    private val ENGINE_LOCK = Any()

    /**
     * 看门狗默认预算（也是全项目 JS 执行的统一超时值）。
     *
     * B3：暴露为 public 供调用方（JsSourceAdapter / JsSourceLoader / JsSourceParser）
     * 复用，避免各处硬编码 15_000 导致口径漂移。项目里没有统一的偏好项存 JS 超时
     * （PreferencesManager 由其它组维护，不在此改动），故沿用引擎既有的 15s 默认值。
     */
    const val DEFAULT_TIMEOUT_MS = 15_000L

    /**
     * 看门狗执行器：**单线程 + daemon**。
     *
     * - 单线程：Rhino 的 [Context] 是 ThreadLocal 绑定的，且 scope 非线程安全，
     *   串行执行才是安全形态；同时保证同一时刻只有一个带超时的任务在跑。
     * - daemon：即使某个源真的死循环卡住，也不会阻止 JVM 退出。
     */
    private val WATCHDOG: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "novelreader-js-watchdog").apply { isDaemon = true }
        }
    }

    /**
     * 创建一个独立的 JS 执行环境。
     * 每个源调用一次，获得独立作用域。
     */
    fun createScope(): ScriptableObject {
        synchronized(ENGINE_LOCK) {
            val context = Context.enter()
            try {
                context.optimizationLevel = -1 // 解释执行，兼容性最好
                val scope = context.initSafeStandardObjects()
                return scope
            } finally {
                Context.exit()
            }
        }
    }

    /**
     * 在指定作用域中执行 JS 代码。
     *
     * @param scope 由 [createScope] 创建的作用域
     * @param script JS 源码
     * @return 执行结果（源通常返回元信息对象）
     */
    fun evaluate(scope: ScriptableObject, script: String): Any? {
        synchronized(ENGINE_LOCK) {
            return evaluateLocked(scope, script)
        }
    }

    /**
     * 调用作用域中的某个函数。
     *
     * @param scope 作用域
     * @param funcName 函数名
     * @param args 参数
     * @return 函数返回值（经 Rhino 包装，需解包为 Kotlin 类型）
     */
    fun callFunction(scope: ScriptableObject, funcName: String, vararg args: Any?): Any? {
        synchronized(ENGINE_LOCK) {
            return callFunctionLocked(scope, funcName, args)
        }
    }

    /**
     * [evaluate] 的带超时版本（B3）。
     *
     * 在独立的看门狗线程里执行，超过 [timeoutMs] 抛 [JsTimeoutException]。
     */
    fun evaluateWithTimeout(
        scope: ScriptableObject,
        script: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Any? = runWithTimeout(timeoutMs) {
        synchronized(ENGINE_LOCK) { evaluateLocked(scope, script) }
    }

    /**
     * [callFunction] 的带超时版本（B3）。
     *
     * 在独立的看门狗线程里执行，超过 [timeoutMs] 抛 [JsTimeoutException]。
     */
    fun callFunctionWithTimeout(
        scope: ScriptableObject,
        funcName: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        vararg args: Any?,
    ): Any? = runWithTimeout(timeoutMs) {
        synchronized(ENGINE_LOCK) { callFunctionLocked(scope, funcName, args) }
    }

    /**
     * 将 Rhino 返回值解包为 Kotlin 对象（Map / List / 基本类型）。
     */
    fun unwrap(value: Any?): Any? = synchronized(ENGINE_LOCK) {
        Context.jsToJava(value, Any::class.java)
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 真正进入 Rhino 执行脚本（调用方必须已持有 [ENGINE_LOCK]） */
    private fun evaluateLocked(scope: ScriptableObject, script: String): Any? {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            return context.evaluateString(scope, script, "<source>", 1, null)
        } finally {
            Context.exit()
        }
    }

    /** 真正进入 Rhino 调用函数（调用方必须已持有 [ENGINE_LOCK]） */
    private fun callFunctionLocked(
        scope: ScriptableObject,
        funcName: String,
        args: Array<out Any?>,
    ): Any? {
        val context = Context.enter()
        try {
            val func = scope.get(funcName, scope)
            if (func is org.mozilla.javascript.Function) {
                val jsArgs = args.map { Context.javaToJS(it, scope) }.toTypedArray()
                return func.call(context, scope, scope, jsArgs)
            }
            return null
        } finally {
            Context.exit()
        }
    }

    /**
     * 看门狗核心：把任务丢进单线程池，主调线程带超时等待。
     *
     * 局限（务必了解）：
     * Rhino 1.7.x 的解释器**没有**可用的单次执行超时开关。真正的硬超时需要
     * `Context.setInstructionObserverThreshold` + 自定义 `ContextFactory`
     * 的 `observeInstructionCount` 抛异常，但那必须替换**全局** ContextFactory
     * （`ContextFactory.initGlobal` 在已有 Context 后会抛 IllegalStateException，
     * 时序不可控且影响 legado 规则引擎等其它 Rhino 调用点），风险大于收益，
     * 因此这里只做「尽力而为」的看门狗：
     *
     * - 纯 JS 死循环（`while(true){}`）不响应 [Thread.interrupt]，
     *   `cancel(true)` 无法真正杀死它 —— 该任务会继续占用看门狗线程，
     *   后续带超时调用依次在各自预算内超时失败，不会拖住 UI 线程。
     * - 卡在网络 / 阻塞调用（fetch、sleep）里的任务能被中断，可正常回收。
     *
     * 也就是说：本机制保证**调用方**永不被无限期阻塞，但不保证回收失控的解释器线程。
     */
    private fun runWithTimeout(timeoutMs: Long, block: () -> Any?): Any? {
        val budget = timeoutMs.coerceAtLeast(1L)
        // 显式声明为 Callable，避免与 submit(Runnable) 重载产生歧义
        val task: Callable<Any?> = Callable<Any?> { block() }
        val future: Future<Any?> = WATCHDOG.submit(task)
        return try {
            future.get(budget, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw JsTimeoutException("JS 执行超过 ${budget}ms 已中止（源可能存在死循环或阻塞调用）")
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw JsTimeoutException("JS 执行被中断")
        } catch (e: ExecutionException) {
            // 保留原始异常（含 JsTimeoutException / Rhino 的 EvaluatorException），便于上层分类提示
            throw e.cause ?: e
        }
    }
}
