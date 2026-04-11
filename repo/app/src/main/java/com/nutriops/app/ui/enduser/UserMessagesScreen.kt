package com.nutriops.app.ui.enduser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MessageUiItem(val id: String, val title: String, val body: String, val type: String, val isRead: Boolean, val createdAt: String)
data class TodoUiItem(val id: String, val title: String, val description: String, val isCompleted: Boolean, val dueDate: String?)

@HiltViewModel
class UserMessagesViewModel @Inject constructor(
    private val messagingUseCase: ManageMessagingUseCase,
    private val authManager: AuthManager
) : ViewModel() {

    private val _messages = MutableStateFlow<List<MessageUiItem>>(emptyList())
    val messages: StateFlow<List<MessageUiItem>> = _messages.asStateFlow()
    private val _todos = MutableStateFlow<List<TodoUiItem>>(emptyList())
    val todos: StateFlow<List<TodoUiItem>> = _todos.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            val msgs = messagingUseCase.getMessages(session.userId, session.userId, session.role)
            _messages.value = msgs.map { MessageUiItem(it.id, it.title, it.body, it.messageType, it.isRead == 1L, it.createdAt) }
            val tds = messagingUseCase.getTodos(session.userId)
            _todos.value = tds.map { TodoUiItem(it.id, it.title, it.description, it.isCompleted == 1L, it.dueDate) }
        }
    }

    fun markAsRead(messageId: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            messagingUseCase.markAsRead(messageId, session.userId, session.role); load()
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            messagingUseCase.markAllAsRead(session.userId, session.userId, session.role); load()
        }
    }

    fun completeTodo(todoId: String) {
        viewModelScope.launch { messagingUseCase.completeTodo(todoId); load() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserMessagesScreen(
    onBack: () -> Unit,
    viewModel: UserMessagesViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val todos by viewModel.todos.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages & Todos") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (selectedTab == 0) TextButton(onClick = { viewModel.markAllAsRead() }) { Text("Mark All Read") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Messages (${messages.count { !it.isRead }})") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Todos (${todos.count { !it.isCompleted }})") })
            }

            when (selectedTab) {
                0 -> LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(messages) { msg ->
                        Card(
                            onClick = { if (!msg.isRead) viewModel.markAsRead(msg.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (!msg.isRead) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(msg.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    if (!msg.isRead) Icon(Icons.Default.Circle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(8.dp))
                                }
                                Text(msg.body, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text("${msg.type} | ${msg.createdAt}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (messages.isEmpty()) { item { Text("No messages yet", style = MaterialTheme.typography.bodyMedium) } }
                }
                1 -> LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(todos) { todo ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp)) {
                                IconButton(onClick = { if (!todo.isCompleted) viewModel.completeTodo(todo.id) }) {
                                    Icon(
                                        if (todo.isCompleted) Icons.Default.CheckCircle else Icons.Default.Circle,
                                        null,
                                        tint = if (todo.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(todo.title, style = MaterialTheme.typography.titleSmall)
                                    if (todo.description.isNotBlank()) Text(todo.description, style = MaterialTheme.typography.bodySmall)
                                    todo.dueDate?.let { Text("Due: $it", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                    if (todos.isEmpty()) { item { Text("No todo items", style = MaterialTheme.typography.bodyMedium) } }
                }
            }
        }
    }
}
