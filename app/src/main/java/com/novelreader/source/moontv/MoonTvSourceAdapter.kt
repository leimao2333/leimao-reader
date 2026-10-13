package com.novelreader.source.moontv

import com.novelreader.core.network.HttpClient
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo

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

    companion object {
        /**
         * 采集接口超时（毫秒）。
         *
         * 一份 MoonTV 配置动辄 30~90 个站点，用全局默认的 15s/90s 会让
         * 个别死站点把搜索拖到分钟级；这里收紧到 12s，保证有界等待。
         */
        const val REQUEST_TIMEOUT_MS = 12_000L
    }

    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        val json = runCatching {
            request(listOf("ac" to "detail", "wd" to keyword, "pg" to page.toString()))
        }.getOrNull() ?: return emptyList()
        return MacCmsParser.parseList(json, info.id, info.name)
    }

    override suspend fun detail(item: MediaItem): MediaItem {
        val json = runCatching {
            request(listOf("ac" to "detail", "ids" to item.id))
        }.getOrNull() ?: return item
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
        val json = runCatching {
            request(listOf("ac" to "detail", "ids" to item.id))
        }.getOrNull() ?: return emptyList()
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

    override suspend fun explore(url: String, page: Int): List<MediaItem> {
        val params = buildList {
            add("ac" to "detail")
            if (url.isNotBlank()) add("t" to url)
            add("pg" to page.toString())
        }
        val json = runCatching { request(params) }.getOrNull() ?: return emptyList()
        return MacCmsParser.parseList(json, info.id, info.name)
    }
}
