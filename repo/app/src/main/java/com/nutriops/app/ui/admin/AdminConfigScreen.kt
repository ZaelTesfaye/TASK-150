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
import com.nutriops.app.domain.model.Role
import com.nutriops.app.domain.usecase.config.ManageConfigUseCase
import com.nutriops.app.security.AuthManager
import com.nutriops.app.ui.common.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConfigUiState(
    val configs: List<ConfigItem> = emptyList(),
    val homepageModules: List<ModuleItem> = emptyList(),
    val adSlots: List<AdSlotItem> = emptyList(),
    val campaigns: List<CampaignItem> = emptyList(),
    val coupons: List<CouponItem> = emptyList(),
    val blacklist: List<ListEntryItem> = emptyList(),
    val whitelist: List<ListEntryItem> = emptyList(),
    val purchaseLimits: List<LimitItem> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null
)

data class ConfigItem(val id: String, val key: String, val value: String, val version: Long)
data class ModuleItem(val id: String, val name: String, val type: String, val position: Long)
data class AdSlotItem(val id: String, val name: String, val position: String)
data class CampaignItem(val id: String, val name: String, val topic: String, val startDate: String, val endDate: String)
data class CouponItem(val id: String, val code: String, val description: String, val discountValue: Double, val discountType: String)
data class ListEntryItem(val id: String, val entityType: String, val entityValue: String, val reason: String)
data class LimitItem(val id: String, val entityType: String, val maxQuantity: Long, val periodDays: Long)

@HiltViewModel
class AdminConfigViewModel @Inject constructor(
    private val configUseCase: ManageConfigUseCase,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    init { loadAll() }

    fun loadAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val session = authManager.currentSession.value ?: return@launch
            val role = session.role
            val configs = configUseCase.getAllConfigs(role).getOrDefault(emptyList()).map { ConfigItem(it.id, it.configKey, it.configValue, it.version) }
            val modules = configUseCase.getAllHomepageModules(role).getOrDefault(emptyList()).map { ModuleItem(it.id, it.name, it.moduleType, it.position) }
            val slots = configUseCase.getAllAdSlots(role).getOrDefault(emptyList()).map { AdSlotItem(it.id, it.name, it.position) }
            val campaigns = configUseCase.getAllCampaigns(role).getOrDefault(emptyList()).map { CampaignItem(it.id, it.name, it.landingTopic, it.startDate, it.endDate) }
            val coupons = configUseCase.getAllCoupons(role).getOrDefault(emptyList()).map { CouponItem(it.id, it.code, it.description, it.discountValue, it.discountType) }
            val blacklist = configUseCase.getBlacklist(role).getOrDefault(emptyList()).map { ListEntryItem(it.id, it.entityType, it.entityValue, it.reason) }
            val whitelist = configUseCase.getWhitelist(role).getOrDefault(emptyList()).map { ListEntryItem(it.id, it.entityType, it.entityValue, it.reason) }
            val limits = configUseCase.getPurchaseLimits(role).getOrDefault(emptyList()).map { LimitItem(it.id, it.entityType, it.maxQuantity, it.periodDays) }
            _uiState.value = ConfigUiState(configs, modules, slots, campaigns, coupons, blacklist, whitelist, limits)
        }
    }

    fun createConfig(key: String, value: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            configUseCase.createConfig(key, value, session.userId, session.role).onSuccess { loadAll() }
        }
    }

    fun createHomepageModule(name: String, type: String, position: Long) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            configUseCase.createHomepageModule(name, type, position, null, session.userId, session.role).onSuccess { loadAll() }
        }
    }

    fun createCoupon(code: String, description: String, discountType: String, discountValue: Double, maxUses: Long, periodDays: Long) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            configUseCase.createCoupon(code, description, discountType, discountValue, "{}", maxUses, periodDays, null, session.userId, session.role).onSuccess { loadAll() }
        }
    }

    fun addToBlacklist(entityType: String, entityValue: String, reason: String) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            configUseCase.addToBlacklist(entityType, entityValue, reason, session.userId, session.role).onSuccess { loadAll() }
        }
    }

    fun setPurchaseLimit(entityType: String, maxQuantity: Long, periodDays: Long) {
        viewModelScope.launch {
            val session = authManager.currentSession.value ?: return@launch
            configUseCase.setPurchaseLimit(entityType, maxQuantity, periodDays, session.userId, session.role).onSuccess { loadAll() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminConfigScreen(
    onBack: () -> Unit,
    viewModel: AdminConfigViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showAddConfigDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuration Center") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddConfigDialog = true }) {
                Icon(Icons.Default.Add, "Add Config")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SectionHeader("Configurations (${state.configs.size})") }
            items(state.configs) { config ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(config.key, style = MaterialTheme.typography.titleSmall)
                        Text("Value: ${config.value}", style = MaterialTheme.typography.bodySmall)
                        Text("Version: ${config.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { SectionHeader("Homepage Modules (${state.homepageModules.size})") }
            items(state.homepageModules) { module ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(module.name, style = MaterialTheme.typography.titleSmall)
                        Text("Type: ${module.type} | Position: ${module.position}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item { SectionHeader("Coupons & Promotions (${state.coupons.size})") }
            items(state.coupons) { coupon ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${coupon.code} - ${coupon.description}", style = MaterialTheme.typography.titleSmall)
                        Text("${coupon.discountType}: \$${coupon.discountValue}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item { SectionHeader("Black/White Lists") }
            items(state.blacklist) { entry ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("BLACKLISTED: ${entry.entityType} = ${entry.entityValue}", style = MaterialTheme.typography.titleSmall)
                        Text("Reason: ${entry.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item { SectionHeader("Purchase Limits (${state.purchaseLimits.size})") }
            items(state.purchaseLimits) { limit ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${limit.entityType}: max ${limit.maxQuantity} per ${limit.periodDays} days", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showAddConfigDialog) {
        var key by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddConfigDialog = false },
            title = { Text("Add Configuration") },
            text = {
                Column {
                    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.createConfig(key, value); showAddConfigDialog = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAddConfigDialog = false }) { Text("Cancel") } }
        )
    }
}
