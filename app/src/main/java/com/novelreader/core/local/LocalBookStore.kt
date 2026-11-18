package com.novelreader.core.local

import android.content.Context
import android.net.Uri
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地 TXT / EPUB 书库（v1.2.0.0 对标 legado 本地书）。
 *
 * - 导入：SAF 选文件 → 读字节 → 编码探测（UTF-8 优先，失败回退 GBK）→ 章节切分
 * - 存储：原文件存 `filesDir/local_books/<id>.txt`，元数据存 `local_books/index.json`
 * - 不入 Room：本地书数量少、结构稳定，用 JSON 索引文件更简单，也免去数据库迁移
 *
 * 每本书生成一个 [LocalBookMeta]，配一个 [com.novelreader.source.local.LocalBookAdapter]
 * 注册语义上的「本地源」（sourceId = local_<id>），详情/目录/正文走统一 SourceAdapter 链路。
 *
 * B9 起新增 EPUB：EPUB 是 zip 包，没法像 TXT 那样按字符偏移读正文，
 * 所以导入时先摊平成「一份纯文本 + 一张章节表」（见 [flattenEpub]），
 * 再复用既有 TXT 链路，**不改阅读侧的任何协议**。
 */
class LocalBookStore(private val context: Context) {

    companion object {
        const val SOURCE_PREFIX = "local_"
        private const val INDEX_FILE = "index.json"
        private const val DIR_NAME = "local_books"

        /**
         * EPUB 摊平文本尾部携带的章节元数据标记。
         *
         * 摊平后的文本形如：
         * ```
         * 第一章 开端
         * 正文……
         * 第二章 深入
         * 正文……
         * #NR-EPUB-META#[{"t":"第一章 开端","s":0,"e":20},{"t":"第二章 深入","s":21,"e":41}]
         * ```
         * 尾部这段元数据**不落在任何章节区间内**，读者看不到；
         * [parseChapters] 读到它就能原样还原目录——EPUB 自带的目录（nav / NCX）
         * 比任何正则猜测都准，不能因为摊平成纯文本而丢掉。
         */
        private const val EPUB_META_MARKER = "#NR-EPUB-META#"

        /** 章节标题正则：第X章/节/卷/回、序章、楔子、番外、Chapter N 等 */
        val CHAPTER_REGEX = Regex(
            "^\\s*(第[0-9零一二三四五六七八九十百千万两]+\\s*[章节卷回集]|序章|楔子|引子|前言|后记|尾声|尾声了|终章|番外[篇外]?|Chapter\\s+\\d+|CHAPTER\\s+\\d+).{0,60}$"
        )

        /**
         * 摊平 EPUB：一本书 = 一份纯文本 + 一张章节表。
         *
         * 为什么摊平：既有阅读链路（[com.novelreader.source.local.LocalBookAdapter]）
         * 认的是「一个文本文件 + 章节的字符区间」，EPUB 是 zip 包，塞进去没法按偏移读正文。
         * 摊平后每章的标题写在该章正文的第一行，章节区间与纯文本里的位置一一对应；
         * 章末再附一段机器可读的章节元数据（见 [EPUB_META_MARKER]），
         * 让 [parseChapters] 能**原样还原**目录，而不是靠正则去猜标题。
         */
        internal fun flattenEpub(book: EpubBookParser.EpubBook): FlattenedEpub {
            val sb = StringBuilder()
            val chapters = mutableListOf<ParsedChapter>()
            book.chapters.forEachIndexed { index, ch ->
                if (index > 0) sb.append('\n')
                val start = sb.length
                sb.append(ch.title).append('\n')
                sb.append(ch.text.trimEnd())
                chapters.add(ParsedChapter(ch.title, start, sb.length))
            }
            // 尾部元数据不落在任何章节区间内，读者看不到
            sb.append('\n').append(EPUB_META_MARKER).append(buildEpubMeta(chapters)).append('\n')
            return FlattenedEpub(sb.toString(), chapters)
        }

        /** 章节区间序列化成 JSON（跟在 [EPUB_META_MARKER] 后面） */
        internal fun buildEpubMeta(chapters: List<ParsedChapter>): String {
            val arr = com.google.gson.JsonArray()
            chapters.forEach { ch ->
                val o = com.google.gson.JsonObject()
                o.addProperty("t", ch.title)
                o.addProperty("s", ch.start)
                o.addProperty("e", ch.end)
                arr.add(o)
            }
            return arr.toString()
        }

        /**
         * 还原 EPUB 摊平文本里的章节表；不是 EPUB 文本（或元数据损坏）返回 null。
         */
        internal fun parseEpubMeta(text: String): List<ParsedChapter>? {
            val idx = text.lastIndexOf(EPUB_META_MARKER)
            if (idx < 0) return null
            val json = text.substring(idx + EPUB_META_MARKER.length).substringBefore('\n').trim()
            if (json.isEmpty()) return null
            return runCatching {
                JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                    val o = el.asJsonObject
                    val title = o.get("t")?.asString ?: return@mapNotNull null
                    val start = o.get("s")?.asInt ?: return@mapNotNull null
                    val end = o.get("e")?.asInt ?: return@mapNotNull null
                    ParsedChapter(title, start, end)
                }.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
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

    /** EPUB 摊平结果：一份纯文本 + 一张章节区间表 */
    internal data class FlattenedEpub(val text: String, val chapters: List<ParsedChapter>)

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
        val bytes = readUriBytes(uri)
        importTxtBytes(bytes, displayName)
    }

    /**
     * 从 SAF Uri 导入 EPUB（B9）。
     *
     * 与 TXT 导入的差别只有「怎么把正文取出来」：EPUB 是 zip 包，
     * 这里用 [EpubBookParser] 解析出章节，再摊平成纯文本 + 章节表，
     * 之后完全复用 TXT 的存储与阅读链路。
     *
     * @return 导入成功的元数据
     * @throws IllegalArgumentException 文件为空 / 太大 / 不是合法 EPUB
     */
    suspend fun importEpub(uri: Uri, displayName: String?): LocalBookMeta = withContext(Dispatchers.IO) {
        importEpubBytes(readUriBytes(uri), displayName)
    }

    /**
     * 自动识别格式导入（TXT / EPUB）。
     *
     * 之所以要这个入口：本地书导入是「选一个文件」的动作，用户不会先判断
     * 自己拿的是 txt 还是 epub；调用方拿不准时直接走这里即可。
     * 判据用 zip 魔数（`PK\x03\x04`）——EPUB 一定是 zip，而 TXT 不会是。
     */
    suspend fun importAuto(uri: Uri, displayName: String?): LocalBookMeta = withContext(Dispatchers.IO) {
        val bytes = readUriBytes(uri)
        if (looksLikeZip(bytes)) importEpubBytes(bytes, displayName) else importTxtBytes(bytes, displayName)
    }

    /** 读取 SAF Uri 的全部字节（读不到就抛，调用方已有同样的报错文案） */
    private fun readUriBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选文件")

    /** zip 魔数：EPUB 是 zip 包，TXT 不是 */
    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** TXT 导入（字节 → 解码 → 切章 → 落盘 → 写索引） */
    private fun importTxtBytes(bytes: ByteArray, displayName: String?): LocalBookMeta {
        if (bytes.isEmpty()) throw IllegalArgumentException("文件为空")
        if (bytes.size > 64 * 1024 * 1024) throw IllegalArgumentException("文件过大（>64MB）")

        val (text, charset) = decode(bytes)
        if (text.isBlank()) throw IllegalArgumentException("文件内容为空或无法解码")

        val id = newBookId()
        val fileName = "$id.txt"
        File(dir, fileName).writeBytes(bytes)

        val title = displayName?.substringBeforeLast('.')?.trim().orEmpty()
            .ifBlank { "本地书 $id" }
        return registerBook(
            id = id,
            fileName = fileName,
            charset = charset,
            title = title,
            author = "本地导入",
            chapterCount = parseChapters(text).size,
        )
    }

    /** EPUB 导入（字节 → 解析 → 摊平 → 落盘 → 写索引） */
    private fun importEpubBytes(bytes: ByteArray, displayName: String?): LocalBookMeta {
        if (bytes.isEmpty()) throw IllegalArgumentException("文件为空")
        if (bytes.size > 64 * 1024 * 1024) throw IllegalArgumentException("文件过大（>64MB）")

        val book = EpubBookParser.parse(bytes)
            ?: throw IllegalArgumentException("EPUB 解析失败：文件可能不是有效的 EPUB")

        val flat = flattenEpub(book)
        val id = newBookId()
        val fileName = "$id.epub.txt"
        File(dir, fileName).writeBytes(flat.text.toByteArray(Charsets.UTF_8))

        // 书名优先取 EPUB 元数据里的 dc:title，取不到再退回文件名
        val title = book.title.ifBlank {
            displayName?.substringBeforeLast('.')?.trim().orEmpty().ifBlank { "本地书 $id" }
        }
        return registerBook(
            id = id,
            fileName = fileName,
            charset = "UTF-8",
            title = title,
            author = book.author.ifBlank { "本地导入" },
            chapterCount = flat.chapters.size,
        )
    }

    /** 生成不冲突的书 id */
    private fun newBookId(): String = "b${System.currentTimeMillis()}${(100..999).random()}"

    /** 写索引（同名覆盖旧导入） */
    private fun registerBook(
        id: String,
        fileName: String,
        charset: String,
        title: String,
        author: String,
        chapterCount: Int,
    ): LocalBookMeta {
        val meta = LocalBookMeta(
            id = id,
            title = title,
            author = author,
            fileName = fileName,
            charset = charset,
            chapterCount = chapterCount,
            addedAt = System.currentTimeMillis(),
        )
        val all = getAll().toMutableList()
        all.removeAll { it.title == meta.title } // 同名覆盖旧导入
        all.add(meta)
        saveIndex(all)
        return meta
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

    /**
     * 解析章节：按标题行切分；无匹配时整本作为单章「全文」。
     *
     * B9 起识别顺序是「EPUB 元数据 → 智能识别 → 旧正则 → 整本一章」：
     * - EPUB 摊平文本尾部带着章节表，直接还原（最准，EPUB 自带目录本来就比正则可靠）；
     * - 其余交给 [ChapterDetector] 的多策略识别（支持第一卷 / Chapter 1 / 番外等旧正则认不出的形态）；
     * - 识别不到再退回旧正则，保证**老行为不丢**；
     * - 仍认不出则整本作为单章「全文」（与旧实现一致）。
     */
    fun parseChapters(text: String): List<ParsedChapter> {
        // 1) EPUB 摊平文本：尾部带章节元数据，原样还原目录
        parseEpubMeta(text)?.let { return it }

        // 2) 智能目录识别
        val marks = ChapterDetector.detect(text)
        if (marks.isNotEmpty()) return buildChapters(text, marks)

        // 3) 旧正则兜底
        val legacy = legacyChapterMarks(text)
        if (legacy.isNotEmpty()) return buildChapters(text, legacy)

        // 4) 整本一章
        return listOf(ParsedChapter("全文", 0, text.length))
    }

    /**
     * 按章节标记切分：补齐首章前的前言、章与章之间的边界。
     */
    private fun buildChapters(
        text: String,
        marks: List<ChapterDetector.ChapterMark>,
    ): List<ParsedChapter> {
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

    /**
     * 旧正则的章节标记（保留原实现的口径，作为智能识别失败后的兜底）。
     */
    private fun legacyChapterMarks(text: String): List<ChapterDetector.ChapterMark> {
        val marks = mutableListOf<ChapterDetector.ChapterMark>()
        var lineStart = 0
        for (line in text.lineSequence()) {
            val lineEnd = lineStart + line.length
            if (line.isNotBlank() && CHAPTER_REGEX.containsMatchIn(line)) {
                marks.add(ChapterDetector.ChapterMark(line.trim(), lineStart, lineEnd))
            }
            lineStart = lineEnd + 1 // +1 跳过换行符
        }
        return marks
    }

    /**
     * 还原 EPUB 摊平文本里的章节表；不是 EPUB 文本（或元数据损坏）返回 null。
     */

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
