package com.example

import com.example.data.model.OrderInstruction
import com.example.data.model.OrderStatus
import com.example.data.model.WarehouseProduct
import com.example.data.remote.DEFAULT_JUBELIO_BASE_URL
import com.example.data.remote.normalizeJubelioBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarehouseModelUnitTest {

    @Test
    fun warehouseProduct_availableStockAndLowStockThreshold_computedCorrectly() {
        val safeProduct = WarehouseProduct(
            sku = "SKU-01",
            systemStock = 20L,
            reservedStock = 5L,
            minThreshold = 10L
        )
        assertEquals(15L, safeProduct.availableStock)
        assertFalse(safeProduct.isLowStock)

        val criticalProduct = WarehouseProduct(
            sku = "SKU-02",
            systemStock = 12L,
            reservedStock = 4L,
            minThreshold = 8L
        )
        assertEquals(8L, criticalProduct.availableStock)
        assertTrue(criticalProduct.isLowStock)
    }

    @Test
    fun orderInstruction_statusStepIndex_progressesMonotonically() {
        val order = OrderInstruction(status = OrderStatus.SIAP_DIKEMAS.name)
        assertEquals(OrderStatus.SIAP_DIKEMAS, order.statusEnum)
        assertEquals(1, order.statusEnum.stepIndex)
    }

    @Test
    fun normalizeJubelioBaseUrl_handlesBlankSchemeAndTrailingSlash() {
        assertEquals(DEFAULT_JUBELIO_BASE_URL, normalizeJubelioBaseUrl(""))
        assertEquals("https://api2.jubelio.com/", normalizeJubelioBaseUrl("api2.jubelio.com"))
        assertEquals("https://api.jubelio.com/", normalizeJubelioBaseUrl("https://api.jubelio.com"))
    }
}
