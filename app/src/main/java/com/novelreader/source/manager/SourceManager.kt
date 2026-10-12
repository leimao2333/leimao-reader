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
class SourceManager(
    /**
     * Venera 源运行时（隐藏 WebView）。
     *
     * 为 null 时仍可导入 Venera 源（会持久化），只是执行时会报「运行时不可用」。
     */
    private val veneraEngine: com.novelreader.source.venera.VeneraEngine? = null,
) {

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

        // Venera 漫画源（独立运行时）
        if (format == SourceFormat.VENERA) {
            return importVenera(rawContent, fileName)
        }

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

    /** 导入 Venera 源（单个 .js 文件） */
    private fun importVenera(code: String, fileName: String?): List<ImportedSource> {
        val engine = veneraEngine
            ?: throw IllegalArgumentException("Venera 运行时不可用，无法导入该源")
        val info = toVeneraInfo(code, fileName)
        val adapter = com.novelreader.source.venera.VeneraSourceAdapter(info, code, engine)
        engine.putSource(info.id, code)
        registerSource(info, adapter, code)
        return listOf(ImportedSource(info, code))
    }

    /** 从源码里抽取 Venera 源的元信息（name / key / version 都是类字段字面量） */
    private fun toVeneraInfo(code: String, fileName: String?): SourceInfo {
        val name = Regex("""\bname\s*=\s*["'`]([^"'`]*)["'`]""").find(code)?.groupValues?.getOrNull(1).orEmpty()
        val key = Regex("""\bkey\s*=\s*["'`]([^"'`]*)["'`]""").find(code)?.groupValues?.getOrNull(1).orEmpty()
        val version = Regex("""\bversion\s*=\s*["'`]([^"'`]*)["'`]""").find(code)?.groupValues?.getOrNull(1).orEmpty()

        // id 约定：由文件名推导（见 VeneraSourceId），保证「重启恢复」与「重复导入」都能命中同一条，
        // 同时避免 index.json 里 key 重复的条目互相覆盖
        val id = com.novelreader.source.venera.VeneraSourceId.fromFileName(fileName)
            ?: ("venera_" + (key.ifBlank { name }.hashCode().toString().replace("-", "_")))
        return SourceInfo(
            id = id,
            name = name.ifBlank { key.ifBlank { "Venera 源" } },
            type = SourceType.COMIC,
            version = version.ifBlank { "venera" },
            author = key,
            desc = "",
            baseUrl = "",
            format = SourceFormat.VENERA,
            category = "漫画",
        )
    }

    private fun toSourceInfo(src: LegadoBookSource, fileName: String?, index: Int): SourceInfo {
        // 类型识别：优先 bookSourceType，其次按 group/名称关键词兜底判断（很多动漫/视频源 bookSourceType 仍为 0）
        val type = when (src.type) {
            2 -> SourceType.COMIC
            3 -> SourceType.VIDEO
            else -> inferTypeFromGroup(src)
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

    /**
     * 根据源的 group/名称/描述关键词推断内容类型。
     * 用于 bookSourceType 无法区分（=0）时的兜底，确保动漫搜索只用动漫源、视频搜索只用视频源。
     */
    private fun inferTypeFromGroup(src: LegadoBookSource): SourceType {
        val text = listOf(src.group, src.name, src.comment)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

        val comicHints = listOf("漫画", "动漫", "动画", "二次元", "番剧", "manga", "manhua", "comic", "anime", "bili")
        val videoHints = listOf("视频", "影视", "电影", "电视剧", "综艺", "片库", "video", "movie", "tv", "film", "直播")

        val comicScore = comicHints.count { text.contains(it) }
        val videoScore = videoHints.count { text.contains(it) }

        return when {
            comicScore > 0 && comicScore >= videoScore -> SourceType.COMIC
            videoScore > 0 && videoScore > comicScore -> SourceType.VIDEO
            else -> SourceType.NOVEL
        }
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
        veneraEngine?.removeSource(sourceId)
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

    /** 获取源原始内容（供编辑） */
    fun getRawContent(sourceId: String): String? = rawContentCache[sourceId]

    /**
     * 更新源的规则内容：用新原始内容重新解析、重建适配器并注册。
     *
     * 采用「先解析成功再替换」策略：新内容解析/构建失败会抛异常，
     * 原源保持不变，不会因编辑错误把源改坏。
     *
     * @return 更新后的 SourceInfo
     */
    fun updateSource(sourceId: String, rawContent: String): SourceInfo {
        // 关键：保留原源的 id 与启停状态，只替换规则。
        // 新内容解析出的 info 可能有新的 id，需要强制沿用原 id。
        val oldInfo = sourceMap[sourceId]
            ?: throw IllegalArgumentException("源不存在：$sourceId")

        val validateResult = SourceValidator.validate(rawContent)
        if (!validateResult.valid) {
            throw IllegalArgumentException(validateResult.message)
        }
        val format = validateResult.format
            ?: throw IllegalArgumentException("无法识别源格式")

        // 先解析构建新适配器（失败则抛异常，原源不动）
        val newAdapter: SourceAdapter = when (format) {
            SourceFormat.VENERA -> {
                val engine = veneraEngine
                    ?: throw IllegalArgumentException("Venera 运行时不可用，无法更新该源")
                val info = toVeneraInfo(rawContent, null).copy(id = oldInfo.id)
                engine.putSource(oldInfo.id, rawContent)
                com.novelreader.source.venera.VeneraSourceAdapter(info, rawContent, engine)
            }
            SourceFormat.LEGADO -> {
                val pairs = LegadoBookSource.parseListWithRaw(rawContent)
                if (pairs.isEmpty()) throw IllegalArgumentException("未解析到有效书源")
                // legado 源：取第一个书源对象（编辑场景按单源处理）
                val (src, singleJson) = pairs.first()
                val info = toSourceInfo(src, null, 0)
                LegadoSourceAdapter(info.copy(id = oldInfo.id), src)
            }
            else -> {
                val loader = loaders[format]
                    ?: throw IllegalArgumentException("不支持的源格式：$format")
                val parser = parsers[format]
                    ?: throw IllegalArgumentException("不支持的源格式：$format")
                val info = loader.load(rawContent, null)
                parser.createAdapter(info.copy(id = oldInfo.id), rawContent)
            }
        }

        // 构建新的 info：沿用原 id、启停状态与分类
        val newInfo = (newAdapter.info).copy(
            id = oldInfo.id,
            enabled = oldInfo.enabled,
            category = oldInfo.category.ifBlank {
                com.novelreader.source.category.SourceCategory.classify(newAdapter.info)
            },
        )

        // 替换注册（先注销旧，再注册新，保证 adapter 与 info 一致）
        unregisterSource(sourceId)
        registerSource(newInfo, newAdapter, rawContent)
        return newInfo
    }

    fun clear() {
        sourceMap.clear()
        adapterCache.clear()
        rawContentCache.clear()
    }
}
