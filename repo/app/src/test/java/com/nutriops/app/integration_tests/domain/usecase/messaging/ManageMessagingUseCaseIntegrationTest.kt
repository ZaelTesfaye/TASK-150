package com.nutriops.app.integration_tests.domain.usecase.messaging

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.domain.model.MessageType
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.TriggerEvent
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class ManageMessagingUseCaseIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var repository: MessageRepository
    private lateinit var useCase: ManageMessagingUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        repository = MessageRepository(database)
        useCase = ManageMessagingUseCase(repository)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `sendTriggeredMessage falls back to default body when no template exists`() = runBlocking {
        val result = useCase.sendTriggeredMessage(
            userId = "user1",
            triggerEvent = TriggerEvent.TICKET_CREATED,
            variables = mapOf("id" to "t1", "subject" to "Late order")
        )
        assertThat(result.isSuccess).isTrue()

        val stored = repository.getMessagesByUserId("user1")
        assertThat(stored).hasSize(1)
        assertThat(stored.first().triggerEvent).isEqualTo(TriggerEvent.TICKET_CREATED.key)
        assertThat(stored.first().title).contains("Notification")
        // Default body surfaces the variable contents
        assertThat(stored.first().body).contains("Late order")
    }

    @Test
    fun `sendTriggeredMessage resolves template variables when a matching template exists`() = runBlocking {
        repository.createTemplate(
            name = "ticket-updated",
            titleTemplate = "Ticket {{subject}} updated",
            bodyTemplate = "Your ticket {{id}} is now {{status}}",
            variablesJson = "{}",
            triggerEvent = TriggerEvent.TICKET_UPDATED.key
        )

        useCase.sendTriggeredMessage(
            userId = "user1",
            triggerEvent = TriggerEvent.TICKET_UPDATED,
            variables = mapOf("subject" to "Late", "id" to "t1", "status" to "RESOLVED")
        )

        val stored = repository.getMessagesByUserId("user1").first()
        assertThat(stored.title).isEqualTo("Ticket Late updated")
        assertThat(stored.body).isEqualTo("Your ticket t1 is now RESOLVED")
    }

    @Test
    fun `sendDirectMessage denied for end user - no row is written`() = runBlocking {
        val result = useCase.sendDirectMessage(
            userId = "target", title = "hi", body = "hey",
            messageType = MessageType.NOTIFICATION, actorRole = Role.END_USER
        )
        assertThat(result.isFailure).isTrue()
        assertThat(repository.getMessagesByUserId("target")).isEmpty()
    }

    @Test
    fun `markAsRead is scoped to the actor owning the message`() = runBlocking {
        val msgId = repository.sendMessage(
            "userA", null, "t", "b", MessageType.NOTIFICATION.name,
            TriggerEvent.TICKET_CREATED.key
        ).getOrNull()!!

        // Another user tries to mark it read -- must be a no-op
        useCase.markAsRead(msgId, "userB", Role.END_USER)
        assertThat(repository.getMessageById(msgId)!!.isRead).isEqualTo(0L)

        // Real owner can mark it read
        useCase.markAsRead(msgId, "userA", Role.END_USER)
        assertThat(repository.getMessageById(msgId)!!.isRead).isEqualTo(1L)
    }

    @Test
    fun `createTodo persists a real todo row owned by the actor`() = runBlocking {
        val result = useCase.createTodo(
            userId = "user1", title = "Follow up",
            description = "Check with agent", dueDate = null,
            relatedEntityType = null, relatedEntityId = null,
            actorId = "user1", actorRole = Role.END_USER
        )
        assertThat(result.isSuccess).isTrue()
        val todos = repository.getTodosByUserId("user1")
        assertThat(todos).hasSize(1)
        assertThat(todos.first().title).isEqualTo("Follow up")
    }

    @Test
    fun `completeTodo scoped to owner - non-owner attempt is a no-op`() = runBlocking {
        val todoId = repository.createTodo(
            "userA", "t", "d", null, null, null
        ).getOrNull()!!

        useCase.completeTodo(todoId, "userB", Role.END_USER)
        assertThat(repository.getTodoById(todoId)!!.isCompleted).isEqualTo(0L)

        useCase.completeTodo(todoId, "userA", Role.END_USER)
        assertThat(repository.getTodoById(todoId)!!.isCompleted).isEqualTo(1L)
    }
}
