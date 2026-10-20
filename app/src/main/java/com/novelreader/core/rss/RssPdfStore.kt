package com.novelreader.core.rss

import android.content.Context
import android.net.Uri
import com.novelreader.data.database.RssPdfDao
import com.novelreader.data.model.RssPdfEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 订阅书架里的本地 PDF 存储（v1.8.6.0 待办 #29）。
 *
 * 职责边界：
 * - 文件本体复制到 `filesDir/rss_pdf/<uuid>.pdf`（UUID 防重名覆盖，
 *   用户两次导入同名文件互不影响）；
 * - 元数据落 `rss_pdfs` 表（[RssPdfDao]）；
 * - 删除 = 先删库记录再删文件，两边都不留孤儿。
 *
 * 导入做两级校验：PDF 魔数（`%PDF`）+ 体积上限。不校验魔数的话，
 * 选错文件（比如把 TXT 当 PDF）要等点开阅读才报「文件损坏」。
 */
class RssPdfStore(
    private val context: Context,
    private val dao: RssPdfDao,
) {

    companion object {
        /** 单文件上限：期刊 PDF 一般 5~20MB，128MB 足够且防住误选的大文件 */
        const val MAX_SIZE = 128L * 1024 * 1024
        private const val DIR_NAME = "rss_pdf"
    }

    private val dir: File
        get() = File(context.filesDir, DIR_NAME)

    fun observe(): Flow<List<RssPdfEntity>> = dao.observeAll()

    /** URI 的展示名（取 displayName，退化到 path 末段） */
    fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /**
     * 导入一个本地 PDF。返回新建记录的 id。
     *
     * @throws IllegalArgumentException 文件不可读 / 不是 PDF / 超过体积上限
     */
    suspend fun import(uri: Uri, displayName: String?): Long = withContext(Dispatchers.IO) {
        runCatching { dir.mkdirs() }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选文件")
        if (bytes.isEmpty()) throw IllegalArgumentException("文件为空")
        if (bytes.size > MAX_SIZE) throw IllegalArgumentException("文件过大（>128MB）")
        if (!bytes.take(5).toByteArray().decodeToString().startsWith("%PDF")) {
            throw IllegalArgumentException("所选文件不是 PDF")
        }
        val fileName = "${UUID.randomUUID()}.pdf"
        File(dir, fileName).writeBytes(bytes)
        val name = displayName?.substringBeforeLast('.')?.trim().orEmpty()
            .ifBlank { "导入的 PDF" }
        dao.insert(
            RssPdfEntity(
                name = name,
                fileName = fileName,
                size = bytes.size.toLong(),
            ),
        )
    }

    /** 删除：库记录 + 文件一起清 */
    suspend fun delete(entity: RssPdfEntity) = withContext(Dispatchers.IO) {
        dao.delete(entity)
        runCatching { File(dir, entity.fileName).delete() }
    }

    /** 本地文件（不在则返回 null） */
    fun fileOf(entity: RssPdfEntity): File? =
        File(dir, entity.fileName).takeIf { it.isFile && it.length() > 0L }
}

/** 文件体积的人话格式（KB / MB） */
fun RssPdfEntity.sizeLabel(): String {
    val kb = size / 1024.0
    return if (kb < 1024) String.format("%.0f KB", kb)
    else String.format("%.1f MB", kb / 1024.0)
}
