package com.novelreader.source.parser

import com.novelreader.core.js.SourceJsBridge
import com.novelreader.core.js.SourceJsEngine
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo

/**
 * JS 源解析器（工厂）。
 *
 * 执行 JS 源脚本，构建 [JsSourceAdapter]。
 * 每个源拥有独立作用域（scope），互不干扰。
 */
class JsSourceParser : SourceParser {

    override val format: SourceFormat = SourceFormat.JS

    override fun createAdapter(info: SourceInfo, rawContent: String): SourceAdapter {
        val scope = SourceJsEngine.createScope()
        SourceJsBridge.inject(scope)
        SourceJsEngine.evaluate(scope, rawContent)
        return JsSourceAdapter(info, scope)
    }
}
