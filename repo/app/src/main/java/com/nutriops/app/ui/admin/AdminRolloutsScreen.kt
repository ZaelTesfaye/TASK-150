package com.nutriops.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.SectionHeader
import com.nutriops.app.ui.common.StatusChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RolloutUiState(
    val activeRolloutId: String? = null,
    val status: String = "No active rollout",
    val canaryPercentage: Int = 10,
    val totalUsers: Long = 0,
    val canaryCount: Int = 0,
    val message: String? = null
)

@HiltViewModel
class AdminRolloutsViewModel @Inject constructor(
    private val configUseCase: ManageConfigUseCase,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(RolloutUiState())
    val uiState: StateFlow<RolloutUiState> = _uiState.asStateFlow()

    init { loadActiveRollout() }

    fun loadActiveRollout() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val rollout = configUseCase.getActiveRollout(session.role).getOrNull()
            if (rollout != null) {
                _uiState.value = RolloutUiState(
                    activeRolloutId = rollout.id,
                    status = rollout.status,
                    canaryPercentage = rollout.canaryPercentage.toInt(),
                    totalUsers = rollout.totalUsers
                )
            } else {
                _uiState.value = RolloutUiState()
            }
        }
    }

    fun startRollout(configVersionId: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            configUseCase.startCanaryRollout(configVersionId, 10, session.userId, session.role)
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Canary rollout started"); loadActiveRollout() }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Error: ${it.message}") }
        }
    }

    fun promoteToFull() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val rolloutId = _uiState.value.activeRolloutId ?: return@launch
            configUseCase.promoteRollout(rolloutId, session.userId, session.role)
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Promoted to full rollout"); loadActiveRollout() }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Error: ${it.message}") }
        }
    }

    fun rollback() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val rolloutId = _uiState.value.activeRolloutId ?: return@launch
            configUseCase.rollbackRollout(rolloutId, session.userId, session.role)
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Rolled back"); loadActiveRollout() }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Error: ${it.message}") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminRolloutsScreen(
    onBack: () -> Unit,
    viewModel: AdminRolloutsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Canary Rollouts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                state.message?.let { Snackbar { Text(it) } }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader("Current Rollout Status")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Status: ", style = MaterialTheme.typography.bodyMedium)
                            StatusChip(state.status, MaterialTheme.colorScheme.primary)
                        }
                        if (state.activeRolloutId != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Canary: ${state.canaryPercentage}% of ${state.totalUsers} users", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                SectionHeader("Actions")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.startRollout("latest") }, enabled = state.activeRolloutId == null) {
                        Text("Start Canary (10%)")
                    }
                    Button(onClick = { viewModel.promoteToFull() }, enabled = state.status == "CANARY") {
                        Text("Promote to Full")
                    }
                    OutlinedButton(onClick = { viewModel.rollback() }, enabled = state.activeRolloutId != null && state.status != "ROLLED_BACK") {
                        Text("Rollback")
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader("Deterministic Canary Assignment")
                        Text("Users are assigned to canary group by sorting all END_USER IDs alphabetically and selecting the first 10%. This ensures consistent, reproducible assignment across rollouts.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
