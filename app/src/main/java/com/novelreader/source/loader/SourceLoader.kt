package com.novelreader.source.loader

import com.novelreader.domain.model.SourceInfo

/**
 * 源加载器接口。
 *
 * 职责：读取源原始内容（JSON / JS 文本），解析为统一的 [SourceInfo]。
 *
 * 实现类：
 * - JsonSourceLoader（M2）
 * - JsSourceLoader（M2）
 */
interface SourceLoader {

    /** 支持的源格式 */
    val format: com.novelreader.domain.model.SourceFormat

    /**
     * 加载并解析源。
     * @param rawContent 源原始内容
     * @param fileName 源文件名（可选，用于生成 ID）
     * @return 解析后的源元信息
     */
    fun load(rawContent: String, fileName: String? = null): SourceInfo
}
