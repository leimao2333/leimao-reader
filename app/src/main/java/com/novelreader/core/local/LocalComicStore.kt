package com.novelreader.core.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 本地漫画库（D20，对标 venera 本地导入 / legado 本地漫画）。
 *
 * 设计原则与 [LocalBookStore] 完全一致：
 * - **不入 Room**：本地漫画数量少、结构稳定，用 JSON 索引 + 目录树，免数据库迁移；
 * - **必须复制进私有目录**：SAF 给的 `content://` 往往只是本次会话可读，直接存 Uri
 *   会在重启后全部失效（本项目里本地书早就踩过同样的坑）。
 *
 * 目录布局（一个漫画 = 一个目录，一个章节 = 一个子目录）：
 * ```
 * filesDir/local_comics/
 *   index.json
 *   <comicId>/
 *     ch_00001/00001.jpg …
 *     ch_00002/00001.jpg …
 * ```
 * 这样「章节」天然是「图片列表」，与 [com.novelreader.domain.model.SourceAdapter.content]
 * 的漫画语义一一对应，阅读器（含缩放、预加载、进度）零改动即可复用。
 */
class LocalComicStore(private val context: Context) {

    companion object {
        /** 本地漫画源的 sourceId 前缀；[sourceId] 必须以它开头才会走这里的适配器 */
        const val SOURCE_PREFIX = "local_comic_"

        private const val DIR_NAME = "local_comics"
        private const val INDEX_FILE = "index.json"

        /** 认定为图片的后缀（CBZ 内过滤、目录导入过滤共用） */
        private val IMAGE_EXTS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jfif",
        )

        /**
         * 自然序比较：`2.jpg` 排在 `10.jpg` 前面。
         *
         * 漫画最常见的内容错乱来源就是按字符串排序（`1,10,2`），所以这里逐字符比较，
         * 遇到数字段就整体按数值比。
         */
        internal fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ni = i
                    var nj = j
                    while (ni < a.length && a[ni].isDigit()) ni++
                    while (nj < b.length && b[nj].isDigit()) nj++
                    val va = a.substring(i, ni).toLongOrNull() ?: 0L
                    val vb = b.substring(j, nj).toLongOrNull() ?: 0L
                    if (va != vb) return if (va < vb) -1 else 1
                    i = ni
                    j = nj
                } else {
                    if (ca != cb) return if (ca < cb) -1 else 1
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }

        internal fun isImageName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
    }

    /** 本地漫画元数据（存 index.json） */
    data class LocalComicMeta(
        val id: String,
        /** 显示名（文件名去掉扩展名） */
        val title: String,
        val author: String = "本地导入",
        /** 封面相对路径（相对漫画目录），无封面为 null */
        val coverFile: String? = null,
        /** 章节数（CBZ 恒为 1） */
        val chapterCount: Int = 0,
        val addedAt: Long = System.currentTimeMillis(),
        /** 导入方式：CBZ / DIR */
        val kind: String = "CBZ",
    )

    /** 章节（= 漫画目录下的一个子目录） */
    data class LocalChapter(
        val name: String,
        val dirName: String,
        val imageCount: Int,
    )

    private val root: File get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val indexFile: File get() = File(root, INDEX_FILE)

    @Volatile
    private var cache: List<LocalComicMeta>? = null

    // ===== 索引 =====

    fun getAll(): List<LocalComicMeta> {
        cache?.let { return it }
        val list = runCatching {
            val json = JsonParser.parseString(indexFile.readText())
            json.asJsonArray.map { el ->
                val o = el.asJsonObject
                LocalComicMeta(
                    id = o.get("id").asString,
                    title = o.get("title")?.asString ?: "本地漫画",
                    author = o.get("author")?.asString ?: "本地导入",
                    coverFile = o.get("coverFile")?.takeIf { it.isJsonPrimitive }?.asString,
                    chapterCount = o.get("chapterCount")?.asInt ?: 0,
                    addedAt = o.get("addedAt")?.asLong ?: 0L,
                    kind = o.get("kind")?.asString ?: "CBZ",
                )
            }
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    fun getComic(id: String): LocalComicMeta? = getAll().firstOrNull { it.id == id }

    private fun saveIndex(list: List<LocalComicMeta>) {
        val arr = com.google.gson.JsonArray()
        list.forEach { m ->
            val o = com.google.gson.JsonObject()
            o.addProperty("id", m.id)
            o.addProperty("title", m.title)
            o.addProperty("author", m.author)
            m.coverFile?.let { o.addProperty("coverFile", it) }
            o.addProperty("chapterCount", m.chapterCount)
            o.addProperty("addedAt", m.addedAt)
            o.addProperty("kind", m.kind)
            arr.add(o)
        }
        indexFile.writeText(arr.toString())
        cache = list
    }

    // ===== 章节 / 图片 =====

    /** 漫画目录 */
    fun comicDir(id: String): File = File(root, id)

    /** 章节列表（按子目录名自然序） */
    fun chapters(id: String): List<LocalChapter> {
        val dir = comicDir(id)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .map { d ->
                LocalChapter(
                    // 目录名是我们自己生成的 ch_00003 时显示成「第 3 话」，
                    // 文件夹导入保留用户原来的子目录名（更接近"一话 = 一个文件夹"的心智）
                    name = chapterDisplayName(d.name),
                    dirName = d.name,
                    imageCount = d.listFiles().orEmpty().count { it.isFile && isImageName(it.name) },
                )
            }
    }

    /** 某章的全部图片（自然序） */
    fun chapterFiles(id: String, chapterIndex: Int): List<File> {
        val chapter = chapters(id).getOrNull(chapterIndex) ?: return emptyList()
        return File(comicDir(id), chapter.dirName)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && isImageName(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }

    /** 目录名净化：去掉文件系统非法字符，空名退回 `ch_0000N` */
    private fun sanitizeDirName(raw: String, index: Int): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.').take(60)
        return cleaned.ifBlank { "ch_%05d".format(index + 1) }
    }

    /** 章节显示名：`ch_00003` → 「第 3 话」，其余原样 */
    private fun chapterDisplayName(dirName: String): String {
        val num = dirName.removePrefix("ch_").toIntOrNull()
        return if (dirName.startsWith("ch_") && num != null) "第 $num 话" else dirName
    }

    /** 封面文件（没有就取第一章第一张） */
    fun coverFile(meta: LocalComicMeta): File? {
        meta.coverFile?.let { rel -> File(comicDir(meta.id), rel).takeIf { it.isFile }?.let { return it } }
        return chapterFiles(meta.id, 0).firstOrNull()
    }

    // ===== 导入 =====

    /**
     * 导入 CBZ（zip 包）：整包作为**一章**，图片按自然序落盘。
     *
     * 用 [ZipInputStream] 顺序读而不是 `ZipFile`：SAF 给的只是 InputStream，
     * 落临时文件再随机读多一次全量拷贝，大包（几百 MB）代价明显。
     */
    suspend fun importCbz(uri: Uri, displayName: String?): LocalComicMeta = withContext(Dispatchers.IO) {
        val id = newComicId()
        val comicDir = comicDir(id).apply { mkdirs() }
        val chapterDir = File(comicDir, "ch_00001").apply { mkdirs() }

        var written = 0
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取所选文件")
        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // macOS 压缩工具会塞一堆垃圾条目，必须过滤，否则目录里全是 ._xxx
                    val skip = entry.isDirectory ||
                        name.startsWith("__MACOSX/") ||
                        name.substringAfterLast('/').startsWith("._") ||
                        !isImageName(name)
                    if (!skip) {
                        // 条目名可能带目录层级（chapter1/001.jpg），只取文件名，避免路径穿越
                        val fileName = name.substringAfterLast('/')
                        val target = File(chapterDir, "%05d_%s".format(written, fileName))
                        target.outputStream().use { out -> zip.copyTo(out) }
                        written++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (written == 0) {
            comicDir.deleteRecursively()
            throw IllegalArgumentException("压缩包里没有图片")
        }

        val firstImage = chapterDir.listFiles().orEmpty()
            .filter { it.isFile && isImageName(it.name) }
            .minWithOrNull { a, b -> naturalCompare(a.name, b.name) }

        val meta = LocalComicMeta(
            id = id,
            title = displayName?.substringBeforeLast('.')?.trim().orEmpty().ifBlank { "本地漫画 $id" },
            chapterCount = 1,
            kind = "CBZ",
            coverFile = firstImage?.let { File("ch_00001", it.name).path },
        )
        registerMeta(meta)
        meta
    }

    /**
     * 导入图片文件夹（SAF 目录树）。
     *
     * 章节切分规则：顶层**子目录**各成一章；若顶层没有子目录（用户选的就是一堆图），
     * 整个目录作为「第 1 话」。这样 CBZ 与文件夹两种导入对阅读器的呈现一致。
     */
    suspend fun importFolder(treeUri: Uri, displayName: String?): LocalComicMeta =
        withContext(Dispatchers.IO) {
            val id = newComicId()
            val comicDir = comicDir(id).apply { mkdirs() }
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

            val children = listChildren(treeUri, rootDocId)
            val dirs = children.filter { it.isDir }
            val looseImages = children.filter { !it.isDir && isImageName(it.name) }

            var chapters = 0
            if (dirs.isNotEmpty()) {
                val used = mutableSetOf<String>()
                dirs.sortedWith { a, b -> naturalCompare(a.name, b.name) }.forEachIndexed { index, dir ->
                    // 保留用户原来的子目录名（它就是一话的名字），只有非法字符 / 重名时才替换
                    var dirName = sanitizeDirName(dir.name, index)
                    if (!used.add(dirName)) dirName = "${dirName}_${index + 1}"
                    val chapterDir = File(comicDir, dirName).apply { mkdirs() }
                    val images = listChildren(treeUri, dir.docId)
                        .filter { !it.isDir && isImageName(it.name) }
                        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    var n = 0
                    images.forEach { img ->
                        copyDocument(treeUri, img.docId, File(chapterDir, "%05d_%s".format(n, img.name)))
                        n++
                    }
                    if (n > 0) chapters++ else chapterDir.deleteRecursively()
                }
            } else if (looseImages.isNotEmpty()) {
                val chapterDir = File(comicDir, "ch_00001").apply { mkdirs() }
                looseImages.sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    .forEachIndexed { n, img ->
                        copyDocument(treeUri, img.docId, File(chapterDir, "%05d_%s".format(n, img.name)))
                    }
                chapters = 1
            }

            if (chapters == 0) {
                comicDir.deleteRecursively()
                throw IllegalArgumentException("所选目录里没有图片")
            }

            val firstImage = comicDir.listFiles().orEmpty()
                .filter { it.isDirectory }
                .minWithOrNull { a, b -> naturalCompare(a.name, b.name) }
                ?.listFiles().orEmpty()
                .filter { it.isFile && isImageName(it.name) }
                .minWithOrNull { a, b -> naturalCompare(a.name, b.name) }

            val meta = LocalComicMeta(
                id = id,
                title = displayName?.trim().orEmpty().ifBlank { "本地漫画 $id" },
                chapterCount = chapters,
                kind = "DIR",
                coverFile = firstImage?.let { File(it.parentFile!!.name, it.name).path },
            )
            registerMeta(meta)
            meta
        }

    /** 删除本地漫画（目录 + 索引） */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val all = getAll().toMutableList()
        if (all.removeAll { it.id == id }) {
            saveIndex(all)
            comicDir(id).deleteRecursively()
        }
    }

    private fun registerMeta(meta: LocalComicMeta) {
        val all = getAll().toMutableList()
        all.removeAll { it.title == meta.title } // 同名覆盖旧导入
        all.add(meta)
        saveIndex(all)
    }

    private fun newComicId(): String = "c${System.currentTimeMillis()}${(100..999).random()}"

    // ===== SAF 读取 =====

    private data class ChildDoc(val docId: String, val name: String, val isDir: Boolean)

    /** 列出目录下的子项（DocumentsContract 直查，避免引入 documentfile 依赖） */
    private fun listChildren(treeUri: Uri, parentDocId: String): List<ChildDoc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = mutableListOf<ChildDoc>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    result.add(
                        ChildDoc(
                            docId = docId,
                            name = name,
                            isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }
        return result
    }

    private fun copyDocument(treeUri: Uri, docId: String, target: File) {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.openInputStream(docUri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: run { target.delete() }
    }
}
