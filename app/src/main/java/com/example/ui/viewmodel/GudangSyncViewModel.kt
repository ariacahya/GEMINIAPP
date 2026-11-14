package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AlertNotification
import com.example.data.model.OrderInstruction
import com.example.data.model.OrderLineItem
import com.example.data.model.OrderStatus
import com.example.data.model.StockOpnameRecord
import com.example.data.model.SyncLogEntry
import com.example.data.model.SyncStatus
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.WarehouseProduct
import com.example.data.model.Workspace
import com.example.data.remote.JubelioApiClient
import com.example.data.remote.JubelioSessionState
import com.example.data.remote.JubelioSessionStore
import com.example.data.remote.JubelioStockAdjustmentItem
import com.example.data.remote.JubelioTokenExpiredException
import com.example.data.remote.SyncConflictPolicy
import com.example.data.repository.WarehouseRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AnalyticsSummary(
    val totalRevenue: Double = 0.0,
    val totalOrders: Int = 0,
    val completedOrders: Int = 0,
    val activeOrders: Int = 0,
    val pendingDispatchOrders: Int = 0,
    val fulfillmentRatePercent: Int = 0,
    val totalSkus: Int = 0,
    val totalUnits: Long = 0L,
    val lowStockCount: Int = 0,
    val pendingSyncCount: Int = 0,
    val channelBreakdown: List<Pair<String, Double>> = emptyList(),
    val topProducts: List<Pair<String, Long>> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class GudangSyncViewModel(
    private val repository: WarehouseRepository,
    private val jubelioApi: JubelioApiClient,
    private val sessionStore: JubelioSessionStore,
    private val currentUserId: String,
    private val displayName: String?,
    private val email: String?
) : ViewModel() {

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _isJubelioSyncing = MutableStateFlow(false)
    val isJubelioSyncing: StateFlow<Boolean> = _isJubelioSyncing.asStateFlow()

    val jubelioSession: StateFlow<JubelioSessionState> = sessionStore.sessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), JubelioSessionState())

    val userProfile: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { err ->
            val fs = unwrapFirestore(err) ?: return@catch emit(UiState.Error(err.message ?: "Gagal memuat profil"))
            emit(UiState.Error(fs.message ?: "Gagal memuat profil"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val workspaceIdFlow = userProfile
        .map { (it as? UiState.Success)?.data?.activeWorkspaceId?.takeIf { id -> id.isNotBlank() } }
        .filterNotNull()
        .distinctUntilChanged()

    val workspace: StateFlow<Workspace?> = workspaceIdFlow
        .flatMapLatest { wsId ->
            repository.observeWorkspace(wsId).catch { err ->
                Log.w(TAG, "Error observing workspace", unwrapFirestore(err) ?: err)
                emit(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val productsState: StateFlow<UiState<List<WarehouseProduct>>> = workspaceIdFlow
        .flatMapLatest { wsId ->
            repository.observeProducts(wsId, currentUserId)
                .map<List<WarehouseProduct>, UiState<List<WarehouseProduct>>> { UiState.Success(it) }
                .catch { err -> emit(toErrorState(err, "Gagal memuat stok produk")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val opnamesState: StateFlow<UiState<List<StockOpnameRecord>>> = workspaceIdFlow
        .flatMapLatest { wsId ->
            repository.observeOpnames(wsId, currentUserId)
                .map<List<StockOpnameRecord>, UiState<List<StockOpnameRecord>>> { UiState.Success(it) }
                .catch { err -> emit(toErrorState(err, "Gagal memuat riwayat opname")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val ordersState: StateFlow<UiState<List<OrderInstruction>>> = workspaceIdFlow
        .flatMapLatest { wsId ->
            repository.observeOrders(wsId, currentUserId)
                .map<List<OrderInstruction>, UiState<List<OrderInstruction>>> { UiState.Success(it) }
                .catch { err -> emit(toErrorState(err, "Gagal memuat daftar pesanan")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val alertsState: StateFlow<UiState<List<AlertNotification>>> = workspaceIdFlow
        .flatMapLatest { wsId ->
            repository.observeAlerts(wsId, currentUserId)
                .map<List<AlertNotification>, UiState<List<AlertNotification>>> { UiState.Success(it) }
                .catch { err -> emit(toErrorState(err, "Gagal memuat notifikasi")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val syncLogsState: StateFlow<UiState<List<SyncLogEntry>>> = workspaceIdFlow
        .flatMapLatest { wsId ->
            repository.observeSyncLogs(wsId, currentUserId)
                .map<List<SyncLogEntry>, UiState<List<SyncLogEntry>>> { UiState.Success(it) }
                .catch { err -> emit(toErrorState(err, "Gagal memuat log sinkronisasi")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val analyticsSummary: StateFlow<AnalyticsSummary> = kotlinx.coroutines.flow.combine(
        productsState,
        ordersState
    ) { prodUi, ordUi ->
        val products = (prodUi as? UiState.Success)?.data.orEmpty()
        val orders = (ordUi as? UiState.Success)?.data.orEmpty()
        computeAnalytics(products, orders)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AnalyticsSummary())

    init {
        viewModelScope.launch {
            repository.ensureUserAndWorkspace(displayName, email)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Gagal menyiapkan workspace") }
        }
        viewModelScope.launch {
            workspace.filterNotNull().collect { ws ->
                if (ws.memberUids.size > 1 && ws.adminUids.contains(currentUserId)) {
                    repository.propagateMembersToWorkspaceDocs(ws)
                }
            }
        }
    }

    fun switchRole(role: UserRole) {
        viewModelScope.launch {
            repository.updateActiveRole(role)
                .onSuccess { _toastEvents.tryEmit("Mode aktif: ${role.label}") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Gagal ubah peran") }
        }
    }

    fun joinWorkspace(inviteCode: String) {
        viewModelScope.launch {
            _isBusy.value = true
            repository.joinWorkspaceByInviteCode(inviteCode)
                .onSuccess { _toastEvents.tryEmit("Berhasil bergabung ke tim gudang!") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Gagal bergabung ke workspace") }
            _isBusy.value = false
        }
    }

    fun saveProduct(
        existingId: String?,
        sku: String,
        name: String,
        category: String,
        rackLocation: String,
        systemStock: Long,
        minThreshold: Long,
        unitPrice: Double
    ) {
        val ws = workspace.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            val session = jubelioSession.value
            val canPush = session.isConnected && !session.isTokenLikelyExpired
            repository.addOrUpdateProduct(
                workspace = ws,
                existingProductId = existingId,
                sku = sku,
                name = name,
                category = category,
                rackLocation = rackLocation,
                systemStock = systemStock,
                minThreshold = minThreshold,
                unitPrice = unitPrice,
                jubelioSynced = false
            ).onSuccess {
                if (canPush) {
                    pushSingleStockAdjustmentToJubelio(
                        token = session.token,
                        ws = ws,
                        items = listOf(
                            JubelioStockAdjustmentItem(
                                item_id = 0L,
                                sku = sku.trim().uppercase(Locale.US),
                                qty = systemStock,
                                note = "Pembaruan stok dari GudangSync"
                            )
                        )
                    )
                }
                _toastEvents.tryEmit("Produk $sku berhasil disimpan.")
            }.onFailure {
                _toastEvents.tryEmit(it.message ?: "Gagal menyimpan produk")
            }
            _isBusy.value = false
        }
    }

    fun adjustStock(product: WarehouseProduct, delta: Long) {
        val ws = workspace.value ?: return
        viewModelScope.launch {
            val session = jubelioSession.value
            val canPush = session.isConnected && !session.isTokenLikelyExpired && session.autoSyncEnabled
            var pushedOk = false
            if (canPush && product.jubelioItemId != null) {
                val newQty = (product.systemStock + delta).coerceAtLeast(0L)
                pushedOk = jubelioApi.pushStockAdjustment(
                    token = session.token,
                    dateIso = todayIso(),
                    note = "Penyesuaian cepat GudangSync",
                    items = listOf(
                        JubelioStockAdjustmentItem(
                            item_id = product.jubelioItemId,
                            sku = product.sku,
                            qty = newQty,
                            note = "Quick adjust (${if (delta > 0) "+$delta" else "$delta"})"
                        )
                    )
                ).isSuccess
            }
            repository.adjustProductStock(ws, product, delta, pushedOk)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Gagal mengubah stok") }
        }
    }

    fun submitRackOpname(
        rackLocation: String,
        physicalCounts: Map<String, Long>,
        productsInRack: List<WarehouseProduct>,
        notes: String
    ) {
        val ws = workspace.value ?: return
        val profile = (userProfile.value as? UiState.Success)?.data
        val performer = profile?.displayName ?: displayName ?: "Staf Gudang"
        viewModelScope.launch {
            _isBusy.value = true
            val session = jubelioSession.value
            var jubelioSynced = false
            if (session.isConnected && !session.isTokenLikelyExpired) {
                val adjItems = productsInRack.mapNotNull { prod ->
                    val itemId = prod.jubelioItemId ?: return@mapNotNull null
                    val physical = (physicalCounts[prod.id] ?: prod.systemStock).coerceAtLeast(0L)
                    JubelioStockAdjustmentItem(
                        item_id = itemId,
                        sku = prod.sku,
                        qty = physical,
                        note = "Opname $rackLocation"
                    )
                }
                if (adjItems.isNotEmpty()) {
                    val pushResult = jubelioApi.pushStockAdjustment(
                        token = session.token,
                        dateIso = todayIso(),
                        note = "Stok Opname Rak $rackLocation: $notes",
                        items = adjItems
                    )
                    jubelioSynced = pushResult.isSuccess
                    pushResult.exceptionOrNull()?.let { err ->
                        if (err is JubelioTokenExpiredException) {
                            sessionStore.clearSession()
                            _toastEvents.tryEmit(err.message ?: "Token Jubelio kedaluwarsa")
                        }
                    }
                }
            }

            repository.submitRackOpname(
                workspace = ws,
                rackLocation = rackLocation,
                physicalCounts = physicalCounts,
                productsInRack = productsInRack,
                notes = notes,
                performedByName = performer,
                syncedToJubelio = jubelioSynced
            ).onSuccess {
                val msg = if (jubelioSynced) {
                    "Opname $rackLocation tersimpan & tersinkron ke Jubelio!"
                } else {
                    "Opname $rackLocation tersimpan ke sistem gudang."
                }
                _toastEvents.tryEmit(msg)
            }.onFailure {
                _toastEvents.tryEmit(it.message ?: "Gagal menyimpan hasil opname")
            }
            _isBusy.value = false
        }
    }

    fun createOrder(
        orderNumber: String,
        customerName: String,
        channel: String,
        items: List<OrderLineItem>,
        adminInstruction: String,
        dispatchImmediately: Boolean
    ) {
        val ws = workspace.value ?: return
        val products = (productsState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch {
            _isBusy.value = true
            repository.createOrder(
                workspace = ws,
                orderNumber = orderNumber,
                customerName = customerName,
                channel = channel,
                items = items,
                adminInstruction = adminInstruction,
                dispatchImmediately = dispatchImmediately,
                allProducts = products
            ).onSuccess {
                _toastEvents.tryEmit(
                    if (dispatchImmediately) "Order dibuat & arahan dikirim ke Gudang."
                    else "Order masuk berhasil dicatat."
                )
            }.onFailure {
                _toastEvents.tryEmit(it.message ?: "Gagal membuat order")
            }
            _isBusy.value = false
        }
    }

    fun dispatchInstruction(order: OrderInstruction, instruction: String) {
        val ws = workspace.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            repository.dispatchOrderInstruction(ws, order, instruction)
                .onSuccess { _toastEvents.tryEmit("Arahan order ${order.orderNumber} dikirim ke Gudang.") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Gagal mengirim arahan") }
            _isBusy.value = false
        }
    }

    fun toggleOrderItemPicked(order: OrderInstruction, itemIndex: Int) {
        val ws = workspace.value ?: return
        viewModelScope.launch {
            repository.toggleOrderItemPicked(ws, order, itemIndex)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Gagal memperbarui item") }
        }
    }

    fun advanceOrderStatus(
        order: OrderInstruction,
        nextStatus: OrderStatus,
        courier: String = order.courier,
        trackingNumber: String = order.trackingNumber
    ) {
        val ws = workspace.value ?: return
        val products = (productsState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch {
            _isBusy.value = true
            val session = jubelioSession.value
            var jubelioSynced = false
            if (session.isConnected && !session.isTokenLikelyExpired && order.jubelioOrderId != null) {
                jubelioSynced = jubelioApi.pushOrderFulfillment(
                    token = session.token,
                    jubelioOrderId = order.jubelioOrderId,
                    status = nextStatus.name,
                    courier = courier,
                    trackingNumber = trackingNumber
                ).isSuccess
            }
            repository.advanceOrderStatus(
                workspace = ws,
                order = order,
                nextStatus = nextStatus,
                courier = courier,
                trackingNumber = trackingNumber,
                allProducts = products,
                jubelioSynced = jubelioSynced
            ).onSuccess {
                _toastEvents.tryEmit("Status ${order.orderNumber}: ${nextStatus.label}")
            }.onFailure {
                _toastEvents.tryEmit(it.message ?: "Gagal memperbarui status pesanan")
            }
            _isBusy.value = false
        }
    }

    fun loginJubelio(emailInput: String, passwordInput: String) {
        val ws = workspace.value ?: return
        viewModelScope.launch {
            _isJubelioSyncing.value = true
            jubelioApi.login(emailInput, passwordInput)
                .onSuccess { token ->
                    sessionStore.saveSession(emailInput.trim(), token)
                    repository.updateWorkspaceJubelioStatus(ws.id, emailInput.trim(), true)
                    _toastEvents.tryEmit("Login Jubelio berhasil! Memulai sinkronisasi...")
                    syncWithJubelioNowInternal(ws, token, sessionStore = sessionStore)
                }
                .onFailure { err ->
                    repository.recordSyncLog(
                        workspace = ws,
                        direction = "TWO_WAY",
                        status = "ERROR",
                        summary = err.message ?: "Gagal login ke API Jubelio",
                        itemsSynced = 0L,
                        discrepanciesFound = 0L
                    )
                    _toastEvents.tryEmit(err.message ?: "Login Jubelio gagal")
                }
            _isJubelioSyncing.value = false
        }
    }

    fun syncWithJubelioNow() {
        val ws = workspace.value ?: return
        val session = jubelioSession.value
        if (!session.isConnected) {
            _toastEvents.tryEmit("Silakan login akun Jubelio terlebih dahulu.")
            return
        }
        viewModelScope.launch {
            _isJubelioSyncing.value = true
            syncWithJubelioNowInternal(ws, session.token, sessionStore = sessionStore)
            _isJubelioSyncing.value = false
        }
    }

    private suspend fun syncWithJubelioNowInternal(
        ws: Workspace,
        token: String,
        sessionStore: JubelioSessionStore
    ) {
        try {
            val invRes = jubelioApi.fetchInventory(token)
            val ordRes = jubelioApi.fetchSalesOrders(token)
            val remoteProducts = invRes.getOrThrow()
            val remoteOrders = ordRes.getOrElse { emptyList() }
            val localProducts = (productsState.value as? UiState.Success)?.data.orEmpty()
            val localOrders = (ordersState.value as? UiState.Success)?.data.orEmpty()

            // Push any local PENDING_PUSH items with Jubelio IDs first if Warehouse priority
            val policy = jubelioSession.value.conflictPolicy
            if (policy == SyncConflictPolicy.WAREHOUSE_PRIORITY) {
                val pendingPush = localProducts.filter {
                    it.syncStatusEnum == SyncStatus.PENDING_PUSH && it.jubelioItemId != null
                }.map {
                    JubelioStockAdjustmentItem(
                        item_id = it.jubelioItemId ?: 0L,
                        sku = it.sku,
                        qty = it.systemStock,
                        note = "Auto-push GudangSync"
                    )
                }
                if (pendingPush.isNotEmpty()) {
                    jubelioApi.pushStockAdjustment(
                        token = token,
                        dateIso = todayIso(),
                        note = "Sinkronisasi stok gudang ke Jubelio",
                        items = pendingPush
                    )
                }
            }

            repository.applyJubelioSyncSnapshot(
                workspace = ws,
                remoteProducts = remoteProducts,
                remoteOrders = remoteOrders,
                localProducts = localProducts,
                localOrders = localOrders,
                policy = policy
            ).onSuccess { (synced, diffs) ->
                val summary = "Sinkron sukses: $synced data, $diffs selisih diselaraskan"
                sessionStore.updateLastSyncMessage(summary)
                repository.updateWorkspaceJubelioStatus(ws.id, jubelioSession.value.email, true)
                _toastEvents.tryEmit(summary)
            }.onFailure { err ->
                _toastEvents.tryEmit(err.message ?: "Gagal menyimpan hasil sinkronisasi")
            }
        } catch (e: JubelioTokenExpiredException) {
            sessionStore.clearSession()
            repository.updateWorkspaceJubelioStatus(ws.id, null, false)
            repository.recordSyncLog(ws, "TWO_WAY", "ERROR", e.message ?: "Token kedaluwarsa", 0L, 0L)
            _toastEvents.tryEmit(e.message ?: "Token Jubelio kedaluwarsa")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = e.message ?: "Gagal sinkronisasi dengan Jubelio"
            sessionStore.updateLastSyncMessage(msg)
            repository.recordSyncLog(ws, "TWO_WAY", "ERROR", msg, 0L, 0L)
            _toastEvents.tryEmit(msg)
        }
    }

    private suspend fun pushSingleStockAdjustmentToJubelio(
        token: String,
        ws: Workspace,
        items: List<JubelioStockAdjustmentItem>
    ) {
        jubelioApi.pushStockAdjustment(
            token = token,
            dateIso = todayIso(),
            note = "Sinkronisasi GudangSync",
            items = items
        ).onFailure { err ->
            if (err is JubelioTokenExpiredException) {
                sessionStore.clearSession()
                repository.updateWorkspaceJubelioStatus(ws.id, null, false)
            }
        }
    }

    fun updateSyncPreferences(autoSync: Boolean, policy: SyncConflictPolicy) {
        viewModelScope.launch {
            sessionStore.updateSyncPreferences(autoSync, policy)
            _toastEvents.tryEmit("Pengaturan sinkronisasi diperbarui.")
        }
    }

    fun disconnectJubelio() {
        val ws = workspace.value ?: return
        viewModelScope.launch {
            sessionStore.clearSession()
            repository.updateWorkspaceJubelioStatus(ws.id, null, false)
            _toastEvents.tryEmit("Koneksi akun Jubelio diputuskan.")
        }
    }

    fun markAlertRead(alertId: String) {
        val ws = workspace.value ?: return
        viewModelScope.launch {
            repository.markAlertRead(ws.id, alertId)
        }
    }

    private fun computeAnalytics(
        products: List<WarehouseProduct>,
        orders: List<OrderInstruction>
    ): AnalyticsSummary {
        val totalRevenue = orders.sumOf { it.totalAmount }
        val completed = orders.count { it.statusEnum == OrderStatus.SELESAI }
        val pendingDispatch = orders.count { it.statusEnum == OrderStatus.MENUNGGU_ARAHAN }
        val active = orders.count {
            it.statusEnum != OrderStatus.SELESAI && it.statusEnum != OrderStatus.MENUNGGU_ARAHAN
        }
        val fulfillmentPct = if (orders.isEmpty()) 0 else ((completed * 100) / orders.size)

        val channelMap = orders.groupBy { it.channel.ifBlank { "Lainnya" } }
            .mapValues { (_, list) -> list.sumOf { it.totalAmount } }
            .toList()
            .sortedByDescending { it.second }

        val topSkuMap = mutableMapOf<String, Long>()
        for (order in orders) {
            for (item in order.items) {
                topSkuMap[item.name.ifBlank { item.sku }] =
                    (topSkuMap[item.name.ifBlank { item.sku }] ?: 0L) + item.qty
            }
        }

        return AnalyticsSummary(
            totalRevenue = totalRevenue,
            totalOrders = orders.size,
            completedOrders = completed,
            activeOrders = active,
            pendingDispatchOrders = pendingDispatch,
            fulfillmentRatePercent = fulfillmentPct,
            totalSkus = products.size,
            totalUnits = products.sumOf { it.systemStock },
            lowStockCount = products.count { it.isLowStock },
            pendingSyncCount = products.count { it.syncStatusEnum != SyncStatus.SYNCED },
            channelBreakdown = channelMap.take(5),
            topProducts = topSkuMap.toList().sortedByDescending { it.second }.take(5)
        )
    }

    private fun unwrapFirestore(error: Throwable): FirebaseFirestoreException? {
        val fs = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (fs == null && error is CancellationException) throw error
        return fs
    }

    private fun <T> toErrorState(error: Throwable, fallback: String): UiState<T> {
        val root = unwrapFirestore(error) ?: error
        Log.w(TAG, fallback, root)
        return UiState.Error(root.message ?: fallback)
    }

    private fun todayIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private companion object {
        const val TAG = "GudangSyncVM"
    }
}
