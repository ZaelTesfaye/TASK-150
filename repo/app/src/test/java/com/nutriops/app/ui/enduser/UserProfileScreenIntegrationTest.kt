package com.nutriops.app.ui.enduser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.data.local.NutriOpsDatabase
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.AgeRange
import com.nutriops.app.domain.model.DietaryPattern
import com.nutriops.app.domain.model.HealthGoal
import com.nutriops.app.domain.model.MealTime
import com.nutriops.app.domain.usecase.profile.ManageProfileUseCase
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
class UserProfileScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NutriOpsDatabase
    private lateinit var authManager: AuthManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var profileUseCase: ManageProfileUseCase

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

        profileRepository = ProfileRepository(database, audit)
        profileUseCase = ManageProfileUseCase(profileRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
    }

    private fun viewModel(): UserProfileViewModel =
        UserProfileViewModel(profileUseCase, profileRepository, authManager)

    @Test
    fun `profile screen renders for a fresh user with no profile row`() {
        composeTestRule.setContent {
            UserProfileScreen(onBack = {}, viewModel = viewModel())
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("My Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create Profile").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `saveProfile persists a real row readable via the repository`(): Unit = runBlocking {
        val vm = viewModel()
        composeTestRule.setContent { UserProfileScreen(onBack = {}, viewModel = vm) }

        vm.saveProfile(
            ageRange = AgeRange.AGE_26_35,
            dietaryPattern = DietaryPattern.VEGAN,
            goal = HealthGoal.MAINTAIN,
            allergies = listOf("peanut"),
            preferredMealTimes = listOf(MealTime.BREAKFAST, MealTime.LUNCH, MealTime.DINNER)
        )
        // saveProfile fires on viewModelScope and suspends into Dispatchers.IO;
        // waitForIdle only drains Compose, so poll the DB until the write lands.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { profileRepository.getProfileByUserId(authManager.currentUserId) != null }
        }

        val stored = profileRepository.getProfileByUserId(authManager.currentUserId)
        assertThat(stored).isNotNull()
        assertThat(stored!!.ageRange).isEqualTo("26-35")
        assertThat(stored.dietaryPattern).isEqualTo("VEGAN")
        assertThat(stored.goal).isEqualTo("MAINTAIN")
    }
}
