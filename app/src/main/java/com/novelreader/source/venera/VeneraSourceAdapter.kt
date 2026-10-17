package com.novelreader.source.venera

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.novelreader.core.util.TagEntryUrl
import com.novelreader.core.util.runCatchingCc
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ExploreCategory
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType

/**
 * Venera 源适配器。
 *
 * 把 Venera 的「搜索 / 详情 / 目录 / 章节图片」四个入口映射到本应用统一的
 * [SourceAdapter] 上，实际执行交给 [VeneraEngine]（隐藏 WebView 里的 V8）。
 *
 * 章节地址编码：Venera 的 `loadEp(comicId, epId)` 需要两个参数，而本应用的
 * [ChapterItem] 只有单个 `url`，这里约定 `url = "comicId|epId"`（按第一个 `|` 切分），
 * 既保证唯一性（用于阅读进度匹配），也不影响其它链路。
 */
class VeneraSourceAdapter(
    override val info: SourceInfo,
    private val code: String,
    private val engine: VeneraEngine,
) : SourceAdapter {

    private val key: String get() = info.id

    /**
     * 发现分类 = 源**自己声明的分类表**（v1.6.6.0；v1.7.0.0 改走真分类页）。
     *
     * Venera 协议没有统一的「发现分类」接口，但源代码里 `category.parts[]` 就是完整的
     * 分类表（含 `type:"dynamic"` 的 loader）。v1.6.6.0 起把它静态解析成分类名、
     * 绑成**按关键词搜索**入口 —— 这是当时的权宜之计：搜索只匹配标题，
     * 漫画里点「恋爱」基本搜不到东西，复合搜索更是永远出不来。
     *
     * v1.7.0.0 改成在 WebView 里**真实评估** `category`（loader 一并执行），
     * 每个分类项带着 `param`（源自己的分类参数）与其它过滤器的默认值，
     * 绑成 `vcat::` 分类入口 —— 点标签 = 走源自己的 `categoryComics.load`，
     * 和 Venera 客户端的分类页完全同一条链路。
     *
     * 运行时报错（源语法特殊 / loader 需要登录）时退回静态解析的 `search::` 入口，
     * 至少还有标签可用。
     */
    override suspend fun exploreCategories(): List<ExploreCategory> {
        runtimeCategories()?.let { if (it.isNotEmpty()) return it }
        return staticCategories()
    }

    /**
     * 廉价兜底（v1.7.2.0）：**只读源码里的静态分类表**，不碰 WebView。
     *
     * 首页推荐要枚举几十个源的分类，运行时评估（[runtimeCategories]）虽然结果最准，
     * 但每个源都要往隐藏 WebView 里派一次 `evaluateJavascript`：25 个漫画源串起来
     * 是十几秒的主线程派发，个别源还有 `type:"dynamic"` 的 loader 要联网。
     * 枚举阶段对超时的源改用这张静态表兜底，至少让该源以「按分类名搜索」的方式
     * 参与聚合，而不是整源被丢掉。
     */
    override suspend fun fastExploreCategories(): List<ExploreCategory> = staticCategories()

    /** 源码里 `category.parts[].categories` 声明的分类名 → 「按分类名搜索」入口 */
    private fun staticCategories(): List<ExploreCategory> =
        VeneraCategories.parse(code).map { name ->
            ExploreCategory(title = name, url = TagEntryUrl.search(name))
        }

    /** 在 WebView 里评估源的分类表 → 分类入口；失败 / 无分类返回 null */
    private suspend fun runtimeCategories(): List<ExploreCategory>? = runCatchingCc {
        engine.putSource(key, code)
        val obj = JsonParser.parseString(engine.call(key, "categories")).asJsonObject
        val defaults: List<JsonElement> =
            obj.getAsJsonArray("options")?.toList() ?: emptyList()
        val parts = obj.getAsJsonArray("parts") ?: return@runCatchingCc null
        val out = mutableListOf<ExploreCategory>()
        for (partEl in parts) {
            val items = partEl.asJsonObject.getAsJsonArray("items") ?: continue
            for (itemEl in items) {
                val item = itemEl.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val name = item.textOrNull("name") ?: continue
                val kind = item.textOrNull("itemType") ?: CATEGORY_KIND
                val url = if (kind == SEARCH_KIND) {
                    TagEntryUrl.search(item.textOrNull("keyword") ?: name)
                } else {
                    TagEntryUrl.category(
                        name = name,
                        param = item.get("param"),
                        options = defaults,
                    )
                }
                // 同名分类在不同 part 里出现时保留先出现的那个（标签身份是名字）
                if (out.none { it.title == name }) out += ExploreCategory(title = name, url = url)
            }
        }
        out.ifEmpty { null }
    }.getOrNull()

    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        engine.putSource(key, code)
        return parseComics(engine.call(key, "search", keyword, page))
    }

    /**
     * 分类页浏览（v1.7.0.0）：标签池里的 `vcat::` 入口落到这里。
     *
     * 分类页拿不到内容时（源改了接口 / 该分类为空）退回按分类名搜索，
     * 至少不是空手 —— 与 v1.6.6.0 的老行为兼容。
     */
    override suspend fun exploreFilter(
        url: String,
        page: Int,
        filters: Map<String, String>,
    ): List<MediaItem> {
        val entry = TagEntryUrl.categoryOf(url)
        if (entry != null && entry.kind != SEARCH_KIND) {
            // 注意用 runCatchingCc：首页给每源的预算到头时取消必须照常传播，
            // 吞掉它会接着跑下面的 search 回落，一次探测烧掉两倍引擎时间
            // （实测单源 16~90s，把共享 JS 线程排爆）。
            val comics = runCatchingCc {
                engine.putSource(key, code)
                parseComics(
                    engine.call(key, "categoryComics", entry.name, entry.param, entry.options, page),
                )
            }.getOrDefault(emptyList())
            if (comics.isNotEmpty()) return comics
        }
        val keyword = entry?.keyword ?: entry?.name ?: TagEntryUrl.searchKeywordOf(url)
        return search(keyword ?: url, page)
    }

    /**
     * 解析源返回的漫画列表。
     *
     * `tags` 一路带出到 [MediaItem.extras]：包子漫画等源在列表里就给出每部作品的
     * 题材标签，详情页标签栏与「同时属于多个标签」的判断都用得上（v1.7.0.0）。
     */
    private fun parseComics(json: String): List<MediaItem> {
        val obj = JsonParser.parseString(json).asJsonObject
        val arr = obj.getAsJsonArray("comics") ?: return emptyList()
        return arr.mapNotNull { el ->
            val c = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = c.str("id")
            if (id.isNullOrBlank()) return@mapNotNull null
            val extras = mutableMapOf<String, String>()
            itemTags(c)?.let { extras[com.novelreader.core.util.ItemTags.EXTRAS_TAGS] = it }
            MediaItem(
                id = id,
                title = c.str("title").orEmpty(),
                cover = c.str("cover")?.takeIf { it.isNotBlank() },
                author = c.str("subTitle")?.takeIf { it.isNotBlank() },
                desc = c.str("description")?.takeIf { it.isNotBlank() },
                type = SourceType.COMIC,
                detailUrl = id,
                sourceId = info.id,
                sourceName = info.name,
                extras = extras,
            )
        }
    }

    /**
     * 列表 / 详情响应里的 `tags` → 逗号分隔字符串。
     *
     * 归一化口径（数组 / 对象两种形态、丢掉作者等元信息分组）收在
     * [VeneraTags.parse]，那边有单测，这里只做「拼接 + 空则不写」。
     */
    private fun itemTags(obj: JsonObject): String? =
        VeneraTags.parse(obj.get("tags"))
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

    /** 取字符串字段（非字符串 / 缺失 → null） */
    private fun JsonObject?.textOrNull(key: String): String? =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    override suspend fun detail(item: MediaItem): MediaItem {
        val comicId = item.detailUrl.ifBlank { item.id }
        engine.putSource(key, code)
        val json = engine.call(key, "info", comicId)
        val obj = JsonParser.parseString(json).asJsonObject
        val extras = item.extras.toMutableMap()
        obj.str("updateTime")?.takeIf { it.isNotBlank() }?.let { extras["lastUpdate"] = it }
        // v1.7.0.0：详情/搜索响应自带的作品题材标签（如包子漫画的 type_names）也带出来，
        // 详情页标签栏据此多一行分类，不用再去猜
        itemTags(obj)?.let { extras[com.novelreader.core.util.ItemTags.EXTRAS_TAGS] = it }
        return item.copy(
            title = obj.str("title")?.takeIf { it.isNotBlank() } ?: item.title,
            cover = obj.str("cover")?.takeIf { it.isNotBlank() } ?: item.cover,
            author = obj.str("subTitle")?.takeIf { it.isNotBlank() } ?: item.author,
            desc = obj.str("description")?.takeIf { it.isNotBlank() } ?: item.desc,
            extras = extras,
        )
    }

    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val comicId = item.detailUrl.ifBlank { item.id }
        engine.putSource(key, code)
        val json = engine.call(key, "chapters", comicId)
        val obj = JsonParser.parseString(json).asJsonObject
        val arr = obj.getAsJsonArray("chapters") ?: return emptyList()
        return arr.mapIndexedNotNull { index, el ->
            val pair = el.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapIndexedNotNull null
            if (pair.size() < 2) return@mapIndexedNotNull null
            val epId = pair.get(0).asString
            val title = pair.get(1).asString
            if (epId.isBlank()) return@mapIndexedNotNull null
            ChapterItem(
                id = epId,
                title = title,
                url = encodeChapterUrl(comicId, epId),
                index = index,
                sourceId = info.id,
            )
        }
    }

    override suspend fun content(chapter: ChapterItem): ContentResult {
        // 章节地址可能是 `comicId|epId`（常见）或 `vj:{json}`（两段自身含 `|` 时，见 VeneraChapterCodec）
        val (comicId, epId) = VeneraChapterCodec.decode(chapter.url, chapter.id)
        engine.putSource(key, code)
        val json = engine.call(key, "content", comicId, epId)
        val obj = JsonParser.parseString(json).asJsonObject
        val images = obj.getAsJsonArray("images")?.mapNotNull { el ->
            el.takeIf { it.isJsonPrimitive }?.asString
        } ?: emptyList()
        val headers = obj.getAsJsonObject("headers")?.entrySet()
            ?.associate { (k, v) -> k to (if (v.isJsonPrimitive) v.asString else v.toString()) }
            ?: emptyMap()
        // 逐图后处理脚本（modifyImage）：部分漫画站的图片是按 N 段纵向打乱的，
        // 解析出段数后在解码阶段重排，否则读者看到的是乱序画面。
        val scripts = obj.getAsJsonObject("scripts")?.entrySet()
            ?.mapNotNull { (k, v) ->
                val js = v.takeIf { it.isJsonPrimitive }?.asString
                if (js.isNullOrBlank()) null else k to js
            }?.toMap()
            ?: emptyMap()
        // 排查「图片加载不了」时用 logcat -s VeneraAdapter 看：数量与是否带防盗链请求头。
        // 这里刻意**不打印源标识与图片地址**（见 core/util/LogSanitizer 的说明）。
        android.util.Log.d(
            TAG,
            "content images=${images.size} headerCount=${headers.size}",
        )
        // 图床请求头注册进封面注册表：封面与正文图片通常同站点，
        // 防盗链规则一致，封面加载（Coil 直发）也能按 host 命中
        if (headers.isNotEmpty()) {
            runCatching {
                images.firstOrNull { it.isNotBlank() }?.let { u ->
                    val host = java.net.URI(u).host
                    if (!host.isNullOrBlank()) {
                        com.novelreader.core.image.CoverHeaderRegistry.register(host, sanitizeHeaders(headers))
                    }
                }
            }
        }
        return ContentResult.comic(images, sanitizeHeaders(headers), scripts)
    }

    /**
     * 去掉必须由客户端自己协商的请求头。
     *
     * 源给的图片请求头里普遍带 `Accept-Encoding: gzip, deflate, br, zstd`
     * （那是浏览器该发的），原样透传会**关掉 OkHttp / Coil 的透明解压**，
     * 一旦 CDN 真返回压缩内容就会解码失败——与引擎侧踩过的同一个坑。
     * `Connection` / `Host` / `Content-Length` 之类同理由客户端接管。
     */
    private fun sanitizeHeaders(raw: Map<String, String>): Map<String, String> =
        raw.filterKeys { it.lowercase() !in DROPPED_HEADERS }

    override suspend fun test(): Boolean = runCatchingCc {
        engine.putSource(key, code)
        engine.call(key, "ping")
        true
    }.getOrDefault(false)

    /**
     * 图片加载失败 → 向源要下一个镜像地址（v1.7.1.0）。
     *
     * 源在 onImageLoad 里返回 `onLoadFailed`（多镜像回退，如禁漫天堂的 5 条线路），
     * 官方 App 会自动调它重试；这里通过运行时新增的 `retryImage` 操作按需调用。
     */
    suspend fun fallbackImage(url: String): Pair<String, Map<String, String>>? = runCatchingCc {
        engine.putSource(key, code)
        val json = engine.call(key, "retryImage", url)
        if (json.isBlank() || json == "null") return@runCatchingCc null
        val obj = JsonParser.parseString(json).asJsonObject
        val next = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString
        if (next.isNullOrBlank()) return@runCatchingCc null
        val hs = obj.getAsJsonObject("headers")?.entrySet()
            ?.associate { (k, v) -> k to (if (v.isJsonPrimitive) v.asString else v.toString()) }
            ?: emptyMap()
        next to sanitizeHeaders(hs)
    }.getOrNull()

    // ===== 账号登录（v1.3.0.0：源管理页登录按钮）=====

    /**
     * 查询该源的账号能力与登录态。
     *
     * @return (是否支持登录, 是否已登录)；引擎异常时返回 (false, false)
     */
    suspend fun accountInfo(): Pair<Boolean, Boolean> = runCatchingCc {
        engine.putSource(key, code)
        engine.accountInfo(key)
    }.getOrDefault(Pair(false, false))

    /** 账号密码登录（调用源自己实现的 account.login） */
    suspend fun login(account: String, password: String) {
        engine.putSource(key, code)
        engine.login(key, account, password)
    }

    /** 退出登录（调用源的 account.logout，清 Cookie 与本地标记） */
    suspend fun logout() {
        engine.putSource(key, code)
        engine.logout(key)
    }

    /** 本地登录标记（只读 prefs，不启动 WebView，供列表即时刷新徽标） */
    fun isLoginMarked(): Boolean = engine.isLoginMarked(key)

    private fun encodeChapterUrl(comicId: String, epId: String) =
        VeneraChapterCodec.encode(comicId, epId)

    private fun JsonObject.str(name: String): String? {
        val el = get(name) ?: return null
        if (el.isJsonNull) return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    companion object {
        private const val TAG = "VeneraAdapter"

        /** 分类项的 itemType：走分类页（默认） */
        private const val CATEGORY_KIND = "category"

        /** 分类项的 itemType：该分类没有分类页，退回搜索 */
        private const val SEARCH_KIND = "search"

        /** 交由客户端协商的请求头（见 [sanitizeHeaders]） */
        private val DROPPED_HEADERS = setOf(
            "accept-encoding",
            "connection",
            "keep-alive",
            "transfer-encoding",
            "content-length",
            "host",
            "te",
            "upgrade",
            "expect",
        )
    }
}
