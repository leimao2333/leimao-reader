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
import kotlinx.coroutines.flow.asStateFlow

/**
 * 源的「优先级顺序」：手动排过序的靠前（按序号升序），未排序的排后按名称。
 *
 * 这个顺序**就是**「先问哪个源」的顺序 —— 首页推荐枚举按它决定 `take(limit)` 截到谁、
 * 同名栏目跨源聚合时谁排前面。此前直接返回 `sourceMap` 的插入顺序（= 数据库
 * `ORDER BY createdAt DESC`，即「最近导入的在前」），既不受用户「手动排序」影响，
 * 也会随导入先后整体漂移。与源管理页 [SourceSortOrder.MANUAL] 用同一套比较器。
 */
private val SOURCE_PRIORITY: Comparator<SourceInfo> =
    compareBy<SourceInfo> { it.sortOrder == 0 }
        .thenBy { it.sortOrder }
        .thenBy { it.name }

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

    /**
     * 最近一次 importSource 中被跳过的条目数（legado 列表里解析失败的条目）。
     * 导入方（ViewModel）在导入完成后读取并拼进结果消息，向用户明示「跳过 N 个」，
     * 避免误以为整份文件全部导入成功（v1.3.0.0「部分小说源不加载」排查产物）。
     */
    @Volatile
    var lastImportSkipped: Int = 0
        private set

    /**
     * 启动恢复完成信号。
     *
     * 冷启动时源从数据库异步恢复，深链自动搜索等入口可能在恢复完成前触发，
     * 导致「源表为空 → 搜索 0 源 → 未找到结果」的竞态（v1.2.11.0 视频深链测试发现）。
     * 搜索入口应先等待此信号为 true 再取源（已完成时立即通过，无额外开销）。
     */
    private val _restored = kotlinx.coroutines.flow.MutableStateFlow(false)
    val restoredFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _restored.asStateFlow()

    /** 标记启动恢复已完成（成功或失败均需调用，避免永久阻塞等待方） */
    fun markRestored() {
        _restored.value = true
    }

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
        val parsed = previewImport(rawContent, fileName)
        return registerParsed(parsed)
    }

    /**
     * 解析源（不注册）。支持单个源或 legado 多源列表 / MoonTV 配置。
     *
     * 与 [importSource] 共享同一套解析逻辑，只是**不写入注册表**，
     * 供「可选添加」流程先行列出候选源、用户勾选后再逐个注册（v1.4.7.0）。
     *
     * @return 解析出的所有源（每个含其原始内容，供后续注册 / 持久化）
     */
    fun previewImport(rawContent: String, fileName: String? = null): List<ImportedSource> {
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

        // MoonTV / LunaTV 采集源（一个配置文件 = 多个视频源）
        if (format == SourceFormat.MOONTV) {
            return importMoonTv(rawContent)
        }

        // legado 格式（单个或列表）
        if (format == SourceFormat.LEGADO) {
            return importLegado(rawContent, fileName)
        }

        // 自研 JSON / JS 格式（单源）
        val loader = loaders[format]
            ?: throw IllegalArgumentException("不支持的源格式：$format")
        val info = loader.load(rawContent, fileName)
        return listOf(ImportedSource(info, rawContent))
    }

    /**
     * 注册一批已解析的源（每个都构建适配器并写入注册表）。
     *
     * [importSource] 与「可选添加」流程共用：前者全量注册，后者注册用户勾选的子集。
     */
    fun registerParsed(parsed: List<ImportedSource>): List<ImportedSource> {
        parsed.forEach { (info, rawContent) ->
            val adapter = buildAdapter(info, rawContent)
            registerSource(info, adapter, rawContent)
        }
        return parsed
    }

    /** 根据已解析的源信息 + 原始内容构建适配器（注册前最后一步） */
    private fun buildAdapter(info: SourceInfo, rawContent: String): SourceAdapter = when (info.format) {
        SourceFormat.LEGADO -> {
            val pairs = LegadoBookSource.parseListWithRaw(rawContent)
            val src = pairs.firstOrNull()?.first
                ?: throw IllegalArgumentException("无法解析源「${info.name}」的规则")
            LegadoSourceAdapter(info, src)
        }
        SourceFormat.MOONTV -> {
            val site = com.novelreader.source.moontv.MoonTvConfig.siteFromRaw(rawContent)
                ?: com.novelreader.source.moontv.MoonTvConfig.parseConfig(rawContent).firstOrNull()
                ?: throw IllegalArgumentException("无法解析源「${info.name}」的规则")
            com.novelreader.source.moontv.MoonTvSourceAdapter(info, site)
        }
        SourceFormat.VENERA -> {
            val engine = veneraEngine
                ?: throw IllegalArgumentException("Venera 运行时不可用，无法导入该源")
            engine.putSource(info.id, rawContent)
            com.novelreader.source.venera.VeneraSourceAdapter(info, rawContent, engine)
        }
        else -> {
            val parser = parsers[info.format]
                ?: throw IllegalArgumentException("不支持的源格式：${info.format}")
            parser.createAdapter(info, rawContent)
        }
    }

    /** 导入 legado 书源（单个或列表） */
    private fun importLegado(rawContent: String, fileName: String?): List<ImportedSource> {
        // 解析出所有 legado 书源对象（保留各自完整原始 JSON）
        val detailed = LegadoBookSource.parseListWithRawDetailed(rawContent)
        val pairs = detailed.pairs
        lastImportSkipped = detailed.skipped
        if (pairs.isEmpty()) {
            throw IllegalArgumentException("未解析到有效书源")
        }
        if (detailed.skipped > 0) {
            // v1.3.0.0：解析失败的条目不再完全静默——留日志 + 供导入消息展示
            android.util.Log.w(
                "SourceManager",
                "legado 列表导入：${detailed.skipped} 个条目解析失败已跳过（共 ${pairs.size + detailed.skipped} 条）",
            )
        }

        val results = mutableListOf<ImportedSource>()
        pairs.forEachIndexed { index, (src, singleJson) ->
            val info = toSourceInfo(src, fileName, index)
            results.add(ImportedSource(info, singleJson))
        }
        return results
    }

    /**
     * 导入 MoonTV / LunaTV 采集源（v1.3.3.0）。
     *
     * 输入可以是 Base58 订阅正文（`*.txt`、`?format=2&source=xxx`），
     * 也可以是原始配置 JSON；一个配置会展开成多个视频源。
     * 单站点 JSON（`{api, name}`）则只注册一个源。
     */
    private fun importMoonTv(rawContent: String): List<ImportedSource> {
        val sites = com.novelreader.source.moontv.MoonTvConfig.parseConfig(rawContent)
            .ifEmpty {
                com.novelreader.source.moontv.MoonTvConfig.siteFromRaw(rawContent)?.let { listOf(it) }
                    ?: emptyList()
            }
        if (sites.isEmpty()) {
            throw IllegalArgumentException("未解析到有效的 MoonTV 采集源")
        }
        lastImportSkipped = 0

        return sites.map { site ->
            val info = SourceInfo(
                id = site.sourceId,
                name = site.name,
                type = SourceType.VIDEO,
                version = "moontv",
                author = site.detail.ifBlank { site.key },
                desc = "",
                baseUrl = site.api,
                format = SourceFormat.MOONTV,
                category = "影视",
            )
            val raw = com.novelreader.source.moontv.MoonTvConfig.siteToRaw(site)
            ImportedSource(info, raw)
        }
    }

    /** 导入 Venera 源（单个 .js 文件） */    private fun importVenera(code: String, fileName: String?): List<ImportedSource> {
        if (veneraEngine == null) {
            throw IllegalArgumentException("Venera 运行时不可用，无法导入该源")
        }
        val info = toVeneraInfo(code, fileName)
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

    /** 设置源的手动排序序号（v1.5.8.0） */
    fun setSourceSortOrder(sourceId: String, sortOrder: Int) {
        sourceMap[sourceId]?.let { info ->
            sourceMap[sourceId] = info.copy(sortOrder = sortOrder)
        }
    }

    fun getAllSources(): List<SourceInfo> = sourceMap.values.toList()

    /**
     * 该类型下**已启用**的源，按 [SOURCE_PRIORITY] 排好序（顺序即优先级）。
     *
     * 调用方（搜索 / 首页推荐枚举）会直接按这个顺序决定「先问哪些源」，所以必须稳定、
     * 且体现用户意图；不要退回 `sourceMap.values` 的原始迭代顺序。
     */
    fun getEnabledSourcesByType(type: SourceType): List<SourceInfo> =
        sourceMap.values.filter { it.type == type && it.enabled }.sortedWith(SOURCE_PRIORITY)

    /** 该类型下全部源（含禁用），同样按 [SOURCE_PRIORITY] 排序 */
    fun getSourcesByType(type: SourceType): List<SourceInfo> =
        sourceMap.values.filter { it.type == type }.sortedWith(SOURCE_PRIORITY)

    /**
     * 预热 Venera 引擎（v1.7.5.0）：串行 ping 该类型下所有 Venera 源。
     *
     * `ping` 不联网，但运行时的 `ensureSource` 会先 `await src.init()` —— 源的
     * init 常联网（拷贝漫画拉接口清单、少年Jump+ 拉 2MB 页面、JM 拉域名表）。
     * 不在后台提前付掉这笔一次性成本，首页聚合每次 explore 都要现场跑 init，
     * 整页推荐会被 init 风暴拖空（实测冷启动整页 0 条）。
     *
     * 引擎侧对预热 ping 用 4s 的排队上界：忙就让位给首页，不抢。
     */
    suspend fun prewarmVenera(type: SourceType) {
        val engine = veneraEngine ?: return
        val keys = sourceMap.values
            .filter { it.type == type && it.format == SourceFormat.VENERA && it.enabled }
            .sortedWith(SOURCE_PRIORITY)
            .map { it.id }
        if (keys.isEmpty()) return
        android.util.Log.i("SourceManager", "Venera 预热：${keys.size} 个源")
        engine.prewarm(keys)
    }

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
            SourceFormat.MOONTV -> {
                val site = com.novelreader.source.moontv.MoonTvConfig.siteFromRaw(rawContent)
                    ?: com.novelreader.source.moontv.MoonTvConfig.parseConfig(rawContent).firstOrNull()
                    ?: throw IllegalArgumentException("未解析到有效的 MoonTV 采集源")
                val info = SourceInfo(
                    id = oldInfo.id,
                    name = site.name,
                    type = SourceType.VIDEO,
                    version = "moontv",
                    author = site.detail.ifBlank { site.key },
                    baseUrl = site.api,
                    format = SourceFormat.MOONTV,
                    category = oldInfo.category.ifBlank { "影视" },
                )
                com.novelreader.source.moontv.MoonTvSourceAdapter(info, site)
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
