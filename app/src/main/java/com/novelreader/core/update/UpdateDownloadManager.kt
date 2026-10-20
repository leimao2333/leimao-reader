package com.novelreader.core.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 安装包下载的**进程级**状态机（v1.8.3.0 待办 #18 / #19）。
 *
 * 之前下载跑在 `SettingsViewModel.viewModelScope` 里：用户退出设置页（ViewModel 被清）
 * 就等于把下载掐了；切后台锁屏还可能整个进程被回收。现在改成：
 *
 * - 下载协程挂在**进程级作用域**上，跟页面 / ViewModel / Service 生命周期都无关；
 * - 前台 Service（[UpdateDownloadService]）只负责「让进程活着 + 通知栏进度」，
 *   它被杀了下载也不会断，回来接着跑；
 * - 失败按 `.part` 文件的长度**断点续传**（Range 请求），不用从头下十几 MB。
 *
 * 状态只有一份，UI 进设置页时直接订阅当前值，删掉「退出页面进度就丢」的问题。
 */
object UpdateDownloadManager {

    /** 下载暂存目录：放私有目录而不是 cache —— cacheDir 在磁盘吃紧时会被系统清掉 */
    private const val DIR_NAME = "update"

    /** 中途失败的重试次数（每次从已下载处续传，退避 2s / 5s / 10s） */
    private const val MAX_ATTEMPTS = 3
    private val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

    /**
     * 下载状态。
     *
     * @property running 是否正在下载（含重试等待）
     * @property downloaded 已落盘字节（含续传前已有的部分）
     * @property total 总字节；-1 = 服务端没给 Content-Length
     * @property finished true 表示 [apkPath] 已是可安装的完整包
     * @property error 最近一次失败原因（非空时 UI 可展示，重试由用户点「重试」触发）
     */
    data class State(
        val running: Boolean = false,
        val version: String = "",
        val downloaded: Long = 0L,
        val total: Long = -1L,
        val apkPath: String? = null,
        val finished: Boolean = false,
        val error: String? = null,
        /** 已重试次数，UI 上可以显示「第 N 次重试」 */
        val attempt: Int = 0,
    ) {
        /** 0..1；服务端不给总长度时为 -1（UI 走不定态） */
        val progress: Float
            get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else -1f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** 下载暂存目录 */
    fun dirOf(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    /** 目标 APK 路径（已完成的正式文件） */
    fun targetOf(context: Context, version: String): File =
        File(dirOf(context), "leimao-reader-v$version.apk")

    /** 是否正在下载 */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 开始下载（已在跑就直接复用，不重复拉）。
     *
     * @return true = 本次真正启动了下载；false = 已经有一份在跑
     */
    @Synchronized
    fun start(context: Context, info: UpdateChecker.UpdateInfo): Boolean {
        if (job?.isActive == true) return false
        val app = context.applicationContext
        _state.value = State(
            running = true,
            version = info.version,
            total = info.apkSize,
        )
        job = scope.launch { runDownload(app, info) }
        return true
    }

    /** 用户放弃下载：停掉协程并清掉半截文件（下次重新开始） */
    @Synchronized
    fun cancel(context: Context) {
        job?.cancel()
        job = null
        val version = _state.value.version
        _state.value = State()
        if (version.isNotBlank()) {
            runCatching { partOf(context, version).delete() }
        }
    }

    /** 清掉下载状态（安装完成后 / 用户关掉提示时调用，不动磁盘文件） */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = State()
    }

    /** 半截文件（续传依据） */
    private fun partOf(context: Context, version: String): File =
        File(dirOf(context), "leimao-reader-v$version.apk.part")

    private suspend fun runDownload(context: Context, info: UpdateChecker.UpdateInfo) {
        val target = targetOf(context, info.version)
        val part = partOf(context, info.version)

        // 同一版本已经下完过（上次装完没删 / 重进设置页）：直接复用，不再重复下载
        if (target.exists() && target.length() > 0 &&
            (info.apkSize <= 0 || target.length() == info.apkSize)
        ) {
            _state.value = State(
                running = false,
                version = info.version,
                downloaded = target.length(),
                total = info.apkSize,
                apkPath = target.absolutePath,
                finished = true,
            )
            return
        }
        // 旧版本的半截包没用了，清掉避免串包
        dirOf(context).listFiles()?.forEach { f ->
            if (f.extension == "part" && f.name != part.name) runCatching { f.delete() }
        }
        // 旧版本的成品包也一并清掉：一个包几十 MB，不清理会越堆越多
        dirOf(context).listFiles()?.forEach { f ->
            if (f.name.endsWith(".apk") && f.absolutePath != target.absolutePath) {
                runCatching { f.delete() }
            }
        }

        var attempt = 0
        while (true) {
            val resumeFrom = if (part.exists()) part.length() else 0L
            _state.value = State(
                running = true,
                version = info.version,
                downloaded = resumeFrom,
                total = info.apkSize,
                attempt = attempt,
            )
            try {
                UpdateChecker.downloadApk(info.apkUrl, part, resumeFrom) { done, total ->
                    _state.value = State(
                        running = true,
                        version = info.version,
                        downloaded = done,
                        // 服务端不给长度时退回清单里的大小，别把「已下载」当总量算出 100%
                        total = when {
                            total > 0 -> total
                            info.apkSize > 0 -> info.apkSize
                            else -> -1L
                        },
                        attempt = attempt,
                    )
                }
                val len = part.length()
                // 完整性校验：知道总大小就必须对得上，否则当失败重试（GitHub CDN 偶发截断）
                if (info.apkSize > 0 && len != info.apkSize) {
                    throw IllegalStateException("安装包大小不符（$len / ${info.apkSize}）")
                }
                if (target.exists()) runCatching { target.delete() }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                _state.value = State(
                    running = false,
                    version = info.version,
                    downloaded = target.length(),
                    total = target.length(),
                    apkPath = target.absolutePath,
                    finished = true,
                )
                return
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                attempt++
                // 已经下了一部分就别丢掉：下一次带 Range 接着下
                if (attempt >= MAX_ATTEMPTS) {
                    _state.value = State(
                        running = false,
                        version = info.version,
                        downloaded = if (part.exists()) part.length() else 0L,
                        total = info.apkSize,
                        error = t.message ?: t.javaClass.simpleName,
                        attempt = attempt,
                    )
                    return
                }
                val delay = RETRY_DELAYS_MS.getOrElse(attempt - 1) { RETRY_DELAYS_MS.last() }
                _state.value = State(
                    running = true,
                    version = info.version,
                    downloaded = if (part.exists()) part.length() else 0L,
                    total = info.apkSize,
                    attempt = attempt,
                )
                kotlinx.coroutines.delay(delay)
            }
        }
    }
}
