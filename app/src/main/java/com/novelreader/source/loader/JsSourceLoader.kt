package com.novelreader.source.loader

import com.novelreader.core.js.SourceJsBridge
import com.novelreader.core.js.SourceJsEngine
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import java.util.UUID

/**
 * JS 源加载器。
 *
 * 通过 Rhino 执行 JS 源的 getMeta() 函数，解析为统一的 [SourceInfo]。
 */
class JsSourceLoader : SourceLoader {

    override val format: SourceFormat = SourceFormat.JS

    override fun load(rawContent: String, fileName: String?): SourceInfo {
        val scope = SourceJsEngine.createScope()
        SourceJsBridge.inject(scope)
        SourceJsEngine.evaluate(scope, rawContent)

        // 调用 getMeta() 获取元信息
        val metaResult = SourceJsEngine.callFunction(scope, "getMeta")
        val meta = metaResult as? Map<*, *>
            ?: throw IllegalArgumentException("getMeta() 返回格式错误")

        val name = meta["name"]?.toString() ?: throw IllegalArgumentException("缺少 name")
        val typeStr = meta["type"]?.toString()
        val type = SourceType.fromString(typeStr)
            ?: throw IllegalArgumentException("无效的内容类型：$typeStr")

        val id = fileName?.substringBeforeLast(".")?.ifBlank { null }
            ?: "js_${UUID.randomUUID().toString().take(8)}"

        return SourceInfo(
            id = id,
            name = name,
            type = type,
            version = meta["version"]?.toString() ?: "1.0.0",
            author = meta["author"]?.toString() ?: "",
            desc = meta["desc"]?.toString() ?: "",
            baseUrl = meta["baseUrl"]?.toString() ?: "",
            format = format,
        )
    }
}
