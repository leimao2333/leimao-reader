package com.novelreader.core.js

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

/**
 * Rhino JS 引擎封装。
 *
 * 用于执行 JS 源。提供：
 * - 隔离的脚本上下文（每个源一个作用域，互不干扰）
 * - 内置辅助函数注入（fetch / parseHtml / log 等）
 *
 * TODO(M2): 完善 — 沙箱安全限制、超时控制、内置辅助函数实现。
 */
object SourceJsEngine {

    /**
     * 创建一个独立的 JS 执行环境。
     * 每个源调用一次，获得独立作用域。
     */
    fun createScope(): ScriptableObject {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1 // 解释执行，兼容性最好
            val scope = context.initSafeStandardObjects()
            // TODO(M2): 注入 fetch、parseHtml、log 等内置函数
            return scope
        } finally {
            Context.exit()
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
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            return context.evaluateString(scope, script, "<source>", 1, null)
        } finally {
            Context.exit()
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
     * 将 Rhino 返回值解包为 Kotlin 对象（Map / List / 基本类型）。
     * TODO(M2): 递归解包 NativeObject / NativeArray。
     */
    fun unwrap(value: Any?): Any? = Context.jsToJava(value, Any::class.java)
}
