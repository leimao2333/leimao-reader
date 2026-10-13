package com.novelreader.source.moontv

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType

/**
 * 苹果 CMS（maccms10）采集接口的**纯解析逻辑**。
 *
 * 全部方法不依赖网络与 Android，便于离线单测。接口约定：
 * - `?ac=list&pg=1`      → 最新列表（无封面）+ 一级分类 `class`
 * - `?ac=detail&pg=1`    → 最新列表（含封面）+ 播放地址
 * - `?ac=detail&t=分类id&pg=N` → 分类分页浏览
 * - `?ac=detail&wd=关键词&pg=N` → 搜索
 * - `?ac=detail&ids=影片id`     → 详情
 */
object MacCmsParser {

    /** 单集（一条播放地址） */
    data class Episode(
        /** 线路名（`vod_play_from`） */
        val line: String,
        /** 集名（`第01集` / `正片` …） */
        val title: String,
        /** 播放地址 */
        val url: String,
    )

    /** 一条线路：线路名 + 该线路的剧集（v1.3.6.0） */
    data class EpisodeLine(
        /** 线路名（`vod_play_from` 对应项，可能为空） */
        val name: String,
        val episodes: List<Episode>,
    )

    /** 拼接采集接口地址（自动处理已有 `?`、URL 编码参数值） */
    fun buildUrl(api: String, params: List<Pair<String, String>>): String {
        val base = api.trim()
        val query = params
            .filter { it.second.isNotEmpty() }
            .joinToString("&") { (k, v) -> "$k=" + urlEncode(v) }
        if (query.isEmpty()) return base
        return base + (if (base.contains('?')) "&" else "?") + query
    }

    private fun urlEncode(value: String): String =
        runCatching { java.net.URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)

    /** 解析影片列表 */
    fun parseList(json: String, sourceId: String, sourceName: String): List<MediaItem> =
        listArray(json)?.mapNotNull { el ->
            (el as? JsonObject)?.let { toItem(it, sourceId, sourceName) }
        }.orEmpty()

    /** 解析「详情」响应里的第一条影片（按 id 精确命中优先） */
    fun findItem(json: String, vodId: String, sourceId: String, sourceName: String): MediaItem? {
        val arr = listArray(json) ?: return null
        val objs = arr.mapNotNull { it as? JsonObject }
        val hit = objs.firstOrNull { str(it, "vod_id") == vodId } ?: objs.firstOrNull()
        return hit?.let { toItem(it, sourceId, sourceName) }
    }

    /** 解析分类响应里的影片剧集（详情接口专用，含 `vod_play_url`） */
    fun episodesOf(json: String, vodId: String): List<Episode> {
        val arr = listArray(json) ?: return emptyList()
        val objs = arr.mapNotNull { it as? JsonObject }
        val hit = objs.firstOrNull { str(it, "vod_id") == vodId } ?: objs.firstOrNull() ?: return emptyList()
        return parseEpisodes(str(hit, "vod_play_from"), str(hit, "vod_play_url"))
    }

    /** 解析分类响应里的**全部线路**（v1.3.6.0 播放页「换源」） */
    fun episodeLinesOf(json: String, vodId: String): List<EpisodeLine> {
        val arr = listArray(json) ?: return emptyList()
        val objs = arr.mapNotNull { it as? JsonObject }
        val hit = objs.firstOrNull { str(it, "vod_id") == vodId } ?: objs.firstOrNull() ?: return emptyList()
        return parseEpisodeLines(str(hit, "vod_play_from"), str(hit, "vod_play_url"))
    }

    /**
     * `vod_play_from` + `vod_play_url` → **全部线路**（v1.3.6.0 播放页「换源」）。
     *
     * 两个字段都用 `$$$` 分割线路、线路内用 `#` 分割集、集内用 `$` 分割「集名 / 地址」。
     *
     * 约定：**按集数降序**返回，集数相同时保持响应里的原始顺序（用稳定排序）。
     * 集数最多的线路更完整、也能避开「预告 / 花絮」这类短线路，所以排第一作为默认选路。
     */
    fun parseEpisodeLines(playFrom: String?, playUrl: String?): List<EpisodeLine> {
        if (playUrl.isNullOrBlank()) return emptyList()
        val lineNames = playFrom?.split("$$$").orEmpty()

        return playUrl.split("$$$").mapIndexedNotNull { index, line ->
            val lineName = lineNames.getOrNull(index)?.trim().orEmpty()
            val episodes = line.split("#").mapNotNull { raw ->
                val text = raw.trim()
                if (text.isEmpty()) return@mapNotNull null
                val sep = text.indexOf('$')
                val title: String
                val url: String
                if (sep <= 0) {
                    // 只有地址、没有集名
                    title = ""
                    url = text
                } else {
                    title = text.substring(0, sep).trim()
                    // 兼容「集名$地址$备用地址」写法，取第一个地址
                    url = text.substring(sep + 1).trim().substringBefore('$').trim()
                }
                if (url.isBlank()) return@mapNotNull null
                Episode(line = lineName, title = title, url = url)
            }
            if (episodes.isEmpty()) {
                null
            } else {
                EpisodeLine(
                    name = lineName,
                    episodes = episodes.mapIndexed { i, ep ->
                        // 没有集名的补「第 N 集」，N 按线路内序号（各线路各自从 1 开始）
                        ep.copy(title = ep.title.ifBlank { "第${i + 1}集" })
                    },
                )
            }
        }.sortedByDescending { it.episodes.size }
    }

    /**
     * 只取一条线路的剧集（`chapterList` 用）：**集数最多的那条**。
     *
     * 语义与 v1.3.3.0 起保持一致，现在只是改由 [parseEpisodeLines] 实现。
     */
    fun parseEpisodes(playFrom: String?, playUrl: String?): List<Episode> =
        parseEpisodeLines(playFrom, playUrl).firstOrNull()?.episodes.orEmpty()

    /** 解析一级分类（`class` 中 `type_pid == 0` 的条目） */
    fun parseCategories(json: String): List<ExploreCategory> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        if (!root.isJsonObject) return emptyList()
        val arr = root.asJsonObject.getAsJsonArray("class") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = str(o, "type_id")
            val name = str(o, "type_name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val pid = str(o, "type_pid")
            if (pid.isNotBlank() && pid != "0") return@mapNotNull null
            ExploreCategory(title = name, url = id)
        }
    }

    /** 响应里是否代表成功（`code`=1 或直接给出 list） */
    fun isOk(json: String): Boolean {
        if (listArray(json) != null) {
            val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            if (root != null && root.isJsonObject) {
                val code = str(root.asJsonObject, "code")
                if (code.isNotBlank() && code != "1") return false
            }
            return true
        }
        return false
    }

    // ===== 内部工具 =====

    private fun listArray(json: String): JsonArray? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        return when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("list")
            else -> null
        }
    }

    private fun toItem(o: JsonObject, sourceId: String, sourceName: String): MediaItem? {
        val id = str(o, "vod_id")
        val name = str(o, "vod_name")
        if (id.isBlank() || name.isBlank()) return null
        return MediaItem(
            id = id,
            title = name,
            cover = str(o, "vod_pic").takeIf { it.isNotBlank() && it.startsWith("http") },
            author = str(o, "type_name").takeIf { it.isNotBlank() },
            desc = stripHtml(str(o, "vod_blurb").ifBlank { str(o, "vod_content") })
                .takeIf { it.isNotBlank() }?.take(200),
            type = SourceType.VIDEO,
            detailUrl = id,
            sourceId = sourceId,
            sourceName = sourceName,
            extras = buildMap {
                put("remarks", str(o, "vod_remarks"))
                put("year", str(o, "vod_year"))
                put("score", str(o, "vod_score"))
                put("area", str(o, "vod_area"))
                put("playFrom", str(o, "vod_play_from"))
                // v1.3.4.0：详情页要展示的元信息，此前解析了却没往外传
                put("actor", str(o, "vod_actor"))
                put("director", str(o, "vod_director"))
                put("lang", str(o, "vod_lang"))
                put("typeName", str(o, "type_name"))
                put("total", str(o, "vod_total"))
                // 详情接口才带 vod_play_url；用它区分「完整简介」是否值得带出 ——
                // 搜索/列表响应不带，免得把 2KB 简介塞进导航参数里
                if (str(o, "vod_play_url").isNotBlank()) {
                    val full = stripHtml(str(o, "vod_content"))
                    if (full.isNotBlank()) put("content", full)
                }
            },
        )
    }

    /** 取字符串字段（数字/字符串均兼容；`null` 返回空串） */
    private fun str(o: JsonObject, name: String): String {
        val el = o.get(name) ?: return ""
        if (el.isJsonNull) return ""
        return runCatching { el.asString.trim() }.getOrDefault("")
    }

    /** 去掉 HTML 标签（简介常带 `<p>` 等） */
    private fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
