package com.nutriops.app.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.rules.EvaluateRuleUseCase
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
class AdminRulesScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var auditManager: AuditManager
    private lateinit var ruleRepository: RuleRepository
    private lateinit var evaluateRuleUseCase: EvaluateRuleUseCase
    private lateinit var authManager: AuthManager

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        auditManager = AuditManager(database)
        authManager = AuthManager(database, auditManager)
        authManager.bootstrapAdmin("root", "AdminPass1!")
        ruleRepository = RuleRepository(database, auditManager)
        evaluateRuleUseCase = EvaluateRuleUseCase(ruleRepository)

        runBlocking {
            ruleRepository.createRule(
                name = "Adherence threshold", description = "Alert below 80%",
                ruleType = "ADHERENCE",
                conditionsJson = """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""",
                hysteresisEnter = 80.0, hysteresisExit = 90.0,
                minimumDurationMinutes = 10,
                effectiveWindowStart = null, effectiveWindowEnd = null,
                actorId = authManager.currentUserId, actorRole = Role.ADMINISTRATOR
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
    }

    @Test
    fun `rules list renders the seeded rule from the in-memory DB`() {
        composeTestRule.setContent {
            AdminRulesScreen(
                onBack = {},
                viewModel = AdminRulesViewModel(ruleRepository, evaluateRuleUseCase, authManager)
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Active Rules (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Adherence threshold").assertIsDisplayed()
    }

    @Test
    fun `creating a rule through the view model persists to the DB`(): Unit = runBlocking {
        val vm = AdminRulesViewModel(ruleRepository, evaluateRuleUseCase, authManager)
        composeTestRule.setContent { AdminRulesScreen(onBack = {}, viewModel = vm) }

        vm.createRule(
            name = "Second rule",
            description = "Exception count rule",
            type = "EXCEPTION",
            conditionsJson = """{"type":"metric","metricType":"exception_count","operator":"<","threshold":2.0}"""
        )
        composeTestRule.waitForIdle()

        val rules = ruleRepository.getAllActiveRules()
        assertThat(rules.map { it.name }).containsAtLeast("Adherence threshold", "Second rule")
    }
}
