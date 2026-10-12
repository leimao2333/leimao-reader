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
        return ContentResult.comic(images, headers)
    }

    override suspend fun test(): Boolean = runCatching {
        engine.putSource(key, code)
        engine.call(key, "ping")
        true
    }.getOrDefault(false)

    private fun encodeChapterUrl(comicId: String, epId: String) = "$comicId$CHAPTER_SEP$epId"

    private fun JsonObject.str(name: String): String? {
        val el = get(name) ?: return null
        if (el.isJsonNull) return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    companion object {
        private const val CHAPTER_SEP = "|"
    }
}
