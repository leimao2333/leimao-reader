package com.novelreader.data.repository

import com.novelreader.core.network.HttpClient
import com.novelreader.core.util.NetworkStatus
import com.novelreader.core.util.TagAlias
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.core.util.TagHit
import com.novelreader.core.util.TagQuery
import com.novelreader.core.util.TagRanking
import com.novelreader.core.util.TagTable
import com.novelreader.core.util.TagTagger
import com.novelreader.core.visual.Wd14Inference
import com.novelreader.core.visual.Wd14Labels
import com.novelreader.core.visual.Wd14Mapper
import com.novelreader.core.visual.Wd14TaggerSpec
import com.novelreader.data.database.LocalTagDao
import com.novelreader.data.database.TagInstanceDao
import com.novelreader.data.model.LocalTagEntity
import com.novelreader.data.model.TagInstanceEntity
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import com.novelreader.domain.repository.ContentRepository
import com.novelreader.domain.repository.SourceRepository
import com.novelreader.domain.repository.TagBehaviorRecorder
import com.novelreader.domain.repository.TagSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

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
    /** 视觉打标需要 Context（模型目录 / Wi-Fi 判断）；测试环境传 null */
    private val appContext: android.content.Context? = null,
) : TagBehaviorRecorder {

    /** 一个查询条件：一组等价写法（组内 OR）+ 可选的大类限定 */
    data class TagClause(
        val names: Set<String>,
        val category: String? = null,
    )

    /** 构建进度 */
    data class Progress(
        val doneSources: Int,
        val totalSources: Int,
        val currentSource: String,
        val indexedCount: Int,
        /** 扫过的作品总数（用来判断是「抓不到列表」还是「打不上标签」） */
        val scannedCount: Int = 0,
    )

    /** 全量索引任务的状态（v1.8.1.9）：放在 repository 层，离开页面 / 去阅读时任务继续。 */
    data class IndexState(
        val running: Boolean = false,
        val paused: Boolean = false,
        val message: String = "",
        val indexedCount: Int = 0,
        val scannedCount: Int = 0,
        val doneSources: Int = 0,
        val totalSources: Int = 0,
    )

    /**
     * 索引任务跑在**仓库级**协程里（不是 ViewModel 的 viewModelScope）：
     * 用户切去阅读页、标签页被销毁重建，遍历都不会中断 —— 这就是「边写边看书」。
     */
    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _indexState = MutableStateFlow(IndexState())
    val indexState: StateFlow<IndexState> = _indexState.asStateFlow()

    /** 暂停 / 停止标记：遍历循环在每个源、每页、每批落库前检查 */
    @Volatile private var indexPaused = false
    @Volatile private var indexStopped = false
    private var indexJob: Job? = null

    /** 启动全量索引（v1.8.1.9）。已在跑时重复点击无效。 */
    fun startBuild(type: SourceType, sourceIds: Set<String> = emptySet()) {
        if (indexJob?.isActive == true) return
        indexPaused = false
        indexStopped = false
        _indexState.value = IndexState(running = true, message = "准备中…")
        indexJob = indexScope.launch {
            try {
                val total = build(
                    type = type,
                    sourceIds = sourceIds,
                    onProgress = { p ->
                        _indexState.value = IndexState(
                            running = true,
                            paused = indexPaused,
                            message = progressMessage(p, indexPaused),
                            indexedCount = p.indexedCount,
                            scannedCount = p.scannedCount,
                            doneSources = p.doneSources,
                            totalSources = p.totalSources,
                        )
                    },
                )
                _indexState.value = IndexState(
                    running = false,
                    message = if (indexStopped) "已停止（已收录 $total 部）" else "完成：$total 部作品已打标签",
                    indexedCount = total,
                )
            } catch (cancelled: CancellationException) {
                _indexState.value = IndexState(running = false, message = "已停止")
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TagIndex", "全量索引失败", error)
                _indexState.value = IndexState(
                    running = false,
                    message = "建索引失败：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    /** 暂停遍历（当前这批落库完成后停在原地，不丢进度） */
    fun pauseIndexing() {
        if (indexJob?.isActive != true) return
        indexPaused = true
        _indexState.update { it.copy(paused = true) }
    }

    /** 从暂停处继续 */
    fun resumeIndexing() {
        if (indexJob?.isActive != true) return
        indexPaused = false
        _indexState.update { it.copy(paused = false) }
    }

    /** 彻底停止（已落库的标签保留） */
    fun stopIndexing() {
        indexStopped = true
        indexPaused = false
        indexJob?.cancel()
        _indexState.update { it.copy(running = false, paused = false, message = "已停止") }
    }

    private fun progressMessage(p: Progress, paused: Boolean): String {
        val base = "扫描 ${p.scannedCount} 部 · 打标签 ${p.indexedCount} 部 · " +
            "${p.doneSources}/${p.totalSources} 源" +
            (if (p.currentSource.isNotBlank()) "（${p.currentSource}）" else "")
        return if (paused) "$base · 已暂停" else base
    }

    /** 遍历循环里的闸门：暂停时挂起等待，停止时由外层 break */
    private suspend fun awaitIndexGate() {
        while (indexPaused && !indexStopped) delay(200)
    }

    /** 已收录作品数 */
    suspend fun count(type: SourceType): Int = tagInstanceDao.itemCount(type.name)

    /**
     * 复合标签查询（v1.8.1.2；v1.8.1.3 加入大类过滤与置信度排序）。
     *
     * @param clauses 每个条件是一组等价写法，**组内 OR、组间 AND**（对应 bool 的 should + must）
     * @param minConfidence 置信度下限（对应 filter）
     * @param excludeTags 排除标签（对应 must_not）
     * @param hideAdult 开启时按 `category = 成人` **整类过滤**，而不是逐个标签名去排
     * @return 按置信度降序（同分保持索引顺序）的作品，置信度取各组中的短板
     */
    suspend fun search(
        type: SourceType,
        clauses: List<TagClause>,
        minConfidence: Int = 0,
        excludeTags: Set<String> = emptySet(),
        hideAdult: Boolean = false,
    ): List<MediaItem> {
        if (clauses.isEmpty() || clauses.any { it.names.isEmpty() }) return emptyList()

        // 组内 OR：命中该组任一写法即可，得分 = 该作品在本组的最高置信度
        val groups = clauses.map { clause ->
            tagInstanceDao.refsWithAny(
                type = type.name,
                names = clause.names.toList(),
                minConfidence = minConfidence,
                category = clause.category,
                excludeAdult = if (hideAdult) 1 else 0,
            ).map { TagHit(it.sourceId, it.itemId, it.confidence) }
        }
        var ranked = TagRanking.intersect(groups)
        if (ranked.isEmpty()) return emptyList()

        // must_not：命中排除标签的作品整条剔除（排除不看大类，用户说排除就是排除）
        if (excludeTags.isNotEmpty()) {
            val banned = tagInstanceDao.refsWithAny(
                type = type.name,
                names = excludeTags.toList(),
                minConfidence = 0,
                category = null,
                excludeAdult = 0,
            ).mapTo(HashSet()) { it.sourceId to it.itemId }
            ranked = ranked.filterNot { (it.sourceId to it.itemId) in banned }
        }
        if (ranked.isEmpty()) return emptyList()

        val byKey = localTagDao.all(type.name).associateBy { it.sourceId to it.itemId }
        return ranked.mapNotNull { hit -> byKey[hit.sourceId to hit.itemId]?.toItem() }
            .distinctBy { TagQuery.identityKey(it.title) }
    }

    /**
     * 边搜边标记（v1.8.1.2）：搜索结果交给 [TagTagger] 打标后入库。
     *
     * 不额外发详情请求 —— 每搜一次，标签层就厚一点，用户用得越多越准。
     */
    suspend fun indexItems(
        type: SourceType,
        items: List<MediaItem>,
        /**
         * 分类页上下文（v1.8.1.9）：这批结果是从哪些标签的**真实分类页**命中的。
         * 每项为「规范标签名 to 置信度」，会与作品自身字段打出的标签合并（同名取高分）。
         * 纯关键词搜索不要传 —— 标题里出现某个词不等于作品属于该标签。
         */
        contextTags: List<Pair<String, Int>> = emptyList(),
        contextOrigin: String = TagTagger.ORIGIN_STATION,
    ) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = mutableListOf<LocalTagEntity>()
        val tags = mutableListOf<TagInstanceEntity>()
        val context = contextTags.map { TagTagger.ContextTag(it.first, it.second, contextOrigin) }
        items.forEach { item ->
            val tagged = TagTagger.tag(item, context)
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
            // 暂停 / 停止（v1.8.1.9）：停在源边界，已落库的成果不丢
            if (indexStopped) return@forEachIndexed
            awaitIndexGate()
            val urls = categories[source.id].orEmpty()
                .map { it.url }.filter { it.isNotBlank() }.distinct()
            // 分类入口常常抓不到列表（源改版 / 分类页失效）。再补几组关键词搜索入口，
            // 保证每个源都能取到作品 —— 这是「遍历所有源」能真正跑起来的前提。
            val probes = (urls + INDEX_PROBE_KEYWORDS.map { TagEntryUrl.search(it) }).distinct()
            if (probes.isNotEmpty()) {
                val seen = mutableSetOf<String>()
                for (page in 1..pagesPerSource) {
                    if (indexStopped) break
                    awaitIndexGate()
                    for (url in probes.take(6)) {
                        if (indexStopped) break
                        awaitIndexGate()
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

    // ===== 行为信号打标（v1.8.1.3）=====

    /** 同一作品同一信号的节流表（阅读进度会周期性落盘，每次都写库没有信息量） */
    private val lastSignalAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 收藏 / 阅读 / 点击回流：把「用户真的在用这部作品」折算成标签置信度。
     *
     * 行为**不新增标签**，只给已有标签加权 —— 单次点击推不出「这部是全彩」，
     * 但能说明「用户确实关注它」，而置信度正是查询排序的依据（见 [TagRanking]）。
     * 文本猜测（[TagTagger.ORIGIN_TEXT]）被行为反复确认后升级为「行为」来源，
     * 表示最强证据已不是字面命中；站方 / 视觉标签的来源保持不变。
     */
    override suspend fun onBehavior(item: MediaItem, signal: TagSignal) {
        if (item.id.isBlank() || item.sourceId.isBlank()) return
        val now = System.currentTimeMillis()
        if (!shouldRecord("${item.sourceId}|${item.id}|${signal.name}", now)) return

        val type = item.type
        var rows = tagInstanceDao.tagsOf(type.name, item.sourceId, item.id)
        if (rows.isEmpty()) {
            // 还没进索引：先用标题 / 站方题材打一遍，再累加行为分
            indexItems(type, listOf(item))
            rows = tagInstanceDao.tagsOf(type.name, item.sourceId, item.id)
        }
        if (rows.isEmpty()) return
        val boosted = rows.map { row ->
            val confidence = (row.confidence + signal.weight).coerceAtMost(100)
            TagInstanceEntity(
                sourceId = item.sourceId,
                itemId = item.id,
                itemType = type.name,
                tagName = row.tagName,
                category = row.category,
                confidence = confidence,
                origin = if (
                    row.origin == TagTagger.ORIGIN_TEXT &&
                    confidence >= BEHAVIOR_PROMOTE_THRESHOLD
                ) {
                    TagTagger.ORIGIN_BEHAVIOR
                } else {
                    row.origin
                },
                updatedAt = now,
            )
        }
        tagInstanceDao.upsertAll(boosted)
    }

    private fun shouldRecord(key: String, now: Long): Boolean {
        val last = lastSignalAt[key]
        if (last != null && now - last < SIGNAL_TTL_MS) return false
        if (lastSignalAt.size > 512) {
            lastSignalAt.entries.removeIf { now - it.value > SIGNAL_TTL_MS }
        }
        lastSignalAt[key] = now
        return true
    }

    // ===== 封面视觉打标（v1.8.1.4）=====

    private var visualInference: Wd14Inference? = null

    /**
     * 确保 WD14 模型就绪：`model.onnx`（约 356MB）+ `selected_tags.csv` 存在则直接复用，
     * 否则**仅 Wi-Fi** 下从镜像站（hf-mirror.com，HuggingFace 主站直连不通）按需下载。
     *
     * 模型不打包进 APK（体积不可接受），属于「用得越深、下得越值」的可选资产。
     */
    private suspend fun ensureVisualModel(onProgress: (String) -> Unit): Wd14Inference {
        val context = appContext ?: error("当前环境不支持视觉打标")
        val dir = File(context.filesDir, "tagger/wd14")
        val model = File(dir, "model.onnx")
        val tagCsv = File(dir, "selected_tags.csv")
        if (!model.exists() || model.length() < 1024 * 1024) {
            if (!NetworkStatus.isWifi(context)) {
                error("视觉模型约 356MB，请在 Wi-Fi 环境下首次下载")
            }
            dir.mkdirs()
            onProgress("下载视觉模型（约 356MB，仅首次）…")
            val part = File(dir, "model.onnx.part")
            part.delete()
            HttpClient.downloadFile(Wd14TaggerSpec.DEFAULT_MODEL_URL, part) { downloaded, total ->
                if (total > 0) {
                    onProgress(
                        "下载视觉模型 ${downloaded * 100 / total}%（${downloaded / 1048576}MB / ${total / 1048576}MB）",
                    )
                } else {
                    onProgress("下载视觉模型 ${downloaded / 1048576}MB…")
                }
            }
            if (!part.renameTo(model)) {
                part.copyTo(model, overwrite = true)
                part.delete()
            }
        }
        if (!tagCsv.exists() || tagCsv.length() < 1024) {
            onProgress("下载标签表…")
            HttpClient.downloadFile(Wd14TaggerSpec.DEFAULT_TAGS_URL, tagCsv)
        }
        // 模型文件可能中途被换，旧会话直接关掉重建
        visualInference?.close()
        val labels = Wd14Labels.parse(tagCsv.readText())
        visualInference = Wd14Inference(model, labels)
        return visualInference!!
    }

    /**
     * 对已收录作品的封面跑视觉打标：模型输出 Danbooru 标签 → [Wd14Mapper] 映射受控词表 →
     * 以 `origin = 视觉` 合并进 `tag_instances`。
     *
     * 合并规则：**同标签取更高置信度**，站方（100）/ 行为加权的证据不会被视觉覆盖；
     * 视觉新增的标签按 `概率 × 100` 记分（封顶 95，永远给站方留出位次）。
     *
     * @return 本轮真正打上标签的作品数
     */
    suspend fun runVisualTagging(
        type: SourceType,
        limit: Int = 60,
        onProgress: (String) -> Unit,
    ): Int {
        val inference = ensureVisualModel(onProgress)
        val pending = localTagDao.coversWithoutVisual(type.name, limit)
        if (pending.isEmpty()) return 0
        var done = 0
        pending.forEachIndexed { index, ref ->
            onProgress("视觉打标 ${index + 1}/${pending.size}（${done} 部命中）…")
            val ok = runCatching {
                val bitmap = withContext(Dispatchers.IO) { fetchCoverBitmap(ref.cover) }
                    ?: return@runCatching false
                val raw = withContext(Dispatchers.Default) { inference.tag(bitmap) }
                bitmap.recycle()
                if (raw.isEmpty()) return@runCatching false
                applyVisualTags(type, ref.sourceId, ref.itemId, raw)
                true
            }.getOrDefault(false)
            if (ok) done++
        }
        return done
    }

    /** 把一次推理结果合并进该作品的标签行（含既有行原样保底，避免 REPLACE 丢行） */
    private suspend fun applyVisualTags(
        type: SourceType,
        sourceId: String,
        itemId: String,
        raw: List<Pair<String, Float>>,
    ) {
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, TagInstanceEntity>()
        tagInstanceDao.tagsOf(type.name, sourceId, itemId).forEach { row ->
            out[row.tagName] = TagInstanceEntity(
                sourceId = sourceId,
                itemId = itemId,
                itemType = type.name,
                tagName = row.tagName,
                category = row.category,
                confidence = row.confidence,
                origin = row.origin,
                updatedAt = row.updatedAt.takeIf { it > 0 } ?: now,
            )
        }
        raw.forEach { (danbooruName, probability) ->
            val controlled = Wd14Mapper.toControlled(danbooruName) ?: return@forEach
            val confidence = (probability * 100).toInt().coerceIn(20, 95)
            val category = if (TagAlias.isExplicitAdultIntent(controlled)) {
                TagTagger.CATEGORY_ADULT
            } else {
                TagTagger.CATEGORY_GENRE
            }
            val old = out[controlled]
            if (old == null || confidence > old.confidence) {
                out[controlled] = TagInstanceEntity(
                    sourceId = sourceId,
                    itemId = itemId,
                    itemType = type.name,
                    tagName = controlled,
                    category = category,
                    confidence = confidence,
                    origin = TagTagger.ORIGIN_VISUAL,
                    updatedAt = now,
                )
            }
        }
        if (out.isNotEmpty()) tagInstanceDao.upsertAll(out.values.toList())
    }

    /** 封面字节：网络封面走 OkHttp，本地路径（本地书）直接读文件 */
    private fun fetchCoverBitmap(coverUrl: String): android.graphics.Bitmap? {
        if (coverUrl.isBlank()) return null
        return runCatching {
            if (coverUrl.startsWith("/")) {
                val file = File(coverUrl)
                if (!file.exists()) return null
                return Wd14Inference.decodeScaled(file.readBytes()) ?: return null
            }
            val request = okhttp3.Request.Builder().url(coverUrl).build()
            HttpClient.executeRaw(request).use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                Wd14Inference.decodeScaled(bytes)
            }
        }.getOrNull()
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

    // ===== 云端打标表（v1.8.1.5：PC 工具打标 → GitHub → App 导入补充）=====

    /** 从 [url]（默认 [DEFAULT_CLOUD_TABLE_URL]）拉取云端打标表 */
    suspend fun downloadCloudTable(url: String = DEFAULT_CLOUD_TABLE_URL): TagTable.Payload =
        withContext(Dispatchers.IO) {
            TagTable.parse(HttpClient.get(url, timeoutMs = 30_000))
        } ?: error("云端打标表下载或解析失败：$url")

    /**
     * 拉取云端版本清单（v1.8.2.0）。
     *
     * 失败返回 null 而不是抛异常 —— 清单只是「要不要下载」的前置判断，
     * 拉不到时上层可以退回直接下载整表，不该因此让导入直接失败。
     */
    suspend fun fetchCloudManifest(url: String = DEFAULT_CLOUD_MANIFEST_URL): TagTable.Manifest? =
        withContext(Dispatchers.IO) {
            runCatching { TagTable.parseManifest(HttpClient.get(url, timeoutMs = 20_000)) }.getOrNull()
        }

    /**
     * 导出**收录清单 + 当前标签**为打标表 —— PC 端工具（仓库 `tagger/`）的输入。
     *
     * 全量导出（不只是待打标队列）：PC 端合并后回传的是完整表，App 导入时按
     * 「同名标签取更高置信度」幂等合并，不会丢已有数据。
     */
    suspend fun buildTagTable(
        type: SourceType,
        /** 导出进度回调（v1.8.1.9）：已完成条目数 / 总条目数 */
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TagTable.Payload {
        val now = System.currentTimeMillis()
        val rowsAll = localTagDao.all(type.name)
        val total = rowsAll.size
        onProgress(0, total)
        val items = rowsAll.mapIndexed { index, row ->
            val tags = tagInstanceDao.tagsOf(type.name, row.sourceId, row.itemId).map { row2 ->
                TagTable.Entry(
                    name = row2.tagName,
                    category = row2.category,
                    confidence = row2.confidence,
                    origin = row2.origin,
                )
            }
            onProgress(index + 1, total)
            TagTable.Item(
                sourceId = row.sourceId,
                itemId = row.itemId,
                itemType = type.name,
                title = row.title,
                cover = row.cover.orEmpty(),
                detailUrl = row.detailUrl,
                sourceName = row.sourceName,
                author = row.author.orEmpty(),
                tags = tags,
            )
        }
        onProgress(total, total)
        return TagTable.Payload(
            version = TagTable.VERSION,
            generatedAt = now,
            source = TagTable.SOURCE_APP,
            items = items,
        )
    }

    /**
     * **流式**导出打标表（v1.8.3.0 待办 #20）。
     *
     * 老实现是「全量读进内存 → 拼 Payload → Gson 一次性 toJson 成十几 MB 字符串 → 再转 byte[]」，
     * 同一份数据在内存里存在 3~4 份（entity / Item / JSON 串 / UTF-16 字节），两万条要几百 MB、
     * 十几秒起步，期间 UI 完全没反应。现在改成边查边写：
     *
     * - 按 [EXPORT_PAGE] 分页查 `local_tags`，每个类型的标签实例只读一次建索引（不再逐条查库）；
     * - 直接写进调用方给的 [out]（SAF 的输出流），内存里最多只有一个分页；
     * - 按已写条目数回调进度，调用方可以边写边刷新进度条。
     *
     * 输出为紧凑 JSON（不缩进）—— 漂亮格式会把文件撑大一倍，而消费方是程序不是人。
     *
     * @return 实际写出的条目数
     */
    suspend fun exportTagTable(
        types: List<SourceType>,
        out: java.io.Writer,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val total = types.sumOf { localTagDao.count(it.name) }
        var done = 0
        onProgress(0, total)

        val writer = com.google.gson.stream.JsonWriter(out)
        writer.beginObject()
        writer.name("version").value(TagTable.VERSION.toLong())
        writer.name("generatedAt").value(System.currentTimeMillis())
        writer.name("source").value(TagTable.SOURCE_APP)
        writer.name("items").beginArray()

        types.forEach { type ->
            // 一次读出该类型的全部标签实例，按作品索引：导出两万条也不会变成两万次查询
            val byItem = HashMap<Pair<String, String>, MutableList<TagInstanceEntity>>()
            tagInstanceDao.allOfType(type.name).forEach { row ->
                byItem.getOrPut(row.sourceId to row.itemId) { mutableListOf() }.add(row)
            }
            var offset = 0
            while (true) {
                val page = localTagDao.pageOfType(type.name, EXPORT_PAGE, offset)
                if (page.isEmpty()) break
                page.forEach { row ->
                    writeItem(writer, type.name, row, byItem[row.sourceId to row.itemId].orEmpty())
                    done++
                }
                offset += page.size
                onProgress(done, total)
            }
        }

        writer.endArray()
        writer.endObject()
        writer.flush()
        done
    }

    /** 写一个条目（字段名必须与 [TagTable.Item] 的 Gson 序列化一致） */
    private fun writeItem(
        writer: com.google.gson.stream.JsonWriter,
        itemType: String,
        row: LocalTagEntity,
        tags: List<TagInstanceEntity>,
    ) {
        writer.beginObject()
        writer.name("sourceId").value(row.sourceId)
        writer.name("itemId").value(row.itemId)
        writer.name("itemType").value(itemType)
        writer.name("title").value(row.title)
        writer.name("cover").value(row.cover.orEmpty())
        writer.name("detailUrl").value(row.detailUrl)
        writer.name("sourceName").value(row.sourceName)
        writer.name("author").value(row.author.orEmpty())
        writer.name("tags").beginArray()
        tags.forEach { tag ->
            writer.beginObject()
            writer.name("name").value(tag.tagName)
            writer.name("category").value(tag.category)
            writer.name("confidence").value(tag.confidence.toLong())
            writer.name("origin").value(tag.origin)
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
    }

    /**
     * 导入云端打标表：
     * - 清单里 App 还没收录的条目，直接落进 `local_tags`（PC 端遍历的成果一起进索引）；
     * - 标签按**同名取更高置信度**合并（[TagTable.mergeEntry]），站方 100 分不会被
     *   PC 端的视觉 72 分顶掉，反之 PC 打上的「黑白」能补掉 App 侧没猜到的空缺。
     *
     * @param onProgress 写入进度回调（已写条目数, 总条目数），在写库线程调用（v1.8.3.0 待办 #17）
     * @return 导入覆盖的条目数
     */
    suspend fun importTagTable(
        payload: TagTable.Payload,
        type: SourceType,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Int {
        val now = System.currentTimeMillis()
        val typeItems = payload.items.filter { it.itemType == type.name }
        if (typeItems.isEmpty()) return 0

        // v1.8.2.0 批量化：原实现是「每部作品 1 次 tagsOf + 1 次 local_tags 写 + 1 次标签写」，
        // 两万条就是六万多次数据库往返（title 为空时还会全表扫一次），实测模拟器上四分钟跑不完。
        // 现在：一次全量读标签 → 内存里按作品合并 → 分块批量写。
        val existingByItem = HashMap<Pair<String, String>, MutableList<TagInstanceEntity>>()
        tagInstanceDao.allOfType(type.name).forEach { row ->
            existingByItem.getOrPut(row.sourceId to row.itemId) { mutableListOf() }.add(row)
        }
        // 只在 item.title 为空时才需要回填旧标题；一次性读回，避免原实现里「逐条扫全表」
        val knownTitles: Map<Pair<String, String>, String> = localTagDao.all(type.name)
            .associate { (it.sourceId to it.itemId) to it.title }

        val localRows = ArrayList<LocalTagEntity>(typeItems.size)
        val tagRows = ArrayList<TagInstanceEntity>(typeItems.size * 3)

        typeItems.forEach { item ->
            val rows = LinkedHashMap<String, TagInstanceEntity>()
            existingByItem[item.sourceId to item.itemId]?.forEach { row ->
                rows[row.tagName] = TagInstanceEntity(
                    sourceId = item.sourceId,
                    itemId = item.itemId,
                    itemType = type.name,
                    tagName = row.tagName,
                    category = row.category,
                    confidence = row.confidence,
                    origin = row.origin,
                    updatedAt = row.updatedAt.takeIf { it > 0 } ?: now,
                )
            }
            item.tags.forEach { entry ->
                val canonical = TagAlias.canonicalOf(entry.name)
                if (canonical.isBlank()) return@forEach
                val incoming = TagTable.Entry(canonical, entry.category, entry.confidence, entry.origin)
                val merged = TagTable.mergeEntry(
                    existing = rows[canonical]?.let {
                        TagTable.Entry(it.tagName, it.category, it.confidence, it.origin)
                    },
                    incoming = incoming,
                )
                rows[canonical] = TagInstanceEntity(
                    sourceId = item.sourceId,
                    itemId = item.itemId,
                    itemType = type.name,
                    tagName = canonical,
                    category = merged.category.ifBlank { TagTagger.CATEGORY_GENRE },
                    confidence = merged.confidence.coerceIn(0, 100),
                    origin = merged.origin.ifBlank { TagTagger.ORIGIN_TEXT },
                    updatedAt = now,
                )
            }
            val mergedTags = rows.values.toList()
            localRows += LocalTagEntity(
                sourceId = item.sourceId,
                itemId = item.itemId,
                itemType = type.name,
                title = item.title.ifBlank { knownTitles[item.sourceId to item.itemId].orEmpty() },
                cover = item.cover,
                detailUrl = item.detailUrl,
                sourceName = item.sourceName,
                author = item.author,
                tags = mergedTags.joinToString("|") { it.tagName },
                tagsLower = "|" + mergedTags.joinToString("|") { it.tagName.lowercase() } + "|",
                updatedAt = now,
            )
            tagRows += mergedTags
        }

        // 分块写：单批 2000 条，既避免一次撑爆内存，也把事务数从「每条一个」降到十几批
        val total = localRows.size
        var written = 0
        localRows.chunked(IMPORT_CHUNK).forEach { chunk ->
            localTagDao.upsertAll(chunk)
            written += chunk.size
            // 每批回报一次：写库是整段导入里最耗时的一步，没有进度用户只会觉得「卡死」
            onProgress?.invoke(written, total)
        }
        tagRows.chunked(IMPORT_CHUNK).forEach { tagInstanceDao.upsertAll(it) }
        return typeItems.size
    }

    companion object {
        /**
         * 云端打标表默认地址（v1.8.1.5）。
         *
         * 走 jsDelivr CDN（国内可达、带缓存）；`raw.githubusercontent.com` 直连经常被墙。
         * PC 端工具（独立仓库 leimao-tagger）把表推到 `data/tag_table.json` 后，这里拉到的就是最新版
         * （jsDelivr 有分钟级缓存，改完稍等片刻）。
         */
        const val DEFAULT_CLOUD_TABLE_URL: String =
            "https://cdn.jsdelivr.net/gh/leimao2333/leimao-tagger@main/data/tag_table.json"

        /**
         * 云端**版本清单**地址（v1.8.2.0）。
         *
         * 打标表现在有十几 MB，每次导入都整表拉一遍太浪费。清单只有几百字节，
         * 先读它判断 `generatedAt` 有没有比本地新，再决定要不要下载 [DEFAULT_CLOUD_TABLE_URL]。
         */
        const val DEFAULT_CLOUD_MANIFEST_URL: String =
            "https://cdn.jsdelivr.net/gh/leimao2333/leimao-tagger@main/data/manifest.json"

        /**
         * 由清单地址推导出整表地址。
         *
         * 追加 `?t=<分钟>` 绕过 jsDelivr 的缓存：同一个 URL 它可能缓存十几小时，
         * 刚 push 完的数据不加参数会一直拿到旧的。参数一变就是新的缓存键。
         */
        fun tableUrlOf(manifestUrl: String, latest: String): String {
            val base = manifestUrl.substringBeforeLast('/')
            val name = latest.ifBlank { "tag_table.json" }
            return "$base/$name?t=${System.currentTimeMillis() / 60_000}"
        }

        /** 同一作品同一信号的最短记录间隔：半小时 */
        private const val SIGNAL_TTL_MS = 30 * 60 * 1000L

        /** 批量导入时每批写入的条数（v1.8.2.0） */
        private const val IMPORT_CHUNK = 2000

        /** 流式导出时每页读多少条（v1.8.3.0 待办 #20）：太大占内存，太小分页往返多 */
        private const val EXPORT_PAGE = 500

        /** 文本标签被行为确认到多少分后改标「行为」来源 */
        private const val BEHAVIOR_PROMOTE_THRESHOLD = 80

        /**
         * 索引时用来取列表的关键词（v1.8.1.2）。
         *
         * 不走分类页是因为大量源的分类入口已经失效（实测部分源抓不到任何条目）；
         * 这几个词覆盖面广，能稳定带出各站的作品列表，再由打标器逐部打标签。
         */
        private val INDEX_PROBE_KEYWORDS = listOf("恋爱", "冒险", "全彩", "无码", "奇幻")
    }
}
