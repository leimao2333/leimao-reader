package com.novelreader.data.repository

import com.novelreader.data.database.SourceDao
import com.novelreader.data.model.SourceEntity
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.SourceRepository
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
            val imported = sourceManager.importSource(rawContent, fileName)

            // 持久化到数据库（可能多个源）
            imported.forEach { (info, content) ->
                sourceDao.insert(
                    SourceEntity(
                        id = info.id,
                        name = info.name,
                        type = info.type.name.lowercase(),
                        version = info.version,
                        author = info.author,
                        desc = info.desc,
                        baseUrl = info.baseUrl,
                        format = info.format.name.lowercase(),
                        rawContent = content,
                        enabled = info.enabled,
                        category = info.category,
                    )
                )
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
            val registered = sourceManager.registerParsed(selected)
            // 持久化勾选的源
            registered.forEach { (info, content) ->
                sourceDao.insert(
                    SourceEntity(
                        id = info.id,
                        name = info.name,
                        type = info.type.name.lowercase(),
                        version = info.version,
                        author = info.author,
                        desc = info.desc,
                        baseUrl = info.baseUrl,
                        format = info.format.name.lowercase(),
                        rawContent = content,
                        enabled = info.enabled,
                        category = info.category,
                    )
                )
            }
            previewCache.clear()
            registered.size
        }

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
