package com.novelreader

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType
import com.novelreader.source.legado.LegadoBookSource
import com.novelreader.source.legado.LegadoSourceAdapter
import com.novelreader.source.legado.LegadoRuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 源目录诊断（手动触发，不参与日常单测）。
 *
 * 对「搜索有结果但目录 0 章」的源，把**原始响应**落盘，便于人工定位是
 * 站点改版、请求方式不对、还是引擎模板/选择器问题。
 *
 * 环境变量 / 项目属性：
 * - `LEIMAO_DIAG=1`               必须，否则跳过
 * - `LEIMAO_DIAG_FILE`            源包路径（默认 ../leimao-sources/legado-all.json）
 * - `LEIMAO_DIAG_ONLY`            源名过滤，逗号分隔包含匹配；缺省 = 全部
 * - `LEIMAO_DIAG_KEYWORD`         搜索词，默认「斗破苍穹」
 * - `LEIMAO_DIAG_DIR`             转储目录（默认 build/toc-diag）
 * - `LEIMAO_DIAG_BOOKS`           每源诊断前 N 本书，默认 2
 */
class SourceTocDiagTest {

    @Test
    fun `诊断目录为空`() {
        println("TOC_DIAG_ENV=" + System.getenv().filterKeys { it.startsWith("LEIMAO") })
        assumeTrue("需要 LEIMAO_DIAG=1", System.getenv("LEIMAO_DIAG") == "1")
        val path = System.getenv("LEIMAO_DIAG_FILE") ?: "../leimao-sources/legado-all.json"
        val file = File(path).absoluteFile
        assumeTrue("找不到源文件：${file.path}", file.exists())
        val only = System.getenv("LEIMAO_DIAG_ONLY")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val keyword = System.getenv("LEIMAO_DIAG_KEYWORD") ?: "斗破苍穹"
        val dir = File(System.getenv("LEIMAO_DIAG_DIR") ?: "build/toc-diag").absoluteFile
        val books = System.getenv("LEIMAO_DIAG_BOOKS")?.toIntOrNull() ?: 2
        dir.mkdirs()

        val all = LegadoBookSource.parseList(file.readText())
        val targets = if (only.isNullOrEmpty()) all else all.filter { s -> only.any { s.name.contains(it) } }
        assumeTrue("没有匹配的源", targets.isNotEmpty())
        println("TOC_DIAG targets=${targets.size} keyword=$keyword dir=${dir.path}")

        val summary = StringBuilder()
        runBlocking {
            targets.forEach { src ->
                val line = diag(src, keyword, dir, books)
                summary.appendLine(line)
                println("TOC_DIAG $line")
            }
        }
        File(dir, "_summary.txt").writeText(summary.toString())
    }

    private suspend fun diag(
        src: LegadoBookSource,
        keyword: String,
        dir: File,
        books: Int,
    ): String {
        val typeName = when (src.type) {
            2 -> "漫画"; 1 -> "有声"; 3 -> "视频"; else -> "小说"
        }
        val st = when (src.type) {
            2 -> SourceType.COMIC; 3 -> SourceType.VIDEO; else -> SourceType.NOVEL
        }
        val info = SourceInfo(
            id = "diag_${src.name.hashCode()}",
            name = src.name,
            type = st,
            baseUrl = src.url,
            format = SourceFormat.LEGADO,
        )
        val ad = LegadoSourceAdapter(info, src)
        val tag = src.name.replace(Regex("[^\\w一-龥]+"), "_")
        val items: List<MediaItem> = runCatching {
            withTimeoutOrNull(20_000) { ad.search(keyword, 1) }
        }.getOrNull().orEmpty()

        if (items.isEmpty()) return "FAIL $typeName ${src.name} :: 搜索 0 条（站点/搜索规则问题，非目录问题）"

        val notes = mutableListOf<String>()
        for ((idx, item) in items.take(books).withIndex()) {
            val prefix = "${tag}__$idx"

            // 1) 原始详情页（按条目自带请求配置发请求）
            val rawDetail = fetchRaw(item.detailUrl)
            rawDetail?.let { File(dir, "$prefix.detail.txt").writeText(it) }

            // 2) 真实引擎的详情 + 目录结果
            val detail = runCatching { withTimeoutOrNull(15_000) { ad.detail(item) } }.getOrNull()
            val chapters = detail?.let { d ->
                runCatching { withTimeoutOrNull(20_000) { ad.chapterList(d) } }.getOrNull().orEmpty()
            }.orEmpty()

            // 3) 目录地址推导（复刻 resolveTocUrl 的公开逻辑）
            val tocRule = (src.ruleToc["url"]?.takeIf { it.isNotBlank() }
                ?: src.ruleBookInfo["tocUrl"]?.takeIf { it.isNotBlank() })
            var tocResolved: String? = null
            if (!tocRule.isNullOrBlank() && rawDetail != null) {
                tocResolved = if (tocRule.startsWith("http") || tocRule.contains("{{")) {
                    tocRule.replace("{{baseUrl}}", item.detailUrl)
                } else {
                    LegadoRuleEngine.extractString(rawDetail, tocRule, item.detailUrl)
                }
            }
            val rawToc = tocResolved?.takeIf { it.startsWith("http") }?.let { fetchRaw(it) }
            rawToc?.let { File(dir, "$prefix.toc.txt").writeText(it) }

            // 4) 详情字段诊断信息
            val diagObj = JsonObject().apply {
                addProperty("bookTitle", item.title)
                addProperty("detailUrl", item.detailUrl)
                addProperty("detailName", detail?.title)
                addProperty("tocRule", tocRule)
                addProperty("tocResolved", tocResolved)
                addProperty("rawDetailLen", rawDetail?.length)
                addProperty("rawDetailHead", rawDetail?.take(300))
                addProperty("rawTocLen", rawToc?.length)
                addProperty("rawTocHead", rawToc?.take(300))
                addProperty("chapterCount", chapters.size)
                addProperty("variableMap", Gson().toJson(item.variableMap))
            }
            File(dir, "$prefix.diag.json").writeText(diagObj.toString())

            notes.add(
                "书${idx}[${item.title.take(12)}] 详情${rawDetail?.length ?: -1}B " +
                    "目录URL=${tocResolved?.take(80) ?: "(用详情页)"} " +
                    "目录页${rawToc?.length ?: -1}B 章节${chapters.size}",
            )
        }
        val verdict = if (notes.any { it.contains("章节0") }) "FAIL" else "OK"
        return "$verdict $typeName ${src.name} :: ${notes.joinToString(" | ")}"
    }

    /** 按 legado 的 `地址,{json}` 约定发请求，返回原始文本 */
    private suspend fun fetchRaw(rawUrl: String): String? {
        val (url, optsJson) = splitOpts(rawUrl)
        if (!url.startsWith("http")) return null
        val headers = mutableMapOf<String, String>()
        var method = "GET"
        var body: String? = null
        var charset: String? = null
        if (!optsJson.isNullOrBlank()) {
            runCatching {
                val o = JsonParser.parseString(optsJson).asJsonObject
                method = o.get("method")?.asString ?: "GET"
                body = o.get("body")?.asString
                charset = o.get("charset")?.asString
                o.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
                    if (!v.isJsonNull) headers[k] = v.asString
                }
            }
        }
        return runCatching {
            withTimeoutOrNull(20_000) {
                if (method.equals("POST", true)) {
                    com.novelreader.core.network.HttpClient.post(
                        url, body ?: "", headers, charset,
                        if ((body ?: "").trimStart().startsWith("{")) "application/json; charset=utf-8"
                        else "application/x-www-form-urlencoded",
                    )
                } else {
                    com.novelreader.core.network.HttpClient.get(url, headers, charset)
                }
            }
        }.getOrNull()
    }

    /** 与 adapter 的 splitUrlOptions 同规则：最后一个 `,{` 之前是地址 */
    private fun splitOpts(raw: String?): Pair<String, String?> {
        if (raw.isNullOrBlank()) return "" to null
        val idx = raw.lastIndexOf(",{")
        return if (idx > 0) raw.substring(0, idx) to raw.substring(idx + 1) else raw to null
    }
}
