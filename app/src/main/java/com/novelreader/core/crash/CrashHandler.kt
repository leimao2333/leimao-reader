package com.novelreader.core.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器：把崩溃堆栈落盘到 `files/crash/`。
 *
 * 动机：用户反馈「LunaTV 源播放视频闪退」，但模拟器无外网无法复现、
 * 代码静态审查未找到确定性崩溃点。有了本地崩溃日志，
 * 下次闪退后可以直接从设置页导出堆栈精确定位，不用再猜。
 *
 * 处理完**必须链回原 handler**，否则系统的「应用已停止」对话框 / ANR 上报失效。
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private const val MAX_FILES = 10

    /** 已安装标记（避免重复包装导致链式嵌套） */
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        installed = true
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeLog(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 崩溃日志目录（`files/crash/`） */
    fun crashDir(context: Context): File = File(context.filesDir, "crash")

    /** 列出全部崩溃日志（新→旧） */
    fun listLogs(context: Context): List<File> =
        crashDir(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun writeLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$ts.txt")

        val sb = StringBuilder().apply {
            appendLine("时间: $ts")
            appendLine("线程: ${thread.name}")
            appendLine("版本: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.MODEL})")
            appendLine()
            appendLine(Log.getStackTraceString(throwable))
        }
        file.writeText(sb.toString())

        // 只保留最近 N 份，避免占空间
        listLogs(context).drop(MAX_FILES).forEach { it.delete() }
    }
}
