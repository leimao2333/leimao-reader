package com.novelreader.core.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.novelreader.core.image.ComicImageScramble
import com.novelreader.core.network.HttpClient
import com.novelreader.domain.model.ContentResult
import com.novelreader.domain.model.ChapterItem
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
            // 部分图床把图片整包 AES 加密下发，先解密再落盘，
            // 否则下载到本地的同样是解不开的「坏图」
            HttpClient.downloadFile(
                url,
                file,
                content.comicHeaders,
                byteTransform = com.novelreader.core.image.ImageDecryptor::maybeDecrypt,
            )
            // 部分站点把每页按 N 段纵向打乱后下发；落盘前先还原，
            // 否则下载到本地的图片同样是乱序的
            unscrambleDownloaded(file, content.comicImageScripts[url])
        }
        return dir
    }

    /**
     * 就地把已下载的乱序图片还原。
     *
     * 识别不出脚本、或段数 ≤ 1 时不动文件；任何异常都只记日志，不影响下载结果。
     */
    private fun unscrambleDownloaded(file: File, script: String?) {
        if (script.isNullOrBlank() || !file.isFile) return
        runCatching {
            val src = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val fixed = ComicImageScramble.unscrambleByScript(src, script)
            if (fixed === src) {
                src.recycle()
                return
            }
            // 按原扩展名编码，避免 png/webp 被写成 jpg 后内容与后缀不符
            val format = if (file.extension.equals("png", true) || file.extension.equals("webp", true)) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            java.io.FileOutputStream(file).use { out -> fixed.compress(format, 92, out) }
            fixed.recycle()
            src.recycle()
        }.onFailure { Log.w(TAG, "图片乱序还原失败 err=${it.javaClass.simpleName}") }
    }

    // ===== 漫画章节离线缓存（v1.2.11） =====

    /** 某本漫画的章节缓存根目录（标题+源标识防同名碰撞） */
    private fun comicCacheDir(item: MediaItem): File =
        File(downloadRoot, "comic_cache/${sanitize(item.title)}_${item.sourceId.takeLast(6)}")

    /** 单章缓存目录：以章节索引 5 位数字命名，天然有序 */
    fun comicChapterCacheDir(item: MediaItem, chapterIndex: Int): File =
        File(comicCacheDir(item), "%05d".format(chapterIndex))

    /** 某本漫画已缓存的章节索引（目录存在且其中含文件） */
    fun cachedComicChapters(item: MediaItem): Set<Int> =
        runCatching {
            comicCacheDir(item).listFiles()
                ?.filter { it.isDirectory && (it.listFiles()?.any(File::isFile) == true) }
                ?.mapNotNull { it.name.toIntOrNull() }
                ?.toSet()
        }.getOrNull() ?: emptySet()

    /**
     * 下载单章到章节缓存（离线阅读）。
     *
     * 与「下载到 downloads/comic/ 导出目录」不同，这里写入 comic_cache/：
     * 先写临时目录、全部成功后原子改名 —— 中途取消/失败不会留下半章坏数据。
     * 图片解密与乱序还原与整部下载完全一致。
     */
    fun downloadChapterToCache(item: MediaItem, chapter: ChapterItem, content: ContentResult): File {
        val images = content.comicImages?.filter { it.isNotBlank() }
            ?: throw IllegalStateException("漫画图片为空")
        val dir = comicChapterCacheDir(item, chapter.index)
        val tmp = File(dir.parentFile, dir.name + ".tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        images.forEachIndexed { index, url ->
            val ext = url.substringBefore('?').substringAfterLast('.', "jpg")
                .takeIf { it.length <= 5 } ?: "jpg"
            val file = File(tmp, "%03d.$ext".format(index + 1))
            HttpClient.downloadFile(
                url,
                file,
                content.comicHeaders,
                byteTransform = com.novelreader.core.image.ImageDecryptor::maybeDecrypt,
            )
            unscrambleDownloaded(file, content.comicImageScripts[url])
        }
        if (dir.exists()) dir.deleteRecursively()
        if (!tmp.renameTo(dir)) {
            tmp.deleteRecursively()
            throw IllegalStateException("缓存目录写入失败")
        }
        return dir
    }

    /** 清除某本漫画的全部章节缓存 */
    fun clearComicCache(item: MediaItem) {
        runCatching { comicCacheDir(item).deleteRecursively() }
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

    private companion object {
        const val TAG = "DownloadManager"
    }
}
