package com.novelreader.ui.navigation

import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.MediaItem

/**
 * 导航内存缓存。
 *
 * 章节列表可能非常大（数千章），若整表 JSON 编码塞进导航路由，
 * 会在主线程做巨量序列化/解析，导致 ANR 甚至 TransactionTooLargeException。
 * 因此章节列表改走本内存缓存，路由只传章节索引。
 *
 * v1.8.8.5：这个缓存从「进阅读页前塞一次」升级成**书架目录预热缓存** ——
 * 书架在后台补「最新章节」时顺手把整份目录存进来，用户点书时直接命中，
 * 不用再等一次目录网络请求（这是「点一下几秒没反应」的主因）。
 * 因此加了容量上限（LRU）与线程保护：书架、阅读页可能在不同协程里同时访问。
 *
 * key 用 `sourceId:id`：不同源可能有相同的 id，只用 id 会串书。
 */
object NavCache {

    /** 最多缓存几本书的目录（每本几千章，太占内存） */
    private const val MAX_BOOKS = 12

    /** accessOrder = true → 按访问顺序淘汰（LRU） */
    private val chapterCache = object : LinkedHashMap<String, List<ChapterItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<ChapterItem>>?,
        ): Boolean = size > MAX_BOOKS
    }

    private fun keyOf(item: MediaItem): String = "${item.sourceId}:${item.id}"

    /** 保存章节列表（书架预热 / 进入阅读、播放页前调用） */
    @Synchronized
    fun putChapters(item: MediaItem, chapters: List<ChapterItem>) {
        if (chapters.isEmpty()) return
        chapterCache[keyOf(item)] = chapters
    }

    /** 取章节列表（书架点击 / 阅读页初始化时调用）；未命中返回空表 */
    @Synchronized
    fun getChapters(item: MediaItem): List<ChapterItem> =
        chapterCache[keyOf(item)] ?: emptyList()

    /** 清除某本书的章节缓存 */
    @Synchronized
    fun clearChapters(item: MediaItem) {
        chapterCache.remove(keyOf(item))
    }
}
