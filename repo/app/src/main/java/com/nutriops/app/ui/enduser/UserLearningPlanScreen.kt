package com.nutriops.app.ui.enduser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.domain.model.LearningPlanStatus
import com.nutriops.app.domain.usecase.learningplan.ManageLearningPlanUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.ConfirmationDialog
import com.nutriops.app.ui.common.SectionHeader
import com.nutriops.app.ui.common.StatusChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LearningPlanUiItem(
    val id: String, val title: String, val description: String,
    val startDate: String, val endDate: String, val frequency: Long,
    val status: String, val allowedTransitions: Set<LearningPlanStatus>
)

@HiltViewModel
class UserLearningPlanViewModel @Inject constructor(
    private val learningPlanUseCase: ManageLearningPlanUseCase,
    private val authManager: AuthManager
) : ViewModel() {

    private val _plans = MutableStateFlow<List<LearningPlanUiItem>>(emptyList())
    val plans: StateFlow<List<LearningPlanUiItem>> = _plans.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { loadPlans() }

    fun loadPlans() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val all = learningPlanUseCase.getPlans(session.userId, session.userId, session.role)
            _plans.value = all.map {
                val status = try { LearningPlanStatus.valueOf(it.status) } catch (_: Exception) { LearningPlanStatus.NOT_STARTED }
                LearningPlanUiItem(it.id, it.title, it.description, it.startDate, it.endDate, it.frequencyPerWeek, it.status, learningPlanUseCase.getAllowedTransitions(status))
            }
        }
    }

    fun createPlan(title: String, description: String, startDate: String, endDate: String, frequency: Int) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            learningPlanUseCase.createPlan(session.userId, title, description, startDate, endDate, frequency, session.userId, session.role)
                .onSuccess { _message.value = "Plan created!"; loadPlans() }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun transitionStatus(planId: String, newStatus: LearningPlanStatus) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            learningPlanUseCase.transitionStatus(planId, newStatus, session.userId, session.role)
                .onSuccess { _message.value = "Status changed to ${newStatus.name}"; loadPlans() }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun duplicatePlan(planId: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            learningPlanUseCase.duplicateForEditing(planId, session.userId, session.role)
                .onSuccess { _message.value = "Plan duplicated for editing"; loadPlans() }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserLearningPlanScreen(
    onBack: () -> Unit,
    viewModel: UserLearningPlanViewModel = hiltViewModel()
) {
    val plans by viewModel.plans.collectAsState()
    val message by viewModel.message.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var confirmTransition by remember { mutableStateOf<Pair<String, LearningPlanStatus>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learning Plans") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.Add, "Create Plan") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            message?.let { Snackbar(modifier = Modifier.padding(8.dp)) { Text(it) } }
            LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plans) { plan ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(plan.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                StatusChip(plan.status, when (plan.status) {
                                    "COMPLETED" -> MaterialTheme.colorScheme.primary
                                    "IN_PROGRESS" -> MaterialTheme.colorScheme.tertiary
                                    "PAUSED" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
                            }
                            Text(plan.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text("${plan.startDate} to ${plan.endDate} | ${plan.frequency}x/week", style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                plan.allowedTransitions.forEach { transition ->
                                    Button(
                                        onClick = { confirmTransition = plan.id to transition },
                                        modifier = Modifier.height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    ) { Text(transition.name.replace("_", " "), style = MaterialTheme.typography.labelSmall) }
                                }
                                if (plan.status == "COMPLETED") {
                                    OutlinedButton(
                                        onClick = { viewModel.duplicatePlan(plan.id) },
                                        modifier = Modifier.height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Duplicate to Edit", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for status changes
    confirmTransition?.let { (planId, newStatus) ->
        ConfirmationDialog(
            title = "Confirm Status Change",
            message = "Change plan status to ${newStatus.name.replace("_", " ")}?",
            onConfirm = { viewModel.transitionStatus(planId, newStatus); confirmTransition = null },
            onDismiss = { confirmTransition = null }
        )
    }

    if (showCreateDialog) {
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var startDate by remember { mutableStateOf("2026-04-14") }
        var endDate by remember { mutableStateOf("2026-05-14") }
        var freq by remember { mutableStateOf("3") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Learning Plan") },
            text = {
                Column {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = startDate, onValueChange = { startDate = it }, label = { Text("Start") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = endDate, onValueChange = { endDate = it }, label = { Text("End") }, modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = freq, onValueChange = { freq = it }, label = { Text("Days/week (1-7)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.createPlan(title, desc, startDate, endDate, freq.toIntOrNull() ?: 3); showCreateDialog = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }
}
