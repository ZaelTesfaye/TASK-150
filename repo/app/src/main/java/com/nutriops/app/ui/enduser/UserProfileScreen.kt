package com.nutriops.app.ui.enduser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.*
import com.nutriops.app.domain.usecase.profile.ManageProfileUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val hasProfile: Boolean = false,
    val ageRange: AgeRange = AgeRange.AGE_26_35,
    val dietaryPattern: DietaryPattern = DietaryPattern.STANDARD,
    val goal: HealthGoal = HealthGoal.MAINTAIN,
    val allergies: String = "",
    val calories: Long = 0,
    val protein: Long = 0,
    val carbs: Long = 0,
    val fat: Long = 0,
    val isLoading: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val profileUseCase: ManageProfileUseCase,
    private val profileRepository: ProfileRepository,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init { loadProfile() }

    fun loadProfile() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val profile = profileRepository.getProfileByUserId(session.userId)
            if (profile != null) {
                _uiState.value = ProfileUiState(
                    hasProfile = true,
                    ageRange = try { AgeRange.fromString(profile.ageRange) } catch (_: Exception) { AgeRange.AGE_26_35 },
                    dietaryPattern = try { DietaryPattern.valueOf(profile.dietaryPattern) } catch (_: Exception) { DietaryPattern.STANDARD },
                    goal = try { HealthGoal.valueOf(profile.goal) } catch (_: Exception) { HealthGoal.MAINTAIN },
                    allergies = profile.allergies,
                    calories = profile.dailyCalorieBudget,
                    protein = profile.proteinTargetGrams,
                    carbs = profile.carbTargetGrams,
                    fat = profile.fatTargetGrams
                )
            }
        }
    }

    fun saveProfile(ageRange: AgeRange, dietaryPattern: DietaryPattern, goal: HealthGoal, allergies: List<String>, preferredMealTimes: List<MealTime>) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)
            profileUseCase.createProfile(
                session.userId, ageRange, dietaryPattern, allergies, goal,
                preferredMealTimes,
                session.userId, session.role
            )
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Profile saved!", isLoading = false); loadProfile() }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Error: ${it.message}", isLoading = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    onBack: () -> Unit,
    viewModel: UserProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var ageRange by remember { mutableStateOf(state.ageRange) }
    var dietary by remember { mutableStateOf(state.dietaryPattern) }
    var goal by remember { mutableStateOf(state.goal) }
    var allergies by remember { mutableStateOf("") }
    var selectedMealTimes by remember { mutableStateOf(setOf(MealTime.BREAKFAST, MealTime.LUNCH, MealTime.DINNER)) }

    LaunchedEffect(state.hasProfile) {
        ageRange = state.ageRange; dietary = state.dietaryPattern; goal = state.goal
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.message?.let { Snackbar { Text(it) } }

            if (state.hasProfile) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader("Current Targets")
                        Text("Daily Calories: ${state.calories} kcal", style = MaterialTheme.typography.bodyLarge)
                        Text("Protein: ${state.protein}g | Carbs: ${state.carbs}g | Fat: ${state.fat}g", style = MaterialTheme.typography.bodyMedium)
                        Text("Goal: ${state.goal.display}", style = MaterialTheme.typography.bodyMedium)
                        Text("Pattern: ${state.dietaryPattern.name}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionHeader("Profile Settings")

            Text("Age Range", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                AgeRange.entries.take(3).forEach { a ->
                    FilterChip(selected = ageRange == a, onClick = { ageRange = a }, label = { Text(a.display) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AgeRange.entries.drop(3).forEach { a ->
                    FilterChip(selected = ageRange == a, onClick = { ageRange = a }, label = { Text(a.display) })
                }
            }

            Text("Dietary Pattern", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                DietaryPattern.entries.take(4).forEach { d ->
                    FilterChip(selected = dietary == d, onClick = { dietary = d }, label = { Text(d.name.take(6)) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DietaryPattern.entries.drop(4).forEach { d ->
                    FilterChip(selected = dietary == d, onClick = { dietary = d }, label = { Text(d.name.take(6)) })
                }
            }

            Text("Health Goal", style = MaterialTheme.typography.labelLarge)
            HealthGoal.entries.forEach { g ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = goal == g, onClick = { goal = g })
                    Text(g.display, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
                }
            }

            Text("Preferred Meal Times", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MealTime.entries.take(3).forEach { mt ->
                    FilterChip(
                        selected = mt in selectedMealTimes,
                        onClick = {
                            selectedMealTimes = if (mt in selectedMealTimes) selectedMealTimes - mt else selectedMealTimes + mt
                        },
                        label = { Text(mt.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MealTime.entries.drop(3).forEach { mt ->
                    FilterChip(
                        selected = mt in selectedMealTimes,
                        onClick = {
                            selectedMealTimes = if (mt in selectedMealTimes) selectedMealTimes - mt else selectedMealTimes + mt
                        },
                        label = { Text(mt.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            OutlinedTextField(
                value = allergies, onValueChange = { allergies = it },
                label = { Text("Allergies (comma-separated)") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { viewModel.saveProfile(ageRange, dietary, goal, allergies.split(",").map { it.trim() }.filter { it.isNotBlank() }, selectedMealTimes.toList()) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !state.isLoading
            ) {
                Text(if (state.hasProfile) "Update Profile" else "Create Profile")
            }
        }
    }
}
