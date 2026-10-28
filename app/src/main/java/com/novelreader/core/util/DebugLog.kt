package com.novelreader.core.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 常驻诊断日志（v1.9.0.2，设置 → 开发者选项）。
 *
 * 排查问题的老流程是「先临时插一行日志 → 打包 → 让用户复现 → 抓 logcat → 删日志」，
 * 一圈下来十几分钟，而且 adb 一断就白跑。这里改成常驻：
 *
 * - 平时不记录（`enabled = false`，零开销，只多一次布尔判断）；
 * - 用户遇到问题时在设置里打开开关，复现一次，然后「导出诊断日志」；
 * - 导出的文件直接发过来就能定位，不用再改代码。
 *
 * 只放**结构信息**（位移量、条数、耗时、错误类型），不放标题 / 封面地址这类内容 ——
 * 导出的文件是要发给别人看的。
 */
object DebugLog {

    /** 由设置页的开关驱动（MainActivity 里监听偏好同步过来） */
    @Volatile
    var enabled: Boolean = false

    private const val CAPACITY = 600

    private val buffer = ArrayDeque<String>(CAPACITY)
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 记一条。开关关着时直接返回，不做字符串拼接 */
    fun d(tag: String, message: String) {
        if (!enabled) return
        val line = "${stamp.format(Date())} [$tag] $message"
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
        Log.d(tag, message)
    }

    /**
     * 强制记一条（忽略开关）。
     *
     * 只给「必须确认到底有没有被调用」的诊断点用 —— 比如上滑手势的连接器：
     * 如果用户没打开开关，`d()` 什么都不记，就分不清「没收到事件」还是「位移为 0」。
     * 这类点本身调用频率很低（滑动时才触发），常开不会刷屏。
     */
    fun forceD(tag: String, message: String) {
        val line = "${stamp.format(Date())} [$tag] $message"
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
        Log.d(tag, message)
    }

    /** 当前缓冲区内容（按时间正序） */
    fun snapshot(): String = synchronized(buffer) { buffer.joinToString("\n") }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    fun size(): Int = synchronized(buffer) { buffer.size }

    /**
     * 导出到指定目录，返回文件；失败返回 null。
     *
     * 目录一般传 `context.getExternalFilesDir(null)/debug` —— 这个路径 adb 能读，
     * 用户也能在系统文件管理器里找到后分享。
     */
    fun export(dir: File?): File? {
        if (dir == null) return null
        return runCatching {
            dir.mkdirs()
            val file = File(dir, "diag-${fileStamp.format(Date())}.txt")
            val body = snapshot().ifEmpty {
                "（缓冲区是空的 —— 先在设置里打开「详细日志」，复现一次问题，再导出）"
            }
            file.writeText(body + "\n")
            file
        }.getOrNull()
    }
}
