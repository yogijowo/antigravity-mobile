package com.example.notifications

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
import com.example.MainActivity
import com.example.R

object NotificationHelper {

    const val CHANNEL_REMINDERS = "antigrafity_reminders"
    const val CHANNEL_WEB_STATUS = "antigrafity_web_status"

    private const val REMINDER_NOTIFICATION_ID = 1001
    private const val WEB_STATUS_NOTIFICATION_BASE_ID = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.notif_channel_reminder_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_reminder_desc)
        }

        val webStatusChannel = NotificationChannel(
            CHANNEL_WEB_STATUS,
            context.getString(R.string.notif_channel_web_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_web_desc)
        }

        manager.createNotificationChannel(reminderChannel)
        manager.createNotificationChannel(webStatusChannel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun showReminderNotification(
        context: Context,
        title: String,
        message: String,
    ): Boolean {
        return postNotification(
            context = context,
            channelId = CHANNEL_REMINDERS,
            notificationId = REMINDER_NOTIFICATION_ID,
            title = title,
            message = message,
            priority = NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    fun showWebBridgeNotification(
        context: Context,
        title: String,
        message: String,
    ): Boolean {
        val dynamicId = WEB_STATUS_NOTIFICATION_BASE_ID + ((System.currentTimeMillis() / 1000L) % 500).toInt()
        return postNotification(
            context = context,
            channelId = CHANNEL_WEB_STATUS,
            notificationId = dynamicId,
            title = title,
            message = message,
            priority = NotificationCompat.PRIORITY_HIGH,
        )
    }

    private fun postNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        message: String,
        priority: Int,
    ): Boolean {
        if (!hasNotificationPermission(context)) return false
        createChannels(context)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_antigrafity)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
