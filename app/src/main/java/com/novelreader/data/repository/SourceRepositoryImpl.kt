package com.novelreader.data.repository

import com.novelreader.data.database.SourceDao
import com.novelreader.data.model.SourceEntity
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.source.manager.ImportedSource
import com.novelreader.source.manager.SourceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 源仓库实现（Data 层，接入 Room 持久化）。
 *
 * - 源导入后写入数据库并注册到 [SourceManager]
 * - 应用启动时从数据库恢复源
 * - 删除/启停状态同步到数据库
 */
class SourceRepositoryImpl(
    private val sourceManager: SourceManager,
    private val sourceDao: SourceDao,
) : SourceRepository {

    override suspend fun getAllSources(): List<SourceInfo> =
        sourceManager.getAllSources()

    override suspend fun getSourcesByType(type: SourceType): List<SourceInfo> =
        sourceManager.getSourcesByType(type)

    /** 等待启动时的源恢复完成（冷启动竞态防护，v1.4.8.0） */
    override suspend fun awaitRestored() {
        if (!sourceManager.restoredFlow.value) {
            sourceManager.restoredFlow.filter { it }.first()
        }
    }

    /** 观察所有源（响应式） */
    fun observeSources(): Flow<List<SourceInfo>> =
        sourceDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun importSource(rawContent: String, fileName: String?): List<SourceInfo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val parsed = sourceManager.previewImport(rawContent, fileName)
            // v1.7.2.0：写库前先与库里已有的源比对，重复的直接跳过 / 覆盖（见 [planImport]）
            val imported = sourceManager.registerParsed(planImport(parsed))

            // 持久化到数据库（可能多个源）
            imported.forEach { (info, content) ->
                sourceDao.insert(info.toEntity(content))
            }
            imported.map { it.info }
        }

    /** 「可选添加」流程的候选缓存：sourceId -> 已解析但尚未注册的源（v1.4.7.0） */
    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, com.novelreader.source.manager.ImportedSource>()

    override suspend fun previewImport(rawContent: String, fileName: String?): List<SourceInfo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val parsed = sourceManager.previewImport(rawContent, fileName)
            // 存进候选缓存，供 importSelected 按 id 取回注册
            previewCache.clear()
            parsed.forEach { previewCache[it.info.id] = it }
            parsed.map { it.info }
        }

    /**
     * 批量解析多个源文件（聚合包导入用，v1.5.6.0）。
     *
     * 候选缓存只清空一次，各文件结果累积到同一份缓存 —— 这是「一条链接导入全部源」
     * 的关键：用户在一个对话框里跨包勾选，确认后由 [importSelected] 按 id 取回注册。
     * 单个文件解析失败不中断整包（计入 [SourceImportBatch.failedFiles]）。
     */
    override suspend fun previewImportBatch(
        files: List<com.novelreader.domain.model.SourceFileContent>,
    ): com.novelreader.domain.model.SourceImportBatch =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            previewCache.clear()
            val sources = mutableListOf<SourceInfo>()
            val failedFiles = mutableListOf<String>()
            var skipped = 0

            files.forEach { file ->
                runCatching { sourceManager.previewImport(file.content, file.fileName) }
                    .onSuccess { parsed ->
                        skipped += sourceManager.lastImportSkipped
                        parsed.forEach { previewCache[it.info.id] = it }
                        sources += parsed.map { it.info }
                    }
                    .onFailure {
                        android.util.Log.w(
                            "SourceImport",
                            "聚合包文件「${file.fileName}」解析失败：${it.message}",
                        )
                        failedFiles += file.fileName
                    }
            }
            com.novelreader.domain.model.SourceImportBatch(
                sources = sources,
                failedFiles = failedFiles,
                skippedEntries = skipped,
            )
        }

    override suspend fun importSelected(ids: Set<String>): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val selected = ids.mapNotNull { previewCache[it] }
            if (selected.isEmpty()) {
                previewCache.clear()
                return@withContext 0
            }
            // v1.7.2.0：同 [importSource]，勾选的源也要先过一遍库内查重
            val registered = sourceManager.registerParsed(planImport(selected))
            // 持久化勾选的源
            registered.forEach { (info, content) ->
                sourceDao.insert(info.toEntity(content))
            }
            previewCache.clear()
            registered.size
        }

    // ===== 导入查重（v1.7.2.0） =====

    /**
     * 最近一次导入里「覆盖了旧版本」的重复源数量（供导入消息展示）。
     *
     * 与 [lastImportDuplicates] 的区别：这条是**用了新内容**（新包更完整），
     * 那条是库里已有且更完整、新包被丢弃。
     */
    @Volatile
    var lastImportUpdated: Int = 0
        private set

    /** 最近一次导入里因「库里已有且更完整」而跳过的重复源数量 */
    @Volatile
    var lastImportDuplicates: Int = 0
        private set

    /** 一次性取回两条查重计数（UI 拼消息用） */
    fun lastImportDedupeStats(): Pair<Int, Int> = lastImportUpdated to lastImportDuplicates

    /**
     * 与库里已有的源比对，决定这批候选哪些该导入、哪些是重复（v1.7.2.0）。
     *
     * **为什么必须有这一步**：实测一份真实库里 138 个小说源中 69 个是同一批源的
     * 第二次导入（`legado_889794069_4` 与 `legado_889794069_79` 同 hash、只差序号）。
     * 危害不只是列表变长——首页聚合、标签搜索、跨源搜索都会**对同一站点发两次完全
     * 相同的请求**：并发额度被占满、超时概率翻倍，用户看到的是「源不少但很慢，
     * 结果还一堆重复」。
     *
     * 判重口径复用 [com.novelreader.core.util.SourceDedupe]（同类型 + 同名 +
     * 归一化 baseUrl）；baseUrl 为空的 Venera 源不参与，避免误伤同名不同站的源。
     *
     * 处理方式（保守，不静默丢弃内容）：
     * - 库里没有 → 正常导入；
     * - 库里有且**新内容更长**（规则更完整）→ 沿用**旧 id** 覆盖写回，
     *   书架里指向该源的引用不会断；
     * - 库里有且旧内容更完整 → 跳过，计入 [lastImportDuplicates]；
     * - 同一批候选内部自己也重复时，第一条之后的都跳过。
     *
     * @return 应当真正注册 + 落库的候选（重复覆盖的已改写 id）
     */
    private suspend fun planImport(candidates: List<ImportedSource>): List<ImportedSource> {
        lastImportUpdated = 0
        lastImportDuplicates = 0
        if (candidates.isEmpty()) return candidates
        val existing = runCatching { sourceDao.getAll() }.getOrDefault(emptyList())
        if (existing.isEmpty()) return candidates

        // 库里已有的「类型 + 名字 + 归一化站点」→ 该组最完整的那条
        val bestOf = HashMap<String, SourceEntity>()
        existing.forEach { e ->
            val key = dedupeKey(e.type, e.name, e.baseUrl) ?: return@forEach
            val prev = bestOf[key]
            if (prev == null || e.rawContent.length > prev.rawContent.length) bestOf[key] = e
        }

        val out = ArrayList<ImportedSource>(candidates.size)
        val claimed = HashSet<String>()
        candidates.forEach { c ->
            val key = dedupeKey(c.info.type.name.lowercase(), c.info.name, c.info.baseUrl)
            // baseUrl 为空（Venera / 内置）不参与判重，原样导入
            if (key == null) {
                out += c
                return@forEach
            }
            if (claimed.contains(key)) {
                lastImportDuplicates++
                return@forEach
            }
            val old = bestOf[key]
            when {
                old == null -> {
                    out += c
                    claimed += key
                }
                // 新包更完整 → 覆盖旧条目，但沿用旧 id，避免书架引用断裂
                c.rawContent.length > old.rawContent.length -> {
                    out += c.copy(info = c.info.copy(id = old.id))
                    claimed += key
                    lastImportUpdated++
                }
                else -> lastImportDuplicates++
            }
        }
        if (lastImportUpdated + lastImportDuplicates > 0) {
            android.util.Log.i(
                "SourceImport",
                "导入查重：新增 ${out.size}、覆盖 $lastImportUpdated、跳过重复 $lastImportDuplicates",
            )
        }
        return out
    }

    /** 判重键；baseUrl 为空时返回 null（不参与） */
    private fun dedupeKey(type: String, name: String, baseUrl: String): String? {
        val url = com.novelreader.core.util.SourceDedupe.urlKey(baseUrl)
        if (url.isEmpty()) return null
        return "${type.lowercase()}\u0000${name.trim()}\u0000$url"
    }

    /** SourceInfo + 原始内容 → 待落库的实体 */
    private fun SourceInfo.toEntity(content: String) = SourceEntity(
        id = id,
        name = name,
        type = type.name.lowercase(),
        version = version,
        author = author,
        desc = desc,
        baseUrl = baseUrl,
        format = format.name.lowercase(),
        rawContent = content,
        enabled = enabled,
        category = category,
    )

    override suspend fun deleteSource(sourceId: String) {
        sourceManager.unregisterSource(sourceId)
        sourceDao.deleteById(sourceId)
    }

    override suspend fun deleteSources(ids: List<String>) {
        if (ids.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 先批量清理内存缓存，再单次写库（只触发一次 invalidation）
            ids.forEach { sourceManager.unregisterSource(it) }
            sourceDao.deleteByIds(ids)
        }
    }

    override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        sourceManager.setSourceEnabled(sourceId, enabled)
        sourceDao.setEnabled(sourceId, enabled)
    }

    override suspend fun dedupeDuplicateSources(): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val redundant = duplicatedIds() ?: return@withContext 0
            if (redundant.isEmpty()) return@withContext 0
            // 复用 deleteSources：批量注销内存适配器 + 单条 DELETE ... WHERE id IN (...)
            // （只触发一次 Room invalidation，避免 Cursor 读失效闪退，见 v1.5.5.3）
            deleteSources(redundant)
            redundant.size
        }

    override suspend fun countDuplicateSources(): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            duplicatedIds()?.size ?: 0
        }

    /** 读出全部源并算出冗余 id（判重口径见 [SourceDedupe]） */
    private suspend fun duplicatedIds(): List<String>? = runCatching {
        val all = sourceDao.getAll()
        com.novelreader.core.util.SourceDedupe.redundantIds(
            all.map { e ->
                com.novelreader.core.util.SourceDedupe.Candidate(
                    id = e.id,
                    type = e.type,
                    name = e.name,
                    baseUrl = e.baseUrl,
                    enabled = e.enabled,
                    rawLength = e.rawContent.length,
                    createdAt = e.createdAt,
                )
            },
        )
    }.getOrNull()

    override suspend fun setSourceSortOrder(sourceId: String, sortOrder: Int) {
        sourceManager.setSourceSortOrder(sourceId, sortOrder)
        sourceDao.setSortOrder(sourceId, sortOrder)
    }

    override suspend fun reorderSources(orderedIds: List<String>) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 批量重写序号：列表内按 1..n 赋值，其余清 0（回退自动排序）
            val all = sourceManager.getAllSources().map { it.id }.toSet()
            orderedIds.forEachIndexed { index, id ->
                sourceManager.setSourceSortOrder(id, index + 1)
                sourceDao.setSortOrder(id, index + 1)
            }
            (all - orderedIds.toSet()).forEach { id ->
                sourceManager.setSourceSortOrder(id, 0)
                sourceDao.setSortOrder(id, 0)
            }
        }
    }

    override suspend fun getAdapter(sourceId: String): SourceAdapter? =
        sourceManager.getAdapter(sourceId)

    override suspend fun getRawContent(sourceId: String): String? =
        sourceManager.getRawContent(sourceId)

    override suspend fun updateSource(sourceId: String, rawContent: String): SourceInfo =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 先在 SourceManager 重建适配器（失败抛异常，原源不变）
            val updated = sourceManager.updateSource(sourceId, rawContent)
            // 持久化新的原始内容
            val entity = sourceDao.getById(sourceId)
            if (entity != null) {
                sourceDao.insert(
                    entity.copy(
                        name = updated.name,
                        type = updated.type.name.lowercase(),
                        version = updated.version,
                        author = updated.author,
                        desc = updated.desc,
                        baseUrl = updated.baseUrl,
                        format = updated.format.name.lowercase(),
                        rawContent = rawContent,
                        category = updated.category,
                    )
                )
            }
            updated
        }

    /** 最近一次导入中被跳过（解析失败）的条目数（供导入消息展示，v1.3.0.0） */
    fun lastImportSkipped(): Int = sourceManager.lastImportSkipped

    /**
     * 预热 Venera 漫画源（v1.7.5.0）：后台串行 ping，触发各自 `init()`。
     *
     * 详见 [SourceManager.prewarmVenera]：把一次性 init 联网成本从首页聚合的
     * 关键时刻挪走，否则冷启动第一次刷新整页推荐 0 条。
     */
    suspend fun prewarmVeneraComics() = sourceManager.prewarmVenera(SourceType.COMIC)

    /**
     * 从数据库恢复所有源到 SourceManager（应用启动时调用）。
     */
    suspend fun restoreSources() {
        try {
            val entities = sourceDao.getAll()
            entities.forEach { entity ->
                // 仅在内存中不存在时恢复
                if (sourceManager.getAdapter(entity.id) == null) {
                    runCatching {
                        sourceManager.importSource(entity.rawContent, "${entity.id}.${entity.format}")
                        // 恢复启用状态
                        sourceManager.setSourceEnabled(entity.id, entity.enabled)
                        // 恢复手动排序序号（v1.5.8.0）
                        sourceManager.setSourceSortOrder(entity.id, entity.sortOrder)
                    }.onFailure {
                        // 恢复失败不能完全静默：留日志便于排查「部分源不加载」（v1.3.0.0）
                        android.util.Log.w(
                            "SourceRestore",
                            "源恢复失败 id=${entity.id}: ${it.message}",
                        )
                    }
                }
            }
            // v1.7.2.0：存量清理。早期版本导入不去重，库里可能躺着上百个**完全相同**
            // 的源（实测 138 个小说源里 69 个是同一批源的第二次导入）。恢复完顺手合并，
            // 否则首页聚合 / 标签搜索会对同一站点发两次一模一样的请求。
            runCatching {
                val removed = dedupeDuplicateSources()
                if (removed > 0) {
                    android.util.Log.i("SourceRestore", "启动清理：已合并 $removed 个重复源")
                }
            }
        } finally {
            // 无论成败都放行等待方（搜索入口等待此信号，避免冷启动竞态空搜）
            sourceManager.markRestored()
        }
    }
}

// ===== 映射函数 =====

/** SourceEntity -> SourceInfo */
fun SourceEntity.toDomain(): SourceInfo = SourceInfo(
    id = id,
    name = name,
    type = SourceType.fromString(type) ?: SourceType.NOVEL,
    version = version,
    author = author,
    desc = desc,
    baseUrl = baseUrl,
    format = when (format) {
        "js" -> SourceFormat.JS
        "legado" -> SourceFormat.LEGADO
        "venera" -> SourceFormat.VENERA
        "moontv" -> SourceFormat.MOONTV
        else -> SourceFormat.JSON
    },
    enabled = enabled,
    category = category.ifBlank {
        // 兼容旧数据：category 为空时动态分类
        com.novelreader.source.category.SourceCategory.classify(
            SourceInfo(id = id, name = name, type = SourceType.fromString(type) ?: SourceType.NOVEL, author = author, desc = desc, baseUrl = baseUrl)
        )
    },
    sortOrder = sortOrder,
)
