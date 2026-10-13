package com.novelreader.source.moontv

import com.novelreader.core.network.HttpClient
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MoonTV / LunaTV 采集源适配器（苹果 CMS maccms10 接口）。
 *
 * 一个 [MoonTvSite] 对应一个源：
 * - 搜索：`?ac=detail&wd=关键词&pg=N`
 * - 详情：`?ac=detail&ids=影片id`
 * - 选集：解析 `vod_play_from` / `vod_play_url`
 * - 分类浏览（影视首页用）：`?ac=list` 取一级分类，`?ac=detail&t=分类id&pg=N` 分页拉取
 */
class MoonTvSourceAdapter(
    override val info: SourceInfo,
    val site: MoonTvSite,
) : SourceAdapter {

    /** 一级分类缓存（分类接口较慢，首页会反复读取） */
    @Volatile
    private var categoriesCache: List<ExploreCategory>? = null

    private val requestHeaders: Map<String, String>
        get() = buildMap {
            put("Accept", "application/json, text/plain, */*")
            if (site.detail.startsWith("http")) put("Referer", site.detail)
        }

    private fun request(params: List<Pair<String, String>>): String =
        HttpClient.get(
            MacCmsParser.buildUrl(site.api, params),
            headers = requestHeaders,
            timeoutMs = REQUEST_TIMEOUT_MS,
            // 站点不可达属于确定性失败，重试只会让等待翻几倍（影视首页会一直转圈）
            retry = false,
        )

    // ===== 详情响应复用 =====
    //
    // 打开详情页会**并发**触发 detail() 与 chapterList()，两者请求的是同一个
    // `?ac=detail&ids=N`。原本是两次独立请求，任一失败就会出现「简介有、选集空」
    // 或反过来的半残页面（弱网下实测复现）。这里用互斥锁把并发调用收敛成一次请求，
    // 第二个调用直接复用第一个的结果。
    //
    // 带 TTL 是为了不长期返回过期剧集表：只在「同一部片、短时间内」
    // （典型场景就是详情页那两次背靠背调用）复用。
    private val detailMutex = Mutex()
    private var cachedDetailId: String? = null
    private var cachedDetailJson: String? = null
    private var cachedDetailAt = 0L

    private suspend fun detailJson(id: String): String = detailMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedDetailJson
        if (cached != null && cachedDetailId == id && now - cachedDetailAt < DETAIL_CACHE_TTL_MS) {
            return@withLock cached
        }
        val json = request(listOf("ac" to "detail", "ids" to id))
        cachedDetailId = id
        cachedDetailJson = json
        cachedDetailAt = now
        json
    }

    companion object {
        /**
         * 采集接口超时（毫秒）。
         *
         * 一份 MoonTV 配置动辄 30~90 个站点，用全局默认的 15s/90s 会让
         * 个别死站点把搜索拖到分钟级；这里收紧到 12s，保证有界等待。
         */
        const val REQUEST_TIMEOUT_MS = 12_000L

        /** 详情响应复用窗口：覆盖详情页那次「detail + 选集」的背靠背调用即可 */
        const val DETAIL_CACHE_TTL_MS = 30_000L
    }

    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        val json = runCatching {
            request(listOf("ac" to "detail", "wd" to keyword, "pg" to page.toString()))
        }.getOrNull() ?: return emptyList()
        return MacCmsParser.parseList(json, info.id, info.name)
    }

    override suspend fun detail(item: MediaItem): MediaItem {
        val json = runCatching { detailJson(item.id) }.getOrNull() ?: return item
        val full = MacCmsParser.findItem(json, item.id, info.id, info.name) ?: return item
        // 用详情补全封面 / 简介 / 备注，但保留原 id 与来源信息
        return full.copy(
            id = item.id,
            title = item.title.ifBlank { full.title },
            sourceId = item.sourceId,
            sourceName = item.sourceName,
            detailUrl = item.detailUrl,
        )
    }

    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val json = runCatching { detailJson(item.id) }.getOrNull() ?: return emptyList()
        return MacCmsParser.episodesOf(json, item.id).mapIndexed { i, ep ->
            ChapterItem(
                id = "$i",
                title = ep.title,
                url = ep.url,
                index = i,
                sourceId = info.id,
            )
        }
    }

    override suspend fun content(chapter: ChapterItem): ContentResult =
        ContentResult.video(chapter.url)

    override suspend fun test(): Boolean = runCatching {
        MacCmsParser.isOk(request(listOf("ac" to "list", "pg" to "1")))
    }.getOrDefault(false)

    override suspend fun exploreCategories(): List<ExploreCategory> {
        categoriesCache?.let { return it }
        // 故意不吞异常：调用方（影视首页）要据此区分「源不可达」与「源本身没有分类」
        val list = MacCmsParser.parseCategories(request(listOf("ac" to "list", "pg" to "1")))
        if (list.isNotEmpty()) categoriesCache = list
        return list
    }

    override suspend fun explore(url: String, page: Int): List<MediaItem> =
        exploreFilter(url, page, emptyMap())

    /**
     * 分类浏览（支持片库筛选）。
     *
     * 苹果 CMS 的 `ac=detail` 筛选参数：
     * - `t`     分类 id（空串 = 全部）
     * - `area`  地区（如「大陆」「美国」）
     * - `year`  年份（如「2024」）
     * - `order` 排序（`time` 最新 / `hits` 最热 / `score` 评分）
     *
     * 值为空的参数不提交（等价于「不限」）；源不支持某个参数时通常只是忽略它，
     * 不会报错，所以这里不需要为「源不支持筛选」做额外降级。
     */
    override suspend fun exploreFilter(
        url: String,
        page: Int,
        filters: Map<String, String>,
    ): List<MediaItem> {
        val params = buildList {
            add("ac" to "detail")
            if (url.isNotBlank()) add("t" to url)
            filters.forEach { (key, value) -> if (value.isNotBlank()) add(key to value) }
            add("pg" to page.toString())
        }
        val json = runCatching { request(params) }.getOrNull() ?: return emptyList()
        return MacCmsParser.parseList(json, info.id, info.name)
    }
}
