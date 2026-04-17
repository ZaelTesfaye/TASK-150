package com.nutriops.app.integration_tests.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MessageRepository
import com.nutriops.app.domain.model.MessageType
import com.nutriops.app.domain.model.TriggerEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Repository integration tests for [MessageRepository] backed by a real
 * in-memory SQLDelight database. Covers message persistence, per-user
 * isolation, unread/read state transitions, todo lifecycle and ordering.
 *
 * Note: the repository has no message-delete API (per product rules messages
 * are retained); the "remove from results" contract is expressed instead
 * through markAsRead + getUnread* queries, which is what end users observe.
 */
class MessageRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        repository = MessageRepository(database)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── sendMessage ──

    @Test
    fun `sendMessage persists a message with recipient, title, body and trigger`() = runBlocking {
        val result = repository.sendMessage(
            userId = "user1", templateId = null, title = "Hello", body = "World",
            messageType = MessageType.NOTIFICATION.name,
            triggerEvent = TriggerEvent.TICKET_CREATED.key
        )

        assertThat(result.isSuccess).isTrue()
        val id = result.getOrNull()!!

        val stored = repository.getMessageById(id)
        assertThat(stored).isNotNull()
        assertThat(stored!!.userId).isEqualTo("user1")
        assertThat(stored.title).isEqualTo("Hello")
        assertThat(stored.body).isEqualTo("World")
        assertThat(stored.messageType).isEqualTo(MessageType.NOTIFICATION.name)
        assertThat(stored.triggerEvent).isEqualTo(TriggerEvent.TICKET_CREATED.key)
        assertThat(stored.isRead).isEqualTo(0L)
        assertThat(stored.createdAt).isNotEmpty()
    }

    // ── Per-user isolation ──

    @Test
    fun `getMessagesByUserId returns only the target users messages`() = runBlocking {
        sendTo("userA", "A1")
        sendTo("userA", "A2")
        sendTo("userB", "B1")

        val forA = repository.getMessagesByUserId("userA")
        val forB = repository.getMessagesByUserId("userB")

        assertThat(forA).hasSize(2)
        assertThat(forB).hasSize(1)
        assertThat(forA.all { it.userId == "userA" }).isTrue()
        assertThat(forB.first().title).isEqualTo("B1")
    }

    @Test
    fun `getMessagesByUserId returns empty list for a user with no messages`() = runBlocking {
        sendTo("someone", "X")
        val messages = repository.getMessagesByUserId("ghost")
        assertThat(messages).isEmpty()
    }

    // ── Ordering (reverse-chronological) ──

    @Test
    fun `getMessagesByUserId returns messages newest-first`() = runBlocking {
        val id1 = sendTo("user1", "first")
        Thread.sleep(10) // ensure a strictly later createdAt timestamp
        val id2 = sendTo("user1", "second")
        Thread.sleep(10)
        val id3 = sendTo("user1", "third")

        val messages = repository.getMessagesByUserId("user1")

        assertThat(messages.map { it.id }).containsExactly(id3, id2, id1).inOrder()
    }

    // ── markAsRead / markAllAsRead ──

    @Test
    fun `markAsRead flips isRead for only the target message`() = runBlocking {
        val m1 = sendTo("user1", "m1")
        val m2 = sendTo("user1", "m2")

        repository.markAsRead(m1)

        assertThat(repository.getMessageById(m1)!!.isRead).isEqualTo(1L)
        assertThat(repository.getMessageById(m2)!!.isRead).isEqualTo(0L)
    }

    @Test
    fun `markAllAsRead clears unread for target user only`() = runBlocking {
        sendTo("userA", "A1")
        sendTo("userA", "A2")
        sendTo("userB", "B1")

        repository.markAllAsRead("userA")

        assertThat(repository.getUnreadCount("userA")).isEqualTo(0L)
        assertThat(repository.getUnreadCount("userB")).isEqualTo(1L)
    }

    @Test
    fun `getUnreadMessages returns only unread entries`() = runBlocking {
        val m1 = sendTo("user1", "m1")
        sendTo("user1", "m2")
        repository.markAsRead(m1)

        val unread = repository.getUnreadMessages("user1")
        assertThat(unread).hasSize(1)
        assertThat(unread.first().title).isEqualTo("m2")
    }

    @Test
    fun `getUnreadCount reports accurate count`() = runBlocking {
        assertThat(repository.getUnreadCount("user1")).isEqualTo(0L)
        sendTo("user1", "m1")
        sendTo("user1", "m2")
        sendTo("user1", "m3")
        assertThat(repository.getUnreadCount("user1")).isEqualTo(3L)

        val first = repository.getMessagesByUserId("user1").first()
        repository.markAsRead(first.id)
        assertThat(repository.getUnreadCount("user1")).isEqualTo(2L)
    }

    // ── Todos ──

    @Test
    fun `createTodo and completeTodo persist state`() = runBlocking {
        val result = repository.createTodo(
            userId = "user1", title = "t", description = "d",
            dueDate = null, relatedEntityType = null, relatedEntityId = null
        )
        assertThat(result.isSuccess).isTrue()
        val todoId = result.getOrNull()!!

        val stored = repository.getTodoById(todoId)
        assertThat(stored).isNotNull()
        assertThat(stored!!.isCompleted).isEqualTo(0L)

        repository.completeTodo(todoId)

        assertThat(repository.getTodoById(todoId)!!.isCompleted).isEqualTo(1L)
    }

    @Test
    fun `getIncompleteTodos hides completed todos`() = runBlocking {
        val t1 = createTodo("user1", "t1")
        createTodo("user1", "t2")
        repository.completeTodo(t1)

        val incomplete = repository.getIncompleteTodos("user1")
        assertThat(incomplete).hasSize(1)
        assertThat(incomplete.first().title).isEqualTo("t2")
    }

    // ── Templates ──

    @Test
    fun `createTemplate and getTemplateByTrigger match on active triggers`() = runBlocking {
        repository.createTemplate(
            name = "ticket-updated",
            titleTemplate = "Ticket {{id}} updated",
            bodyTemplate = "New status: {{status}}",
            variablesJson = "{}",
            triggerEvent = TriggerEvent.TICKET_UPDATED.key
        )

        val hit = repository.getTemplateByTrigger(TriggerEvent.TICKET_UPDATED.key)
        val miss = repository.getTemplateByTrigger("nonexistent")

        assertThat(hit).hasSize(1)
        assertThat(hit.first().name).isEqualTo("ticket-updated")
        assertThat(miss).isEmpty()
    }

    // ── helpers ──

    private suspend fun sendTo(userId: String, title: String): String =
        repository.sendMessage(
            userId = userId, templateId = null, title = title, body = "b",
            messageType = MessageType.NOTIFICATION.name,
            triggerEvent = TriggerEvent.TICKET_UPDATED.key
        ).getOrNull()!!

    private suspend fun createTodo(userId: String, title: String): String =
        repository.createTodo(
            userId = userId, title = title, description = "d",
            dueDate = null, relatedEntityType = null, relatedEntityId = null
        ).getOrNull()!!
}
