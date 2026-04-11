package com.nutriops.app.ui.enduser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutriops.app.domain.usecase.messaging.ManageMessagingUseCase
import com.nutriops.app.security.AuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserDashboardViewModel @Inject constructor(
    private val messagingUseCase: ManageMessagingUseCase,
    private val authManager: AuthManager
) : ViewModel() {
    private val _unreadCount = MutableStateFlow(0L)
    val unreadCount: StateFlow<Long> = _unreadCount.asStateFlow()

    init { loadUnreadCount() }

    fun loadUnreadCount() {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            _unreadCount.value = messagingUseCase.getUnreadCount(session.userId, session.userId, session.role)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDashboardScreen(
    onNavigateToProfile: () -> Unit,
    onNavigateToMealPlan: () -> Unit,
    onNavigateToLearningPlans: () -> Unit,
    onNavigateToMessages: () -> Unit,
    onNavigateToTickets: () -> Unit,
    onLogout: () -> Unit,
    viewModel: UserDashboardViewModel = hiltViewModel()
) {
    val unreadCount by viewModel.unreadCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NutriOps") },
                actions = { IconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Welcome back!", style = MaterialTheme.typography.headlineSmall) }
            item {
                DashCard("My Profile", "Age, dietary preferences, goals, and macro targets", Icons.Default.Person, onNavigateToProfile)
            }
            item {
                DashCard("Weekly Meal Plan", "Personalized meals with calorie budgets and swap options", Icons.Default.Restaurant, onNavigateToMealPlan)
            }
            item {
                DashCard("Learning Plans", "Track your nutrition learning journey", Icons.Default.School, onNavigateToLearningPlans)
            }
            item {
                Card(onClick = onNavigateToMessages, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.Default.Mail, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Messages & Reminders", style = MaterialTheme.typography.titleMedium)
                            Text("In-app notifications and todo items", style = MaterialTheme.typography.bodySmall)
                        }
                        if (unreadCount > 0) {
                            Badge { Text("$unreadCount") }
                        }
                    }
                }
            }
            item {
                DashCard("Support Tickets", "Report issues: delays, disputes, lost items", Icons.Default.Support, onNavigateToTickets)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashCard(title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
