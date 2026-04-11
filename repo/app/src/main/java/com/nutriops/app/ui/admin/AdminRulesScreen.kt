package com.nutriops.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.domain.usecase.rules.EvaluateRuleUseCase
import com.nutriops.app.data.repository.RuleRepository
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.SectionHeader
import com.nutriops.app.ui.common.StatusChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleUiItem(
    val id: String, val name: String, val description: String, val type: String,
    val hysteresisEnter: Double, val hysteresisExit: Double, val minDuration: Long,
    val version: Long, val isActive: Boolean
)

@HiltViewModel
class AdminRulesViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val evaluateRuleUseCase: EvaluateRuleUseCase,
    private val authManager: AuthManager
) : ViewModel() {

    private val _rules = MutableStateFlow<List<RuleUiItem>>(emptyList())
    val rules: StateFlow<List<RuleUiItem>> = _rules.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { loadRules() }

    fun loadRules() {
        viewModelScope.launch {
            val all = ruleRepository.getAllActiveRules()
            _rules.value = all.map { RuleUiItem(it.id, it.name, it.description, it.ruleType, it.hysteresisEnterPercent, it.hysteresisExitPercent, it.minimumDurationMinutes, it.currentVersion, it.isActive == 1L) }
        }
    }

    fun createRule(name: String, description: String, type: String, conditionsJson: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            ruleRepository.createRule(name, description, type, conditionsJson, 80.0, 90.0, 10, null, null, session.userId, session.role)
                .onSuccess { _message.value = "Rule created"; loadRules() }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun evaluateRules() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val metrics = mapOf("adherence_rate" to 85.0, "exception_count" to 2.0, "completion_rate" to 78.0)
            evaluateRuleUseCase.evaluateAllRules(session.userId, metrics, session.userId, session.role)
                .onSuccess { results ->
                    val triggered = results.count { it.triggered }
                    _message.value = "Evaluated ${results.size} rules: $triggered triggered"
                }
                .onFailure { _message.value = "Evaluation error: ${it.message}" }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminRulesScreen(
    onBack: () -> Unit,
    viewModel: AdminRulesViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
    val message by viewModel.message.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Metrics & Rules Engine") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { viewModel.evaluateRules() }) { Text("Evaluate All") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.Add, "Add Rule") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            message?.let {
                Snackbar(modifier = Modifier.padding(16.dp)) { Text(it) }
            }
            LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SectionHeader("Active Rules (${rules.size})") }
                items(rules) { rule ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(rule.name, style = MaterialTheme.typography.titleSmall)
                                StatusChip(rule.type, MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(rule.description, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Hysteresis: enter=${rule.hysteresisEnter}% exit=${rule.hysteresisExit}%", style = MaterialTheme.typography.labelSmall)
                            Text("Min Duration: ${rule.minDuration}min | Version: ${rule.version}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("ADHERENCE") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Rule") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ADHERENCE", "EXCEPTION", "OPERATIONAL_KPI").forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.take(5)) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createRule(name, desc, type, """{"type":"metric","metricType":"adherence_rate","operator":">=","threshold":80.0}""")
                    showCreateDialog = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }
}
