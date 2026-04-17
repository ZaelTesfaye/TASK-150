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
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import com.nutriops.app.security.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AdminConfigScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var authManager: AuthManager
    private lateinit var useCase: ManageConfigUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        authManager = AuthManager(database, auditManager)
        authManager.bootstrapAdmin("root", "AdminPass1!")
        val configRepo = ConfigRepository(database, auditManager)
        val rolloutRepo = RolloutRepository(database, auditManager)
        useCase = ManageConfigUseCase(configRepo, rolloutRepo)

        // Seed real config rows so the screen renders non-empty headers
        runBlocking {
            useCase.createConfig("feature.x", "on", authManager.currentUserId, Role.ADMINISTRATOR)
            useCase.createConfig("feature.y", "off", authManager.currentUserId, Role.ADMINISTRATOR)
        }
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `section counts reflect real rows in the in-memory DB`() {
        composeTestRule.setContent {
            AdminConfigScreen(onBack = {}, viewModel = AdminConfigViewModel(useCase, authManager))
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Configurations (2)").assertIsDisplayed()
        // Real config keys are rendered by the list composable
        composeTestRule.onNodeWithText("feature.x").assertIsDisplayed()
        composeTestRule.onNodeWithText("feature.y").assertIsDisplayed()
    }

    @Test
    fun `viewmodel createConfig writes a new row readable via the repository`(): Unit = runBlocking {
        val vm = AdminConfigViewModel(useCase, authManager)
        composeTestRule.setContent { AdminConfigScreen(onBack = {}, viewModel = vm) }

        vm.createConfig("feature.z", "true")
        // The ViewModel launches the write in its own scope, which hops to
        // Dispatchers.IO inside the repository. Poll until the row is
        // visible rather than relying on waitForIdle.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            database.configsQueries.getConfigByKey("feature.z").executeAsOneOrNull() != null
        }

        val stored = database.configsQueries.getConfigByKey("feature.z").executeAsOneOrNull()
        assertThat(stored).isNotNull()
        assertThat(stored!!.configValue).isEqualTo("true")
    }
}
