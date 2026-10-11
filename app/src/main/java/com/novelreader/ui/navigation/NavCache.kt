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
 * key 使用 item.id（源内唯一），进入阅读页时写入，离开后由下次进入覆盖。
 */
object NavCache {

    private val chapterCache = mutableMapOf<String, List<ChapterItem>>()

    /** 保存章节列表（进入阅读/播放页前调用） */
    fun putChapters(item: MediaItem, chapters: List<ChapterItem>) {
        chapterCache[item.id] = chapters
    }

    /** 取章节列表（阅读/播放页初始化时调用） */
    fun getChapters(item: MediaItem): List<ChapterItem> =
        chapterCache[item.id] ?: emptyList()

    /** 清除某本书的章节缓存 */
    fun clearChapters(item: MediaItem) {
        chapterCache.remove(item.id)
    }
}
