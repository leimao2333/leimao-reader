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

    override suspend fun deleteSource(sourceId: String) {
        sourceManager.unregisterSource(sourceId)
        sourceDao.deleteById(sourceId)
    }

    override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        sourceManager.setSourceEnabled(sourceId, enabled)
        sourceDao.setEnabled(sourceId, enabled)
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
        else -> SourceFormat.JSON
    },
    enabled = enabled,
    category = category.ifBlank {
        // 兼容旧数据：category 为空时动态分类
        com.novelreader.source.category.SourceCategory.classify(
            SourceInfo(id = id, name = name, type = SourceType.fromString(type) ?: SourceType.NOVEL, author = author, desc = desc, baseUrl = baseUrl)
        )
    },
)
