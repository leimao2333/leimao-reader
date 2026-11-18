package com.novelreader.source.local

import android.net.Uri
import com.novelreader.core.local.LocalComicStore
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType

/**
 * 本地漫画适配器（D20）。
 *
 * 与 [LocalBookAdapter] 同一套思路：把一个本地漫画伪装成一个「源」，
 * 让详情 / 目录 / 阅读 / 进度 / 收藏全部复用在线源链路，阅读器零改动。
 *
 * 关键约定：
 * - `ChapterItem.url` 存章节下标（`ch_0`），不存绝对路径 —— 私有目录路径可能随
 *   备份恢复变化，存下标更稳；
 * - `content()` 返回 `file://` 图片地址，Coil 与阅读器的 `ImageCookies` /
 *   `ThumbnailChannel` 会自动跳过（本地文件不走网络通道）。
 */
class LocalComicAdapter(
    private val meta: LocalComicStore.LocalComicMeta,
    private val store: LocalComicStore,
) : SourceAdapter {

    override val info: SourceInfo = SourceInfo(
        id = LocalComicStore.SOURCE_PREFIX + meta.id,
        name = "本地：${meta.title}",
        type = SourceType.COMIC,
        version = "1.0.0",
        author = "leimao",
        desc = "本地漫画（${meta.chapterCount} 话）",
        baseUrl = "",
        format = SourceFormat.LOCAL,
        enabled = true,
        category = "本地",
    )

    private val itemId = "comic_${meta.id}"

    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        if (page > 1) return emptyList()
        if (keyword.isNotBlank() && !meta.title.contains(keyword.trim(), ignoreCase = true)) {
            return emptyList()
        }
        return listOf(item())
    }

    override suspend fun detail(item: MediaItem): MediaItem =
        item.copy(
            desc = "本地导入 · 共 ${store.chapters(meta.id).size} 话",
            sourceName = "本地书架",
            cover = coverUrl() ?: item.cover,
        )

    override suspend fun chapterList(item: MediaItem): List<ChapterItem> =
        store.chapters(meta.id).mapIndexed { index, chapter ->
            ChapterItem(
                id = "ch_$index",
                title = chapter.name,
                url = "ch_$index",
                index = index,
                sourceId = info.id,
            )
        }

    override suspend fun content(chapter: ChapterItem): ContentResult {
        val index = chapter.url.removePrefix("ch_").toIntOrNull() ?: chapter.index
        val files = store.chapterFiles(meta.id, index)
        if (files.isEmpty()) return ContentResult()
        // 统一 file:// —— 阅读器里已有 Uri.fromFile 的先例，Coil 直接支持
        return ContentResult.comic(files.map { Uri.fromFile(it).toString() })
    }

    /** 构造 MediaItem（加入书架 / 书架页展示用） */
    fun item(): MediaItem = MediaItem(
        id = itemId,
        title = meta.title,
        cover = coverUrl(),
        author = meta.author,
        desc = "本地导入 · 共 ${meta.chapterCount} 话",
        type = SourceType.COMIC,
        detailUrl = meta.id,
        sourceId = info.id,
        sourceName = "本地书架",
    )

    /** 封面（file:// 地址；没有封面时返回 null，UI 会显示占位） */
    fun coverUrl(): String? = store.coverFile(meta)?.let { Uri.fromFile(it).toString() }
}
