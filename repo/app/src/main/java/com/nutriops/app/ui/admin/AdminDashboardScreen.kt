package com.nutriops.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    onNavigateToConfig: () -> Unit,
    onNavigateToRules: () -> Unit,
    onNavigateToRollouts: () -> Unit,
    onNavigateToUsers: () -> Unit,
    onNavigateToAudit: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Administrator Dashboard") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Operations & Configuration Center", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                DashboardCard(
                    title = "Configuration Center",
                    description = "Homepage modules, ad slots, campaigns, coupons, black/whitelist, purchase limits",
                    icon = Icons.Default.Settings,
                    onClick = onNavigateToConfig
                )
            }

            item {
                DashboardCard(
                    title = "Metrics & Rules Engine",
                    description = "Compound conditions, hysteresis, minimum duration, effective windows, versioning",
                    icon = Icons.Default.Analytics,
                    onClick = onNavigateToRules
                )
            }

            item {
                DashboardCard(
                    title = "Canary Rollouts",
                    description = "Config versioning with 10% canary rollout to local users",
                    icon = Icons.Default.RocketLaunch,
                    onClick = onNavigateToRollouts
                )
            }

            item {
                DashboardCard(
                    title = "User Management",
                    description = "Manage administrators, agents, and end users",
                    icon = Icons.Default.People,
                    onClick = onNavigateToUsers
                )
            }

            item {
                DashboardCard(
                    title = "Audit Trail",
                    description = "Immutable audit log of all system actions",
                    icon = Icons.Default.Security,
                    onClick = onNavigateToAudit
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
