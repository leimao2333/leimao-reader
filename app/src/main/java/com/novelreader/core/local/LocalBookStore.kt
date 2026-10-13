package com.novelreader.core.local

import android.content.Context
import android.net.Uri
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地 TXT 书库（v1.2.0.0 对标 legado 本地书）。
 *
 * - 导入：SAF 选文件 → 读字节 → 编码探测（UTF-8 优先，失败回退 GBK）→ 章节切分
 * - 存储：TXT 原文件存 `filesDir/local_books/<id>.txt`，元数据存 `local_books/index.json`
 * - 不入 Room：本地书数量少、结构稳定，用 JSON 索引文件更简单，也免去数据库迁移
 *
 * 每本书生成一个 [LocalBookMeta]，配一个 [com.novelreader.source.local.LocalBookAdapter]
 * 注册语义上的「本地源」（sourceId = local_<id>），详情/目录/正文走统一 SourceAdapter 链路。
 */
class LocalBookStore(private val context: Context) {

    companion object {
        const val SOURCE_PREFIX = "local_"
        private const val INDEX_FILE = "index.json"
        private const val DIR_NAME = "local_books"

        /** 章节标题正则：第X章/节/卷/回、序章、楔子、番外、Chapter N 等 */
        val CHAPTER_REGEX = Regex(
            "^\\s*(第[0-9零一二三四五六七八九十百千万两]+\\s*[章节卷回集]|序章|楔子|引子|前言|后记|尾声|尾声了|终章|番外[篇外]?|Chapter\\s+\\d+|CHAPTER\\s+\\d+).{0,60}$"
        )
    }

    /** 本地书元数据 */
    data class LocalBookMeta(
        val id: String,
        val title: String,
        val author: String,
        val fileName: String,
        val charset: String,
        val chapterCount: Int,
        val addedAt: Long,
    )

    /** 章节切分结果：标题 + 字符偏移区间（基于解码后的字符串） */
    data class ParsedChapter(val title: String, val start: Int, val end: Int)

    private val dir: File get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val indexFile: File get() = File(dir, INDEX_FILE)

    @Volatile
    private var cache: List<LocalBookMeta>? = null

    /** 全部本地书 */
    fun getAll(): List<LocalBookMeta> {
        cache?.let { return it }
        val list = runCatching {
            val json = JsonParser.parseString(indexFile.readText())
            json.asJsonArray.map { el ->
                val o = el.asJsonObject
                LocalBookMeta(
                    id = o.get("id").asString,
                    title = o.get("title").asString,
                    author = o.get("author")?.asString ?: "",
                    fileName = o.get("fileName").asString,
                    charset = o.get("charset")?.asString ?: "UTF-8",
                    chapterCount = o.get("chapterCount")?.asInt ?: 0,
                    addedAt = o.get("addedAt")?.asLong ?: 0L,
                )
            }
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    fun getBook(id: String): LocalBookMeta? = getAll().firstOrNull { it.id == id }

    fun bookFile(meta: LocalBookMeta): File = File(dir, meta.fileName)

    /**
     * 从 SAF Uri 导入 TXT。
     *
     * @return 导入成功的元数据
     * @throws IllegalArgumentException 文件为空 / 太大 / 解析失败
     */
    suspend fun import(uri: Uri, displayName: String?): LocalBookMeta = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选文件")
        if (bytes.isEmpty()) throw IllegalArgumentException("文件为空")
        if (bytes.size > 64 * 1024 * 1024) throw IllegalArgumentException("文件过大（>64MB）")

        val (text, charset) = decode(bytes)
        if (text.isBlank()) throw IllegalArgumentException("文件内容为空或无法解码")

        val id = "b${System.currentTimeMillis()}${(100..999).random()}"
        val fileName = "$id.txt"
        File(dir, fileName).writeBytes(bytes)

        val title = displayName?.substringBeforeLast('.')?.trim().orEmpty()
            .ifBlank { "本地书 $id" }
        val meta = LocalBookMeta(
            id = id,
            title = title,
            author = "本地导入",
            fileName = fileName,
            charset = charset,
            chapterCount = parseChapters(text).size,
            addedAt = System.currentTimeMillis(),
        )
        val all = getAll().toMutableList()
        all.removeAll { it.title == meta.title } // 同名覆盖旧导入
        all.add(meta)
        saveIndex(all)
        meta
    }

    /** 删除本地书（文件 + 索引） */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val all = getAll().toMutableList()
        val removed = all.removeAll { it.id == id }
        if (removed) {
            saveIndex(all)
            getBookFileOrNull(id)?.delete()
        }
    }

    private fun getBookFileOrNull(id: String): File? =
        getBook(id)?.let { File(dir, it.fileName) }

    /** 解析章节：按标题行切分；无匹配时整本作为单章「全文」 */
    fun parseChapters(text: String): List<ParsedChapter> {
        val marks = mutableListOf<ParsedChapter>()
        var lineStart = 0
        for (line in text.lineSequence()) {
            val lineEnd = lineStart + line.length
            if (line.isNotBlank() && CHAPTER_REGEX.containsMatchIn(line)) {
                marks.add(ParsedChapter(line.trim(), lineStart, lineEnd))
            }
            lineStart = lineEnd + 1 // +1 跳过换行符
        }
        if (marks.isEmpty()) {
            return listOf(ParsedChapter("全文", 0, text.length))
        }
        // 补齐首章前的前言、章与章之间的边界（start 到下一章 start）
        val chapters = mutableListOf<ParsedChapter>()
        // 序言（第一个章节标记前的内容）非空白则作为「前言」章
        if (marks.first().start > 0 && text.substring(0, marks.first().start).isNotBlank()) {
            chapters.add(ParsedChapter("前言", 0, marks.first().start))
        }
        marks.forEachIndexed { i, m ->
            val end = if (i + 1 < marks.size) marks[i + 1].start else text.length
            chapters.add(ParsedChapter(m.title, m.start, end))
        }
        return chapters
    }

    /** 读取章节正文（按字符偏移切片） */
    fun readChapter(meta: LocalBookMeta, start: Int, end: Int): String {
        val text = decode(bookFile(meta).readBytes()).first
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return text.substring(s, e)
    }

    /**
     * 编码探测：BOM → UTF-8 严格解码 → GBK 兜底。
     * @return (解码文本, 字符集名)
     */
    private fun decode(bytes: ByteArray): Pair<String, String> {
        // BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        }
        // UTF-8 严格解码（非法序列会抛异常）
        runCatching {
            val s = String(bytes, Charsets.UTF_8)
            // 二次校验：避免 GBK 文本被「成功但乱码」地按 UTF-8 解码（少量 U+FFFD 说明不是纯 UTF-8）
            if (!s.contains('\uFFFD')) return s to "UTF-8"
        }
        return String(bytes, charset("GBK")) to "GBK"
    }

    private fun saveIndex(list: List<LocalBookMeta>) {
        val arr = com.google.gson.JsonArray()
        list.forEach { m ->
            val o = com.google.gson.JsonObject()
            o.addProperty("id", m.id)
            o.addProperty("title", m.title)
            o.addProperty("author", m.author)
            o.addProperty("fileName", m.fileName)
            o.addProperty("charset", m.charset)
            o.addProperty("chapterCount", m.chapterCount)
            o.addProperty("addedAt", m.addedAt)
            arr.add(o)
        }
        indexFile.writeText(arr.toString())
        cache = list
    }
}
