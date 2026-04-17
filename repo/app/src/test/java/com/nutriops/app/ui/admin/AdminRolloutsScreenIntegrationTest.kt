package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.ConfigRepository
import com.nutriops.app.data.repository.RolloutRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.model.RolloutStatus
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import com.nutriops.app.security.AuthManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
class AdminRolloutsScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var authManager: AuthManager
    private lateinit var configUseCase: ManageConfigUseCase
    private lateinit var rolloutRepository: RolloutRepository

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        authManager = AuthManager(database, auditManager)
        authManager.bootstrapAdmin("root", "AdminPass1!")
        val configRepo = ConfigRepository(database, auditManager)
        rolloutRepository = RolloutRepository(database, auditManager)
        configUseCase = ManageConfigUseCase(configRepo, rolloutRepository)

        // Seed end-user rows so canary assignment has a candidate pool
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        for (id in listOf("u-a", "u-b", "u-c")) {
            database.usersQueries.insertUser(
                id = id, username = id, passwordHash = "x", role = "END_USER",
                isActive = 1, isLocked = 0, failedLoginAttempts = 0, lockoutUntil = null,
                createdAt = now, updatedAt = now
            )
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `no-rollout default state is surfaced before any rollout is created`() {
        composeTestRule.setContent {
            AdminRolloutsScreen(onBack = {}, viewModel = AdminRolloutsViewModel(configUseCase, authManager))
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No active rollout").assertIsDisplayed()
    }

    @Test
    fun `starting a canary rollout writes a CANARY row and updates the screen status`(): Unit = runBlocking {
        val vm = AdminRolloutsViewModel(configUseCase, authManager)
        composeTestRule.setContent { AdminRolloutsScreen(onBack = {}, viewModel = vm) }

        vm.startRollout("config-version-1")
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            runCatching { composeTestRule.onNodeWithText("CANARY").assertIsDisplayed() }.isSuccess
        }
        val active = rolloutRepository.getActiveRollout()
        assertThat(active).isNotNull()
        assertThat(active!!.status).isEqualTo(RolloutStatus.CANARY.name)
    }
}
