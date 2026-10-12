package com.novelreader.source.local

import com.novelreader.core.local.LocalBookStore
import com.novelreader.domain.model.ChapterItem
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceAdapter
import com.novelreader.domain.model.SourceFormat
import com.novelreader.domain.model.SourceInfo
import com.novelreader.domain.model.SourceType

/**
 * 本地 TXT 书适配器（v1.2.0 对标 legado 本地书）。
 *
 * 实现统一的 [SourceAdapter] 四接口，使本地书走与在线书完全相同的
 * 详情 / 目录 / 正文 / 阅读链路（含进度、缓存、换源排除等）。
 *
 * @param meta 本地书元数据
 * @param store 本地书库（负责文件 IO 与章节切分）
 */
class LocalBookAdapter(
    private val meta: LocalBookStore.LocalBookMeta,
    private val store: LocalBookStore,
) : SourceAdapter {

    override val info: SourceInfo = SourceInfo(
        id = LocalBookStore.SOURCE_PREFIX + meta.id,
        name = "本地：${meta.title}",
        type = SourceType.NOVEL,
        version = "1.0.0",
        author = "leimao",
        desc = "本地 TXT 书籍（${meta.chapterCount} 章）",
        baseUrl = "",
        format = SourceFormat.LOCAL,
        enabled = true,
        category = "本地",
    )

    /** 本地书 item 的唯一 id（源内），与 sourceId 组成进度/缓存 key */
    private val bookItemId = "book_${meta.id}"

    override suspend fun search(keyword: String, page: Int): List<MediaItem> {
        // 本地书不参与全局搜索；仅标题包含关键词时返回自身（保持接口语义完整）
        if (page > 1) return emptyList()
        if (keyword.isNotBlank() && !meta.title.contains(keyword.trim(), ignoreCase = true)) {
            return emptyList()
        }
        return listOf(item())
    }

    override suspend fun detail(item: MediaItem): MediaItem =
        item.copy(desc = "本地导入 · 共 ${meta.chapterCount} 章", sourceName = "本地书架")

    override suspend fun chapterList(item: MediaItem): List<ChapterItem> {
        val text = readText()
        return store.parseChapters(text).mapIndexed { index, ch ->
            // url 编码字符偏移区间："start-end"
            ChapterItem(
                id = "${ch.start}-$ch.end",
                title = ch.title,
                url = "${ch.start}-${ch.end}",
                index = index,
                sourceId = info.id,
            )
        }
    }

    override suspend fun content(chapter: ChapterItem): ContentResult {
        val parts = chapter.url.split("-")
        val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val end = parts.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
        val text = store.readChapter(meta, start, end)
        // 清理章首冗余（章节标题行本身 + 前导空白），正文段落保留
        val body = text.lineSequence()
            .dropWhile { it.isBlank() || it.trim() == chapter.title }
            .joinToString("\n")
        return ContentResult.novel(body.ifBlank { text })
    }

    /** 构造本地书的 MediaItem（加入书架 / 详情页用） */
    fun item(): MediaItem = MediaItem(
        id = bookItemId,
        title = meta.title,
        cover = null,
        author = meta.author,
        desc = "本地导入 · 共 ${meta.chapterCount} 章",
        type = SourceType.NOVEL,
        detailUrl = meta.fileName,
        sourceId = info.id,
        sourceName = "本地书架",
    )

    private fun readText(): String =
        store.let { String(store.bookFile(meta).readBytes(), charset(meta.charset)) }

    private fun charset(name: String): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
}
