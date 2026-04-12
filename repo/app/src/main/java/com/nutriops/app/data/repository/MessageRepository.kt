package com.nutriops.app.data.repository

import com.nutriops.app.config.AppConfig
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.domain.model.ReminderStatus
import com.nutriops.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val database: NutriOpsDatabase
) {
    private val msgQueries get() = database.messagesQueries

    // ── Templates ──

    suspend fun createTemplate(
        name: String, titleTemplate: String, bodyTemplate: String,
        variablesJson: String, triggerEvent: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            msgQueries.insertMessageTemplate(id, name, titleTemplate, bodyTemplate, variablesJson, triggerEvent, 1, now, now)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTemplateByTrigger(triggerEvent: String) = withContext(Dispatchers.IO) {
        msgQueries.getTemplateByTrigger(triggerEvent).executeAsList()
    }

    suspend fun getAllTemplates() = withContext(Dispatchers.IO) {
        msgQueries.getAllTemplates().executeAsList()
    }

    // ── Messages ──

    suspend fun sendMessage(
        userId: String, templateId: String?, title: String, body: String,
        messageType: String, triggerEvent: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            msgQueries.insertMessage(id, userId, templateId, title, body, messageType, triggerEvent, 0, now)
            AppLogger.info("MessageRepo", "Message sent to user=$userId type=$messageType trigger=$triggerEvent")
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMessageById(messageId: String) = withContext(Dispatchers.IO) {
        msgQueries.getMessageById(messageId).executeAsOneOrNull()
    }

    suspend fun getMessagesByUserId(userId: String) = withContext(Dispatchers.IO) {
        msgQueries.getMessagesByUserId(userId).executeAsList()
    }

    suspend fun getUnreadMessages(userId: String) = withContext(Dispatchers.IO) {
        msgQueries.getUnreadMessagesByUserId(userId).executeAsList()
    }

    suspend fun getUnreadCount(userId: String) = withContext(Dispatchers.IO) {
        msgQueries.getUnreadMessageCount(userId).executeAsOne()
    }

    suspend fun markAsRead(messageId: String) = withContext(Dispatchers.IO) {
        msgQueries.markMessageAsRead(messageId)
    }

    suspend fun markAllAsRead(userId: String) = withContext(Dispatchers.IO) {
        msgQueries.markAllMessagesAsRead(userId)
    }

    // ── Reminders (quiet hours + daily cap) ──

    suspend fun scheduleReminder(
        userId: String, messageId: String?, title: String, scheduledAt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            msgQueries.insertReminder(id, userId, messageId, title, scheduledAt, null, ReminderStatus.PENDING.name, now)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deliverReminder(reminderId: String): Result<ReminderStatus> = withContext(Dispatchers.IO) {
        try {
            val reminder = msgQueries.getReminderById(reminderId).executeAsOneOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Reminder not found"))

            val now = LocalDateTime.now()
            val nowStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            // Check quiet hours (9:00 PM - 7:00 AM)
            if (isQuietHours(now)) {
                msgQueries.updateReminderStatus(ReminderStatus.SKIPPED_QUIET_HOURS.name, null, reminderId)
                AppLogger.info("MessageRepo", "Reminder $reminderId skipped: quiet hours")
                return@withContext Result.success(ReminderStatus.SKIPPED_QUIET_HOURS)
            }

            // Check daily cap (max 3 per day)
            val todayStart = now.toLocalDate().atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val todayEnd = now.toLocalDate().plusDays(1).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val deliveredToday = msgQueries.getDeliveredRemindersToday(reminder.userId, todayStart, todayEnd).executeAsOne()

            if (deliveredToday >= AppConfig.MAX_REMINDERS_PER_DAY) {
                msgQueries.updateReminderStatus(ReminderStatus.SKIPPED_CAP_REACHED.name, null, reminderId)
                AppLogger.info("MessageRepo", "Reminder $reminderId skipped: daily cap reached ($deliveredToday)")
                return@withContext Result.success(ReminderStatus.SKIPPED_CAP_REACHED)
            }

            // Deliver
            msgQueries.updateReminderStatus(ReminderStatus.DELIVERED.name, nowStr, reminderId)
            AppLogger.info("MessageRepo", "Reminder $reminderId delivered to user=${reminder.userId}")
            Result.success(ReminderStatus.DELIVERED)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPendingReminders(beforeTime: String) = withContext(Dispatchers.IO) {
        msgQueries.getPendingReminders(beforeTime).executeAsList()
    }

    suspend fun getRemindersByUserId(userId: String) = withContext(Dispatchers.IO) {
        msgQueries.getRemindersByUserId(userId).executeAsList()
    }

    // ── Todo Items ──

    suspend fun createTodo(
        userId: String, title: String, description: String,
        dueDate: String?, relatedEntityType: String?, relatedEntityId: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            msgQueries.insertTodoItem(id, userId, title, description, 0, dueDate, relatedEntityType, relatedEntityId, now, now)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeTodo(todoId: String) = withContext(Dispatchers.IO) {
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        msgQueries.updateTodoCompletion(1, now, todoId)
    }

    suspend fun getTodosByUserId(userId: String) = withContext(Dispatchers.IO) {
        msgQueries.getTodosByUserId(userId).executeAsList()
    }

    suspend fun getTodoById(todoId: String) = withContext(Dispatchers.IO) {
        msgQueries.getTodoById(todoId).executeAsOneOrNull()
    }

    suspend fun getIncompleteTodos(userId: String) = withContext(Dispatchers.IO) {
        msgQueries.getIncompleteTodosByUserId(userId).executeAsList()
    }

    private fun isQuietHours(dateTime: LocalDateTime): Boolean {
        val time = dateTime.toLocalTime()
        val quietStart = LocalTime.parse(AppConfig.QUIET_HOURS_START)
        val quietEnd = LocalTime.parse(AppConfig.QUIET_HOURS_END)

        return if (quietStart > quietEnd) {
            // Crosses midnight: 21:00 - 07:00
            time >= quietStart || time < quietEnd
        } else {
            time in quietStart..quietEnd
        }
    }
}
