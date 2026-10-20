package com.novelreader.core.update

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.novelreader.MainActivity
import com.novelreader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * 更新包下载的前台 Service（v1.8.3.0 待办 #19）。
 *
 * 职责只有两件事：
 * 1. **保活**：前台 Service + 常驻通知，切后台 / 锁屏时进程不被回收，下载不断；
 * 2. **通知栏进度**：下载中显示百分比（不确定时流动条），完成后变成「点击安装」。
 *
 * 真正的下载在 [UpdateDownloadManager] 的进程级作用域里跑 —— 即使这个 Service 被系统
 * 掐掉，回来重新 start 一下就接着显示进度，不会出现「通知没了下载也断了」。
 */
class UpdateDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "update_download"
        private const val CHANNEL_NAME = "更新下载"
        private const val NOTIFICATION_ID = 4701

        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SIZE = "size"

        /** 启动下载（Android 8+ 必须走 startForegroundService） */
        fun start(context: Context, info: UpdateChecker.UpdateInfo) {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                putExtra(EXTRA_VERSION, info.version)
                putExtra(EXTRA_URL, info.apkUrl)
                putExtra(EXTRA_SIZE, info.apkSize)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 拉起系统安装器（通知里点击 / 前台自动安装共用） */
        fun installIntent(context: Context, apk: File): Intent? = runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()

        /** 应用是否在前台（用于决定下载完是自动弹安装器还是只发通知） */
        fun appInForeground(context: Context): Boolean = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.any {
                it.pid == Process.myPid() && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        }.getOrDefault(false)
    }

    private var watchJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 上次刷新通知的百分比（0..100），相同就不重发，省得滑动通知栏掉帧 */
    private var lastPercent = Int.MIN_VALUE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 一进来就得进前台，否则 5 秒内会被系统判 ANR 崩掉。
        // Android 10+ 显式声明 dataSync 类型（清单里也写了，两边保持一致）
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        val version = intent?.getStringExtra(EXTRA_VERSION)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (version != null && url != null) {
            val info = UpdateChecker.UpdateInfo(
                version = version,
                apkUrl = url,
                apkSize = intent.getLongExtra(EXTRA_SIZE, 0L),
                body = "",
            )
            // 已经有一份在跑就不重复拉（重复点「下载更新」也只算一次）
            UpdateDownloadManager.start(applicationContext, info)
        }
        watch()
        return START_NOT_STICKY
    }

    /** 订阅下载状态 → 刷新通知；下完则收尾 */
    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            UpdateDownloadManager.state.collectLatest { state ->
                if (state.error != null) {
                    notifyFailure(state.error)
                    stopSelfSafely()
                    return@collectLatest
                }
                if (state.finished && state.apkPath != null) {
                    notifyDone(state.apkPath)
                    // 前台：直接拉系统安装器（后台起 Activity 会被系统拦，交给通知点击）
                    if (appInForeground(this@UpdateDownloadService)) {
                        val apk = File(state.apkPath)
                        installIntent(this@UpdateDownloadService, apk)?.let {
                            runCatching { startActivity(it) }
                        }
                        NotificationManagerCompat.from(this@UpdateDownloadService)
                            .cancel(NOTIFICATION_ID)
                        UpdateDownloadManager.reset()
                    }
                    stopSelfSafely()
                    return@collectLatest
                }
                if (state.running) {
                    val percent = if (state.progress >= 0f) (state.progress * 100).toInt() else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notifyProgress(state)
                    }
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = "应用更新安装包的下载进度"
                setShowBadge(false)
                enableVibration(false)
            }
        mgr.createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("磊毛阅读 更新下载")
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun buildProgressNotification(state: UpdateDownloadManager.State?): Notification {
        val builder = baseNotification()
        if (state == null || state.progress < 0f) {
            builder.setContentText("准备下载…")
                .setProgress(0, 0, true)
        } else {
            val percent = (state.progress * 100).toInt()
            builder.setContentText(progressText(state, percent))
                .setProgress(100, percent, false)
        }
        contentIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun progressText(state: UpdateDownloadManager.State, percent: Int): String {
        val text = StringBuilder("正在下载 v${state.version} · $percent%")
        if (state.downloaded > 0 && state.total > 0) {
            text.append("（${mb(state.downloaded)} / ${mb(state.total)} MB）")
        }
        if (state.attempt > 0) text.append(" · 第 ${state.attempt} 次续传")
        return text.toString()
    }

    private fun notifyProgress(state: UpdateDownloadManager.State) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildProgressNotification(state))
        }
    }

    private fun notifyDone(apkPath: String) {
        val apk = File(apkPath)
        val install = installIntent(this, apk)
        val builder = baseNotification()
            .setContentTitle("更新包下载完成")
            .setContentText("点击安装 v${UpdateDownloadManager.state.value.version}")
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
        if (install != null) {
            val pi = PendingIntent.getActivity(
                this, 1, install,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pi)
        } else {
            contentIntent()?.let { builder.setContentIntent(it) }
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun notifyFailure(message: String) {
        val builder = baseNotification()
            .setContentTitle("更新下载失败")
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
        contentIntent()?.let { builder.setContentIntent(it) }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
        }
    }

    /** 退出前台但把通知留在栏里（完成 / 失败提示要能被用户看到并点击） */
    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun mb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f", bytes / 1024f / 1024f)
}
