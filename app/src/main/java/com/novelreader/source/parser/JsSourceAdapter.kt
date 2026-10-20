package com.novelreader.source.parser

import com.novelreader.core.js.SourceJsBridge
import com.novelreader.core.js.SourceJsEngine
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceInfo
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * JS 源适配器。
 *
 * 通过 Rhino 执行 JS 源的函数，将返回值转换为统一中间模型。
 * JS 源函数约定见 samples/js/ 下样例。
 */
class JsSourceAdapter(
    override val info: SourceInfo,
    private val scope: ScriptableObject,
) : SourceAdapter {

    private val type = info.type

    // ===== 搜索 =====
    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        val result = SourceJsEngine.callFunction(scope, "search", keyword, page)
        val list = unwrapList(result)
        return list.map { obj ->
            val map = toMap(obj)
            MediaItem(
                id = map.str("id") ?: "",
                title = map.str("title") ?: "",
                // v1.8.3.0：JS 返回的 cover 可能是 undefined 字符串化的垃圾值，清洗后置空
                cover = com.novelreader.core.util.CoverUrl.sanitize(map.str("cover")),
                author = map.str("author"),
                desc = map.str("desc"),
                type = type,
                detailUrl = map.str("id") ?: "",
                sourceId = info.id,
            )
        }
    }

    // ===== 详情 =====
    override suspend fun detail(item: MediaItem): MediaItem {
        val result = SourceJsEngine.callFunction(scope, "detail", item.detailUrl)
        val map = toMap(result)
        return item.copy(
            cover = com.novelreader.core.util.CoverUrl.sanitize(map.str("cover")) ?: item.cover,
            author = map.str("author") ?: item.author,
            desc = map.str("desc") ?: item.desc,
        )
    }

    // ===== 目录 =====
    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val result = SourceJsEngine.callFunction(scope, "chapterList", item.detailUrl)
        val list = unwrapList(result)
        return list.mapIndexed { index, obj ->
            val map = toMap(obj)
            ChapterItem(
                id = map.str("url") ?: map.str("id") ?: "",
                title = map.str("title") ?: "",
                url = map.str("url") ?: map.str("id") ?: "",
                index = index,
                sourceId = info.id,
            )
        }
    }

    // ===== 正文 =====
    override suspend fun content(chapter: ChapterItem): ContentResult {
        val result = SourceJsEngine.callFunction(scope, "content", chapter.url)
        val map = toMap(result)

        val text = map.str("text")
        val videoUrl = map.str("url")
        return when {
            // 小说：text 字段
            text != null -> ContentResult.novel(text)
            // 漫画：images 字段（数组）
            map.has("images") -> {
                val images = unwrapList(map.raw("images")).mapNotNull { toMap(it).str("src") ?: (it as? String) }
                ContentResult.comic(images)
            }
            // 视频：url 字段
            videoUrl != null -> {
                ContentResult.video(videoUrl, map.headers())
            }
            else -> ContentResult()
        }
    }

    // ===== 有效性自检 =====
    override suspend fun test(): Boolean {
        return kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            runCatching {
                search("test", 1)
                true
            }.getOrDefault(false)
        } ?: false
    }

    // ===== 类型转换工具 =====

    /** 将 Rhino 返回值解包为 List<Any?> */
    private fun unwrapList(value: Any?): List<Any?> {
        return when (value) {
            null, is Undefined -> emptyList()
            is NativeArray -> {
                val context = Context.enter()
                try {
                    val jsArray = Context.jsToJava(value, Any::class.java) as? Array<*>
                    jsArray?.toList() ?: emptyList()
                } finally {
                    Context.exit()
                }
            }
            is List<*> -> value
            else -> listOf(value)
        }
    }

    /** 将 Rhino NativeObject 转为可读的 Map 包装 */
    private fun toMap(obj: Any?): JsMap {
        return when (obj) {
            null, is Undefined -> JsMap(emptyMap())
            is NativeObject -> {
                val context = Context.enter()
                try {
                    val map = Context.jsToJava(obj, java.util.Map::class.java) as? Map<*, *>
                    val stringMap = map?.entries?.associate { (k, v) ->
                        k.toString() to v
                    } ?: emptyMap()
                    JsMap(stringMap)
                } finally {
                    Context.exit()
                }
            }
            is Map<*, *> -> {
                JsMap(obj.entries.associate { (k, v) -> k.toString() to v })
            }
            is String -> JsMap(mapOf("value" to obj))
            else -> JsMap(emptyMap())
        }
    }

    /** 轻量 Map 访问包装 */
    private class JsMap(private val map: Map<String, Any?>) {
        fun str(key: String): String? {
            val v = map[key] ?: return null
            if (v is Undefined) return null
            val s = v.toString()
            return if (s == "undefined" || s.isEmpty()) null else s
        }

        fun has(key: String): Boolean {
            val v = map[key] ?: return false
            return v !is Undefined
        }

        fun raw(key: String): Any? = map[key]

        fun headers(): Map<String, String> {
            val headersObj = map["headers"] ?: return emptyMap()
            val headersMap = when (headersObj) {
                is Map<*, *> -> headersObj.entries.associate { (k, v) ->
                    k.toString() to (v?.toString() ?: "")
                }
                else -> emptyMap()
            }
            return headersMap
        }
    }
}
