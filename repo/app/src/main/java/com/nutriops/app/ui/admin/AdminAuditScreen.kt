package com.nutriops.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.ui.common.StatusChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuditEventUiItem(
    val id: String, val entityType: String, val entityId: String,
    val action: String, val actorId: String, val actorRole: String,
    val timestamp: String, val details: String
)

@HiltViewModel
class AdminAuditViewModel @Inject constructor(
    private val auditManager: AuditManager
) : ViewModel() {

    private val _events = MutableStateFlow<List<AuditEventUiItem>>(emptyList())
    val events: StateFlow<List<AuditEventUiItem>> = _events.asStateFlow()

    init { loadEvents() }

    fun loadEvents() {
        viewModelScope.launch(Dispatchers.IO) {
            val recent = auditManager.getRecentEvents(100)
            _events.value = recent.map {
                AuditEventUiItem(it.id, it.entityType, it.entityId, it.action, it.actorId, it.actorRole, it.timestamp, it.details)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAuditScreen(
    onBack: () -> Unit,
    viewModel: AdminAuditViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit Trail (Immutable)") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { viewModel.loadEvents() }) { Text("Refresh") } }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("${events.size} events (append-only, no modifications allowed)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(events) { event ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(event.action, MaterialTheme.colorScheme.primary)
                            StatusChip(event.actorRole, MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${event.entityType}:${event.entityId.take(8)}", style = MaterialTheme.typography.bodySmall)
                        Text("Actor: ${event.actorId.take(8)}... | ${event.timestamp}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
