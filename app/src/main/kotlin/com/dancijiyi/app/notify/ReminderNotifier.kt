package com.dancijiyi.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dancijiyi.app.MainActivity
import com.dancijiyi.app.R

/**
 * 每日复习提醒的通知通道与展示。
 *
 * 权限降级（docs/01 §8）：
 * - Android 13+ 需要 `POST_NOTIFICATIONS`；
 * - 未授予时 `notify()` 会被系统**静默丢弃**（不抛异常），所以这里显式检查并返回结果，
 *   由调用方决定是否提示用户「提醒已关闭，可改用桌面小组件」。
 */
object ReminderNotifier {

    const val CHANNEL_ID = "daily_review_reminder"
    private const val NOTIFICATION_ID = 1001

    /** 复习页全限定名（app 模块内可直接引用类，但用常量便于统一维护） */
    private const val REVIEW_ACTIVITY = "com.dancijiyi.app.ui.review.ReviewActivity"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "每日复习提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "每天固定时间提醒今天还有多少单词要复习"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 展示提醒。
     *
     * @return true 表示已发出；false 表示没权限（调用方可据此提示降级方案）
     */
    fun show(context: Context, dueCount: Int): Boolean {
        if (!hasPermission(context)) return false
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("今天还有 $dueCount 个单词要复习")
            .setContentText("点一下，3 分钟就能过完")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(reviewIntent(context))
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    /** 点击通知 → 直接进复习页 */
    private fun reviewIntent(context: Context): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, REVIEW_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // 如果意图拿不到目标类（被裁剪），退回主页，保证通知始终可点
            if (runCatching { Class.forName(REVIEW_ACTIVITY) }.isFailure) {
                setClassName(context.packageName, MainActivity::class.java.name)
            }
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
