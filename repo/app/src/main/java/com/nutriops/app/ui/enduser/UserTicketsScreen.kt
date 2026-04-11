package com.nutriops.app.ui.enduser

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.domain.model.EvidenceType
import com.nutriops.app.domain.model.TicketPriority
import com.nutriops.app.domain.model.TicketType
import com.nutriops.app.domain.usecase.ticket.ManageTicketUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.DownsampledImage
import com.nutriops.app.ui.common.StatusChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserTicketUiItem(
    val id: String, val subject: String, val type: String, val status: String,
    val priority: String, val compensationStatus: String, val createdAt: String
)

@HiltViewModel
class UserTicketsViewModel @Inject constructor(
    private val ticketUseCase: ManageTicketUseCase,
    private val authManager: AuthManager
) : ViewModel() {

    private val _tickets = MutableStateFlow<List<UserTicketUiItem>>(emptyList())
    val tickets: StateFlow<List<UserTicketUiItem>> = _tickets.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { loadTickets() }

    fun loadTickets() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val all = ticketUseCase.getTicketsByUser(session.userId, session.userId, session.role)
            _tickets.value = all.map {
                UserTicketUiItem(it.id, it.subject, it.ticketType, it.status, it.priority, it.compensationStatus ?: "NONE", it.createdAt)
            }
        }
    }

    fun createTicket(type: TicketType, priority: TicketPriority, subject: String, description: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            ticketUseCase.createTicket(session.userId, type, priority, subject, description, session.userId, session.role)
                .onSuccess { _message.value = "Ticket created"; loadTickets() }
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
                .onSuccess { _message.value = "Evidence attached" }
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
                .onSuccess { _message.value = "Image evidence attached" }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserTicketsScreen(
    onBack: () -> Unit,
    viewModel: UserTicketsViewModel = hiltViewModel()
) {
    val tickets by viewModel.tickets.collectAsState()
    val message by viewModel.message.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var evidenceTicketId by remember { mutableStateOf<String?>(null) }
    var imagePickerTicketId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val tid = imagePickerTicketId
        if (uri != null && tid != null) {
            val fileSize = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            } catch (_: Exception) { null }
            viewModel.addImageEvidence(tid, uri.toString(), fileSize)
        }
        imagePickerTicketId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Tickets") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.Add, "Create Ticket") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            message?.let { Snackbar(modifier = Modifier.padding(8.dp)) { Text(it) } }
            LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tickets) { ticket ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ticket.subject, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                StatusChip(ticket.status, MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip(ticket.type, MaterialTheme.colorScheme.secondary)
                                StatusChip(ticket.priority, MaterialTheme.colorScheme.tertiary)
                                if (ticket.compensationStatus != "NONE") {
                                    StatusChip("Comp: ${ticket.compensationStatus}", MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("Created: ${ticket.createdAt}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { evidenceTicketId = ticket.id }) {
                                    Text("Add Evidence")
                                }
                                OutlinedButton(onClick = { imagePickerTicketId = ticket.id; imagePickerLauncher.launch("image/*") }) {
                                    Text("Attach Image")
                                }
                            }
                        }
                    }
                }
                if (tickets.isEmpty()) {
                    item { Text("No tickets. Create one if you need support.", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }

    if (showCreateDialog) {
        var subject by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var selectedType by remember { mutableStateOf(TicketType.DELAY) }
        var selectedPriority by remember { mutableStateOf(TicketPriority.MEDIUM) }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Support Ticket") },
            text = {
                Column {
                    OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Type", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TicketType.entries.forEach { type ->
                            FilterChip(selected = selectedType == type, onClick = { selectedType = type }, label = { Text(type.name) })
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Priority", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TicketPriority.entries.forEach { p ->
                            FilterChip(selected = selectedPriority == p, onClick = { selectedPriority = p }, label = { Text(p.name) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.createTicket(selectedType, selectedPriority, subject, description); showCreateDialog = false },
                    enabled = subject.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }

    if (evidenceTicketId != null) {
        var evidenceText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { evidenceTicketId = null },
            title = { Text("Add Evidence") },
            text = {
                Column {
                    Text("Attach text evidence to this ticket.", style = MaterialTheme.typography.bodySmall)
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
                        viewModel.addTextEvidence(evidenceTicketId!!, evidenceText)
                        evidenceTicketId = null
                    },
                    enabled = evidenceText.isNotBlank()
                ) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { evidenceTicketId = null }) { Text("Cancel") } }
        )
    }
}
