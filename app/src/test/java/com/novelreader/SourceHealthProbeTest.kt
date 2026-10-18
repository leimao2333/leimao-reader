package com.novelreader

import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.legado.LegadoBookSource
import com.novelreader.source.legado.LegadoSourceAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * 源健康遍历（手动触发，不参与日常单测）。
 *
 * 环境变量 / 项目属性：
 * - `LEIMAO_REAL_TESTS=1`            必须，否则跳过
 * - `LEIMAO_HEALTH_FILE`             源包路径或聚合清单 URL（默认聚合清单 all.json）
 * - `LEIMAO_HEALTH_LIMIT`            只测前 N 个（0/缺省 = 全部）
 * - `LEIMAO_HEALTH_CONCURRENCY`      并发数，默认 8
 * - `LEIMAO_HEALTH_KEYWORD`          逗号分隔的覆盖词；默认按源类型尝试 2 个词
 * - `LEIMAO_HEALTH_OUT`              报告输出路径（建议绝对路径）
 *
 * 探测策略：优先从源自己的发现页取书；再按媒体类型尝试多个关键词。
 * 无任何匹配结果是「无法判定」，与目录/正文规则失败分开统计；HTTP 请求使用真实
 * OkHttp call timeout，避免阻塞式 socket 读穿透协程超时并长期占用并发许可。
 */
class SourceHealthProbeTest {

    private data class Probe(
        val sourceId: String,
        val name: String,
        val type: String,
        val status: String,
        val stage: String,
        val via: String,
        val counts: String,
        val searchMs: Long,
        val exploreMs: Long,
        val detailTocMs: Long,
        val contentMs: Long,
        val totalMs: Long,
        val error: String?,
    ) {
        val ok: Boolean get() = status == "healthy"
    }

    @Test
    fun `遍历源健康检查`() {
        println("SOURCE_HEALTH_ENV=" + System.getenv().filterKeys { it.startsWith("LEIMAO") })
        assumeTrue("需要 LEIMAO_REAL_TESTS=1", System.getenv("LEIMAO_REAL_TESTS") == "1")
        val path = System.getenv("LEIMAO_HEALTH_FILE")
        val loaded = runCatching { SourceBundleTestSupport.loadLegado(path) }
            .getOrElse { throw AssertionError("无法加载书源清单：${it.message}", it) }
        val limit = System.getenv("LEIMAO_HEALTH_LIMIT")?.toIntOrNull() ?: 0
        val concurrency = System.getenv("LEIMAO_HEALTH_CONCURRENCY")?.toIntOrNull() ?: 8
        val keywordOverride = System.getenv("LEIMAO_HEALTH_KEYWORD")
            ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.take(3)

        val all = loaded.sources.let { if (limit > 0) it.take(limit) else it }
        assumeTrue("源列表为空", all.isNotEmpty())
        println(
            "SOURCE_HEALTH_MANIFEST=${loaded.manifestUrl} " +
                "MANIFEST_MS=${loaded.manifestMs} LEGADO_BUNDLE_MS=${loaded.legadoBundleMs}",
        )
        println(
            "SOURCE_HEALTH_RECORDS=${all.size} " +
                "UNIQUE_NAMES=${all.map { it.name }.distinct().size} " +
                "UNIQUE_CONFIGS=${all.map { SourceBundleTestSupport.sourceId(it, "probe") }.distinct().size}",
        )

        val sem = Semaphore(concurrency.coerceIn(1, 16))
        val results = runBlocking {
            coroutineScope {
                all.map { src ->
                    async(Dispatchers.IO) { sem.withPermit { probe(src, keywordOverride) } }
                }.awaitAll()
            }
        }

        val outPath = System.getenv("LEIMAO_HEALTH_OUT")
        val out = if (!outPath.isNullOrBlank()) {
            File(outPath).absoluteFile
        } else {
            File(System.getProperty("user.dir"), "build/source-health-report.json").absoluteFile
        }
        out.parentFile?.mkdirs()
        out.writeText(toJson(results))
        val csv = File(out.parentFile, out.nameWithoutExtension + ".csv")
        csv.writeText(toCsv(results))
        println("SOURCE_HEALTH_FILE=${path ?: SourceBundleTestSupport.DEFAULT_MANIFEST_URL}")
        val inconclusive = results.count { it.status == "inconclusive" }
        val failed = results.size - results.count { it.ok } - inconclusive
        println(
            "SOURCE_HEALTH_TOTAL=${results.size} HEALTHY=${results.count { it.ok }} " +
                "ISSUES=$failed INCONCLUSIVE=$inconclusive",
        )
        println(
            "SOURCE_HEALTH_UNIQUE_NAMES=${results.map { it.name }.distinct().size} " +
                "UNIQUE_CONFIGS=${results.map { it.sourceId }.distinct().size} " +
                "MEDIAN_MS=${SourceBundleTestSupport.medianDurationMs(results.map { it.totalMs })}",
        )
        results.groupingBy { it.name }.eachCount().filterValues { it > 1 }
            .forEach { (name, count) -> println("SOURCE_HEALTH_DUPLICATE_NAME $name records=$count") }
        println("SOURCE_HEALTH_REPORT=${out.absolutePath}")
        println("SOURCE_HEALTH_CSV=${csv.absolutePath}")
        results.forEach { r ->
            println(
                "SOURCE_HEALTH ${r.status.uppercase()} ${r.sourceId} ${r.type} ${r.name} " +
                    "[${r.stage}/${r.via}] ${r.counts} " +
                    "ms(search=${r.searchMs}, explore=${r.exploreMs}, detailToc=${r.detailTocMs}, " +
                    "content=${r.contentMs}, total=${r.totalMs})" +
                    "${r.error?.let { " :: $it" } ?: ""}",
            )
        }
        results.sortedByDescending { it.totalMs }.take(10).forEachIndexed { index, r ->
            println("SOURCE_HEALTH_SLOW_${index + 1} ${r.name} totalMs=${r.totalMs} stage=${r.stage}")
        }
    }

    private suspend fun probe(src: LegadoBookSource, keywordOverride: List<String>?): Probe {
        var searchMs = 0L
        var exploreMs = 0L
        var detailTocMs = 0L
        var contentMs = 0L
        val totalStartedAt = System.nanoTime()
        val typeName = when (src.type) {
            2 -> "漫画"
            1 -> "有声"
            3 -> "视频"
            else -> "小说"
        }
        val st = when (src.type) {
            2 -> SourceType.COMIC
            3 -> SourceType.VIDEO
            else -> SourceType.NOVEL
        }
        val info = SourceInfo(
            id = SourceBundleTestSupport.sourceId(src, "probe"),
            name = src.name,
            type = st,
            baseUrl = src.url,
            format = SourceFormat.LEGADO,
        )
        val ad = LegadoSourceAdapter(info, src, requestTimeoutMs = REQUEST_TIMEOUT_MS)
        var chapters = emptyList<ChapterItem>()
        var via = "discovery"
        var searchItemCount = 0
        var candidateCount = 0

        var cats = emptyList<com.novelreader.domain.model.ExploreCategory>()
        exploreMs += measureTimeMillis {
            cats = runCatching {
                withTimeoutOrNull(COROUTINE_TIMEOUT_MS) { ad.exploreCategories() }
            }.getOrNull().orEmpty()
        }
        for (cat in cats.filter { it.url.isNotBlank() }.distinctBy { it.url }.take(2)) {
            var list = emptyList<MediaItem>()
            exploreMs += measureTimeMillis {
                list = runCatching {
                    withTimeoutOrNull(COROUTINE_TIMEOUT_MS) { ad.explore(cat.url, 1) }
                }.getOrNull().orEmpty()
            }
            candidateCount += list.size
            if (list.isEmpty()) continue
            val (toc, tocMs) = findChapters(ad, list.take(1))
            detailTocMs += tocMs
            if (toc.isNotEmpty()) {
                chapters = toc
                break
            }
        }

        if (chapters.isEmpty()) {
            via = "search"
            for (keyword in keywordOverride ?: defaultKeywords(src)) {
                var searchItems = emptyList<MediaItem>()
                searchMs += measureTimeMillis {
                    searchItems = runCatching {
                        withTimeoutOrNull(COROUTINE_TIMEOUT_MS) { ad.search(keyword, 1) }
                    }.getOrNull().orEmpty()
                }
                searchItemCount += searchItems.size
                candidateCount += searchItems.size
                if (searchItems.isEmpty()) continue
                val (toc, tocMs) = findChapters(ad, searchItems.take(1))
                detailTocMs += tocMs
                if (toc.isNotEmpty()) {
                    chapters = toc
                    break
                }
            }
        }

        if (chapters.isEmpty()) {
            val status = if (candidateCount == 0) "inconclusive" else "toc_failed"
            val detail = if (candidateCount == 0) {
                "发现页和类型关键词均无匹配书目；不能据此判定源失效"
            } else {
                "发现/搜索有候选书目，但详情或目录未取到章节"
            }
            return Probe(
                info.id, src.name, typeName, status, "toc", via, "搜索$searchItemCount 候选$candidateCount",
                searchMs, exploreMs, detailTocMs, contentMs,
                (System.nanoTime() - totalStartedAt) / 1_000_000, detail,
            )
        }

        // 3) 正文：卷首/公告章可能为空，试前几章，取第一个有内容的
        var textLen = 0
        var imgN = 0
        contentMs = measureTimeMillis {
            for (chapter in chapters.take(2)) {
                val content = runCatching {
                    withTimeoutOrNull(COROUTINE_TIMEOUT_MS) { ad.content(chapter) }
                }.getOrNull()
                textLen = content?.novelText?.length ?: 0
                imgN = content?.comicImages?.size ?: 0
                if (textLen > 0 || imgN > 0) break
            }
        }
        val body = if (src.isComic) "图${imgN}" else "字${textLen}"
        if (textLen == 0 && imgN == 0) {
            return Probe(
                info.id, src.name, typeName, "content_failed", "content", via,
                "搜索$searchItemCount 目录${chapters.size}",
                searchMs, exploreMs, detailTocMs, contentMs,
                (System.nanoTime() - totalStartedAt) / 1_000_000,
                "正文为空（试了前 2 章）",
            )
        }
        return Probe(
            info.id, src.name, typeName, "healthy", "-", via,
            "搜索$searchItemCount 目录${chapters.size} $body",
            searchMs, exploreMs, detailTocMs, contentMs,
            (System.nanoTime() - totalStartedAt) / 1_000_000,
            null,
        )
    }

    /** 依次对候选书取详情+目录，返回第一个非空目录 */
    private suspend fun findChapters(
        ad: LegadoSourceAdapter,
        items: List<MediaItem>,
    ): Pair<List<ChapterItem>, Long> {
        var elapsed = 0L
        for (candidate in items) {
            var detail: MediaItem? = null
            elapsed += measureTimeMillis {
                detail = runCatching {
                    withTimeoutOrNull(COROUTINE_TIMEOUT_MS) { ad.detail(candidate) }
                }.getOrNull()
            }
            val detailItem = detail ?: continue
            var toc = emptyList<ChapterItem>()
            elapsed += measureTimeMillis {
                toc = runCatching {
                    withTimeoutOrNull(COROUTINE_TIMEOUT_MS) { ad.chapterList(detailItem) }
                }.getOrNull().orEmpty()
            }
            if (toc.isNotEmpty()) return toc to elapsed
        }
        return emptyList<ChapterItem>() to elapsed
    }

    private fun defaultKeywords(source: LegadoBookSource): List<String> = when (source.type) {
        2 -> listOf("鬼灭之刃", "海贼王")
        3 -> listOf("流浪地球", "斗破苍穹")
        else -> listOf("斗破苍穹", "三体")
    }

    private fun toJson(results: List<Probe>): String {
        val arr = com.google.gson.JsonArray()
        results.forEach { r ->
            arr.add(com.google.gson.JsonObject().apply {
                addProperty("sourceId", r.sourceId)
                addProperty("name", r.name)
                addProperty("type", r.type)
                addProperty("status", r.status)
                addProperty("stage", r.stage)
                addProperty("via", r.via)
                addProperty("counts", r.counts)
                addProperty("searchMs", r.searchMs)
                addProperty("exploreMs", r.exploreMs)
                addProperty("detailTocMs", r.detailTocMs)
                addProperty("contentMs", r.contentMs)
                addProperty("totalMs", r.totalMs)
                addProperty("ok", r.ok)
                addProperty("error", r.error)
            })
        }
        val durations = results.map { it.totalMs }
        val summary = com.google.gson.JsonObject().apply {
            addProperty("recordCount", results.size)
            addProperty("uniqueNameCount", results.map { it.name }.distinct().size)
            addProperty("uniqueConfigCount", results.map { it.sourceId }.distinct().size)
            addProperty("healthyCount", results.count { it.ok })
            addProperty("inconclusiveCount", results.count { it.status == "inconclusive" })
            addProperty("issueCount", results.count { it.status.endsWith("_failed") })
            addProperty("averageTotalMs", durations.average().takeIf { it.isFinite() } ?: 0.0)
            addProperty("medianTotalMs", SourceBundleTestSupport.medianDurationMs(durations))
            val duplicateNames = com.google.gson.JsonArray()
            results.groupingBy { it.name }.eachCount().filterValues { it > 1 }
                .forEach { (name, count) ->
                    duplicateNames.add(com.google.gson.JsonObject().apply {
                        addProperty("name", name)
                        addProperty("count", count)
                    })
                }
            add("duplicateNames", duplicateNames)
        }
        return com.google.gson.JsonObject().apply {
            add("summary", summary)
            add("results", arr)
        }.toString()
    }

    private fun toCsv(results: List<Probe>): String = buildString {
        appendLine("sourceId,name,type,status,ok,stage,via,searchMs,exploreMs,detailTocMs,contentMs,totalMs,counts,error")
        results.forEach { r ->
            appendLine(
                listOf(
                    r.sourceId, r.name, r.type, r.status, r.ok.toString(), r.stage, r.via,
                    r.searchMs.toString(), r.exploreMs.toString(), r.detailTocMs.toString(),
                    r.contentMs.toString(), r.totalMs.toString(), r.counts, r.error.orEmpty(),
                ).joinToString(",") { value ->
                    "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
                },
            )
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 6_000L
        const val COROUTINE_TIMEOUT_MS = 7_000L
    }
}
