package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.LearningPlanRepository
import com.nutriops.app.domain.model.LearningPlanStatus
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.learningplan.ManageLearningPlanUseCase
import com.nutriops.app.security.AuthManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserLearningPlanScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var authManager: AuthManager
    private lateinit var learningPlanRepo: LearningPlanRepository
    private lateinit var useCase: ManageLearningPlanUseCase

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NutriOpsDatabase.Schema.create(driver)
        database = NutriOpsDatabase(driver)
        val audit = AuditManager(database)
        authManager = AuthManager(database, audit)
        authManager.bootstrapAdmin("root", "AdminPass1!"); authManager.logout()
        authManager.register("alice", "AlicePass1!")
        learningPlanRepo = LearningPlanRepository(database, audit)
        useCase = ManageLearningPlanUseCase(learningPlanRepo)

        // Seed a real learning plan owned by Alice
        runBlocking {
            learningPlanRepo.createLearningPlan(
                userId = authManager.currentUserId,
                title = "Nutrition 101",
                description = "Basics",
                startDate = "2026-04-01", endDate = "2026-05-01",
                frequencyPerWeek = 3,
                actorId = authManager.currentUserId, actorRole = Role.END_USER
            )
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `learning plan list renders the real plan from the DB`() {
        composeTestRule.setContent {
            UserLearningPlanScreen(
                onBack = {},
                viewModel = UserLearningPlanViewModel(useCase, authManager)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Nutrition 101").assertIsDisplayed()
        // Status chip shows NOT_STARTED
        composeTestRule.onNodeWithText("NOT_STARTED").assertIsDisplayed()
    }

    @Test
    fun `transitionStatus() persists the new status and the chip updates`() = runBlocking {
        val vm = UserLearningPlanViewModel(useCase, authManager)
        composeTestRule.setContent { UserLearningPlanScreen(onBack = {}, viewModel = vm) }

        val plan = learningPlanRepo.getLearningPlansByUserId(authManager.currentUserId).first()
        vm.transitionStatus(plan.id, LearningPlanStatus.IN_PROGRESS)
        composeTestRule.waitForIdle()

        val updated = learningPlanRepo.getLearningPlanById(plan.id)!!
        assertThat(updated.status).isEqualTo(LearningPlanStatus.IN_PROGRESS.name)
    }
}
