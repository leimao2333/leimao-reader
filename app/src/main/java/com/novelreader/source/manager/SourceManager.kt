package com.novelreader.source.manager

import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.legado.LegadoBookSource
import com.novelreader.source.legado.LegadoSourceAdapter
import com.novelreader.source.legado.LegadoSourceLoader
import com.novelreader.source.loader.JsonSourceLoader
import com.novelreader.source.loader.JsSourceLoader
import com.novelreader.source.loader.SourceLoader
import com.novelreader.source.loader.SourceValidator
import com.novelreader.source.parser.JsonSourceParser
import com.novelreader.source.parser.JsSourceParser
import com.novelreader.source.parser.SourceParser

/**
 * 导入结果：一个源及其原始内容。
 */
data class ImportedSource(
    val info: SourceInfo,
    val rawContent: String,
)

/**
 * 源管理器。
 *
 * 统一调度源的生命周期，打通「加载 → 解析 → 适配器」完整链路。
 */
class SourceManager {

    /** 源注册表：sourceId -> SourceInfo */
    private val sourceMap = mutableMapOf<String, SourceInfo>()

    /** 适配器缓存：sourceId -> SourceAdapter */
    private val adapterCache = mutableMapOf<String, SourceAdapter>()

    /** 源原始内容缓存：sourceId -> 原始文本 */
    private val rawContentCache = mutableMapOf<String, String>()

    private val loaders: Map<SourceFormat, SourceLoader> = mapOf(
        SourceFormat.JSON to JsonSourceLoader(),
        SourceFormat.JS to JsSourceLoader(),
    )

    private val parsers: Map<SourceFormat, SourceParser> = mapOf(
        SourceFormat.JSON to JsonSourceParser(),
        SourceFormat.JS to JsSourceParser(),
    )

    private val legadoLoader = LegadoSourceLoader()

    /**
     * 导入源（完整链路）。支持单个源或 legado 多源列表。
     *
     * @return 导入的所有源（每个含其原始内容，供持久化）
     */
    fun importSource(rawContent: String, fileName: String? = null): List<ImportedSource> {
        val validateResult = SourceValidator.validate(rawContent, fileName)
        if (!validateResult.valid) {
            throw IllegalArgumentException(validateResult.message)
        }
        val format = validateResult.format
            ?: throw IllegalArgumentException("无法识别源格式")

        // legado 格式（单个或列表）
        if (format == SourceFormat.LEGADO) {
            return importLegado(rawContent, fileName)
        }

        // 自研 JSON / JS 格式（单源）
        val loader = loaders[format]
            ?: throw IllegalArgumentException("不支持的源格式：$format")
        val info = loader.load(rawContent, fileName)

        val parser = parsers[format]
            ?: throw IllegalArgumentException("不支持的源格式：$format")
        val adapter = parser.createAdapter(info, rawContent)

        registerSource(info, adapter, rawContent)
        return listOf(ImportedSource(info, rawContent))
    }

    /** 导入 legado 书源（单个或列表） */
    private fun importLegado(rawContent: String, fileName: String?): List<ImportedSource> {
        // 解析出所有 legado 书源对象（保留各自完整原始 JSON）
        val pairs = LegadoBookSource.parseListWithRaw(rawContent)
        if (pairs.isEmpty()) {
            throw IllegalArgumentException("未解析到有效书源")
        }

        val results = mutableListOf<ImportedSource>()
        pairs.forEachIndexed { index, (src, singleJson) ->
            val info = toSourceInfo(src, fileName, index)
            val adapter = LegadoSourceAdapter(info, src)
            registerSource(info, adapter, singleJson)
            results.add(ImportedSource(info, singleJson))
        }
        return results
    }

    private fun toSourceInfo(src: LegadoBookSource, fileName: String?, index: Int): SourceInfo {
        val type = when (src.type) {
            2 -> SourceType.COMIC
            3 -> SourceType.VIDEO
            else -> SourceType.NOVEL
        }
        val base = fileName?.substringBeforeLast(".")?.ifBlank { null }
        val id = if (index == 0 && base != null) base
        else "legado_${src.name.hashCode().toString().replace("-", "_")}_${index}"

        val info = SourceInfo(
            id = id,
            name = src.name,
            type = type,
            version = "legado",
            author = src.group,
            desc = src.comment,
            baseUrl = src.url,
            format = SourceFormat.LEGADO,
        )
        return info.copy(
            category = com.novelreader.source.category.SourceCategory.classify(info),
        )
    }

    /** 注册源 */
    fun registerSource(info: SourceInfo, adapter: SourceAdapter, rawContent: String? = null) {
        sourceMap[info.id] = info
        adapterCache[info.id] = adapter
        rawContent?.let { rawContentCache[info.id] = it }
    }

    /** 注销源 */
    fun unregisterSource(sourceId: String) {
        sourceMap.remove(sourceId)
        adapterCache.remove(sourceId)
        rawContentCache.remove(sourceId)
    }

    /** 启用 / 禁用源 */
    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        sourceMap[sourceId]?.let { info ->
            sourceMap[sourceId] = info.copy(enabled = enabled)
        }
    }

    fun getAllSources(): List<SourceInfo> = sourceMap.values.toList()

    fun getEnabledSourcesByType(type: SourceType): List<SourceInfo> =
        sourceMap.values.filter { it.type == type && it.enabled }

    fun getSourcesByType(type: SourceType): List<SourceInfo> =
        sourceMap.values.filter { it.type == type }

    fun getAdapter(sourceId: String): SourceAdapter? = adapterCache[sourceId]

    fun clear() {
        sourceMap.clear()
        adapterCache.clear()
        rawContentCache.clear()
    }
}
