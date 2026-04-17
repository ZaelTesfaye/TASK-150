package com.nutriops.app.unit_tests.domain.usecase.messaging

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.MessageTemplates
import com.nutriops.app.data.local.Messages
import com.nutriops.app.data.local.Reminders
import com.nutriops.app.data.local.TodoItems
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.domain.model.MessageType
import com.nutriops.app.domain.model.ReminderStatus
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class ManageMessagingUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: ManageMessagingUseCase

    @Before
    fun setup() {
        messageRepository = mockk(relaxed = true)
        useCase = ManageMessagingUseCase(messageRepository)
    }

    // ── sendTriggeredMessage ──

    @Test
    fun `sendTriggeredMessage resolves template variables`() = runBlocking {
        val template = MessageTemplates(
            id = "tpl1",
            name = "Ticket Updated",
            titleTemplate = "Ticket {{subject}} updated",
            bodyTemplate = "Your ticket {{id}} is now {{status}}",
            variablesJson = "{}",
            triggerEvent = TriggerEvent.TICKET_UPDATED.key,
            isActive = 1L,
            createdAt = "2026-01-01T00:00:00",
            updatedAt = "2026-01-01T00:00:00"
        )
        coEvery {
            messageRepository.getTemplateByTrigger(TriggerEvent.TICKET_UPDATED.key)
        } returns listOf(template)
        coEvery {
            messageRepository.sendMessage(any(), any(), any(), any(), any(), any())
        } returns Result.success("m1")

        val result = useCase.sendTriggeredMessage(
            userId = "user1",
            triggerEvent = TriggerEvent.TICKET_UPDATED,
            variables = mapOf("subject" to "Late", "id" to "t1", "status" to "RESOLVED")
        )

        assertThat(result.isSuccess).isTrue()
        coVerify {
            messageRepository.sendMessage(
                userId = "user1",
                templateId = "tpl1",
                title = "Ticket Late updated",
                body = "Your ticket t1 is now RESOLVED",
                messageType = MessageType.NOTIFICATION.name,
                triggerEvent = TriggerEvent.TICKET_UPDATED.key
            )
        }
    }

    @Test
    fun `sendTriggeredMessage uses default body when no template configured`() = runBlocking {
        coEvery {
            messageRepository.getTemplateByTrigger(TriggerEvent.TICKET_UPDATED.key)
        } returns emptyList()
        coEvery {
            messageRepository.sendMessage(any(), any(), any(), any(), any(), any())
        } returns Result.success("m1")

        useCase.sendTriggeredMessage(
            userId = "user1",
            triggerEvent = TriggerEvent.TICKET_UPDATED,
            variables = mapOf("key" to "val")
        )

        coVerify {
            messageRepository.sendMessage(
                userId = "user1",
                templateId = null,
                title = match { it.startsWith("Notification:") },
                body = match { it.contains("key: val") },
                messageType = MessageType.NOTIFICATION.name,
                triggerEvent = TriggerEvent.TICKET_UPDATED.key
            )
        }
    }

    // ── sendDirectMessage ──

    @Test
    fun `sendDirectMessage denied for end user`() = runBlocking {
        val result = useCase.sendDirectMessage(
            userId = "u1", title = "hi", body = "body",
            messageType = MessageType.NOTIFICATION, actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        coVerify(exactly = 0) { messageRepository.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendDirectMessage succeeds for admin`() = runBlocking {
        coEvery {
            messageRepository.sendMessage(any(), any(), any(), any(), any(), any())
        } returns Result.success("m1")

        val result = useCase.sendDirectMessage(
            userId = "u1", title = "hi", body = "body",
            messageType = MessageType.NOTIFICATION, actorRole = Role.ADMINISTRATOR
        )

        assertThat(result.isSuccess).isTrue()
    }

    // ── Reminders ──

    @Test
    fun `scheduleReminder delegates to repo`() = runBlocking {
        coEvery {
            messageRepository.scheduleReminder("u1", null, "hi", "2026-01-01T09:00:00")
        } returns Result.success("r1")

        val result = useCase.scheduleReminder("u1", "hi", "2026-01-01T09:00:00", null)

        assertThat(result.getOrNull()).isEqualTo("r1")
    }

    @Test
    fun `deliverPendingReminders returns IDs of successfully delivered reminders`() = runBlocking {
        val r1 = mockk<Reminders> { every { id } returns "r1" }
        val r2 = mockk<Reminders> { every { id } returns "r2" }
        coEvery { messageRepository.getPendingReminders(any()) } returns listOf(r1, r2)
        coEvery { messageRepository.deliverReminder("r1") } returns Result.success(ReminderStatus.DELIVERED)
        coEvery { messageRepository.deliverReminder("r2") } returns Result.failure(IllegalStateException("quiet hours"))

        val delivered = useCase.deliverPendingReminders("2026-01-01T12:00:00")

        assertThat(delivered).hasSize(1)
        assertThat(delivered.first().first).isEqualTo("r1")
        assertThat(delivered.first().second).isEqualTo(ReminderStatus.DELIVERED.name)
    }

    // ── getMessages authorization ──

    @Test
    fun `getMessages returns empty for another users id`() = runBlocking {
        val result = useCase.getMessages("userB", "userA", Role.END_USER)
        assertThat(result).isEmpty()
        coVerify(exactly = 0) { messageRepository.getMessagesByUserId(any()) }
    }

    @Test
    fun `getMessages returns repo result for owner`() = runBlocking {
        val msg = mockk<Messages> { every { userId } returns "user1" }
        coEvery { messageRepository.getMessagesByUserId("user1") } returns listOf(msg)

        val result = useCase.getMessages("user1", "user1", Role.END_USER)

        assertThat(result).hasSize(1)
    }

    @Test
    fun `getUnreadCount returns zero when actor does not own target user id`() = runBlocking {
        val count = useCase.getUnreadCount("userB", "userA", Role.END_USER)
        assertThat(count).isEqualTo(0L)
    }

    @Test
    fun `markAsRead does nothing when message belongs to another user`() = runBlocking {
        val msg = mockk<Messages> { every { userId } returns "userB" }
        coEvery { messageRepository.getMessageById("m1") } returns msg

        useCase.markAsRead("m1", "userA", Role.END_USER)

        coVerify(exactly = 0) { messageRepository.markAsRead("m1") }
    }

    @Test
    fun `markAsRead calls repo when actor owns the message`() = runBlocking {
        val msg = mockk<Messages> { every { userId } returns "user1" }
        coEvery { messageRepository.getMessageById("m1") } returns msg

        useCase.markAsRead("m1", "user1", Role.END_USER)

        coVerify { messageRepository.markAsRead("m1") }
    }

    // ── Todos ──

    @Test
    fun `createTodo denied for end user creating on behalf of another user`() = runBlocking {
        val result = useCase.createTodo(
            userId = "userB", title = "t", description = "d",
            dueDate = null, relatedEntityType = null, relatedEntityId = null,
            actorId = "userA", actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) {
            messageRepository.createTodo(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `createTodo delegates to repo when actor owns target user id`() = runBlocking {
        coEvery {
            messageRepository.createTodo("user1", "t", "d", null, null, null)
        } returns Result.success("todo1")

        val result = useCase.createTodo(
            userId = "user1", title = "t", description = "d",
            dueDate = null, relatedEntityType = null, relatedEntityId = null,
            actorId = "user1", actorRole = Role.END_USER
        )

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `completeTodo does nothing when actor does not own the todo`() = runBlocking {
        val todo = mockk<TodoItems> { every { userId } returns "userB" }
        coEvery { messageRepository.getTodoById("todo1") } returns todo

        useCase.completeTodo("todo1", "userA", Role.END_USER)

        coVerify(exactly = 0) { messageRepository.completeTodo(any()) }
    }

    @Test
    fun `completeTodo delegates when actor owns the todo`() = runBlocking {
        val todo = mockk<TodoItems> { every { userId } returns "user1" }
        coEvery { messageRepository.getTodoById("todo1") } returns todo

        useCase.completeTodo("todo1", "user1", Role.END_USER)

        coVerify { messageRepository.completeTodo("todo1") }
    }
}
