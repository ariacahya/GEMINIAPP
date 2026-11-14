package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class UserRole(val label: String) {
    ADMIN("Admin"),
    GUDANG("Staf Gudang")
}

enum class SyncStatus(val label: String) {
    SYNCED("Tersinkron"),
    PENDING_PUSH("Antre Sync"),
    CONFLICT("Selisih"),
    LOCAL_ONLY("Lokal")
}

enum class OrderStatus(val label: String, val stepIndex: Int) {
    MENUNGGU_ARAHAN("Menunggu Arahan", 0),
    SIAP_DIKEMAS("Siap Dikemas", 1),
    DIKEMAS("Sedang Dikemas", 2),
    SIAP_KIRIM("Siap Kirim", 3),
    DIKIRIM("Dalam Pengiriman", 4),
    SELESAI("Diterima Pelanggan", 5)
}

enum class AlertType {
    LOW_STOCK,
    NEW_ORDER_INSTRUCTION,
    SYNC_ALERT
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val activeRole: String = UserRole.GUDANG.name,
    val activeWorkspaceId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val roleEnum: UserRole
        get() = runCatching { UserRole.valueOf(activeRole) }.getOrDefault(UserRole.GUDANG)
}

@IgnoreExtraProperties
data class Workspace(
    @DocumentId val id: String = "",
    val name: String = "",
    val inviteCode: String = "",
    val ownerId: String = "",
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val lastJoinCode: String? = null,
    val jubelioEmail: String? = null,
    val jubelioConnected: Boolean = false,
    val lastJubelioSyncAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class WarehouseProduct(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val sku: String = "",
    val jubelioItemId: Long? = null,
    val name: String = "",
    val category: String = "Umum",
    val rackLocation: String = "RAK-A1",
    val systemStock: Long = 0L,
    val reservedStock: Long = 0L,
    val minThreshold: Long = 5L,
    val unitPrice: Double = 0.0,
    val syncStatus: String = SyncStatus.LOCAL_ONLY.name,
    val lastJubelioSyncAt: Timestamp? = null,
    val memberUids: List<String> = emptyList(),
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val availableStock: Long
        get() = (systemStock - reservedStock).coerceAtLeast(0L)

    val isLowStock: Boolean
        get() = availableStock <= minThreshold

    val syncStatusEnum: SyncStatus
        get() = runCatching { SyncStatus.valueOf(syncStatus) }.getOrDefault(SyncStatus.LOCAL_ONLY)
}

@IgnoreExtraProperties
data class StockOpnameRecord(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val rackLocation: String = "",
    val performedByUid: String = "",
    val performedByName: String = "",
    val totalItemsCounted: Long = 0L,
    val totalVariance: Long = 0L,
    val itemSummaries: List<String> = emptyList(),
    val notes: String? = null,
    val syncedToJubelio: Boolean = false,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class OrderLineItem(
    val sku: String = "",
    val name: String = "",
    val rackLocation: String = "",
    val qty: Long = 1L,
    val unitPrice: Double = 0.0,
    val picked: Boolean = false
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "sku" to sku,
        "name" to name,
        "rackLocation" to rackLocation,
        "qty" to qty,
        "unitPrice" to unitPrice,
        "picked" to picked
    )
}

@IgnoreExtraProperties
data class OrderInstruction(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val orderNumber: String = "",
    val jubelioOrderId: Long? = null,
    val customerName: String = "",
    val channel: String = "Jubelio",
    val items: List<OrderLineItem> = emptyList(),
    val adminInstruction: String = "",
    val status: String = OrderStatus.MENUNGGU_ARAHAN.name,
    val courier: String = "",
    val trackingNumber: String = "",
    val totalAmount: Double = 0.0,
    val timelineEvents: List<String> = emptyList(),
    val assignedByUid: String = "",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val statusEnum: OrderStatus
        get() = runCatching { OrderStatus.valueOf(status) }.getOrDefault(OrderStatus.MENUNGGU_ARAHAN)
}

@IgnoreExtraProperties
data class AlertNotification(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val targetRole: String = "ALL",
    val type: String = AlertType.LOW_STOCK.name,
    val title: String = "",
    val message: String = "",
    val referenceId: String? = null,
    val isRead: Boolean = false,
    val createdByUid: String = "",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SyncLogEntry(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val direction: String = "TWO_WAY",
    val status: String = "SUCCESS",
    val summary: String = "",
    val itemsSynced: Long = 0L,
    val discrepanciesFound: Long = 0L,
    val performedByUid: String = "",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
