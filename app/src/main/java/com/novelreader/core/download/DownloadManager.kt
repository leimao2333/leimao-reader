package com.novelreader.core.download

import android.content.Context
import com.novelreader.core.network.HttpClient
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.MediaItem
import com.novelreader.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载任务状态。
 */
data class DownloadState(
    /** 进度 0~1 */
    val progress: Float = 0f,
    /** 是否下载中 */
    val isDownloading: Boolean = false,
    /** 已完成/失败提示 */
    val message: String? = null,
    /** 是否成功 */
    val success: Boolean = false,
)

/**
 * 下载管理器。
 *
 * 负责将内容（小说正文 / 漫画图片 / 视频）下载到应用私有目录，
 * 避免申请外部存储权限。带实时进度反馈。
 */
class DownloadManager(private val context: Context) {

    /** 下载根目录：应用私有外部目录（无需权限） */
    private val downloadRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    /**
     * 下载一条内容到本地。
     *
     * @param item 条目信息
     * @param content 已解析的内容结果（小说文本/漫画图片/视频地址）
     * @return 保存的文件（小说=单个 txt；漫画=目录；视频=单个视频文件）
     */
    fun download(
        item: MediaItem,
        content: ContentResult,
    ): Flow<DownloadState> = flow {
        emit(DownloadState(progress = 0f, isDownloading = true))
        runCatching {
            withContext(Dispatchers.IO) {
                when (item.type) {
                    SourceType.NOVEL -> downloadNovel(item, content)
                    SourceType.COMIC -> downloadComic(item, content)
                    SourceType.VIDEO -> downloadVideo(item, content)
                }
            }
        }.onSuccess { file ->
            emit(DownloadState(progress = 1f, isDownloading = false, success = true, message = "已保存：${file.absolutePath}"))
        }.onFailure { e ->
            emit(DownloadState(progress = 1f, isDownloading = false, success = false, message = "下载失败：${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /** 下载小说正文（保存为 txt） */
    private fun downloadNovel(item: MediaItem, content: ContentResult): File {
        val text = content.novelText
            ?: throw IllegalStateException("正文内容为空")
        val dir = File(downloadRoot, "novel")
        val safeName = sanitize("${item.title}.txt")
        val file = File(dir, safeName)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** 下载漫画（多张图片保存到以标题命名的目录） */
    private fun downloadComic(item: MediaItem, content: ContentResult): File {
        val images = content.comicImages ?: emptyList()
        if (images.isEmpty()) throw IllegalStateException("漫画图片为空")
        val dir = File(downloadRoot, "comic/${sanitize(item.title)}")
        dir.mkdirs()
        images.forEachIndexed { index, url ->
            val ext = url.substringBefore('?').substringAfterLast('.', "jpg").takeIf { it.length <= 5 } ?: "jpg"
            val file = File(dir, "%03d.$ext".format(index + 1))
            HttpClient.downloadFile(url, file)
        }
        return dir
    }

    /** 下载视频（单文件） */
    private fun downloadVideo(item: MediaItem, content: ContentResult): File {
        val url = content.videoUrl
            ?: throw IllegalStateException("视频地址为空")
        val dir = File(downloadRoot, "video")
        dir.mkdirs()
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").takeIf { it.length <= 5 } ?: "mp4"
        val file = File(dir, sanitize("${item.title}.$ext"))
        HttpClient.downloadFile(url, file, content.videoHeaders)
        return file
    }

    /** 清理文件名中的非法字符 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
}
