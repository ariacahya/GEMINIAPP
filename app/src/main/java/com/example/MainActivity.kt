package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.UiState
import com.example.data.model.UserRole
import com.example.data.remote.JubelioApiClient
import com.example.data.remote.JubelioSessionStore
import com.example.data.repository.WarehouseRepository
import com.example.notifications.WarehouseNotifier
import com.example.ui.auth.SignInScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutUser
import com.example.ui.components.StatusPill
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.InventoryScreen
import com.example.ui.screens.JubelioAndAlertsScreen
import com.example.ui.screens.OpnameScreen
import com.example.ui.screens.OrdersScreen
import com.example.ui.theme.Emerald100
import com.example.ui.theme.Emerald600
import com.example.ui.theme.GudangSyncTheme
import com.example.ui.theme.SafetyOrange100
import com.example.ui.theme.SafetyOrange600
import com.example.ui.viewmodel.GudangSyncViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WarehouseNotifier.ensureChannel(this)

        setContent {
            GudangSyncTheme {
                AppRootNavigation()
            }
        }
    }
}

@Composable
fun AppRootNavigation(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val user = currentUser
    if (user == null) {
        SignInScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedWorkspaceShell(
                currentUserId = user.uid,
                displayName = user.displayName,
                email = user.email
            )
        }
    }
}

private enum class AppTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    STOK("Stok", Icons.Filled.Inventory2, Icons.Outlined.Inventory2, "nav_stok"),
    OPNAME("Opname", Icons.Filled.FactCheck, Icons.Outlined.FactCheck, "nav_opname"),
    ORDER("Order", Icons.Filled.LocalShipping, Icons.Outlined.LocalShipping, "nav_order"),
    ANALITIK("Analitik", Icons.Filled.Assessment, Icons.Outlined.Assessment, "nav_analitik"),
    SYNC("Sync & Tim", Icons.Filled.Sync, Icons.Outlined.Sync, "nav_sync")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedWorkspaceShell(
    currentUserId: String,
    displayName: String?,
    email: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: GudangSyncViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                GudangSyncViewModel(
                    repository = WarehouseRepository(db),
                    jubelioApi = JubelioApiClient(),
                    sessionStore = JubelioSessionStore(app),
                    currentUserId = currentUserId,
                    displayName = displayName,
                    email = email
                )
            }
        }
    )

    val userProfileUi by viewModel.userProfile.collectAsStateWithLifecycle()
    val workspace by viewModel.workspace.collectAsStateWithLifecycle()
    val productsState by viewModel.productsState.collectAsStateWithLifecycle()
    val opnamesState by viewModel.opnamesState.collectAsStateWithLifecycle()
    val ordersState by viewModel.ordersState.collectAsStateWithLifecycle()
    val alertsState by viewModel.alertsState.collectAsStateWithLifecycle()
    val syncLogsState by viewModel.syncLogsState.collectAsStateWithLifecycle()
    val jubelioSession by viewModel.jubelioSession.collectAsStateWithLifecycle()
    val analyticsSummary by viewModel.analyticsSummary.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val isJubelioSyncing by viewModel.isJubelioSyncing.collectAsStateWithLifecycle()

    val profile = (userProfileUi as? UiState.Success)?.data
    val activeRole = profile?.roleEnum ?: UserRole.GUDANG
    val isAdmin = activeRole == UserRole.ADMIN

    val visibleTabs = remember(isAdmin) {
        if (isAdmin) {
            listOf(AppTab.ANALITIK, AppTab.ORDER, AppTab.STOK, AppTab.OPNAME, AppTab.SYNC)
        } else {
            listOf(AppTab.ORDER, AppTab.STOK, AppTab.OPNAME, AppTab.SYNC)
        }
    }
    var activeTab by remember(isAdmin) { mutableStateOf(visibleTabs.first()) }
    var syncSubTab by remember { mutableIntStateOf(0) }

    // Request POST_NOTIFICATIONS runtime permission on Android 13+
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Emit local Android notifications for newly arriving real-time alerts targeting current role
    val seenAlertIds = remember(currentUserId) { mutableSetOf<String>() }
    var initializedAlerts by remember(currentUserId) { mutableStateOf(false) }
    val alertsList = (alertsState as? UiState.Success)?.data.orEmpty()
    LaunchedEffect(alertsList, activeRole) {
        if (!initializedAlerts && alertsState is UiState.Success) {
            seenAlertIds.addAll(alertsList.map { it.id })
            initializedAlerts = true
        } else if (initializedAlerts) {
            alertsList.filter { !it.isRead && !seenAlertIds.contains(it.id) }.forEach { alert ->
                seenAlertIds.add(alert.id)
                if (alert.targetRole == "ALL" || alert.targetRole == activeRole.name) {
                    WarehouseNotifier.showNotification(context, alert)
                }
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.toastEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val unreadAlertsCount = alertsList.count {
        !it.isRead && (it.targetRole == "ALL" || it.targetRole == activeRole.name)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.navigationBars,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    title = {
                        Column {
                            Text(
                                text = "GudangSync",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = workspace?.name ?: "Menyiapkan gudang...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            )
                        }
                    },
                    actions = {
                        StatusPill(
                            text = activeRole.label.uppercase(),
                            containerColor = if (isAdmin) SafetyOrange100 else Emerald100,
                            contentColor = if (isAdmin) SafetyOrange600 else Emerald600
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                syncSubTab = 0
                                activeTab = AppTab.SYNC
                            },
                            modifier = Modifier.testTag("top_notifications_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (unreadAlertsCount > 0) {
                                        Badge { Text(unreadAlertsCount.toString()) }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Notifications,
                                    contentDescription = "Notifikasi"
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        visibleTabs.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (tab == AppTab.SYNC && activeTab != AppTab.SYNC) {
                                        syncSubTab = 1
                                    }
                                    activeTab = tab
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Spacer(modifier = Modifier.size(12.dp))
                        visibleTabs.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    if (tab == AppTab.SYNC && activeTab != AppTab.SYNC) {
                                        syncSubTab = 1
                                    }
                                    activeTab = tab
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (activeTab) {
                        AppTab.STOK -> InventoryScreen(
                            productsState = productsState,
                            isAdmin = isAdmin,
                            onAdjustStock = viewModel::adjustStock,
                            onSaveProduct = viewModel::saveProduct
                        )
                        AppTab.OPNAME -> OpnameScreen(
                            productsState = productsState,
                            opnamesState = opnamesState,
                            isBusy = isBusy,
                            jubelioConnected = jubelioSession.isConnected && !jubelioSession.isTokenLikelyExpired,
                            onSubmitOpname = viewModel::submitRackOpname
                        )
                        AppTab.ORDER -> OrdersScreen(
                            ordersState = ordersState,
                            productsState = productsState,
                            isAdmin = isAdmin,
                            onCreateOrder = viewModel::createOrder,
                            onDispatchInstruction = viewModel::dispatchInstruction,
                            onToggleItemPicked = viewModel::toggleOrderItemPicked,
                            onAdvanceStatus = viewModel::advanceOrderStatus
                        )
                        AppTab.ANALITIK -> AnalyticsScreen(
                            summary = analyticsSummary,
                            productsState = productsState,
                            onNavigateToInventory = { activeTab = AppTab.STOK },
                            onNavigateToJubelio = {
                                syncSubTab = 1
                                activeTab = AppTab.SYNC
                            }
                        )
                        AppTab.SYNC -> JubelioAndAlertsScreen(
                            initialSubTab = syncSubTab,
                            userProfile = profile,
                            workspace = workspace,
                            alertsState = alertsState,
                            syncLogsState = syncLogsState,
                            jubelioSession = jubelioSession,
                            isJubelioSyncing = isJubelioSyncing,
                            isBusy = isBusy,
                            onMarkAlertRead = viewModel::markAlertRead,
                            onLoginJubelio = viewModel::loginJubelio,
                            onConnectManualToken = viewModel::connectWithManualToken,
                            onSyncJubelioNow = viewModel::syncWithJubelioNow,
                            onDisconnectJubelio = viewModel::disconnectJubelio,
                            onUpdateSyncPreferences = viewModel::updateSyncPreferences,
                            onSwitchRole = viewModel::switchRole,
                            onJoinWorkspace = viewModel::joinWorkspace,
                            onSignOut = { signOutUser(credentialManager, scope) }
                        )
                    }
                }
            }
        }
    }
}
