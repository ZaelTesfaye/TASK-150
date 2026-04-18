package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.LearningPlanRepository
import com.nutriops.app.domain.model.LearningPlanStatus
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.learningplan.ManageLearningPlanUseCase
import com.nutriops.app.security.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
class UserLearningPlanScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var authManager: AuthManager
    private lateinit var learningPlanRepo: LearningPlanRepository
    private lateinit var useCase: ManageLearningPlanUseCase
    private val activeVms = mutableListOf<ViewModel>()

    private fun <T : ViewModel> T.tracked(): T = also { activeVms.add(it) }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
        activeVms.forEach { it.viewModelScope.cancel() }
        activeVms.clear()
        Dispatchers.resetMain()
        Thread.sleep(500)
        driver.close()
    }

    @Test
    fun `learning plan list renders the real plan from the DB`() {
        composeTestRule.setContent {
            UserLearningPlanScreen(
                onBack = {},
                viewModel = UserLearningPlanViewModel(useCase, authManager).tracked()
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Nutrition 101").assertIsDisplayed()
        // Status chip shows NOT_STARTED
        composeTestRule.onNodeWithText("NOT_STARTED").assertIsDisplayed()
    }

    @Test
    fun `transitionStatus() persists the new status and the chip updates`(): Unit = runBlocking {
        val vm = UserLearningPlanViewModel(useCase, authManager).tracked()
        composeTestRule.setContent { UserLearningPlanScreen(onBack = {}, viewModel = vm) }

        val plan = learningPlanRepo.getLearningPlansByUserId(authManager.currentUserId).first()
        vm.transitionStatus(plan.id, LearningPlanStatus.IN_PROGRESS)

        // viewModelScope.launch is fire-and-forget — poll the DB until the
        // status change has been persisted, then assert.
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            runBlocking {
                learningPlanRepo.getLearningPlanById(plan.id)?.status ==
                    LearningPlanStatus.IN_PROGRESS.name
            }
        }
        val updated = learningPlanRepo.getLearningPlanById(plan.id)!!
        assertThat(updated.status).isEqualTo(LearningPlanStatus.IN_PROGRESS.name)
    }
}
