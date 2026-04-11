package com.nutriops.app.ui.agent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.audit.AuditManager
import com.nutriops.app.domain.model.*
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModels ──

data class TicketUiItem(
    val id: String, val subject: String, val type: String, val status: String,
    val priority: String, val userId: String, val agentId: String?,
    val slaFirstResponseDue: String, val compensationStatus: String,
    val createdAt: String
)

data class EvidenceUiItem(
    val id: String, val evidenceType: String, val contentUri: String?,
    val textContent: String?, val createdAt: String
)

@HiltViewModel
class AgentTicketsViewModel @Inject constructor(
    private val ticketUseCase: ManageTicketUseCase,
    private val authManager: AuthManager,
    private val auditManager: AuditManager
) : ViewModel() {

    private val _tickets = MutableStateFlow<List<TicketUiItem>>(emptyList())
    val tickets: StateFlow<List<TicketUiItem>> = _tickets.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _selectedTicket = MutableStateFlow<TicketUiItem?>(null)
    val selectedTicket: StateFlow<TicketUiItem?> = _selectedTicket.asStateFlow()
    private val _piiRevealed = MutableStateFlow(false)
    val piiRevealed: StateFlow<Boolean> = _piiRevealed.asStateFlow()
    private val _evidenceItems = MutableStateFlow<List<EvidenceUiItem>>(emptyList())
    val evidenceItems: StateFlow<List<EvidenceUiItem>> = _evidenceItems.asStateFlow()

    init { loadTickets() }

    fun loadTickets() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val open = ticketUseCase.getOpenTickets(session.role)
            _tickets.value = open.map {
                TicketUiItem(it.id, it.subject, it.ticketType, it.status, it.priority, it.userId, it.agentId, it.slaFirstResponseDue, it.compensationStatus ?: "NONE", it.createdAt)
            }
        }
    }

    fun loadTicketDetail(ticketId: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val ticket = ticketUseCase.getTicketDetails(ticketId, session.userId, session.role)
            if (ticket != null) {
                _selectedTicket.value = TicketUiItem(ticket.id, ticket.subject, ticket.ticketType, ticket.status, ticket.priority, ticket.userId, ticket.agentId, ticket.slaFirstResponseDue, ticket.compensationStatus ?: "NONE", ticket.createdAt)
            }
            loadEvidence(ticketId)
        }
    }

    private fun loadEvidence(ticketId: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val items = ticketUseCase.getEvidence(ticketId, session.userId, session.role)
            _evidenceItems.value = items.map {
                EvidenceUiItem(it.id, it.evidenceType, it.contentUri, it.textContent, it.createdAt)
            }
        }
    }

    fun assignToSelf(ticketId: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            ticketUseCase.assignToAgent(ticketId, session.userId, session.userId, session.role)
                .onSuccess { _message.value = "Ticket assigned"; loadTickets(); loadTicketDetail(ticketId) }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun transitionStatus(ticketId: String, newStatus: TicketStatus) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            ticketUseCase.transitionStatus(ticketId, newStatus, session.userId, session.role)
                .onSuccess { _message.value = "Status updated to ${newStatus.name}"; loadTicketDetail(ticketId) }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun suggestCompensation(ticketId: String, amount: Double) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            ticketUseCase.suggestCompensation(ticketId, amount, session.userId, session.role)
                .onSuccess { _message.value = "Compensation suggested: \$$amount"; loadTicketDetail(ticketId) }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun approveCompensation(ticketId: String, amount: Double) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            ticketUseCase.approveCompensation(ticketId, amount, session.userId, session.role)
                .onSuccess { _message.value = "Compensation approved"; loadTicketDetail(ticketId) }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun addTextEvidence(ticketId: String, text: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            ticketUseCase.addEvidence(
                ticketId = ticketId,
                evidenceType = EvidenceType.TEXT,
                contentUri = null,
                textContent = text,
                uploadedBy = session.userId,
                fileSizeBytes = null,
                actorRole = session.role
            )
                .onSuccess { _message.value = "Evidence added"; loadTicketDetail(ticketId) }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun addImageEvidence(ticketId: String, contentUri: String, fileSizeBytes: Long?) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            ticketUseCase.addEvidence(
                ticketId = ticketId,
                evidenceType = EvidenceType.IMAGE,
                contentUri = contentUri,
                textContent = null,
                uploadedBy = session.userId,
                fileSizeBytes = fileSizeBytes,
                actorRole = session.role
            )
                .onSuccess { _message.value = "Image evidence added"; loadTicketDetail(ticketId) }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun togglePiiReveal() {
        val session = authManager.currentSession.value ?: return
        _piiRevealed.value = !_piiRevealed.value
        // Log PII reveal to audit trail
        val ticket = _selectedTicket.value ?: return
        auditManager.log(
            entityType = "Ticket", entityId = ticket.id,
            action = AuditAction.REVEAL_PII, actorId = session.userId, actorRole = session.role,
            details = """{"revealed":${_piiRevealed.value}}"""
        )
    }
}

// ── Screens ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDashboardScreen(
    onNavigateToTickets: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Dashboard") },
                actions = { IconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Exception & After-Sales Management", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Card(onClick = onNavigateToTickets, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Default.ConfirmationNumber, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Ticket Management", style = MaterialTheme.typography.titleMedium)
                        Text("Handle delay, dispute, and lost-item tickets with SLA tracking", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTicketListScreen(
    onTicketClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: AgentTicketsViewModel = hiltViewModel()
) {
    val tickets by viewModel.tickets.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open Tickets (${tickets.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { viewModel.loadTickets() }) { Text("Refresh") } }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tickets) { ticket ->
                Card(onClick = { onTicketClick(ticket.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ticket.subject, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            StatusChip(ticket.priority, when (ticket.priority) {
                                "CRITICAL" -> MaterialTheme.colorScheme.error
                                "HIGH" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            })
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(ticket.type, MaterialTheme.colorScheme.secondary)
                            StatusChip(ticket.status, MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("SLA Due: ${ticket.slaFirstResponseDue}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTicketDetailScreen(
    ticketId: String,
    onBack: () -> Unit,
    viewModel: AgentTicketsViewModel = hiltViewModel()
) {
    val ticket by viewModel.selectedTicket.collectAsState()
    val message by viewModel.message.collectAsState()
    val piiRevealed by viewModel.piiRevealed.collectAsState()
    val evidenceItems by viewModel.evidenceItems.collectAsState()
    var showCompensationDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var showEvidenceDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileSize = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            } catch (_: Exception) { null }
            viewModel.addImageEvidence(ticketId, uri.toString(), fileSize)
        }
    }

    LaunchedEffect(ticketId) { viewModel.loadTicketDetail(ticketId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ticket Detail") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        if (ticket == null) {
            LoadingIndicator(modifier = Modifier.padding(padding))
        } else {
            val t = ticket!!
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { message?.let { Snackbar { Text(it) } } }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(t.subject, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip(t.type, MaterialTheme.colorScheme.secondary)
                                StatusChip(t.status, MaterialTheme.colorScheme.primary)
                                StatusChip(t.priority, MaterialTheme.colorScheme.tertiary)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // PII masked by default with agent-only reveal toggle
                            Text("User ID: ${if (piiRevealed) t.userId else "${t.userId.take(4)}****"}", style = MaterialTheme.typography.bodySmall)
                            Text("SLA First Response Due: ${t.slaFirstResponseDue}", style = MaterialTheme.typography.bodySmall)
                            Text("Compensation: ${t.compensationStatus}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    SectionHeader("Actions")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { viewModel.assignToSelf(t.id) }, enabled = t.status == "OPEN") { Text("Assign to Me") }
                        Button(onClick = { showStatusDialog = true }) { Text("Change Status") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showCompensationDialog = true }) { Text("Compensation") }
                        OutlinedButton(onClick = { viewModel.togglePiiReveal() }) {
                            Text(if (piiRevealed) "Hide PII" else "Reveal PII")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showEvidenceDialog = true }) { Text("Add Evidence") }
                        OutlinedButton(onClick = { imagePickerLauncher.launch("image/*") }) { Text("Attach Image") }
                    }
                }

                // ── Evidence Items ──
                if (evidenceItems.isNotEmpty()) {
                    item { SectionHeader("Evidence (${evidenceItems.size})") }
                    items(evidenceItems) { evidence ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${evidence.evidenceType} - ${evidence.createdAt}", style = MaterialTheme.typography.labelSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                if (evidence.evidenceType == "IMAGE" && evidence.contentUri != null) {
                                    DownsampledImage(
                                        model = evidence.contentUri,
                                        contentDescription = "Evidence image",
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                    )
                                } else if (evidence.textContent != null) {
                                    Text(evidence.textContent, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCompensationDialog) {
        var amount by remember { mutableStateOf("5.0") }
        AlertDialog(
            onDismissRequest = { showCompensationDialog = false },
            title = { Text("Compensation ($3-$20)") },
            text = {
                Column {
                    Text("Auto-approved if <= \$10. Agent approval needed if > \$10.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = amount.toDoubleOrNull() ?: 5.0
                    viewModel.suggestCompensation(ticketId, amt)
                    showCompensationDialog = false
                }) { Text("Suggest") }
            },
            dismissButton = { TextButton(onClick = { showCompensationDialog = false }) { Text("Cancel") } }
        )
    }

    if (showEvidenceDialog) {
        var evidenceText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEvidenceDialog = false },
            title = { Text("Add Text Evidence") },
            text = {
                Column {
                    Text("Attach text notes as evidence for this ticket.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = evidenceText, onValueChange = { evidenceText = it },
                        label = { Text("Evidence Text") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addTextEvidence(ticketId, evidenceText)
                        showEvidenceDialog = false
                    },
                    enabled = evidenceText.isNotBlank()
                ) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { showEvidenceDialog = false }) { Text("Cancel") } }
        )
    }

    if (showStatusDialog) {
        val currentStatus = try { TicketStatus.valueOf(ticket?.status ?: "OPEN") } catch (_: Exception) { TicketStatus.OPEN }
        val allowedTransitions = currentStatus.allowedTransitions()
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text("Change Status") },
            text = {
                Column {
                    Text("Current: ${currentStatus.name}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    allowedTransitions.forEach { status ->
                        Button(
                            onClick = { viewModel.transitionStatus(ticketId, status); showStatusDialog = false },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) { Text(status.name) }
                    }
                    if (allowedTransitions.isEmpty()) {
                        Text("No transitions available from current status", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showStatusDialog = false }) { Text("Close") } }
        )
    }
}
