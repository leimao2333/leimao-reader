package com.novelreader.ui.navigation

import com.google.gson.Gson
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem

/**
 * 导航参数编解码工具。
 *
 * 将 MediaItem / ChapterItem 序列化为字符串，作为导航路由参数传递。
 * 避免使用复杂的 SavedStateHandle 类型转换。
 *
 * 编码方案：JSON → Base64（URL-safe，无 '=' 补位）。
 *
 * 历史实现用 `android.net.Uri.encode(json)`，但 `Uri.encode` **默认不编码 `/`**，
 * 而 MediaItem 的 detailUrl / cover 都是含 `/` 的 URL；一旦 JSON 里带 `/`，
 * 整串就会被 Navigation 当作多级路径拆分，导致 route 匹配失败抛
 * `IllegalArgumentException: Navigation destination that matches request ... cannot be found`，
 * 也就是用户看到的「点击源闪退」。Base64 只含 `A-Za-z0-9-_`，天然 URL 安全。
 */
object NavArgs {

    private val gson = Gson()

    private val encoder: java.util.Base64.Encoder =
        java.util.Base64.getUrlEncoder().withoutPadding()
    private val decoder: java.util.Base64.Decoder = java.util.Base64.getUrlDecoder()

    /** 编码 MediaItem 为 URL 安全字符串 */
    fun encodeItem(item: MediaItem): String = encode(gson.toJson(item))

    /** 解码 MediaItem */
    fun decodeItem(encoded: String): MediaItem =
        runCatching { gson.fromJson(decode(encoded), MediaItem::class.java) }
            .getOrNull() ?: MediaItem(id = "", title = "", type = com.novelreader.domain.model.SourceType.NOVEL)

    /** 编码 ChapterItem */
    fun encodeChapter(chapter: ChapterItem): String = encode(gson.toJson(chapter))

    /** 解码 ChapterItem */
    fun decodeChapter(encoded: String): ChapterItem? =
        runCatching { gson.fromJson(decode(encoded), ChapterItem::class.java) }.getOrNull()

    /** 编码章节列表 */
    fun encodeChapters(chapters: List<ChapterItem>): String = encode(gson.toJson(chapters))

    /** 解码章节列表 */
    fun decodeChapters(encoded: String): List<ChapterItem> {
        val type = com.google.gson.reflect.TypeToken.getParameterized(
            List::class.java, ChapterItem::class.java
        ).type
        return runCatching { gson.fromJson<List<ChapterItem>>(decode(encoded), type) }
            .getOrDefault(emptyList())
    }

    private fun encode(json: String): String =
        encoder.encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun decode(encoded: String): String {
        // 兼容旧的 Uri.encode 产物：Base64 解码失败时回退按 URI 解码
        return runCatching { String(decoder.decode(encoded), Charsets.UTF_8) }
            .getOrElse { android.net.Uri.decode(encoded) }
    }
}
