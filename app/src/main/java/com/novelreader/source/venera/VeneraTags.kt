package com.novelreader.source.venera

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Venera 源响应里 `tags` 的归一化（v1.7.0.0）。
 *
 * 真实源里 `tags` 有两种写法，**详情响应几乎都是后者**：
 * - 数组：`["玄幻", "热血"]`（搜索列表结果多数是这种）
 * - 对象：`{ 作者: ["xxx"], 类型: ["热血"], 标签: ["本地"] }`
 *   —— Venera `ComicDetails.tags` / `Comic.tags` 的标准结构
 *   （kavita 用「作者 / 类型 / 标签」，包子漫画用「作者 / 标签」）
 *
 * 以前这里直接按数组解析（`getAsJsonArray`），遇到对象会抛
 * `IllegalStateException: Not a JSON Array`，把整个源的搜索 / 详情一起带崩。
 *
 * 对象形态摊平时丢掉**元信息分组**（作者 / 别名 / 提示）：作者在详情页本来就单独展示，
 * 当成标签只会重复。分组顺序保留，便于「类型」「标签」按源给的原序展示。
 */
object VeneraTags {

    /**
     * 不当标签展示的分组名（与 `runtime.js` 的 `TAG_GROUP_SKIP` 同口径）。
     * JS 侧已先归一过一遍，这里是第二道防线（防止绕过序列化层的响应）。
     */
    val GROUP_SKIP = setOf("作者", "作者名", "别名", "提示", "author", "authors")

    /**
     * 把 `tags` 元素摊平成标签名列表（保序去重）。
     *
     * @param el 源响应里的 `tags` 元素；null / JSON null / 其它形状一律返回空列表
     */
    fun parse(el: JsonElement?): List<String> {
        if (el == null || el.isJsonNull) return emptyList()
        val out = mutableListOf<String>()
        when {
            el.isJsonArray -> el.asJsonArray.forEach { addName(it, out) }
            el.isJsonObject -> el.asJsonObject.entrySet().forEach { (group, value) ->
                if (group.trim() in GROUP_SKIP) return@forEach
                if (value.isJsonArray) value.asJsonArray.forEach { addName(it, out) }
                else addName(value, out)
            }
            el.isJsonPrimitive -> addName(el, out)
        }
        return out
    }

    /** 单个标签元素 → 名字：字符串直接用，对象取 name / title / label */
    private fun addName(el: JsonElement?, out: MutableList<String>) {
        val name = when {
            el == null || el.isJsonNull -> return
            // 只认字符串（含裸字符串 `tags: "都市"`）；数字 / 布尔是坏数据，不当标签
            el.isJsonPrimitive -> {
                val p = el.asJsonPrimitive
                if (!p.isString) return
                p.asString
            }
            el.isJsonObject -> {
                val o = el.asJsonObject
                o.strOrNull("name") ?: o.strOrNull("title") ?: o.strOrNull("label")
            }
            else -> null
        }?.trim().orEmpty()
        if (name.isNotEmpty() && name !in out) out += name
    }

    /** 取对象里的字符串字段（非字符串 / 缺失 → null） */
    private fun JsonObject.strOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
