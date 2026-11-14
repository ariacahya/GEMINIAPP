package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.OrderLineItem
import com.example.data.model.OrderStatus
import com.example.data.model.UserRole
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class WarehouseRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureUserAndWorkspace_createsWorkspaceAndAllowsProductAndOpnameFlow() = runBlocking {
        val aliceUid = signInTestUser("alice_${UUID.randomUUID().toString().take(8)}@test.com")
        val repository = WarehouseRepository(firestore)

        val profileResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserAndWorkspace("Alice Admin", "alice@test.com")
        }
        assertTrue(profileResult.isSuccess)
        val profile = profileResult.getOrThrow()
        assertEquals(aliceUid, profile.userId)
        assertEquals(UserRole.ADMIN.name, profile.activeRole)

        val workspace = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeWorkspace(profile.activeWorkspaceId).first { it != null }
        }
        assertNotNull(workspace)
        val ws = workspace!!

        val productId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.addOrUpdateProduct(
                workspace = ws,
                existingProductId = null,
                sku = "SKU-${UUID.randomUUID().toString().take(4)}",
                name = "Kopi Arabika",
                category = "Minuman",
                rackLocation = "RAK-A1",
                systemStock = 12L,
                minThreshold = 15L,
                unitPrice = 65000.0,
                jubelioSynced = false
            ).getOrThrow()
        }
        assertTrue(productId.isNotBlank())

        val products = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeProducts(ws.id, aliceUid).first { list -> list.any { it.id == productId } }
        }
        val createdProduct = products.first { it.id == productId }

        // Rack opname + low stock alert verification
        val opnameId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.submitRackOpname(
                workspace = ws,
                rackLocation = "RAK-A1",
                physicalCounts = mapOf(productId to 4L),
                productsInRack = listOf(createdProduct),
                notes = "Opname pagi",
                performedByName = "Alice",
                syncedToJubelio = false
            ).getOrThrow()
        }
        assertTrue(opnameId.isNotBlank())

        val alerts = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeAlerts(ws.id, aliceUid).first { it.isNotEmpty() }
        }
        assertTrue(alerts.any { it.referenceId == productId })
    }

    @Test
    fun createAndAdvanceOrder_updatesTimelineAndStatus() = runBlocking {
        val aliceUid = signInTestUser("alice_ord_${UUID.randomUUID().toString().take(8)}@test.com")
        val repository = WarehouseRepository(firestore)
        val profile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserAndWorkspace("Alice", "alice@test.com").getOrThrow()
        }
        val ws = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeWorkspace(profile.activeWorkspaceId).first { it != null }!!
        }

        val orderId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createOrder(
                workspace = ws,
                orderNumber = "ORD-${UUID.randomUUID().toString().take(6)}",
                customerName = "Budi",
                channel = "Shopee",
                items = listOf(OrderLineItem(sku = "SKU-1", name = "Produk A", rackLocation = "RAK-A1", qty = 2L, unitPrice = 50000.0)),
                adminInstruction = "Packing bubble wrap",
                dispatchImmediately = true,
                allProducts = emptyList()
            ).getOrThrow()
        }

        val order = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeOrders(ws.id, aliceUid).first { list -> list.any { it.id == orderId } }
                .first { it.id == orderId }
        }
        assertEquals(OrderStatus.SIAP_DIKEMAS, order.statusEnum)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.advanceOrderStatus(
                workspace = ws,
                order = order,
                nextStatus = OrderStatus.DIKIRIM,
                courier = "JNE",
                trackingNumber = "JNE998877",
                allProducts = emptyList(),
                jubelioSynced = false
            ).getOrThrow()
        }

        val updatedOrder = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeOrders(ws.id, aliceUid).first { list ->
                list.any { it.id == orderId && it.status == OrderStatus.DIKIRIM.name }
            }.first { it.id == orderId }
        }
        assertEquals("JNE998877", updatedOrder.trackingNumber)
    }

    @Test
    fun crossUserAccess_bobCannotReadAliceProducts() = runBlocking {
        val aliceUid = signInTestUser("alice_iso_${UUID.randomUUID().toString().take(8)}@test.com")
        val aliceRepo = WarehouseRepository(firestore)
        val aliceProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureUserAndWorkspace("Alice", "alice@test.com").getOrThrow()
        }
        val aliceWs = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observeWorkspace(aliceProfile.activeWorkspaceId).first { it != null }!!
        }
        val prodId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.addOrUpdateProduct(
                workspace = aliceWs,
                existingProductId = null,
                sku = "SKU-SECRET",
                name = "Rahasia",
                category = "Umum",
                rackLocation = "RAK-Z9",
                systemStock = 5L,
                minThreshold = 2L,
                unitPrice = 10000.0,
                jubelioSynced = false
            ).getOrThrow()
        }

        // Bob signs in as a completely separate user
        signInTestUser("bob_iso_${UUID.randomUUID().toString().take(8)}@test.com")
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("workspaces").document(aliceWs.id)
                    .collection("products").document(prodId)
                    .get()
                    .await()
            }
            fail("Expected PERMISSION_DENIED when Bob reads Alice's product")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }

    @Test
    fun unauthenticatedObserveProducts_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = WarehouseRepository(firestore)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeProducts("ws_dummy", "uid_dummy").first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated listener")
        } catch (e: Exception) {
            val firestoreException = checkNotNull(e.unwrapFirestoreException()) {
                "Expected nested FirebaseFirestoreException, got $e"
            }
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
