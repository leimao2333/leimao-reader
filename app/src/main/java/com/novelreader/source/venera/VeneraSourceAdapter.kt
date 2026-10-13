package com.novelreader.source.venera

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
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

    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        engine.putSource(key, code)
        val json = engine.call(key, "search", keyword, page)
        val obj = JsonParser.parseString(json).asJsonObject
        val arr = obj.getAsJsonArray("comics") ?: return emptyList()
        return arr.mapNotNull { el ->
            val c = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = c.str("id")
            if (id.isNullOrBlank()) return@mapNotNull null
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
            )
        }
    }

    override suspend fun detail(item: MediaItem): MediaItem {
        val comicId = item.detailUrl.ifBlank { item.id }
        engine.putSource(key, code)
        val json = engine.call(key, "info", comicId)
        val obj = JsonParser.parseString(json).asJsonObject
        val extras = item.extras.toMutableMap()
        obj.str("updateTime")?.takeIf { it.isNotBlank() }?.let { extras["lastUpdate"] = it }
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
        val comicId = chapter.url.substringBefore(CHAPTER_SEP)
        val epId = chapter.url.substringAfter(CHAPTER_SEP, chapter.id)
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

    override suspend fun test(): Boolean = runCatching {
        engine.putSource(key, code)
        engine.call(key, "ping")
        true
    }.getOrDefault(false)

    // ===== 账号登录（v1.3.0：源管理页登录按钮）=====

    /**
     * 查询该源的账号能力与登录态。
     *
     * @return (是否支持登录, 是否已登录)；引擎异常时返回 (false, false)
     */
    suspend fun accountInfo(): Pair<Boolean, Boolean> = runCatching {
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

    private fun encodeChapterUrl(comicId: String, epId: String) = "$comicId$CHAPTER_SEP$epId"

    private fun JsonObject.str(name: String): String? {
        val el = get(name) ?: return null
        if (el.isJsonNull) return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    companion object {
        private const val TAG = "VeneraAdapter"
        private const val CHAPTER_SEP = "|"

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
