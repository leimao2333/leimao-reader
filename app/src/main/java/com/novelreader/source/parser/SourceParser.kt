package com.novelreader.source.parser

import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo

/**
 * 源解析器（工厂）接口。
 *
 * 职责：根据已解析的 [SourceInfo] 和原始内容，构建可调用的 [SourceAdapter]。
 *
 * 实现类：
 * - JsonSourceParser（M2）：解析 JSON 源的选择器规则
 * - JsSourceParser（M2）：通过 Rhino 执行 JS 源
 */
interface SourceParser {

    /** 支持的源格式 */
    val format: com.novelreader.domain.model.SourceFormat

    /**
     * 构建源适配器。
     * @param info 源元信息
     * @param rawContent 源原始内容
     */
    fun createAdapter(info: SourceInfo, rawContent: String): SourceAdapter
}
