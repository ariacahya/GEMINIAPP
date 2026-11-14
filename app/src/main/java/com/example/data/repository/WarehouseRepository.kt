package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AlertNotification
import com.example.data.model.AlertType
import com.example.data.model.OrderInstruction
import com.example.data.model.OrderLineItem
import com.example.data.model.OrderStatus
import com.example.data.model.StockOpnameRecord
import com.example.data.model.SyncLogEntry
import com.example.data.model.SyncStatus
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.WarehouseProduct
import com.example.data.model.Workspace
import com.example.data.remote.JubelioRemoteOrder
import com.example.data.remote.JubelioRemoteProduct
import com.example.data.remote.OperationType
import com.example.data.remote.SyncConflictPolicy
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class WarehouseRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Pengguna harus login dengan Google terlebih dahulu.")
    }

    private fun newSafeId(prefix: String): String {
        val raw = UUID.randomUUID().toString().replace("-", "").take(16)
        return "${prefix}_$raw"
    }

    private fun formatShortTime(): String =
        SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID")).format(Date())

    suspend fun ensureUserAndWorkspace(
        displayName: String?,
        email: String?
    ): Result<UserProfile> = runCatching {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val existingSnap = userRef.get().await()
        if (existingSnap.exists()) {
            val profile = existingSnap.toObject(
                UserProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            if (profile != null && profile.activeWorkspaceId.isNotBlank()) {
                return@runCatching profile
            }
        }

        val workspaceId = "ws_${uid.replace(Regex("[^a-zA-Z0-9_-]"), "").take(20)}"
        val inviteCode = "GS-${uid.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(6)}"
        val safeName = displayName?.takeIf { it.isNotBlank() }?.take(80) ?: "Tim Gudang"

        val workspaceRef = db.collection("workspaces").document(workspaceId)
        val existingWs = runCatching { workspaceRef.get().await() }.getOrNull()
        if (existingWs == null || !existingWs.exists()) {
            val wsPayload = mapOf(
                "name" to "Gudang Utama $safeName".take(80),
                "inviteCode" to inviteCode,
                "ownerId" to uid,
                "memberUids" to listOf(uid),
                "adminUids" to listOf(uid),
                "jubelioConnected" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            workspaceRef.set(wsPayload).await()

            val invitePayload = mapOf(
                "inviteCode" to inviteCode,
                "workspaceId" to workspaceId,
                "workspaceName" to "Gudang Utama $safeName".take(80),
                "ownerId" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("workspace_invites").document(inviteCode).set(invitePayload).await()
        }

        val userPayload = mapOf(
            "userId" to uid,
            "displayName" to safeName,
            "email" to email?.take(120),
            "activeRole" to UserRole.ADMIN.name,
            "activeWorkspaceId" to workspaceId,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }
        userRef.set(userPayload).await()

        UserProfile(
            id = uid,
            userId = uid,
            displayName = safeName,
            email = email,
            activeRole = UserRole.ADMIN.name,
            activeWorkspaceId = workspaceId
        )
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.WRITE, "users/ensure")
    }

    suspend fun updateActiveRole(role: UserRole): Result<Unit> = runCatching {
        val uid = requireUserId()
        val path = "users/$uid"
        db.collection("users").document(uid)
            .update(
                mapOf(
                    "activeRole" to role.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "users/role")
    }

    suspend fun joinWorkspaceByInviteCode(rawInviteCode: String): Result<String> = runCatching {
        val uid = requireUserId()
        val code = rawInviteCode.trim().uppercase(Locale.US)
        require(code.matches(Regex("^[A-Za-z0-9_\\-]{4,24}$"))) {
            "Format kode undangan tidak valid."
        }
        val inviteDoc = db.collection("workspace_invites").document(code).get().await()
        require(inviteDoc.exists()) { "Kode undangan gudang tidak ditemukan." }
        val targetWorkspaceId = inviteDoc.getString("workspaceId")
            ?: throw IllegalStateException("Data undangan tidak lengkap.")

        val wsRef = db.collection("workspaces").document(targetWorkspaceId)
        val alreadyMember = runCatching { wsRef.get().await() }.getOrNull()?.exists() == true
        if (!alreadyMember) {
            wsRef.update(
                mapOf(
                    "memberUids" to FieldValue.arrayUnion(uid),
                    "lastJoinCode" to code,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }

        db.collection("users").document(uid).update(
            mapOf(
                "activeWorkspaceId" to targetWorkspaceId,
                "activeRole" to UserRole.GUDANG.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        targetWorkspaceId
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "workspace_invites/join")
    }

    suspend fun propagateMembersToWorkspaceDocs(workspace: Workspace): Result<Unit> = runCatching {
        val uid = requireUserId()
        val wsId = workspace.id
        if (wsId.isBlank() || workspace.memberUids.isEmpty()) return@runCatching
        val subcollections = listOf("products", "opnames", "orders", "alerts", "syncLogs")
        val batch = db.batch()
        var updates = 0
        for (col in subcollections) {
            val snap = db.collection("workspaces").document(wsId).collection(col)
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
            for (doc in snap.documents) {
                @Suppress("UNCHECKED_CAST")
                val existingMembers = doc.get("memberUids") as? List<String> ?: emptyList()
                if (!existingMembers.containsAll(workspace.memberUids)) {
                    batch.update(
                        doc.reference,
                        mapOf(
                            "memberUids" to workspace.memberUids,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    updates++
                }
            }
        }
        if (updates > 0) {
            batch.commit().await()
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "workspaces/${workspace.id}/propagate")
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val docRef = db.collection("users").document(userId)
        emitAll(
            docRef.snapshots().map { snapshot ->
                snapshot.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
        )
    }

    fun observeWorkspace(workspaceId: String): Flow<Workspace?> = flow {
        val docRef = db.collection("workspaces").document(workspaceId)
        emitAll(
            docRef.snapshots().map { snapshot ->
                snapshot.toObject(
                    Workspace::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
        )
    }

    fun observeProducts(workspaceId: String, userId: String): Flow<List<WarehouseProduct>> = flow {
        emitAll(
            db.collection("workspaces").document(workspaceId).collection("products")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        WarehouseProduct::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(compareBy<WarehouseProduct> { it.rackLocation }.thenBy { it.name })
                }
        )
    }

    fun observeOpnames(workspaceId: String, userId: String): Flow<List<StockOpnameRecord>> = flow {
        emitAll(
            db.collection("workspaces").document(workspaceId).collection("opnames")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        StockOpnameRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
        )
    }

    fun observeOrders(workspaceId: String, userId: String): Flow<List<OrderInstruction>> = flow {
        emitAll(
            db.collection("workspaces").document(workspaceId).collection("orders")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        OrderInstruction::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L }
                }
        )
    }

    fun observeAlerts(workspaceId: String, userId: String): Flow<List<AlertNotification>> = flow {
        emitAll(
            db.collection("workspaces").document(workspaceId).collection("alerts")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        AlertNotification::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
        )
    }

    fun observeSyncLogs(workspaceId: String, userId: String): Flow<List<SyncLogEntry>> = flow {
        emitAll(
            db.collection("workspaces").document(workspaceId).collection("syncLogs")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        SyncLogEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
        )
    }

    suspend fun addOrUpdateProduct(
        workspace: Workspace,
        existingProductId: String?,
        sku: String,
        name: String,
        category: String,
        rackLocation: String,
        systemStock: Long,
        minThreshold: Long,
        unitPrice: Double,
        jubelioSynced: Boolean
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val wsId = workspace.id
        val members = workspace.memberUids.ifEmpty { listOf(uid) }
        val productId = existingProductId?.takeIf { it.isNotBlank() } ?: newSafeId("prd")
        val docRef = db.collection("workspaces").document(wsId)
            .collection("products").document(productId)

        val syncStatus = if (jubelioSynced) SyncStatus.SYNCED.name else SyncStatus.PENDING_PUSH.name
        if (existingProductId.isNullOrBlank()) {
            val payload = mapOf(
                "workspaceId" to wsId,
                "sku" to sku.trim().uppercase(Locale.US).take(64),
                "name" to name.trim().take(140),
                "category" to category.trim().ifBlank { "Umum" }.take(60),
                "rackLocation" to rackLocation.trim().uppercase(Locale.US).take(40),
                "systemStock" to systemStock.coerceAtLeast(0L),
                "reservedStock" to 0L,
                "minThreshold" to minThreshold.coerceAtLeast(0L),
                "unitPrice" to unitPrice.coerceAtLeast(0.0),
                "syncStatus" to syncStatus,
                "memberUids" to members,
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        } else {
            val updatePayload = mapOf(
                "sku" to sku.trim().uppercase(Locale.US).take(64),
                "name" to name.trim().take(140),
                "category" to category.trim().ifBlank { "Umum" }.take(60),
                "rackLocation" to rackLocation.trim().uppercase(Locale.US).take(40),
                "systemStock" to systemStock.coerceAtLeast(0L),
                "minThreshold" to minThreshold.coerceAtLeast(0L),
                "unitPrice" to unitPrice.coerceAtLeast(0.0),
                "syncStatus" to syncStatus,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updatePayload).await()
        }

        if (systemStock <= minThreshold) {
            createLowStockAlert(
                workspaceId = wsId,
                members = members,
                uid = uid,
                productName = name.trim(),
                sku = sku.trim().uppercase(Locale.US),
                rack = rackLocation.trim().uppercase(Locale.US),
                remaining = systemStock,
                threshold = minThreshold,
                productId = productId
            )
        }
        productId
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.WRITE, "workspaces/${workspace.id}/products")
    }

    suspend fun adjustProductStock(
        workspace: Workspace,
        product: WarehouseProduct,
        delta: Long,
        jubelioSynced: Boolean
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val newStock = (product.systemStock + delta).coerceAtLeast(0L)
        val docRef = db.collection("workspaces").document(workspace.id)
            .collection("products").document(product.id)

        val updates = mutableMapOf<String, Any>(
            "systemStock" to newStock,
            "syncStatus" to if (jubelioSynced) SyncStatus.SYNCED.name else SyncStatus.PENDING_PUSH.name,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (jubelioSynced) {
            updates["lastJubelioSyncAt"] = FieldValue.serverTimestamp()
        }
        docRef.update(updates).await()

        val availableAfter = (newStock - product.reservedStock).coerceAtLeast(0L)
        if (availableAfter <= product.minThreshold) {
            createLowStockAlert(
                workspaceId = workspace.id,
                members = workspace.memberUids.ifEmpty { listOf(uid) },
                uid = uid,
                productName = product.name,
                sku = product.sku,
                rack = product.rackLocation,
                remaining = availableAfter,
                threshold = product.minThreshold,
                productId = product.id
            )
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "workspaces/${workspace.id}/products/${product.id}")
    }

    suspend fun submitRackOpname(
        workspace: Workspace,
        rackLocation: String,
        physicalCounts: Map<String, Long>,
        productsInRack: List<WarehouseProduct>,
        notes: String,
        performedByName: String,
        syncedToJubelio: Boolean
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val wsId = workspace.id
        val members = workspace.memberUids.ifEmpty { listOf(uid) }
        val opnameId = newSafeId("opn")
        val batch = db.batch()

        var totalVariance = 0L
        val summaries = mutableListOf<String>()
        val lowStockTriggered = mutableListOf<Pair<WarehouseProduct, Long>>()

        for (product in productsInRack) {
            val physical = (physicalCounts[product.id] ?: product.systemStock).coerceAtLeast(0L)
            val variance = physical - product.systemStock
            totalVariance += variance
            val sign = if (variance > 0) "+$variance" else "$variance"
            summaries.add("${product.sku}: Sistem ${product.systemStock} -> Fisik $physical ($sign)")

            val prodRef = db.collection("workspaces").document(wsId)
                .collection("products").document(product.id)
            val prodUpdates = mutableMapOf<String, Any>(
                "systemStock" to physical,
                "syncStatus" to if (syncedToJubelio) SyncStatus.SYNCED.name else SyncStatus.PENDING_PUSH.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (syncedToJubelio) {
                prodUpdates["lastJubelioSyncAt"] = FieldValue.serverTimestamp()
            }
            batch.update(prodRef, prodUpdates)

            val availableAfter = (physical - product.reservedStock).coerceAtLeast(0L)
            if (availableAfter <= product.minThreshold) {
                lowStockTriggered.add(product to availableAfter)
            }
        }

        val opnameRef = db.collection("workspaces").document(wsId)
            .collection("opnames").document(opnameId)
        val opnamePayload = mapOf(
            "workspaceId" to wsId,
            "rackLocation" to rackLocation.take(40),
            "performedByUid" to uid,
            "performedByName" to performedByName.ifBlank { "Staf Gudang" }.take(80),
            "totalItemsCounted" to productsInRack.size.toLong(),
            "totalVariance" to totalVariance,
            "itemSummaries" to summaries.take(50),
            "notes" to notes.trim().take(400).ifBlank { null },
            "syncedToJubelio" to syncedToJubelio,
            "memberUids" to members,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }
        batch.set(opnameRef, opnamePayload)
        batch.commit().await()

        for ((prod, remaining) in lowStockTriggered) {
            createLowStockAlert(
                workspaceId = wsId,
                members = members,
                uid = uid,
                productName = prod.name,
                sku = prod.sku,
                rack = prod.rackLocation,
                remaining = remaining,
                threshold = prod.minThreshold,
                productId = prod.id
            )
        }
        opnameId
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.WRITE, "workspaces/${workspace.id}/opnames")
    }

    suspend fun createOrder(
        workspace: Workspace,
        orderNumber: String,
        customerName: String,
        channel: String,
        items: List<OrderLineItem>,
        adminInstruction: String,
        dispatchImmediately: Boolean,
        allProducts: List<WarehouseProduct>
    ): Result<String> = runCatching {
        val uid = requireUserId()
        require(items.isNotEmpty()) { "Order minimal memiliki 1 produk." }
        val wsId = workspace.id
        val members = workspace.memberUids.ifEmpty { listOf(uid) }
        val orderId = newSafeId("ord")
        val initialStatus = if (dispatchImmediately) OrderStatus.SIAP_DIKEMAS else OrderStatus.MENUNGGU_ARAHAN
        val total = items.sumOf { it.unitPrice * it.qty }
        val timeLabel = formatShortTime()
        val timeline = buildList {
            add("$timeLabel — Order dibuat ($channel)")
            if (dispatchImmediately) {
                add("$timeLabel — Admin mengirim arahan ke Gudang")
            }
        }

        val batch = db.batch()
        val orderRef = db.collection("workspaces").document(wsId).collection("orders").document(orderId)
        val payload = mapOf(
            "workspaceId" to wsId,
            "orderNumber" to orderNumber.trim().take(64),
            "customerName" to customerName.trim().take(100),
            "channel" to channel.trim().take(50),
            "items" to items.take(30).map { it.toFirestoreMap() },
            "adminInstruction" to adminInstruction.trim().take(500),
            "status" to initialStatus.name,
            "courier" to "",
            "trackingNumber" to "",
            "totalAmount" to total,
            "timelineEvents" to timeline,
            "assignedByUid" to uid,
            "memberUids" to members,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(orderRef, payload)

        // Reserve stock for ordered SKUs
        for (line in items) {
            val match = allProducts.firstOrNull { it.sku.equals(line.sku, ignoreCase = true) }
            if (match != null) {
                val newReserved = match.reservedStock + line.qty
                val prodRef = db.collection("workspaces").document(wsId)
                    .collection("products").document(match.id)
                batch.update(
                    prodRef,
                    mapOf(
                        "reservedStock" to newReserved,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }
        batch.commit().await()

        if (dispatchImmediately) {
            createOrderInstructionAlert(
                workspaceId = wsId,
                members = members,
                uid = uid,
                orderNumber = orderNumber,
                instruction = adminInstruction,
                orderId = orderId
            )
        }
        orderId
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.CREATE, "workspaces/${workspace.id}/orders")
    }

    suspend fun dispatchOrderInstruction(
        workspace: Workspace,
        order: OrderInstruction,
        instruction: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val wsId = workspace.id
        val members = workspace.memberUids.ifEmpty { listOf(uid) }
        val updatedTimeline = (order.timelineEvents + "${formatShortTime()} — Arahan Admin dikirim: ${instruction.take(80)}").takeLast(20)
        db.collection("workspaces").document(wsId)
            .collection("orders").document(order.id)
            .update(
                mapOf(
                    "adminInstruction" to instruction.trim().take(500),
                    "status" to OrderStatus.SIAP_DIKEMAS.name,
                    "timelineEvents" to updatedTimeline,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

        createOrderInstructionAlert(
            workspaceId = wsId,
            members = members,
            uid = uid,
            orderNumber = order.orderNumber,
            instruction = instruction,
            orderId = order.id
        )
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "workspaces/${workspace.id}/orders/${order.id}")
    }

    suspend fun toggleOrderItemPicked(
        workspace: Workspace,
        order: OrderInstruction,
        itemIndex: Int
    ): Result<Unit> = runCatching {
        requireUserId()
        val updatedItems = order.items.mapIndexed { idx, item ->
            if (idx == itemIndex) item.copy(picked = !item.picked) else item
        }
        db.collection("workspaces").document(workspace.id)
            .collection("orders").document(order.id)
            .update(
                mapOf(
                    "items" to updatedItems.map { it.toFirestoreMap() },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "workspaces/${workspace.id}/orders/${order.id}")
    }

    suspend fun advanceOrderStatus(
        workspace: Workspace,
        order: OrderInstruction,
        nextStatus: OrderStatus,
        courier: String,
        trackingNumber: String,
        allProducts: List<WarehouseProduct>,
        jubelioSynced: Boolean
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val wsId = workspace.id
        val members = workspace.memberUids.ifEmpty { listOf(uid) }
        val detailSuffix = if (nextStatus == OrderStatus.DIKIRIM && trackingNumber.isNotBlank()) {
            " ($courier • Resi: $trackingNumber)"
        } else ""
        val timelineEntry = "${formatShortTime()} — Status: ${nextStatus.label}$detailSuffix"
        val updatedTimeline = (order.timelineEvents + timelineEntry).takeLast(20)

        val batch = db.batch()
        val orderRef = db.collection("workspaces").document(wsId).collection("orders").document(order.id)
        val orderUpdates = mutableMapOf<String, Any>(
            "status" to nextStatus.name,
            "timelineEvents" to updatedTimeline,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (courier.isNotBlank()) orderUpdates["courier"] = courier.trim().take(60)
        if (trackingNumber.isNotBlank()) orderUpdates["trackingNumber"] = trackingNumber.trim().take(80)
        batch.update(orderRef, orderUpdates)

        val lowStockTriggered = mutableListOf<Pair<WarehouseProduct, Long>>()

        // Deduct physical & reserved stock when order transitions to DIKIRIM from an unshipped status
        val wasShippedBefore = order.statusEnum.stepIndex >= OrderStatus.DIKIRIM.stepIndex
        if (!wasShippedBefore && nextStatus.stepIndex >= OrderStatus.DIKIRIM.stepIndex) {
            for (line in order.items) {
                val prod = allProducts.firstOrNull { it.sku.equals(line.sku, ignoreCase = true) }
                if (prod != null) {
                    val newSystem = (prod.systemStock - line.qty).coerceAtLeast(0L)
                    val newReserved = (prod.reservedStock - line.qty).coerceAtLeast(0L)
                    val prodRef = db.collection("workspaces").document(wsId)
                        .collection("products").document(prod.id)
                    batch.update(
                        prodRef,
                        mapOf(
                            "systemStock" to newSystem,
                            "reservedStock" to newReserved,
                            "syncStatus" to if (jubelioSynced) SyncStatus.SYNCED.name else SyncStatus.PENDING_PUSH.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    val availableAfter = (newSystem - newReserved).coerceAtLeast(0L)
                    if (availableAfter <= prod.minThreshold) {
                        lowStockTriggered.add(prod to availableAfter)
                    }
                }
            }
        }
        batch.commit().await()

        for ((prod, remaining) in lowStockTriggered) {
            createLowStockAlert(
                workspaceId = wsId,
                members = members,
                uid = uid,
                productName = prod.name,
                sku = prod.sku,
                rack = prod.rackLocation,
                remaining = remaining,
                threshold = prod.minThreshold,
                productId = prod.id
            )
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "workspaces/${workspace.id}/orders/${order.id}")
    }

    suspend fun applyJubelioSyncSnapshot(
        workspace: Workspace,
        remoteProducts: List<JubelioRemoteProduct>,
        remoteOrders: List<JubelioRemoteOrder>,
        localProducts: List<WarehouseProduct>,
        localOrders: List<OrderInstruction>,
        policy: SyncConflictPolicy
    ): Result<Pair<Int, Int>> = runCatching {
        val uid = requireUserId()
        val wsId = workspace.id
        val members = workspace.memberUids.ifEmpty { listOf(uid) }
        val batch = db.batch()
        var syncedCount = 0
        var discrepancyCount = 0

        for (remote in remoteProducts) {
            val local = localProducts.firstOrNull {
                it.sku.equals(remote.sku, ignoreCase = true) ||
                    (it.jubelioItemId != null && it.jubelioItemId == remote.itemId)
            }
            if (local == null) {
                val prodId = newSafeId("prd")
                val ref = db.collection("workspaces").document(wsId).collection("products").document(prodId)
                batch.set(
                    ref,
                    mapOf(
                        "workspaceId" to wsId,
                        "sku" to remote.sku.take(64),
                        "jubelioItemId" to remote.itemId,
                        "name" to remote.name.take(140),
                        "category" to remote.category.take(60),
                        "rackLocation" to "RAK-JBL",
                        "systemStock" to remote.availableQty.coerceAtLeast(0L),
                        "reservedStock" to remote.reservedQty.coerceAtLeast(0L),
                        "minThreshold" to 5L,
                        "unitPrice" to remote.unitPrice.coerceAtLeast(0.0),
                        "syncStatus" to SyncStatus.SYNCED.name,
                        "lastJubelioSyncAt" to FieldValue.serverTimestamp(),
                        "memberUids" to members,
                        "createdByUid" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                syncedCount++
            } else {
                val hasDiff = local.systemStock != remote.availableQty
                if (hasDiff) discrepancyCount++
                val resolvedStock = when (policy) {
                    SyncConflictPolicy.JUBELIO_PRIORITY -> remote.availableQty.coerceAtLeast(0L)
                    SyncConflictPolicy.WAREHOUSE_PRIORITY -> local.systemStock
                }
                val ref = db.collection("workspaces").document(wsId).collection("products").document(local.id)
                batch.update(
                    ref,
                    mapOf(
                        "jubelioItemId" to remote.itemId,
                        "systemStock" to resolvedStock,
                        "syncStatus" to SyncStatus.SYNCED.name,
                        "lastJubelioSyncAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                syncedCount++
            }
        }

        for (remoteOrder in remoteOrders) {
            val existing = localOrders.firstOrNull {
                it.orderNumber.equals(remoteOrder.orderNumber, ignoreCase = true) ||
                    (it.jubelioOrderId != null && it.jubelioOrderId == remoteOrder.orderId)
            }
            if (existing == null && remoteOrder.items.isNotEmpty()) {
                val orderId = newSafeId("ord")
                val ref = db.collection("workspaces").document(wsId).collection("orders").document(orderId)
                val lineItems = remoteOrder.items.take(30).map { item ->
                    val matchedRack = localProducts.firstOrNull { it.sku.equals(item.sku, ignoreCase = true) }?.rackLocation ?: "RAK-A1"
                    OrderLineItem(
                        sku = item.sku,
                        name = item.name,
                        rackLocation = matchedRack,
                        qty = item.qty,
                        unitPrice = item.unitPrice,
                        picked = false
                    ).toFirestoreMap()
                }
                batch.set(
                    ref,
                    mapOf(
                        "workspaceId" to wsId,
                        "orderNumber" to remoteOrder.orderNumber.take(64),
                        "jubelioOrderId" to remoteOrder.orderId,
                        "customerName" to remoteOrder.customerName.take(100),
                        "channel" to remoteOrder.channel.take(50),
                        "items" to lineItems,
                        "adminInstruction" to "Order masuk dari Jubelio — tunggu konfirmasi Admin",
                        "status" to OrderStatus.MENUNGGU_ARAHAN.name,
                        "courier" to "",
                        "trackingNumber" to "",
                        "totalAmount" to remoteOrder.totalAmount.coerceAtLeast(0.0),
                        "timelineEvents" to listOf("${formatShortTime()} — Ditarik otomatis dari Jubelio"),
                        "assignedByUid" to uid,
                        "memberUids" to members,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                syncedCount++
            }
        }

        val logRef = db.collection("workspaces").document(wsId).collection("syncLogs").document(newSafeId("log"))
        batch.set(
            logRef,
            mapOf(
                "workspaceId" to wsId,
                "direction" to "TWO_WAY",
                "status" to if (discrepancyCount > 0) "PARTIAL" else "SUCCESS",
                "summary" to "Sinkronisasi Jubelio selesai: $syncedCount item/order diproses, $discrepancyCount selisih stok diselaraskan.",
                "itemsSynced" to syncedCount.toLong(),
                "discrepanciesFound" to discrepancyCount.toLong(),
                "performedByUid" to uid,
                "memberUids" to members,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        syncedCount to discrepancyCount
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.WRITE, "workspaces/${workspace.id}/sync")
    }

    suspend fun updateWorkspaceJubelioStatus(
        workspaceId: String,
        jubelioEmail: String?,
        connected: Boolean
    ): Result<Unit> = runCatching {
        requireUserId()
        val updates = mutableMapOf<String, Any>(
            "jubelioConnected" to connected,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!jubelioEmail.isNullOrBlank()) {
            updates["jubelioEmail"] = jubelioEmail.trim().take(120)
        }
        if (connected) {
            updates["lastJubelioSyncAt"] = FieldValue.serverTimestamp()
        }
        db.collection("workspaces").document(workspaceId).update(updates).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "workspaces/$workspaceId")
    }

    suspend fun recordSyncLog(
        workspace: Workspace,
        direction: String,
        status: String,
        summary: String,
        itemsSynced: Long,
        discrepanciesFound: Long
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val members = workspace.memberUids.ifEmpty { listOf(uid) }
        val logRef = db.collection("workspaces").document(workspace.id)
            .collection("syncLogs").document(newSafeId("log"))
        logRef.set(
            mapOf(
                "workspaceId" to workspace.id,
                "direction" to direction,
                "status" to status,
                "summary" to summary.take(400),
                "itemsSynced" to itemsSynced.coerceAtLeast(0L),
                "discrepanciesFound" to discrepanciesFound.coerceAtLeast(0L),
                "performedByUid" to uid,
                "memberUids" to members,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.CREATE, "workspaces/${workspace.id}/syncLogs")
    }

    suspend fun markAlertRead(workspaceId: String, alertId: String): Result<Unit> = runCatching {
        requireUserId()
        db.collection("workspaces").document(workspaceId)
            .collection("alerts").document(alertId)
            .update(
                mapOf(
                    "isRead" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "workspaces/$workspaceId/alerts/$alertId")
    }

    private suspend fun createLowStockAlert(
        workspaceId: String,
        members: List<String>,
        uid: String,
        productName: String,
        sku: String,
        rack: String,
        remaining: Long,
        threshold: Long,
        productId: String
    ) {
        val alertRef = db.collection("workspaces").document(workspaceId)
            .collection("alerts").document(newSafeId("alt"))
        alertRef.set(
            mapOf(
                "workspaceId" to workspaceId,
                "targetRole" to UserRole.ADMIN.name,
                "type" to AlertType.LOW_STOCK.name,
                "title" to "Stok Minimum Tercapai: $sku".take(120),
                "message" to "$productName di $rack tersisa $remaining unit (ambang minimum $threshold). Segera restok & cek sinkronisasi Jubelio.".take(400),
                "referenceId" to productId,
                "isRead" to false,
                "createdByUid" to uid,
                "memberUids" to members,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private suspend fun createOrderInstructionAlert(
        workspaceId: String,
        members: List<String>,
        uid: String,
        orderNumber: String,
        instruction: String,
        orderId: String
    ) {
        val alertRef = db.collection("workspaces").document(workspaceId)
            .collection("alerts").document(newSafeId("alt"))
        alertRef.set(
            mapOf(
                "workspaceId" to workspaceId,
                "targetRole" to UserRole.GUDANG.name,
                "type" to AlertType.NEW_ORDER_INSTRUCTION.name,
                "title" to "Arahan Order Baru: $orderNumber".take(120),
                "message" to instruction.ifBlank { "Admin mengirim pesanan baru untuk segera disiapkan dan dikemas." }.take(400),
                "referenceId" to orderId,
                "isRead" to false,
                "createdByUid" to uid,
                "memberUids" to members,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }
}
