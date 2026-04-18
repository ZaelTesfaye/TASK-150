package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.mealplan.GenerateWeeklyPlanUseCase
import com.nutriops.app.domain.usecase.mealplan.SwapMealUseCase
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
class UserMealPlanScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var authManager: AuthManager
    private lateinit var mealPlanRepository: MealPlanRepository
    private lateinit var generateUseCase: GenerateWeeklyPlanUseCase
    private lateinit var swapUseCase: SwapMealUseCase
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

        mealPlanRepository = MealPlanRepository(database, audit)
        val profileRepo = ProfileRepository(database, audit)
        runBlocking {
            profileRepo.createProfile(
                userId = authManager.currentUserId,
                ageRange = "26-35", dietaryPattern = "STANDARD", allergies = "[]",
                goal = "MAINTAIN", preferredMealTimes = "[]",
                dailyCalorieBudget = 2000L, proteinTargetGrams = 120L,
                carbTargetGrams = 225L, fatTargetGrams = 67L,
                actorId = authManager.currentUserId, actorRole = Role.END_USER
            )
        }
        generateUseCase = GenerateWeeklyPlanUseCase(mealPlanRepository, profileRepo)
        swapUseCase = SwapMealUseCase(mealPlanRepository)
    }

    @After
    fun tearDown() {
        activeVms.forEach { it.viewModelScope.cancel() }
        activeVms.clear()
        Dispatchers.resetMain()
        Thread.sleep(500)
        driver.close()
    }

    private fun viewModel(): UserMealPlanViewModel =
        UserMealPlanViewModel(generateUseCase, swapUseCase, mealPlanRepository, authManager).tracked()

    @Test
    fun `empty state shows Generate Weekly Plan CTA before any plan exists`() {
        composeTestRule.setContent {
            UserMealPlanScreen(onBack = {}, viewModel = viewModel())
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No active meal plan").assertIsDisplayed()
        composeTestRule.onNodeWithText("Generate Weekly Plan").assertIsDisplayed()
    }

    @Test
    fun `generatePlan() writes 21 meal rows and the screen renders breakfast slot`(): Unit = runBlocking {
        val vm = viewModel()
        composeTestRule.setContent { UserMealPlanScreen(onBack = {}, viewModel = vm) }

        vm.generatePlan()
        // Let the generation + reload complete. We poll until all 21 meals
        // (7 days * 3 slots) have been written, otherwise the repository can
        // return a partial list while generation is still running.
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            runBlocking {
                val plan = mealPlanRepository.getActivePlanForUser(authManager.currentUserId)
                plan != null && mealPlanRepository.getMealsByPlanId(plan.id).size == 21
            }
        }

        val plan = mealPlanRepository.getActivePlanForUser(authManager.currentUserId)!!
        val meals = mealPlanRepository.getMealsByPlanId(plan.id)
        // 7 days * 3 slots = 21
        assertThat(meals).hasSize(21)

        // After the DB is populated, the ViewModel still has to re-read the
        // plan via its own viewModelScope.launch, update StateFlow, and
        // recompose before "BREAKFAST" lands in the semantics tree. Poll for
        // the node rather than asserting on the first recomposition.
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("BREAKFAST").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("BREAKFAST").assertIsDisplayed()
    }
}
