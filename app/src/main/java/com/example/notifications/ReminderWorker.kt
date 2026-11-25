package com.example.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppPreferencesRepository

class ReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = AppPreferencesRepository(applicationContext)
        val settings = repository.getNotificationSettings()

        if (!settings.enabled) {
            return Result.success()
        }

        NotificationHelper.showReminderNotification(
            context = applicationContext,
            title = settings.title,
            message = settings.message,
        )
        return Result.success()
    }
}
