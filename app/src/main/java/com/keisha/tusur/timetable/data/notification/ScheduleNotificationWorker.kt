package com.keisha.tusur.timetable.data.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.keisha.tusur.timetable.App
import com.keisha.tusur.timetable.domain.model.AppError
import kotlinx.coroutines.CancellationException

class ScheduleNotificationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            val service = (applicationContext.applicationContext as App).container.scheduleNotifications
            val error = service.refreshInBackground()?.error
            val retry = error is AppError.Network || error is AppError.Server && error.code >= 500
            if (retry && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ScheduleNotifications", "Не удалось проверить изменения расписания", e)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
