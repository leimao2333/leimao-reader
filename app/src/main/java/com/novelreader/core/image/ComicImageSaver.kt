package com.novelreader.core.image

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.novelreader.core.network.HttpClient
import java.io.File
import java.io.FileOutputStream
import okhttp3.Request

/**
 * 把网络图片保存到系统相册（D22 封面保存）。
 *
 * - Android 10（API 29）及以上：走 MediaStore 写入 `Pictures/Leimao`，无需存储权限。
 * - 更低版本：落到应用外部私有目录 `Pictures/Leimao`（避免申请 WRITE_EXTERNAL_STORAGE）。
 *
 * 需要在后台线程调用（内部为阻塞 IO），调用方用协程 + Dispatchers.IO。
 */
object ComicImageSaver {

    /**
     * 下载并保存图片。
     *
     * @param headers 防盗链所需的请求头（Referer / UA 等），可为空。
     * @return 成功返回 true；网络失败 / 解码失败返回 false。
     */
    suspend fun saveToGallery(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v)
            }
            val response = HttpClient.executeRaw(builder.build(), timeoutMs = 30_000)
            response.use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                val bytes = resp.body?.bytes() ?: return@runCatching false
                if (bytes.isEmpty()) return@runCatching false
                val (ext, mime) = resolveFormat(url)
                writeBytes(context, bytes, ext, mime)
            }
        }.getOrDefault(false)
    }

    /** 按 URL 后缀猜格式，避免 PNG/WEBP 被当成 JPG 存（相册按 MIME 解码更稳） */
    private fun resolveFormat(url: String): Pair<String, String> {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".png") -> "png" to "image/png"
            path.endsWith(".webp") -> "webp" to "image/webp"
            path.endsWith(".gif") -> "gif" to "image/gif"
            path.endsWith(".bmp") -> "bmp" to "image/bmp"
            else -> "jpg" to "image/jpeg"
        }
    }

    private fun writeBytes(context: Context, bytes: ByteArray, ext: String, mime: String): Boolean {
        val name = "leimao_${System.currentTimeMillis()}.$ext"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Leimao",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        }
        // API < 29：写系统相册公共目录（需要 WRITE_EXTERNAL_STORAGE，由调用方先申请）
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Leimao",
        ).apply { mkdirs() }
        val file = File(dir, name)
        return runCatching {
            FileOutputStream(file).use { it.write(bytes) }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) != null
        }.getOrDefault(false)
    }
}
