package com.example.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.NotificationSettings
import java.util.concurrent.TimeUnit

object NotificationScheduler {

    private const val UNIQUE_REMINDER_WORK_NAME = "antigrafity_periodic_reminder"

    fun applySchedule(context: Context, settings: NotificationSettings) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.enabled) {
            workManager.cancelUniqueWork(UNIQUE_REMINDER_WORK_NAME)
            return
        }

        val intervalMinutes = settings.intervalMinutes
            .coerceAtLeast(NotificationSettings.MIN_INTERVAL_MINUTES)

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_REMINDER_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
