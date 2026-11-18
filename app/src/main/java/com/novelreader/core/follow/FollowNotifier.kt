package com.novelreader.core.follow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.novelreader.R

/**
 * 追更提醒的通知出口（D30）。
 *
 * 只发**一条汇总通知**：书架二三十本同时更新时逐本通知，用户第一件事就是关权限。
 * 单本更新用 `BigTextStyle` 列出前几本，其余用「等 N 本」概括。
 */
object FollowNotifier {

    private const val CHANNEL_ID = "follow_update"
    private const val CHANNEL_NAME = "追更提醒"
    private const val NOTIFICATION_ID = 4801

    /** 建渠道（幂等；Android 8.0 以下无需渠道） */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "书架里的作品有新章节时提醒" }
        runCatching { mgr.createNotificationChannel(channel) }
    }

    /** 是否有权发通知（Android 13+ 未授权时静默丢弃，调用方据此只更新界面） */
    fun canPost(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)

    /**
     * 发一条汇总通知。
     *
     * @param updates 本次检查到的更新（为空则什么都不做）
     */
    fun notify(context: Context, updates: List<FollowUpdate>) {
        if (updates.isEmpty()) return
        ensureChannel(context)
        if (!canPost(context)) return

        val lines = updates.take(5).map { "${it.itemTitle} 更新 ${it.newCount} 话（${it.latestChapter}）" }
        val summary = lines.joinToString("\n")
        val collapse = if (updates.size > 5) "\n…等 ${updates.size} 本" else ""

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("书架有 ${updates.size} 本更新")
            .setContentText(updates.first().let { "${it.itemTitle} 更新 ${it.newCount} 话" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary + collapse))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        pendingIntent?.let { builder.setContentIntent(it) }

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }
}
