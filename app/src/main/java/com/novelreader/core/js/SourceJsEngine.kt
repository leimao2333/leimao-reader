package com.novelreader.core.js

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

/**
 * Rhino JS 引擎封装。
 *
 * 用于执行 JS 源。提供：
 * - 隔离的脚本上下文（每个源一个作用域，互不干扰）
 * - 执行源码、调用作用域内函数、Rhino 返回值解包
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
            val context = Context.enter()
            try {
                context.optimizationLevel = -1
                return context.evaluateString(scope, script, "<source>", 1, null)
            } finally {
                Context.exit()
            }
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
    }

    /**
     * 将 Rhino 返回值解包为 Kotlin 对象（Map / List / 基本类型）。
     */
    fun unwrap(value: Any?): Any? = synchronized(ENGINE_LOCK) {
        Context.jsToJava(value, Any::class.java)
    }
}
