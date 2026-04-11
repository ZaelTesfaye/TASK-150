package com.nutriops.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.logging.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * WorkManager worker for delivering pending reminders.
 * Scheduled to run when device is idle to reduce battery impact.
 * Respects quiet hours (9 PM - 7 AM) and daily cap (3/day).
 * Fully offline — no network dependency.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messagingUseCase: ManageMessagingUseCase
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val results = messagingUseCase.deliverPendingReminders(now)

            AppLogger.info("ReminderWorker", "Processed ${results.size} reminders")
            for ((id, status) in results) {
                AppLogger.debug("ReminderWorker", "Reminder $id: $status")
            }

            Result.success()
        } catch (e: Exception) {
            AppLogger.error("ReminderWorker", "Failed to process reminders", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "reminder_delivery"
    }
}
