package com.nutriops.app.domain.usecase.messaging

import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.domain.model.MessageType
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.security.RbacManager
import javax.inject.Inject

/**
 * In-app messaging and reminder management:
 * - Trigger-based messages (plan started, ticket updated, rule rollout, etc.)
 * - Template variables substitution
 * - Quiet hours enforcement (9:00 PM - 7:00 AM)
 * - Daily reminder cap (max 3 per day)
 * - In-app only (email/SMS/WeCom disabled in offline mode)
 */
class ManageMessagingUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    /**
     * Send a triggered message by resolving the template and substituting variables.
     */
    suspend fun sendTriggeredMessage(
        userId: String,
        triggerEvent: TriggerEvent,
        variables: Map<String, String>
    ): Result<String> {
        val templates = messageRepository.getTemplateByTrigger(triggerEvent.key)
        if (templates.isEmpty()) {
            // No template configured; send a default message
            val title = "Notification: ${triggerEvent.key.replace("_", " ").replaceFirstChar { it.uppercase() }}"
            val body = variables.entries.joinToString(". ") { "${it.key}: ${it.value}" }
            return messageRepository.sendMessage(
                userId, null, title, body, MessageType.NOTIFICATION.name, triggerEvent.key
            )
        }

        val template = templates.first()
        val resolvedTitle = resolveTemplate(template.titleTemplate, variables)
        val resolvedBody = resolveTemplate(template.bodyTemplate, variables)

        return messageRepository.sendMessage(
            userId, template.id, resolvedTitle, resolvedBody,
            MessageType.NOTIFICATION.name, triggerEvent.key
        )
    }

    suspend fun sendDirectMessage(
        userId: String,
        title: String,
        body: String,
        messageType: MessageType,
        actorRole: Role
    ): Result<String> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.SEND_MESSAGES)
            .getOrElse { return Result.failure(it) }

        return messageRepository.sendMessage(
            userId, null, title, body, messageType.name, ""
        )
    }

    suspend fun scheduleReminder(
        userId: String,
        title: String,
        scheduledAt: String,
        messageId: String?
    ): Result<String> {
        return messageRepository.scheduleReminder(userId, messageId, title, scheduledAt)
    }

    suspend fun deliverPendingReminders(beforeTime: String): List<Pair<String, String>> {
        val pending = messageRepository.getPendingReminders(beforeTime)
        val results = mutableListOf<Pair<String, String>>()

        for (reminder in pending) {
            val result = messageRepository.deliverReminder(reminder.id)
            result.onSuccess { status ->
                results.add(reminder.id to status.name)
            }
        }

        return results
    }

    suspend fun getMessages(userId: String, actorId: String, actorRole: Role): List<com.nutriops.app.data.local.Messages> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_OWN_MESSAGES)
            .getOrElse { return emptyList() }
        RbacManager.checkObjectOwnership(actorId, userId, actorRole)
            .getOrElse { return emptyList() }
        return messageRepository.getMessagesByUserId(userId)
    }

    suspend fun getUnreadMessages(userId: String, actorId: String, actorRole: Role): List<com.nutriops.app.data.local.Messages> {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_OWN_MESSAGES)
            .getOrElse { return emptyList() }
        RbacManager.checkObjectOwnership(actorId, userId, actorRole)
            .getOrElse { return emptyList() }
        return messageRepository.getUnreadMessages(userId)
    }

    suspend fun getUnreadCount(userId: String, actorId: String, actorRole: Role): Long {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_OWN_MESSAGES)
            .getOrElse { return 0L }
        RbacManager.checkObjectOwnership(actorId, userId, actorRole)
            .getOrElse { return 0L }
        return messageRepository.getUnreadCount(userId)
    }

    suspend fun markAsRead(messageId: String, actorId: String, actorRole: Role) {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_OWN_MESSAGES)
            .getOrElse { return }
        val message = messageRepository.getMessageById(messageId) ?: return
        RbacManager.checkObjectOwnership(actorId, message.userId, actorRole)
            .getOrElse { return }
        messageRepository.markAsRead(messageId)
    }

    suspend fun markAllAsRead(userId: String, actorId: String, actorRole: Role) {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.VIEW_OWN_MESSAGES)
            .getOrElse { return }
        RbacManager.checkObjectOwnership(actorId, userId, actorRole)
            .getOrElse { return }
        messageRepository.markAllAsRead(userId)
    }

    // ── Todos ──
    suspend fun createTodo(
        userId: String, title: String, description: String,
        dueDate: String?, relatedEntityType: String?, relatedEntityId: String?
    ) = messageRepository.createTodo(userId, title, description, dueDate, relatedEntityType, relatedEntityId)

    suspend fun completeTodo(todoId: String) = messageRepository.completeTodo(todoId)
    suspend fun getTodos(userId: String) = messageRepository.getTodosByUserId(userId)
    suspend fun getIncompleteTodos(userId: String) = messageRepository.getIncompleteTodos(userId)

    // ── Templates ──
    suspend fun createTemplate(
        name: String, titleTemplate: String, bodyTemplate: String,
        variablesJson: String, triggerEvent: String
    ) = messageRepository.createTemplate(name, titleTemplate, bodyTemplate, variablesJson, triggerEvent)

    suspend fun getAllTemplates() = messageRepository.getAllTemplates()

    /**
     * Resolve template variables: {{variableName}} -> value
     */
    private fun resolveTemplate(template: String, variables: Map<String, String>): String {
        var resolved = template
        for ((key, value) in variables) {
            resolved = resolved.replace("{{$key}}", value)
        }
        return resolved
    }
}
