package com.novelreader.data.repository

import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.core.util.TagQuery
import com.novelreader.core.util.TagTagger
import com.novelreader.data.database.ItemRef
import com.novelreader.data.database.LocalTagDao
import com.novelreader.data.database.TagInstanceDao
import com.novelreader.data.model.LocalTagEntity
import com.novelreader.data.model.TagInstanceEntity
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.SourceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 自建标签层（v1.8.1.2）。
 *
 * 网络源漫画没有结构化标签，这是聚合搜索里典型的「脏数据」困境：
 * 试图去补全 / 修正每一个源站的标签，成本不可控也无法持续。
 *
 * 这里采用的思路是**在源数据之上建一个独立的标签层**，搜索与分类只信任这一层：
 *
 * ```
 * 源站原始数据（无标签 / 脏标签）
 *   ↓  自动打标（站方字段 + 文本关键词；视觉 / 行为管道后续接入）
 *   ↓  标准化（TagAlias 受控词表，同义归一）
 *   ↓  tag_instances（每个标签一行：名称 / 大类 / 置信度 / 来源）
 *   ↓  嵌套式查询（组内 OR、组间 AND、置信度过滤、排除标签）
 * ```
 *
 * 复合筛选因此与源站标签质量解耦。
 */
class TagIndexRepository(
    private val contentRepository: ContentRepository,
    private val sourceRepository: SourceRepository,
    private val localTagDao: LocalTagDao,
    private val tagInstanceDao: TagInstanceDao,
) {

    /** 构建进度 */
    data class Progress(
        val doneSources: Int,
        val totalSources: Int,
        val currentSource: String,
        val indexedCount: Int,
        /** 扫过的作品总数（用来判断是「抓不到列表」还是「打不上标签」） */
        val scannedCount: Int = 0,
    )

    /** 已收录作品数 */
    suspend fun count(type: SourceType): Int = tagInstanceDao.itemCount(type.name)

    /**
     * 复合标签查询（v1.8.1.2）。
     *
     * @param groups 每组是一组同义写法，**组内 OR、组间 AND**（对应 bool 的 should + must）
     * @param minConfidence 置信度下限（对应 filter）
     * @param excludeTags 排除标签（对应 must_not）
     */
    suspend fun search(
        type: SourceType,
        groups: List<Set<String>>,
        minConfidence: Int = 0,
        excludeTags: Set<String> = emptySet(),
    ): List<MediaItem> {
        if (groups.isEmpty()) return emptyList()
        // 组内 OR：命中该组任一标签即可
        val perGroup = groups.map { group ->
            tagInstanceDao.itemIdsWithAny(type.name, group.toList(), minConfidence).toSet()
        }
        if (perGroup.any { it.isEmpty() }) return emptyList()
        // 组间 AND：在**条目级别**求交，等价于 nested must ——
        // 拼串存储会把「题材命中 A、成人命中 B」也算命中，这里不会。
        var ids = perGroup.first()
        perGroup.drop(1).forEach { ids = ids.intersect(it) }
        if (excludeTags.isNotEmpty()) {
            val banned = tagInstanceDao
                .itemIdsWithAny(type.name, excludeTags.toList(), minConfidence)
                .toSet()
            ids = ids - banned
        }
        if (ids.isEmpty()) return emptyList()
        return localTagDao.all(type.name)
            .filter { ItemRef(it.sourceId, it.itemId) in ids }
            .map { it.toItem() }
            .distinctBy { TagQuery.identityKey(it.title) }
    }

    /**
     * 边搜边标记（v1.8.1.2）：搜索结果交给 [TagTagger] 打标后入库。
     *
     * 不额外发详情请求 —— 每搜一次，标签层就厚一点，用户用得越多越准。
     */
    suspend fun indexItems(type: SourceType, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = mutableListOf<LocalTagEntity>()
        val tags = mutableListOf<TagInstanceEntity>()
        items.forEach { item ->
            val tagged = TagTagger.tag(item)
            if (tagged.isEmpty()) return@forEach
            rows += LocalTagEntity(
                sourceId = item.sourceId,
                itemId = item.id,
                itemType = type.name,
                title = item.title,
                cover = item.cover,
                detailUrl = item.detailUrl,
                sourceName = item.sourceName,
                author = item.author,
                tags = tagged.joinToString("|") { it.name },
                tagsLower = "|" + tagged.joinToString("|") { it.name.lowercase() } + "|",
                updatedAt = now,
            )
            tagged.forEach { tag ->
                tags += TagInstanceEntity(
                    sourceId = item.sourceId,
                    itemId = item.id,
                    itemType = type.name,
                    tagName = tag.name,
                    category = tag.category,
                    confidence = tag.confidence,
                    origin = tag.origin,
                    updatedAt = now,
                )
            }
        }
        if (rows.isNotEmpty()) localTagDao.upsertAll(rows)
        if (tags.isNotEmpty()) tagInstanceDao.upsertAll(tags)
    }

    /**
     * 全量构建（重活，可选）。
     *
     * 遍历启用的源：抓列表 → 逐部拉详情 → 打标 → 入库。
     * 日常使用不需要它（边搜边标记已经够），只用于主动预热。
     */
    suspend fun build(
        type: SourceType,
        pagesPerSource: Int = 2,
        maxItemsPerSource: Int = 40,
        /** 只索引这些源（空 = 全部已启用源） */
        sourceIds: Set<String> = emptySet(),
        onProgress: suspend (Progress) -> Unit = {},
    ): Int {
        val sources = sourceRepository.getSourcesByType(type)
            .filter { it.enabled && (sourceIds.isEmpty() || it.id in sourceIds) }
        if (sources.isEmpty()) return 0
        // 一次枚举全部源的发现分类，避免逐源重复请求
        val categories = runCatching {
            contentRepository.exploreSourcesFast(type, includeDisabled = false).items
        }.getOrDefault(emptyList()).associate { (info, cats) -> info.id to cats }

        var indexed = 0
        var scanned = 0
        val buffer = mutableListOf<MediaItem>()
        val semaphore = Semaphore(6)

        sources.forEachIndexed { index, source ->
            val urls = categories[source.id].orEmpty()
                .map { it.url }.filter { it.isNotBlank() }.distinct()
            // 分类入口常常抓不到列表（源改版 / 分类页失效）。再补几组关键词搜索入口，
            // 保证每个源都能取到作品 —— 这是「遍历所有源」能真正跑起来的前提。
            val probes = (urls + INDEX_PROBE_KEYWORDS.map { TagEntryUrl.search(it) }).distinct()
            if (probes.isNotEmpty()) {
                val seen = mutableSetOf<String>()
                for (page in 1..pagesPerSource) {
                    for (url in probes.take(6)) {
                        val items = runCatching {
                            contentRepository.explore(source.id, url, page)
                        }.getOrDefault(emptyList())
                        if (items.isEmpty()) continue
                        val fresh = items.filter { it.id !in seen }
                            .take((maxItemsPerSource - seen.size).coerceAtLeast(0))
                        if (fresh.isEmpty()) continue
                        fresh.forEach { seen.add(it.id) }
                        scanned += fresh.size
                        val details = coroutineScope {
                            fresh.map { item ->
                                async {
                                    semaphore.withPermit {
                                        runCatching { contentRepository.detail(item) }
                                            .getOrDefault(item)
                                    }
                                }
                            }.awaitAll()
                        }
                        details.forEach { detail ->
                            // 详情才有简介 / kind，打标更准；一个标签都打不上就丢弃
                            if (TagTagger.tag(detail).isNotEmpty()) {
                                buffer += detail
                                indexed++
                            }
                        }
                        if (buffer.size >= 40) {
                            indexItems(type, buffer.toList())
                            buffer.clear()
                        }
                        if (seen.size >= maxItemsPerSource) break
                    }
                    if (seen.size >= maxItemsPerSource) break
                }
            }
            if (buffer.isNotEmpty()) {
                indexItems(type, buffer.toList())
                buffer.clear()
            }
            onProgress(Progress(index + 1, sources.size, source.name, indexed, scanned))
        }
        return indexed
    }

    private fun LocalTagEntity.toItem(): MediaItem = MediaItem(
        id = itemId,
        title = title,
        cover = cover,
        author = author,
        type = runCatching { SourceType.valueOf(itemType) }.getOrDefault(SourceType.COMIC),
        detailUrl = detailUrl.ifEmpty { itemId },
        sourceId = sourceId,
        sourceName = sourceName,
        tags = tags.split('|').filter { it.isNotBlank() },
    )

    companion object {
        /**
         * 索引时用来取列表的关键词（v1.8.1.2）。
         *
         * 不走分类页是因为大量源的分类入口已经失效（实测部分源抓不到任何条目）；
         * 这几个词覆盖面广，能稳定带出各站的作品列表，再由打标器逐部打标签。
         */
        private val INDEX_PROBE_KEYWORDS = listOf("恋爱", "冒险", "全彩", "无码", "奇幻")
    }
}
