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

/**
 * 源健康遍历（手动触发，不参与日常单测）。
 *
 * 环境变量 / 项目属性：
 * - `LEIMAO_REAL_TESTS=1`            必须，否则跳过
 * - `LEIMAO_HEALTH_FILE`             源包路径（建议绝对路径）
 * - `LEIMAO_HEALTH_LIMIT`            只测前 N 个（0/缺省 = 全部）
 * - `LEIMAO_HEALTH_CONCURRENCY`      并发数，默认 8
 * - `LEIMAO_HEALTH_KEYWORD`          搜索探测词，默认「斗破苍穹」
 * - `LEIMAO_HEALTH_OUT`              报告输出路径（建议绝对路径）
 *
 * 探测策略（v2）：**不把单一关键词的无结果当成源坏了**。
 * 先用关键词搜索；拿不到可读章节时，改用该源自己的「发现页」取一本真书再试。
 * 这样言情/文学站（没有「斗破苍穹」）不会被误判成目录为空。
 */
class SourceHealthProbeTest {

    private data class Probe(
        val name: String,
        val type: String,
        val stage: String,
        val via: String,
        val counts: String,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null
    }

    @Test
    fun `遍历源健康检查`() {
        println("SOURCE_HEALTH_ENV=" + System.getenv().filterKeys { it.startsWith("LEIMAO") })
        assumeTrue("需要 LEIMAO_REAL_TESTS=1", System.getenv("LEIMAO_REAL_TESTS") == "1")
        val path = System.getenv("LEIMAO_HEALTH_FILE") ?: "../leimao-sources/novel.json"
        val file = File(path).absoluteFile
        println("SOURCE_HEALTH_CWD=" + File(".").absolutePath + " FILE=" + file.path)
        assumeTrue("找不到源文件：${file.path}", file.exists())
        val limit = System.getenv("LEIMAO_HEALTH_LIMIT")?.toIntOrNull() ?: 0
        val concurrency = System.getenv("LEIMAO_HEALTH_CONCURRENCY")?.toIntOrNull() ?: 8
        val keyword = System.getenv("LEIMAO_HEALTH_KEYWORD") ?: "斗破苍穹"

        val all = LegadoBookSource.parseList(file.readText())
            .let { if (limit > 0) it.take(limit) else it }
        assumeTrue("源列表为空", all.isNotEmpty())

        val sem = Semaphore(concurrency.coerceIn(1, 16))
        val results = runBlocking {
            coroutineScope {
                all.map { src -> async(Dispatchers.IO) { sem.withPermit { probe(src, keyword) } } }.awaitAll()
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
        println("SOURCE_HEALTH_FILE=$path")
        println("SOURCE_HEALTH_TOTAL=${results.size} OK=${results.count { it.ok }} FAIL=${results.count { !it.ok }}")
        println("SOURCE_HEALTH_REPORT=${out.absolutePath}")
        results.forEach { r ->
            println(
                "SOURCE_HEALTH ${if (r.ok) "OK  " else "FAIL"} ${r.type} ${r.name} " +
                    "[${r.stage}/${r.via}] ${r.counts}${r.error?.let { " :: $it" } ?: ""}",
            )
        }
    }

    private suspend fun probe(src: LegadoBookSource, keyword: String): Probe {
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
            id = "probe_${src.name.hashCode()}",
            name = src.name,
            type = st,
            baseUrl = src.url,
            format = SourceFormat.LEGADO,
        )
        val ad = LegadoSourceAdapter(info, src)

        // 1) 搜索
        val searchItems = runCatching { withTimeoutOrNull(20_000) { ad.search(keyword, 1) } }
            .getOrNull().orEmpty()
        var via = "search"
        var chapters = findChapters(ad, searchItems.take(3))

        // 2) 搜索拿不到可读章节 → 用该源的发现页取一本真书（避免关键词不适用造成误判）
        if (chapters.isEmpty()) {
            val cats = runCatching { withTimeoutOrNull(15_000) { ad.exploreCategories() } }
                .getOrNull().orEmpty()
            for (cat in cats.take(4)) {
                val list = runCatching { withTimeoutOrNull(20_000) { ad.explore(cat.url, 1) } }
                    .getOrNull().orEmpty()
                if (list.isEmpty()) continue
                val toc = findChapters(ad, list.take(3))
                if (toc.isNotEmpty()) {
                    chapters = toc
                    via = "discovery"
                    break
                }
            }
        }

        if (chapters.isEmpty()) {
            val detail = if (searchItems.isEmpty() && via == "search") "搜索无结果、发现页也无可用书" else "目录 0 章"
            return Probe(src.name, typeName, "toc", via, "搜索${searchItems.size}", detail)
        }

        // 3) 正文：卷首/公告章可能为空，试前几章，取第一个有内容的
        var textLen = 0
        var imgN = 0
        for (chapter in chapters.take(3)) {
            val content = runCatching { withTimeoutOrNull(20_000) { ad.content(chapter) } }.getOrNull()
            textLen = content?.novelText?.length ?: 0
            imgN = content?.comicImages?.size ?: 0
            if (textLen > 0 || imgN > 0) break
        }
        val body = if (src.isComic) "图${imgN}" else "字${textLen}"
        if (textLen == 0 && imgN == 0) {
            return Probe(
                src.name, typeName, "content", via,
                "搜索${searchItems.size} 目录${chapters.size}", "正文为空（试了前 3 章）",
            )
        }
        return Probe(
            src.name, typeName, "-", via,
            "搜索${searchItems.size} 目录${chapters.size} $body", null,
        )
    }

    /** 依次对候选书取详情+目录，返回第一个非空目录 */
    private suspend fun findChapters(ad: LegadoSourceAdapter, items: List<MediaItem>): List<ChapterItem> {
        for (candidate in items) {
            val detail = runCatching { withTimeoutOrNull(15_000) { ad.detail(candidate) } }.getOrNull()
                ?: continue
            val toc = runCatching { withTimeoutOrNull(20_000) { ad.chapterList(detail) } }
                .getOrNull().orEmpty()
            if (toc.isNotEmpty()) return toc
        }
        return emptyList()
    }

    private fun toJson(results: List<Probe>): String {
        val arr = com.google.gson.JsonArray()
        results.forEach { r ->
            arr.add(com.google.gson.JsonObject().apply {
                addProperty("name", r.name)
                addProperty("type", r.type)
                addProperty("stage", r.stage)
                addProperty("via", r.via)
                addProperty("counts", r.counts)
                addProperty("ok", r.ok)
                addProperty("error", r.error)
            })
        }
        return arr.toString()
    }
}
