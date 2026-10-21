package com.novelreader.core.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

/**
 * 外部应用「用其他软件打开」推进来的 PDF（v1.8.7.2）。
 *
 * 以前应用只是个「PDF 消费者」：从订阅里拿到链接，自己下载自己看。
 * 但手机上 PDF 的来源远不止订阅 —— 微信/QQ 收到、文件管理器里点开、
 * 浏览器下载完点通知，系统都会弹「用其他软件打开」。清单里注册了
 * `application/pdf` 之后，本应用就会出现在那个列表里，选中即直接进阅读页。
 *
 * 关键点：外部给的 URI 是**临时授权**的 `content://`，进程一挂就失效。
 * 所以进来第一件事是把它复制进 `filesDir/pdf_inbox/`，之后所有逻辑
 * （渲染、分享、重开）都只认这个本地副本，不再依赖那个 URI。
 */
object PdfIntentImport {

    /** 导入目录名（与订阅书架的 `rss_pdf/` 分开，方便单独清理） */
    private const val INBOX_DIR = "pdf_inbox"

    /** 文件名最长保留多少个字符（剩下的丢掉，避免超出文件系统限制） */
    private const val MAX_NAME_LENGTH = 60

    /** 这个 Intent 是不是「让我打开一份 PDF」 */
    fun isPdfIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return false
        // 有些应用（尤其是老文件管理器）不给 MIME，只能靠后缀判断
        val uri = extractUri(intent) ?: return false
        if (intent.type == "application/pdf") return true
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return name.endsWith(".pdf")
    }

    /** 取出待打开的 URI：ACTION_VIEW 在 data 里，ACTION_SEND 在 EXTRA_STREAM 里 */
    fun extractUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        else -> null
    }

    /**
     * 把外部 URI 复制进应用私有目录，返回可直接喂给 PDF 阅读页的请求。
     *
     * 返回 null 表示「拿到了但打不开」（不是 PDF / 读不出来），调用方应给一句提示，
     * 而不是跳进阅读页再报错 —— 那边只会说「PDF 打不开」，用户不知道是哪一步坏的。
     */
    suspend fun import(context: Context, uri: Uri): IncomingPdf? = withContext(Dispatchers.IO) {
        val name = displayName(context, uri) ?: "外来文档.pdf"
        val target = File(
            File(context.filesDir, INBOX_DIR).apply { mkdirs() },
            sanitize(name),
        )
        // 同一份文件反复「打开」很常见（微信里点两次），大小一致就复用，
        // 不要每次都把 20MB 重新写一遍
        val size = querySize(context, uri)
        val reusable = target.isFile && target.length() > 0L && (size <= 0L || target.length() == size)
        if (!reusable) {
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (!ok) {
                runCatching { target.delete() }
                return@withContext null
            }
        }
        // 魔数校验：微信会把「需要登录」的网页存成 .pdf，直接交给 PdfRenderer
        // 只会得到一句「文件已损坏」，这里提前挡掉
        if (!PdfSource.looksLikePdf(PdfSource.head(target))) {
            runCatching { target.delete() }
            return@withContext null
        }
        IncomingPdf(fileUri = "file://" + target.absolutePath, title = titleOf(name))
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            val fromCursor = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    }
            }.getOrNull()
            if (!fromCursor.isNullOrBlank()) return fromCursor
        }
        // file:// 或查询失败：从路径末段猜
        val segment = uri.lastPathSegment ?: return null
        return runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
    }

    private fun querySize(context: Context, uri: Uri): Long {
        if (uri.scheme != "content") return -1L
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                        cursor.getLong(index)
                    } else -1L
                } ?: -1L
        }.getOrDefault(-1L)
    }

    /**
     * 文件名清洗：去掉路径分隔符、控制字符和 `:`（Windows 同步盘会用到），
     * 保留中文 / 字母 / 数字，其余折成下划线，并保证以 `.pdf` 结尾。
     */
    private fun sanitize(raw: String): String {
        val stem = raw.substringAfterLast('/').substringAfterLast('\\')
            .let { if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it }
        val base = stem
            .map { ch ->
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == ' ' || ch == '(' || ch == ')') ch else '_'
            }
            .joinToString("")
            .trim()
            .take(MAX_NAME_LENGTH)
            .ifBlank { "外来文档" }
        return "$base.pdf"
    }

    private fun titleOf(fileName: String): String =
        fileName.removeSuffix(".pdf").ifBlank { "PDF" }
}

/**
 * 一次「外部要求打开 PDF」的请求。
 *
 * [fileUri] 是 `file://` 开头的绝对路径，正好是 [com.novelreader.ui.screens.pdf.PdfViewerViewModel]
 * 认的本地文件形式；[title] 只用于顶栏显示。
 */
data class IncomingPdf(
    val fileUri: String,
    val title: String,
)
