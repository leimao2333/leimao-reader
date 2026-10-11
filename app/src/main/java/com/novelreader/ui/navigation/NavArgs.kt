package com.novelreader.ui.navigation

import com.google.gson.Gson
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem

/**
 * 导航参数编解码工具。
 *
 * 将 MediaItem / ChapterItem 序列化为 JSON 字符串，作为导航路由参数传递。
 * 避免使用复杂的 SavedStateHandle 类型转换。
 */
object NavArgs {

    private val gson = Gson()

    /** 编码 MediaItem 为 URL 安全的字符串 */
    fun encodeItem(item: MediaItem): String =
        android.net.Uri.encode(gson.toJson(item))

    /** 解码 MediaItem */
    fun decodeItem(encoded: String): MediaItem =
        gson.fromJson(android.net.Uri.decode(encoded), MediaItem::class.java)

    /** 编码 ChapterItem */
    fun encodeChapter(chapter: ChapterItem): String =
        android.net.Uri.encode(gson.toJson(chapter))

    /** 解码 ChapterItem */
    fun decodeChapter(encoded: String): ChapterItem =
        gson.fromJson(android.net.Uri.decode(encoded), ChapterItem::class.java)

    /** 编码章节列表 */
    fun encodeChapters(chapters: List<ChapterItem>): String =
        android.net.Uri.encode(gson.toJson(chapters))

    /** 解码章节列表 */
    fun decodeChapters(encoded: String): List<ChapterItem> {
        val type = com.google.gson.reflect.TypeToken.getParameterized(
            List::class.java, ChapterItem::class.java
        ).type
        return gson.fromJson(android.net.Uri.decode(encoded), type)
    }
}
