package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.AlertNotification
import com.example.data.model.SyncLogEntry
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.Workspace
import com.example.data.remote.ALT_JUBELIO_BASE_URL
import com.example.data.remote.DEFAULT_JUBELIO_BASE_URL
import com.example.data.remote.JubelioSessionState
import com.example.data.remote.SyncConflictPolicy
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.Amber100
import com.example.ui.theme.Amber600
import com.example.ui.theme.Crimson100
import com.example.ui.theme.Crimson600
import com.example.ui.theme.Emerald100
import com.example.ui.theme.Emerald600
import com.example.ui.theme.SafetyOrange100
import com.example.ui.theme.SafetyOrange600
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JubelioAndAlertsScreen(
    initialSubTab: Int,
    userProfile: UserProfile?,
    workspace: Workspace?,
    alertsState: UiState<List<AlertNotification>>,
    syncLogsState: UiState<List<SyncLogEntry>>,
    jubelioSession: JubelioSessionState,
    isJubelioSyncing: Boolean,
    isBusy: Boolean,
    onMarkAlertRead: (String) -> Unit,
    onLoginJubelio: (String, String, String) -> Unit,
    onConnectManualToken: (String, String) -> Unit,
    onSyncJubelioNow: () -> Unit,
    onDisconnectJubelio: () -> Unit,
    onUpdateSyncPreferences: (Boolean, SyncConflictPolicy) -> Unit,
    onSwitchRole: (UserRole) -> Unit,
    onJoinWorkspace: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var selectedTab by remember(initialSubTab) { mutableIntStateOf(initialSubTab) }
    val tabs = listOf("Notifikasi", "Jubelio API", "Tim & Peran")

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> AlertsTabContent(
                alerts = (alertsState as? UiState.Success)?.data.orEmpty(),
                currentRole = userProfile?.roleEnum ?: UserRole.GUDANG,
                onMarkAlertRead = onMarkAlertRead
            )
            1 -> JubelioIntegrationTabContent(
                session = jubelioSession,
                syncLogs = (syncLogsState as? UiState.Success)?.data.orEmpty(),
                isSyncing = isJubelioSyncing,
                onLogin = onLoginJubelio,
                onConnectManualToken = onConnectManualToken,
                onSyncNow = onSyncJubelioNow,
                onDisconnect = onDisconnectJubelio,
                onUpdatePrefs = onUpdateSyncPreferences
            )
            2 -> TeamAndRoleTabContent(
                profile = userProfile,
                workspace = workspace,
                isBusy = isBusy,
                onSwitchRole = onSwitchRole,
                onJoinWorkspace = onJoinWorkspace,
                onSignOut = onSignOut
            )
        }
    }
}

@Composable
private fun AlertsTabContent(
    alerts: List<AlertNotification>,
    currentRole: UserRole,
    onMarkAlertRead: (String) -> Unit
) {
    var filterUnread by remember { mutableStateOf(false) }
    val filtered = remember(alerts, filterUnread, currentRole) {
        alerts.filter { alert ->
            val roleMatches = alert.targetRole == "ALL" || alert.targetRole == currentRole.name
            val readMatches = !filterUnread || !alert.isRead
            roleMatches && readMatches
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !filterUnread,
                    onClick = { filterUnread = false },
                    label = { Text("Semua (${alerts.size})") }
                )
                FilterChip(
                    selected = filterUnread,
                    onClick = { filterUnread = true },
                    label = { Text("Belum Dibaca (${alerts.count { !it.isRead }})") }
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.Notifications,
                    title = "Belum Ada Notifikasi Baru",
                    subtitle = if (currentRole == UserRole.ADMIN) {
                        "Peringatan otomatis akan muncul di sini saat ada stok SKU menyentuh ambang batas minimum."
                    } else {
                        "Notifikasi otomatis akan muncul di sini saat Admin mengirim arahan pesanan baru."
                    }
                )
            }
        } else {
            items(filtered, key = { it.id }) { alert ->
                val dateLabel = alert.createdAt?.toDate()?.let {
                    SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID")).format(it)
                } ?: "Baru saja"

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (!alert.isRead) onMarkAlertRead(alert.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (alert.isRead) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (alert.isRead) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(
                                text = when (alert.type) {
                                    "LOW_STOCK" -> "STOK MINIMUM"
                                    "NEW_ORDER_INSTRUCTION" -> "ARAHAN ORDER"
                                    else -> "SINKRONISASI"
                                },
                                containerColor = if (alert.type == "LOW_STOCK") Crimson100 else SafetyOrange100,
                                contentColor = if (alert.type == "LOW_STOCK") Crimson600 else SafetyOrange600
                            )
                            Text(
                                text = dateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = alert.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = alert.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!alert.isRead) {
                            Text(
                                text = "Ketuk untuk tandai sudah dibaca",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JubelioIntegrationTabContent(
    session: JubelioSessionState,
    syncLogs: List<SyncLogEntry>,
    isSyncing: Boolean,
    onLogin: (String, String, String) -> Unit,
    onConnectManualToken: (String, String) -> Unit,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit,
    onUpdatePrefs: (Boolean, SyncConflictPolicy) -> Unit
) {
    var emailInput by remember(session.email) { mutableStateOf(session.email) }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var useManualToken by remember { mutableStateOf(false) }
    var manualTokenInput by remember { mutableStateOf("") }
    var baseUrlInput by remember(session.baseUrl) { mutableStateOf(session.baseUrl) }
    var showEndpointSettings by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (session.isConnected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = if (session.isConnected) Emerald600 else Amber600
                            )
                            Text(
                                text = "REST API Jubelio",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        StatusPill(
                            text = when {
                                session.isConnected && session.isTokenLikelyExpired -> "TOKEN KEDALUWARSA"
                                session.isConnected -> "TERHUBUNG"
                                else -> "BELUM LOGIN"
                            },
                            containerColor = when {
                                session.isConnected && !session.isTokenLikelyExpired -> Emerald100
                                else -> Amber100
                            },
                            contentColor = when {
                                session.isConnected && !session.isTokenLikelyExpired -> Emerald600
                                else -> Amber600
                            }
                        )
                    }

                    Text(
                        text = session.lastSyncMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (session.lastErrorMessage.isNotBlank()) {
                        Surface(
                            color = Crimson100,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Crimson600.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = Crimson600
                                    )
                                    Text(
                                        text = "Detail Kendala Koneksi Jubelio",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Crimson600
                                    )
                                }
                                Text(
                                    text = session.lastErrorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Crimson600
                                )
                                Text(
                                    text = "Tips: Pastikan email & password adalah akun pemilik/Open API Jubelio (bukan login Google/SSO), fitur Open API aktif pada paket Jubelio Anda, atau gunakan mode Token API Manual.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (!session.isConnected || session.isTokenLikelyExpired) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !useManualToken,
                                onClick = { useManualToken = false },
                                label = { Text("Email & Password") }
                            )
                            FilterChip(
                                selected = useManualToken,
                                onClick = { useManualToken = true },
                                label = { Text("Token API Manual") }
                            )
                        }

                        if (!useManualToken) {
                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("Email Akun Jubelio") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("jubelio_email_input")
                            )
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password Akun Jubelio") },
                                visualTransformation = if (showPassword) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (showPassword) "Sembunyikan password" else "Lihat password"
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("jubelio_password_input")
                            )
                        } else {
                            OutlinedTextField(
                                value = manualTokenInput,
                                onValueChange = { manualTokenInput = it },
                                label = { Text("Bearer / JWT Token Jubelio") },
                                placeholder = { Text("Tempel token dari Jubelio Open API...") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("jubelio_token_input")
                            )
                        }

                        TextButton(
                            onClick = { showEndpointSettings = !showEndpointSettings },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (showEndpointSettings) {
                                    "Sembunyikan Pengaturan Server API"
                                } else {
                                    "Server API: $baseUrlInput (Ubah)"
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        if (showEndpointSettings) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = baseUrlInput.trim() == DEFAULT_JUBELIO_BASE_URL,
                                    onClick = { baseUrlInput = DEFAULT_JUBELIO_BASE_URL },
                                    label = { Text("api2.jubelio.com") }
                                )
                                FilterChip(
                                    selected = baseUrlInput.trim() == ALT_JUBELIO_BASE_URL,
                                    onClick = { baseUrlInput = ALT_JUBELIO_BASE_URL },
                                    label = { Text("api.jubelio.com") }
                                )
                            }
                            OutlinedTextField(
                                value = baseUrlInput,
                                onValueChange = { baseUrlInput = it },
                                label = { Text("Base URL REST API Jubelio") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                if (useManualToken) {
                                    onConnectManualToken(manualTokenInput, baseUrlInput)
                                } else {
                                    onLogin(emailInput, passwordInput, baseUrlInput)
                                }
                            },
                            enabled = !isSyncing && if (useManualToken) {
                                manualTokenInput.isNotBlank()
                            } else {
                                emailInput.isNotBlank() && passwordInput.isNotBlank()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("jubelio_login_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp).width(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                if (useManualToken) "Hubungkan via Token API" else "Hubungkan Akun Jubelio"
                            )
                        }
                    } else {
                        Text(
                            text = "Endpoint aktif: ${session.baseUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSyncNow,
                                enabled = !isSyncing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("jubelio_sync_now_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                )
                            ) {
                                Icon(Icons.Filled.Sync, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isSyncing) "Menyinkron..." else "Sinkron Sekarang")
                            }
                            OutlinedButton(
                                onClick = onDisconnect,
                                enabled = !isSyncing,
                                modifier = Modifier.height(50.dp)
                            ) {
                                Text("Putuskan")
                            }
                        }
                    }
                }
            }
        }

        // Sync Policy Settings
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Aturan Sinkronisasi Anti-Selisih",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Push Penyesuaian Stok", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Kirim otomatis perubahan opname & picking ke Jubelio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = session.autoSyncEnabled,
                            onCheckedChange = { enabled ->
                                onUpdatePrefs(enabled, session.conflictPolicy)
                            }
                        )
                    }
                    Text("Kebijakan Resolusi Selisih:", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncConflictPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = session.conflictPolicy == policy,
                                onClick = { onUpdatePrefs(session.autoSyncEnabled, policy) },
                                label = { Text(policy.label) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Riwayat Log Sinkronisasi Jubelio",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (syncLogs.isEmpty()) {
            item {
                Text(
                    text = "Belum ada aktivitas sinkronisasi tercatat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(syncLogs.take(15), key = { it.id }) { log ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(log.direction, style = MaterialTheme.typography.labelSmall)
                            StatusPill(
                                text = log.status,
                                containerColor = if (log.status == "SUCCESS") Emerald100 else Amber100,
                                contentColor = if (log.status == "SUCCESS") Emerald600 else Amber600
                            )
                        }
                        Text(log.summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamAndRoleTabContent(
    profile: UserProfile?,
    workspace: Workspace?,
    isBusy: Boolean,
    onSwitchRole: (UserRole) -> Unit,
    onJoinWorkspace: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var inviteInput by remember { mutableStateOf("") }
    val activeRole = profile?.roleEnum ?: UserRole.GUDANG

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Peran Operasional Aktif", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "${profile?.displayName.orEmpty()} (${profile?.email.orEmpty()})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        UserRole.entries.forEach { role ->
                            val selected = activeRole == role
                            Button(
                                onClick = { onSwitchRole(role) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("switch_role_${role.name}"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onSecondary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            ) {
                                Text(role.label, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Kolaborasi Tim Gudang & Admin",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Gudang Aktif: ${workspace?.name ?: "-"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Kode Undangan Tim Anda:",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = workspace?.inviteCode ?: "-",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Bagikan kode ini ke perangkat Staf Gudang atau Admin lain agar terhubung ke stok & order yang sama (${workspace?.memberUids?.size ?: 1} anggota).",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    OutlinedTextField(
                        value = inviteInput,
                        onValueChange = { inviteInput = it.uppercase() },
                        label = { Text("Gabung ke Gudang Lain (Kode Undangan)") },
                        placeholder = { Text("Contoh: GS-A1B2C3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onJoinWorkspace(inviteInput)
                            inviteInput = ""
                        },
                        enabled = !isBusy && inviteInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Gabung Workspace Gudang")
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button"),
                border = BorderStroke(1.dp, Crimson600)
            ) {
                Text("Keluar Akun Google", color = Crimson600, fontWeight = FontWeight.Bold)
            }
        }
    }
}
