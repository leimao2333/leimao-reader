package com.novelreader.core.js

import com.novelreader.core.network.HttpClient
import org.mozilla.javascript.Context
import org.mozilla.javascript.FunctionObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * JS 源内置辅助函数注入器。
 *
 * 向 Rhino 作用域注入 JS 源可用的辅助函数：
 * - fetch(url, headers?) — 发起 HTTP 请求并返回响应文本
 * - parseHtml(html)      — 解析 HTML 为可查询的 DOM 对象（基于 jsoup 封装）
 * - log(msg)             — 日志输出
 *
 * 使用 [FunctionObject] 直接绑定 Java 静态方法，避免复杂的 defineClass 机制。
 */
object SourceJsBridge {

    /**
     * 向作用域注入内置函数。
     */
    fun inject(scope: ScriptableObject) {
        val context = Context.getCurrentContext() ?: Context.enter()
        try {
            // fetch(url) 单参数
            scope.put(
                "fetch",
                scope,
                FunctionObject("fetch", BridgeMethods::class.java.getMethod("fetch", String::class.java), scope),
            )
            // fetch(url, headers) 双参数（通过统一方法处理）
            scope.put(
                "fetchWithHeaders",
                scope,
                FunctionObject(
                    "fetchWithHeaders",
                    BridgeMethods::class.java.getMethod("fetchWithHeaders", String::class.java, Scriptable::class.java),
                    scope,
                ),
            )
            // parseHtml
            scope.put(
                "parseHtml",
                scope,
                FunctionObject(
                    "parseHtml",
                    BridgeMethods::class.java.getMethod("parseHtml", String::class.java),
                    scope,
                ),
            )
            // log
            scope.put(
                "log",
                scope,
                FunctionObject(
                    "log",
                    BridgeMethods::class.java.getMethod("log", String::class.java),
                    scope,
                ),
            )
        } catch (e: Exception) {
            android.util.Log.e("SourceJsBridge", "注入内置函数失败", e)
        } finally {
            if (context !== Context.getCurrentContext()) {
                Context.exit()
            }
        }
    }
}

/**
 * 桥接方法（静态，供 FunctionObject 绑定）。
 */
object BridgeMethods {

    @JvmStatic
    fun fetch(url: String): String = HttpClient.get(url)

    @JvmStatic
    fun fetchWithHeaders(url: String, headers: Scriptable): String {
        val map = mutableMapOf<String, String>()
        val keys = headers.ids
        keys.forEach { key ->
            val value = headers.get(key as String, headers)
            if (value != null && value !is org.mozilla.javascript.Undefined) {
                map[key.toString()] = Context.toString(value)
            }
        }
        return HttpClient.get(url, map)
    }

    @JvmStatic
    fun parseHtml(html: String): JsDocument = JsDocument(html)

    @JvmStatic
    fun log(msg: String) {
        // 源自己打出的日志内容不可控（常直接带站点名 / 接口地址），只记录长度
        android.util.Log.d("SourceJsEngine", com.novelreader.core.util.LogSanitizer.lengthOnly(msg))
    }
}
