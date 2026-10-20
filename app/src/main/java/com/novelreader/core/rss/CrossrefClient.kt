package com.novelreader.core.rss

import com.novelreader.core.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Crossref REST API 客户端（v1.8.5.0）。
 *
 * 为什么选 Crossref 作为期刊数据源（而不是各家官方 RSS）：
 * - **免 key、免登录**，`api.crossref.org` 是学术基础设施，长期稳定；
 * - 返回结构化题录（DOI / 作者 / 卷 / 期 / 页 / 日期 / 类型），比 RSS 里的字段齐全；
 * - ACS、RSC 的 RSS 有 Cloudflare 挑战（实测 403），Crossref 不受影响；
 * - 顺带能按 DOI 查单篇，用于「补全题录」。
 *
 * 使用限制（<https://api.crossref.org/swagger-ui/index.html>）：礼貌池建议带联系方式，
 * 并发别太猛 —— 这里单请求串行、超时 20s，一个源一次最多拉 30 条。
 */
object CrossrefClient {

    private const val BASE = "https://api.crossref.org"

    /** 单次最多拉多少条（Crossref 单页上限 1000，但期刊订阅没必要拉那么多） */
    const val DEFAULT_ROWS = 30

    private const val TIMEOUT_MS = 20_000L

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        // 说明身份便于 Crossref 排查滥用（礼貌池）
        "User-Agent" to "LeimaoReader/1.0 (Android; +https://github.com/leimao2333/leimao-reader)",
    )

    /**
     * 按刊名检索期刊（用于「订阅任意期刊」）。
     *
     * @return 有 ISSN 的期刊，按收录量降序（把正牌刊排前面，野鸡刊靠后）
     */
    suspend fun searchJournals(query: String, rows: Int = 10): List<JournalRef> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val url = "$BASE/journals?query=${enc(q)}&rows=${rows.coerceIn(1, 50)}"
            val json = runCatching {
                HttpClient.get(url, headers(), timeoutMs = TIMEOUT_MS)
            }.getOrNull() ?: return@withContext emptyList()
            CrossrefJson.parseJournals(json).sortedByDescending { it.totalDois }
        }

    /**
     * 某刊最新论文（按见刊日期倒序）。
     *
     * @param issn 期刊 ISSN（print 或 online 均可）
     */
    suspend fun recentWorks(issn: String, rows: Int = DEFAULT_ROWS): List<WorkRef> =
        withContext(Dispatchers.IO) {
            val id = issn.trim()
            if (id.isEmpty()) return@withContext emptyList()
            val url = buildString {
                append("$BASE/journals/${enc(id)}/works")
                append("?rows=${rows.coerceIn(1, 100)}")
                append("&sort=published&order=desc")
                append("&select=DOI,title,author,published,published-online,published-print,")
                append("container-title,volume,issue,page,type,URL,abstract,publisher")
            }
            val json = runCatching {
                HttpClient.get(url, headers(), timeoutMs = TIMEOUT_MS)
            }.getOrNull() ?: return@withContext emptyList()
            CrossrefJson.parseWorks(json)
        }

    /** 按 DOI 查单篇（补全题录 / 摘要用） */
    suspend fun workByDoi(doi: String): WorkRef? = withContext(Dispatchers.IO) {
        val d = PaperMetaParser.normalizeDoi(doi)
        if (d.isEmpty()) return@withContext null
        val json = runCatching {
            HttpClient.get("$BASE/works/${enc(d)}", headers(), timeoutMs = TIMEOUT_MS)
        }.getOrNull() ?: return@withContext null
        CrossrefJson.parseWork(json)
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
