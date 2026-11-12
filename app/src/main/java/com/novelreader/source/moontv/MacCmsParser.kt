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

    /** 看起来是真实媒体文件的播放地址（m3u8 / mp4 / …，可带查询串） */
    private val MEDIA_URL_EXT =
        Regex("\\.(m3u8|mp4|mkv|flv|ts|mov|avi|webm)(\\?|$)", RegexOption.IGNORE_CASE)

    /** 同集数的线路里，优先挑播放地址带媒体后缀的那条（v1.9.5.1） */
    private fun looksLikePlayableUrl(url: String): Boolean = MEDIA_URL_EXT.containsMatchIn(url)

    /** 该地址能否直接交给播放器（不用再打开网页找真实流） */
    fun looksLikeDirectMedia(url: String): Boolean = looksLikePlayableUrl(url)

    /**
     * 从「播放页」HTML / 内联 JS 里提取真实流地址（v1.9.5.1）。
     *
     * 采集站常把 `vod_play_url` 写成播放页地址（页面本身是 HTML，用 hls.js / DPlayer
     * 拼出 m3u8），而不是直链。播放器直接打开这种地址只会拿到网页，
     * 报「无法识别格式」/「不是有效视频」。这些页面几乎都把真实地址以字符串常量
     * 写在脚本里，所以静态提取就够，不需要跑 JS。
     *
     * 兼容三种常见写法：完整 https 地址、`\ /` 转义过的地址、协议相对 `//host/...`；
     * 外加单引号包住的相对路径。提取不到就返回 null，由调用方保持原地址不变
     * （行为不会比现在更差）。
     */
    fun extractPlayUrl(page: String, baseUrl: String): String? {
        val text = page
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        // 先抓页面里所有绝对地址，再按「**路径**确实以媒体后缀结尾」筛选。
        //
        // 不能用 `…\.m3u8` 结尾的整体正则：统计 / 跳转地址会把真实地址编码进查询串，
        // 例如 `//l.cdn-fileserver.com/bping.php?…&requrl=https%3A%2F%2Fhost%2Fx%2Findex.m3u8`
        // —— 整串以 `.m3u8` 结尾会被误判成流地址，拿去播放又是一轮「不是有效视频」。
        Regex(
            """(?:https?:)?//[^\s'"<>()\\]+""",
            RegexOption.IGNORE_CASE,
        ).findAll(text).forEach { m ->
            // 被当成**另一个地址的查询参数值**的不算：`…?requrl=https://host/x.m3u8`
            // 这种只是把原地址又抄了一遍（停放页 / 统计页常见），取出来等于原地打转。
            val prev = text.getOrNull(m.range.first - 1)
            if (prev == '=') return@forEach
            var url = m.value.trimEnd(',', ';', ')', ']', '\\', '.', '"', '\'')
            if (url.startsWith("//")) {
                url = (if (baseUrl.startsWith("http://")) "http:" else "https:") + url
            }
            if (isMediaPath(url)) return url
        }

        // 相对路径写法：const vid = '/play/xxx/index.m3u8'
        val relative = Regex(
            """['"](/[^'"\s]+\.(?:m3u8|mp4)(?:\?[^'"\s]*)?)['"]""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1)
        if (relative != null) {
            return runCatching { java.net.URL(java.net.URL(baseUrl), relative).toString() }
                .getOrNull()
        }
        return null
    }

    /** 地址的路径部分（忽略查询串 / 锚点）是否以媒体后缀结尾 */
    private fun isMediaPath(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?')
        return MEDIA_URL_EXT.containsMatchIn(path)
    }

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

        // 「?url=」反代站（如 `https://pz.xxx/?url=https://采集站/api.php/provide/vod`）：
        // 采集参数必须**编码后追加进 url 参数值**，拼在外层会命中反代的 System Error。
        // 已实测：拼外层返回 HTML 错误页，编码进 url 值才返回正确的采集 JSON。
        val urlParamIdx = base.indexOf("?url=")
        if (urlParamIdx >= 0) {
            val prefix = base.substring(0, urlParamIdx + 5) // 含 "?url="
            val target = base.substring(urlParamIdx + 5)
            // 目标地址自身可能已带 query（如 .../provide/vod?ac=list），用 & 追加
            val joined = target + (if (target.contains('?')) "&" else "?") + query
            return prefix + urlEncode(joined)
        }

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
     *
     * v1.9.5.1 补一条次级判据：集数相同时，**播放地址带媒体后缀的线路优先**。
     * 采集站常把同一个地址给两条线路，一条写成 `/play/xxx`（返回 HTML 播放页，
     * 播放器拿到后报「无法识别格式」），另一条写成 `/play/xxx/index.m3u8`（真正的流）。
     * 两条都只有 1 集时原顺序会选中前者，用户看到的就是「点进去一直加载不出来」。
     * 所有线路都没有后缀时计数同为 0，稳定排序保持原顺序，不影响既有源。
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
        }.sortedWith(
            compareByDescending<EpisodeLine> { it.episodes.size }
                .thenByDescending { line -> line.episodes.count { looksLikePlayableUrl(it.url) } },
        )
    }

    /**
     * 只取一条线路的剧集（`chapterList` 用）：**集数最多的那条**。
     *
     * 语义与 v1.3.3.0 起保持一致，现在只是改由 [parseEpisodeLines] 实现。
     */
    fun parseEpisodes(playFrom: String?, playUrl: String?): List<Episode> =
        parseEpisodeLines(playFrom, playUrl).firstOrNull()?.episodes.orEmpty()

    /** 解析一级分类（`class` 中 `type_pid == 0` 的条目） */
    fun parseCategories(json: String): List<ExploreCategory> =
        parseCategoryTree(json).filter { it.parent.isBlank() }

    /**
     * 解析**完整分类树**（v1.6.7.0）。
     *
     * 苹果 CMS 的 `class` 数组同时包含一级类型（`type_pid == 0`，如「电影 / 电视剧 /
     * 动漫 / 综艺 / 短剧 / 预告片」）与二级分类（`type_pid == 父级 type_id`，
     * 如「电影 → 动作片 / 喜剧片 / 科幻片」）。
     *
     * 这里把两级都取出来，[ExploreCategory.parent] 填**父级名字**（不是父级 id）——
     * 名字才是跨源稳定的身份；[ExploreCategory.url] 是该源自己的 `type_id`。
     *
     * 实测：`hhzyapi.com` 返回 48 条 = 8 个一级 + 40 个二级。
     */
    fun parseCategoryTree(json: String): List<ExploreCategory> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        if (!root.isJsonObject) return emptyList()
        val arr = root.asJsonObject.getAsJsonArray("class") ?: return emptyList()

        // 先建 id → 名字映射，才能把子分类的 type_pid 还原成父级名字
        val raw = arr.mapNotNull { el -> (el as? JsonObject)?.let { str(it, "type_id") to it } }
            .filter { it.first.isNotBlank() }
        val nameById = raw.associate { (id, o) -> id to str(o, "type_name") }

        return raw.mapNotNull { (id, o) ->
            val name = str(o, "type_name")
            if (name.isBlank()) return@mapNotNull null
            val pid = str(o, "type_pid")
            // 父级名字：pid 为空 / "0" / 查不到都算一级
            val parent = if (pid.isNotBlank() && pid != "0") nameById[pid].orEmpty() else ""
            ExploreCategory(title = name, url = id, parent = parent)
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
                // v1.8.7.0（待办 #28）：vod_class 是多题材字段（「剧情,动作,悬疑」），
                // 进标签层后一部片子能像小说 / 漫画那样带多个题材
                put("class", str(o, "vod_class"))
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
