package com.nutriops.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.auth.ManageUsersUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.StatusChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserUiItem(val id: String, val username: String, val role: String, val isActive: Boolean, val isLocked: Boolean)

@HiltViewModel
class AdminUsersViewModel @Inject constructor(
    private val manageUsersUseCase: ManageUsersUseCase,
    private val authManager: AuthManager
) : ViewModel() {

    private val _users = MutableStateFlow<List<UserUiItem>>(emptyList())
    val users: StateFlow<List<UserUiItem>> = _users.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { loadUsers() }

    fun loadUsers() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            manageUsersUseCase.getAllUsers(session.role)
                .onSuccess { all ->
                    _users.value = all.map { UserUiItem(it.id, it.username, it.role, it.isActive == 1L, it.isLocked == 1L) }
                }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun createUser(username: String, password: String, role: Role) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            manageUsersUseCase.createUser(username, password, role, session.userId, session.role)
                .onSuccess { _message.value = "User created: $username"; loadUsers() }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminUsersScreen(
    onBack: () -> Unit,
    viewModel: AdminUsersViewModel = hiltViewModel()
) {
    val users by viewModel.users.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Management") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.PersonAdd, "Add User") }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(users) { user ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(user.username, style = MaterialTheme.typography.titleSmall)
                            Text("ID: ${user.id.take(8)}...", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            StatusChip(user.role, MaterialTheme.colorScheme.primary)
                            if (user.isLocked) StatusChip("LOCKED", MaterialTheme.colorScheme.error)
                            if (!user.isActive) StatusChip("INACTIVE", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var selectedRole by remember { mutableStateOf(Role.END_USER) }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create User") },
            text = {
                Column {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Role.entries.forEach { role ->
                            FilterChip(selected = selectedRole == role, onClick = { selectedRole = role }, label = { Text(role.name.take(5)) })
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { viewModel.createUser(username, password, selectedRole); showCreateDialog = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }
}
