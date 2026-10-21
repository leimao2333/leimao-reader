package com.novelreader.ui.screens.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.core.network.HttpClient
import com.novelreader.core.pdf.PdfNightFilter
import com.novelreader.core.pdf.PdfRenderSizing
import com.novelreader.core.pdf.PdfSource
import com.novelreader.core.preferences.PreferencesManager
import com.novelreader.data.cache.PdfFileCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** PDF 阅读器的阶段（v1.8.5.1） */
enum class PdfStage {
    /** 检查缓存 / 准备目录 */
    Preparing,
    /** 正在下载 */
    Downloading,
    /** 已落盘，正在打开 */
    Opening,
    /** 可翻页 */
    Ready,
    /** 失败（[PdfUiState.error] 里有原因） */
    Failed,
}

/**
 * PDF 阅读器状态（v1.8.5.1）。
 *
 * 集中成一个 data class：下载进度、页数、失败原因在 UI 上互斥，
 * 散成多个 StateFlow 很容易出现「进度条还在转、错误提示已经出来」的错位。
 */
data class PdfUiState(
    val stage: PdfStage = PdfStage.Preparing,
    /** 0f~1f；服务端不给 Content-Length 时为 null（显示不定态） */
    val progress: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val pageCount: Int = 0,
    val error: String = "",
    /** 本次是否直接命中了本地缓存（UI 上省掉「下载」文案） */
    val fromCache: Boolean = false,
    /** 夜间反色是否开启（v1.8.7.2 待办 #23）：白底页面反色 + 压暗亮度 */
    val nightInvert: Boolean = false,
)

/**
 * 应用内 PDF 阅读（v1.8.5.1）。
 *
 * 为什么不再丢给浏览器：v1.8.5.0 的「打开 PDF」是 `Intent.ACTION_VIEW`，
 * 跳出去之后**读完就回不来了** —— 手机浏览器不保留原页面，回来要从订阅列表
 * 重新找到那篇文章；而且很多出版社的 PDF 会先落进下载器 / 网盘 App。
 * 论文是拿来对照着正文读的，链路越短越好。
 *
 * 实现选择 **系统 `PdfRenderer`** 而不是第三方库：
 * - 零依赖、零 native 库（项目已有 ONNX 18MB/ABI，不想再加）；
 * - 只需要「翻页 + 缩放」这一件事，`PdfRenderer` 足够；
 * - 不支持的特性（表单 / 注释 / 文字选择）明确不做，也不假装支持。
 *
 * 线程模型：`PdfRenderer` **不是线程安全的**，且同一时刻只能打开一页，
 * 所以渲染全程串行（[renderMutex]）。UI 侧用协程按页请求，翻页快时后面的请求
 * 会排队 —— 比多线程渲染崩在 native 层强。
 */
class PdfViewerViewModel(
    private val cache: PdfFileCache,
    /** PDF 直链（拼不出来时为空串，此时直接失败并提示用浏览器） */
    val url: String,
    /** 标题（顶栏显示；通常是论文标题） */
    val title: String,
    /** 偏好（夜间反色开关，v1.8.7.2 待办 #23） */
    private val preferences: PreferencesManager,
) : ViewModel() {

    private val _state = MutableStateFlow(PdfUiState())
    val state: StateFlow<PdfUiState> = _state.asStateFlow()

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null

    /** 渲染串行化：PdfRenderer 同一时刻只能开一页 */
    private val renderMutex = Mutex()

    /** 页面位图缓存：只留 2 页（当前页 + 相邻页），再多就是白占内存 */
    private val pageCache = object : LruCache<Int, Bitmap>(PAGE_CACHE_SIZE) {
        // 不在这里 recycle：Compose 的 Image 可能仍持有引用，
        // 回收后绘制会抛「trying to use a recycled bitmap」。交给 GC。
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) = Unit
    }

    /** 上次渲染用的目标宽度；屏幕旋转 / 分屏变化时缓存要整体失效 */
    private var renderedWidthPx: Int = 0

    init {
        load()
        // 夜间反色：设置页与阅读页都能改，两边都直接改同一份偏好，
        // 这里用 Flow 订阅，任一处改动阅读页都会跟着变
        viewModelScope.launch {
            preferences.pdfNightInvertFlow.collect { on -> setNightInvert(on) }
        }
    }

    /**
     * 切换夜间反色（v1.8.7.2 待办 #23）。
     *
     * 位图是**渲染时**套滤镜的（缓存里存的就是套完之后的结果），
     * 所以翻转开关必须把页缓存整体失效，否则当前页还显示旧效果 ——
     * 只改状态位的话，用户看到的会是「图标变了、页面没变」。
     */
    fun setNightInvert(on: Boolean) {
        if (_state.value.nightInvert == on) return
        _state.value = _state.value.copy(nightInvert = on)
        pageCache.evictAll()
    }

    /**
     * 顶栏图标点击（v1.8.7.2 待办 #23）。
     *
     * 先改状态让页面立刻重渲染，再把值写回偏好 —— 反过来等 DataStore 落盘 + Flow
     * 回流才变的话，点一下要过一拍才亮，手感上像「没点中」。
     */
    fun toggleNightInvert() {
        val next = !_state.value.nightInvert
        setNightInvert(next)
        viewModelScope.launch { preferences.setPdfNightInvert(next) }
    }

    /** 首次进入 / 重试都走这里：命中缓存直接开，否则下载 */
    fun load() {
        viewModelScope.launch {
            closeDocument()
            if (url.isBlank()) {
                fail("这篇文章没有可用的 PDF 地址 —— 请用浏览器打开原文")
                return@launch
            }
            _state.value = PdfUiState(stage = PdfStage.Preparing)

            // 本地文件（v1.8.6.0 待办 #29：订阅书架导入的 PDF）：
            // 不走缓存与下载，直接开；先验魔数，选错文件时给「不是 PDF」而不是渲染崩溃
            val local = localFile()
            if (local != null) {
                if (!PdfSource.looksLikePdf(PdfSource.head(local))) {
                    fail("这份文件不是有效的 PDF")
                    return@launch
                }
                open(local, fromCache = true)
                return@launch
            }

            val target = cache.fileFor(url)
            val cached = target.isFile && target.length() > 0L &&
                PdfSource.looksLikePdf(PdfSource.head(target))
            if (!cached) {
                // 上次下到一半的 .part 留着，downloadFile 会按 Range 续传
                if (!download(target)) return@launch
            } else {
                cache.touch(target)
            }
            open(target, fromCache = cached)
        }
    }

    /** 下载到 [target]；失败时已写好状态并返回 false */
    private suspend fun download(target: File): Boolean = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        runCatching { target.parentFile?.mkdirs() }

        _state.value = _state.value.copy(stage = PdfStage.Downloading, error = "")
        val ok = runCatching {
            HttpClient.downloadFile(
                url = url,
                target = part,
                headers = PDF_HEADERS,
                // 期刊 PDF 常见 5~20MB，默认 20s 读超时对慢站不够
                timeoutMs = 90_000L,
                resumeFrom = if (part.isFile) part.length() else 0L,
                onProgress = { done, total -> publishProgress(done, total) },
            )
        }.onFailure { e ->
            // 下载器在非 2xx 时直接抛异常，拿不到响应体；这里把状态码翻译成人话
            fail(describeFailure(e))
        }.isSuccess
        if (!ok) return@withContext false

        // 200 + 网页是出版方拦爬虫时最常见的形态：必须校验魔数，
        // 否则 PdfRenderer 会抛「文件损坏」，用户完全不知道发生了什么
        val reason = PdfSource.rejectReason(PdfSource.head(part))
        if (reason.isNotEmpty()) {
            runCatching { part.delete() }
            fail(reason)
            return@withContext false
        }

        if (!part.renameTo(target)) {
            // 跨卷 / 已存在时 renameTo 会失败，退化成复制
            runCatching { part.copyTo(target, overwrite = true) }
            runCatching { part.delete() }
        }
        cache.prune()
        true
    }

    private fun publishProgress(done: Long, total: Long) {
        val current = _state.value
        val percent = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        // 每 128KB 回调一次，但界面只需要「百分比变了」或「字节数跨过 256KB」时刷新
        val changed = when {
            current.stage != PdfStage.Downloading -> true
            current.totalBytes != total -> true
            percent != null && current.progress != null &&
                (percent * 100).toInt() != (current.progress * 100).toInt() -> true
            percent == null && done - current.downloadedBytes >= 256 * 1024 -> true
            else -> false
        }
        if (!changed) return
        _state.value = current.copy(
            stage = PdfStage.Downloading,
            progress = percent,
            downloadedBytes = done,
            totalBytes = total,
        )
    }

    /** 打开本地 PDF（[fromCache] 只影响提示文案） */
    private fun open(file: File, fromCache: Boolean) {
        _state.value = _state.value.copy(stage = PdfStage.Opening, error = "", fromCache = fromCache)
        runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = pfd
            val r = PdfRenderer(pfd)
            renderer = r
            _state.value = _state.value.copy(stage = PdfStage.Ready, pageCount = r.pageCount)
        }.onFailure { e ->
            closeDocument()
            fail(
                when (e) {
                    // 加密 / 需要密码的 PDF：PdfRenderer 直接抛 SecurityException
                    is SecurityException -> "这份 PDF 有密码保护，应用内打不开 —— 请用浏览器或电脑打开"
                    else -> "PDF 打不开：${e.message ?: "文件可能已损坏"}"
                },
            )
        }
    }

    /**
     * 渲染第 [index] 页（0 基），返回可直接绘制的位图。
     *
     * [targetWidthPx] 一般给屏宽的 1.5 倍：适配宽度显示时略有余量，
     * 放大到 2 倍也不会立刻糊成一团。
     */
    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? = renderMutex.withLock {
        withContext(Dispatchers.IO) {
            val r = renderer ?: return@withContext null
            if (index < 0 || index >= r.pageCount) return@withContext null

            if (renderedWidthPx != targetWidthPx) {
                pageCache.evictAll()
                renderedWidthPx = targetWidthPx
            }
            pageCache.get(index)?.let { return@withContext it }

            runCatching {
                r.openPage(index).use { page ->
                    val size = PdfRenderSizing.compute(page.width, page.height, targetWidthPx)
                    if (!size.isValid) return@runCatching null
                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                    // PDF 页面默认透明：不铺白底的话，深色主题下会变成「黑纸黑字」
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    // 夜间反色在渲染管线的最后一环：拿到的就是最终要显示的位图，
                    // 缓存与调用方都不用再关心有没有套滤镜
                    val filtered = if (_state.value.nightInvert) PdfNightFilter.apply(bitmap) else bitmap
                    if (filtered !== bitmap) bitmap.recycle()
                    pageCache.put(index, filtered)
                    filtered
                }
            }.getOrNull()
        }
    }

    /** 已下载/已缓存的文件（「用浏览器打开」优先给本地文件，避免出版方二次拦截） */
    /**
     * 本地 PDF 文件（分享 / 本地导入共用）。
     *
     * `file://` 前缀 = 订阅书架导入的本地 PDF（v1.8.6.0 待办 #29），
     * 直接映射到 `filesDir/rss_pdf/`；否则按 url 查下载缓存。
     */
    fun localFile(): File? {
        if (url.startsWith("file://")) {
            val f = File(url.removePrefix("file://"))
            return f.takeIf { it.isFile && it.length() > 0L }
        }
        val f = cache.fileFor(url)
        return f.takeIf { it.isFile && it.length() > 0L }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(
            stage = PdfStage.Failed,
            error = message,
            progress = null,
        )
    }

    /** 把网络异常翻译成用户能理解的原因（下载器只给了「下载失败: 403 <url>」这类原文） */
    private fun describeFailure(e: Throwable): String {
        val raw = e.message.orEmpty()
        // 下载器抛的是「下载失败: 403 https://…」；其它路径可能给「HTTP 403」
        val code = Regex("""(?:下载失败:\s*|HTTP\s+)(\d{3})""")
            .find(raw)?.groupValues?.getOrNull(1)
        return when {
            code == "403" -> "出版方拒绝了下载请求（HTTP 403）：多半需要机构订阅或人机验证 —— 请用浏览器打开"
            code == "404" -> "PDF 地址不存在（HTTP 404）：出版方可能改了路径 —— 请用浏览器打开原文"
            code == "401" -> "需要登录才能下载（HTTP 401）—— 请用浏览器打开"
            code != null -> "下载失败（HTTP $code）—— 请用浏览器打开"
            raw.contains("Timeout", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) ||
                raw.contains("超时") -> "下载超时：网络较慢或出版方限速，可稍后重试"
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("UnknownHost", ignoreCase = true) -> "域名解析失败，请检查网络"
            // OkHttp 跟满 20 次重定向后放弃。出版方的反爬 / 登录流程就是靠反复跳转
            // 逼浏览器带 cookie，应用内拿不到文件时这条链路注定走不完
            raw.contains("Too many follow-up requests", ignoreCase = true) ->
                "出版方反复跳转（多半是 cookie 校验或人机验证），应用内取不到这份 PDF —— 请用浏览器打开"
            else -> {
                // 兜底文案里不要把整条 URL 甩给用户（又长又没信息量）
                val brief = raw.replace(Regex("""https?://\S+"""), "").trim().trimEnd(':', ' ')
                "下载失败：${brief.ifBlank { e::class.java.simpleName }}"
            }
        }
    }

    private fun closeDocument() {
        runCatching { renderer?.close() }
        renderer = null
        runCatching { descriptor?.close() }
        descriptor = null
        pageCache.evictAll()
        renderedWidthPx = 0
    }

    override fun onCleared() {
        closeDocument()
        super.onCleared()
    }

    private companion object {
        /** 同时保留的页数：当前页 + 相邻页，够顺滑翻页又不至于吃内存 */
        const val PAGE_CACHE_SIZE = 2

        /**
         * 下载 PDF 的附加请求头。
         *
         * `Accept: application/pdf` 不是必需的，但部分出版社（ACS / RSC）在
         * `Accept: text/html` 时会返回文章页而不是 PDF；显式声明能少走一次跳转。
         */
        val PDF_HEADERS: Map<String, String> = mapOf(
            "Accept" to "application/pdf,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        )
    }
}
