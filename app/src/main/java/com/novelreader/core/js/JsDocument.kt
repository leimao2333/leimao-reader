package com.novelreader.core.js

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.annotations.JSFunction
import org.mozilla.javascript.annotations.JSGetter

/**
 * JS 可访问的 DOM 文档对象（封装 jsoup）。
 *
 * 提供与浏览器类似的查询 API：
 * - querySelector(selector)  返回单个元素
 * - querySelectorAll(selector) 返回元素数组
 *
 * 元素（[JsElement]）提供：
 * - text() / html() / getAttribute(name)
 */
class JsDocument(html: String) : ScriptableObject() {

    private val doc: Document = Jsoup.parse(html)

    override fun getClassName(): String = "JsDocument"

    @JSFunction
    fun querySelector(selector: String): Any {
        val el = doc.selectFirst(selector) ?: return Context.getUndefinedValue()
        return JsElement(el)
    }

    @JSFunction
    fun querySelectorAll(selector: String): Any {
        val elements: Elements = doc.select(selector)
        val array = NativeArray(elements.size.toLong())
        val scope = Context.getCurrentContext()?.initSafeStandardObjects()
        elements.forEachIndexed { index, element ->
            array.put(index, array, JsElement(element))
        }
        return array
    }

    @JSFunction
    fun text(): String = doc.text()

    @JSFunction
    fun html(): String = doc.html()
}

/**
 * JS 可访问的 DOM 元素对象。
 */
class JsElement(private val element: Element) : ScriptableObject() {

    override fun getClassName(): String = "JsElement"

    @JSFunction
    fun text(): String = element.text()

    @JSFunction
    fun html(): String = element.html()

    @JSFunction
    fun getAttribute(name: String): String = element.attr(name)

    @JSGetter
    fun getAttr(): Scriptable {
        val context = Context.getCurrentContext()
        val obj = context?.newObject(this) ?: return this
        element.attributes().forEach { attr ->
            obj.put(attr.key, obj, attr.value)
        }
        return obj
    }

    @JSFunction
    fun querySelector(selector: String): Any {
        val el = element.selectFirst(selector) ?: return Context.getUndefinedValue()
        return JsElement(el)
    }

    @JSFunction
    fun querySelectorAll(selector: String): Any {
        val elements = element.select(selector)
        val array = NativeArray(elements.size.toLong())
        elements.forEachIndexed { index, el ->
            array.put(index, array, JsElement(el))
        }
        return array
    }
}
