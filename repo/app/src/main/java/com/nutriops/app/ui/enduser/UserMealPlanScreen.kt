package com.nutriops.app.ui.enduser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.domain.usecase.mealplan.GenerateWeeklyPlanUseCase
import com.nutriops.app.domain.usecase.mealplan.SwapMealUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class MealUiItem(
    val id: String, val name: String, val description: String,
    val calories: Long, val protein: Double, val carbs: Double, val fat: Double,
    val mealTime: String, val dayOfWeek: Long, val reasons: String, val isSwapped: Boolean
)

data class MealPlanUiState(
    val hasPlan: Boolean = false,
    val planId: String? = null,
    val weekStart: String = "",
    val weekEnd: String = "",
    val dailyCalories: Long = 0,
    val meals: List<MealUiItem> = emptyList(),
    val selectedDay: Int = 1,
    val swapOptions: List<SwapMealUseCase.SwapOption> = emptyList(),
    val showingSwapsForMealId: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class UserMealPlanViewModel @Inject constructor(
    private val generatePlanUseCase: GenerateWeeklyPlanUseCase,
    private val swapMealUseCase: SwapMealUseCase,
    private val mealPlanRepo: MealPlanRepository,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealPlanUiState())
    val uiState: StateFlow<MealPlanUiState> = _uiState.asStateFlow()

    init { loadActivePlan() }

    fun loadActivePlan() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val plan = mealPlanRepo.getActivePlanForUser(session.userId)
            if (plan != null) {
                val meals = mealPlanRepo.getMealsByPlanId(plan.id)
                _uiState.value = MealPlanUiState(
                    hasPlan = true, planId = plan.id,
                    weekStart = plan.weekStartDate, weekEnd = plan.weekEndDate,
                    dailyCalories = plan.dailyCalorieBudget,
                    meals = meals.map { MealUiItem(it.id, it.name, it.description, it.calories, it.proteinGrams, it.carbGrams, it.fatGrams, it.mealTime, it.dayOfWeek, it.reasons, it.isSwapped == 1L) }
                )
            }
        }
    }

    fun generatePlan() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)
            val nextMonday = LocalDate.now().let { d -> d.plusDays((8 - d.dayOfWeek.value).toLong() % 7) }
            generatePlanUseCase.execute(session.userId, nextMonday, session.userId, session.role)
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Plan generated!", isLoading = false); loadActivePlan() }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Error: ${it.message}", isLoading = false) }
        }
    }

    fun selectDay(day: Int) { _uiState.value = _uiState.value.copy(selectedDay = day) }

    fun showSwapOptions(mealId: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            swapMealUseCase.getAvailableSwaps(mealId, session.userId, session.role)
                .onSuccess { _uiState.value = _uiState.value.copy(swapOptions = it, showingSwapsForMealId = mealId) }
        }
    }

    fun executeSwap(originalMealId: String, swapId: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            swapMealUseCase.executeSwap(originalMealId, swapId, session.userId, session.role)
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Meal swapped!", showingSwapsForMealId = null); loadActivePlan() }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Error: ${it.message}") }
        }
    }

    fun dismissSwaps() { _uiState.value = _uiState.value.copy(showingSwapsForMealId = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserMealPlanScreen(
    onBack: () -> Unit,
    viewModel: UserMealPlanViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val todayMeals = state.meals.filter { it.dayOfWeek == state.selectedDay.toLong() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weekly Meal Plan") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            if (!state.hasPlan) {
                FloatingActionButton(onClick = { viewModel.generatePlan() }) { Icon(Icons.Default.Add, "Generate Plan") }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.message?.let { Snackbar(modifier = Modifier.padding(8.dp)) { Text(it) } }

            if (!state.hasPlan) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("No active meal plan", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Create your profile first, then generate a weekly plan", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.generatePlan() }, enabled = !state.isLoading) {
                            Text(if (state.isLoading) "Generating..." else "Generate Weekly Plan")
                        }
                    }
                }
            } else {
                // Day selector tabs
                ScrollableTabRow(selectedTabIndex = state.selectedDay - 1) {
                    dayNames.forEachIndexed { index, name ->
                        Tab(selected = state.selectedDay == index + 1, onClick = { viewModel.selectDay(index + 1) }, text = { Text(name) })
                    }
                }

                // Daily summary
                val dailyCal = todayMeals.sumOf { it.calories }
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Daily Total: $dailyCal / ${state.dailyCalories} kcal", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(
                            progress = (dailyCal.toFloat() / state.dailyCalories.coerceAtLeast(1)).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }

                // Meals list
                LazyColumn(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(todayMeals) { meal ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(meal.mealTime.replace("_", " "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Text(meal.name, style = MaterialTheme.typography.titleSmall)
                                        Text(meal.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.showSwapOptions(meal.id) }) {
                                        Icon(Icons.Default.SwapHoriz, "Swap", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("${meal.calories} kcal | P: ${meal.protein.toInt()}g C: ${meal.carbs.toInt()}g F: ${meal.fat.toInt()}g", style = MaterialTheme.typography.labelSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                // Explainable reasons (at least 2)
                                Text("Why this meal:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(meal.reasons.removeSurrounding("[", "]").replace("\"", ""), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                if (meal.isSwapped) {
                                    Text("(Swapped)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Swap options dialog
    if (state.showingSwapsForMealId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSwaps() },
            title = { Text("One-Click Swap Options") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.swapOptions) { swap ->
                        Card(onClick = { viewModel.executeSwap(state.showingSwapsForMealId!!, swap.swapId) }) {
                            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                Text(swap.name, style = MaterialTheme.typography.titleSmall)
                                Text("${swap.calories} kcal | Protein: ${swap.proteinGrams.toInt()}g", style = MaterialTheme.typography.bodySmall)
                                Text("Cal diff: ${"%.1f".format(swap.caloriesDiffPercent)}% | Protein diff: ${"%.1f".format(swap.proteinDiffGrams)}g", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (state.swapOptions.isEmpty()) {
                        item { Text("No swap options available within tolerance", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { viewModel.dismissSwaps() }) { Text("Close") } }
        )
    }
}
