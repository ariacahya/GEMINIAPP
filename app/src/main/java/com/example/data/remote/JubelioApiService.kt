package com.example.data.remote

import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class JubelioLoginRequest(
    val email: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class JubelioStockAdjustmentItem(
    val item_id: Long,
    val sku: String,
    val qty: Long,
    val note: String
)

@JsonClass(generateAdapter = true)
data class JubelioAdjustmentRequest(
    val transaction_date: String,
    val location_id: Long = -1L,
    val note: String,
    val items: List<JubelioStockAdjustmentItem>
)

@JsonClass(generateAdapter = true)
data class JubelioFulfillmentRequest(
    val salesorder_id: Long,
    val status: String,
    val courier: String = "",
    val tracking_number: String = ""
)

data class JubelioRemoteProduct(
    val itemId: Long,
    val sku: String,
    val name: String,
    val category: String,
    val availableQty: Long,
    val reservedQty: Long,
    val unitPrice: Double
)

data class JubelioRemoteOrderItem(
    val sku: String,
    val name: String,
    val qty: Long,
    val unitPrice: Double
)

data class JubelioRemoteOrder(
    val orderId: Long,
    val orderNumber: String,
    val customerName: String,
    val channel: String,
    val totalAmount: Double,
    val status: String,
    val items: List<JubelioRemoteOrderItem>
)

interface JubelioRetrofitApi {
    @POST("login")
    suspend fun login(@Body request: JubelioLoginRequest): Response<ResponseBody>

    @GET("inventory/items")
    suspend fun getInventoryItems(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50
    ): Response<ResponseBody>

    @POST("inventory/adjustments")
    suspend fun postStockAdjustment(
        @Header("Authorization") authorization: String,
        @Body request: JubelioAdjustmentRequest
    ): Response<ResponseBody>

    @GET("sales/orders")
    suspend fun getSalesOrders(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50
    ): Response<ResponseBody>

    @POST("sales/orders/fulfill")
    suspend fun updateOrderFulfillment(
        @Header("Authorization") authorization: String,
        @Body request: JubelioFulfillmentRequest
    ): Response<ResponseBody>
}

class JubelioApiClient {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    private val api: JubelioRetrofitApi = Retrofit.Builder()
        .baseUrl("https://api2.jubelio.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(JubelioRetrofitApi::class.java)

    suspend fun login(email: String, password: String): Result<String> = runCatching {
        val response = api.login(JubelioLoginRequest(email.trim(), password))
        val rawBody = response.body()?.string().orEmpty().ifBlank {
            response.errorBody()?.string().orEmpty()
        }
        if (!response.isSuccessful) {
            val message = parseErrorMessage(rawBody) ?: "Login Jubelio gagal (HTTP ${response.code()})"
            throw IllegalStateException(message)
        }
        val json = JSONObject(rawBody)
        val token = json.optString("token")
            .ifBlank { json.optString("access_token") }
            .ifBlank { json.optJSONObject("data")?.optString("token").orEmpty() }
        require(token.isNotBlank()) { "Respons login Jubelio tidak menyertakan token." }
        token
    }

    suspend fun fetchInventory(token: String): Result<List<JubelioRemoteProduct>> = runCatching {
        val response = api.getInventoryItems(bearer(token))
        val rawBody = response.body()?.string().orEmpty().ifBlank {
            response.errorBody()?.string().orEmpty()
        }
        if (response.code() == 401) {
            throw JubelioTokenExpiredException("Token sesi Jubelio kedaluwarsa. Silakan login ulang.")
        }
        if (!response.isSuccessful) {
            val message = parseErrorMessage(rawBody) ?: "Gagal mengambil stok Jubelio (HTTP ${response.code()})"
            throw IllegalStateException(message)
        }
        parseProducts(rawBody)
    }

    suspend fun fetchSalesOrders(token: String): Result<List<JubelioRemoteOrder>> = runCatching {
        val response = api.getSalesOrders(bearer(token))
        val rawBody = response.body()?.string().orEmpty().ifBlank {
            response.errorBody()?.string().orEmpty()
        }
        if (response.code() == 401) {
            throw JubelioTokenExpiredException("Token sesi Jubelio kedaluwarsa. Silakan login ulang.")
        }
        if (!response.isSuccessful) {
            val message = parseErrorMessage(rawBody) ?: "Gagal mengambil order Jubelio (HTTP ${response.code()})"
            throw IllegalStateException(message)
        }
        parseOrders(rawBody)
    }

    suspend fun pushStockAdjustment(
        token: String,
        dateIso: String,
        note: String,
        items: List<JubelioStockAdjustmentItem>
    ): Result<Unit> = runCatching {
        if (items.isEmpty()) return@runCatching
        val response = api.postStockAdjustment(
            authorization = bearer(token),
            request = JubelioAdjustmentRequest(
                transaction_date = dateIso,
                note = note,
                items = items
            )
        )
        if (response.code() == 401) {
            throw JubelioTokenExpiredException("Token sesi Jubelio kedaluwarsa. Silakan login ulang.")
        }
        if (!response.isSuccessful) {
            val rawBody = response.errorBody()?.string().orEmpty()
            val message = parseErrorMessage(rawBody) ?: "Gagal kirim penyesuaian stok ke Jubelio (HTTP ${response.code()})"
            throw IllegalStateException(message)
        }
    }

    suspend fun pushOrderFulfillment(
        token: String,
        jubelioOrderId: Long,
        status: String,
        courier: String,
        trackingNumber: String
    ): Result<Unit> = runCatching {
        val response = api.updateOrderFulfillment(
            authorization = bearer(token),
            request = JubelioFulfillmentRequest(
                salesorder_id = jubelioOrderId,
                status = status,
                courier = courier,
                tracking_number = trackingNumber
            )
        )
        if (response.code() == 401) {
            throw JubelioTokenExpiredException("Token sesi Jubelio kedaluwarsa. Silakan login ulang.")
        }
        if (!response.isSuccessful) {
            val rawBody = response.errorBody()?.string().orEmpty()
            val message = parseErrorMessage(rawBody) ?: "Gagal update status order Jubelio (HTTP ${response.code()})"
            throw IllegalStateException(message)
        }
    }

    private fun bearer(token: String): String =
        if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"

    private fun parseErrorMessage(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            obj.optString("message").ifBlank { obj.optString("error") }.ifBlank { null }
        }.getOrNull()
    }

    private fun extractArray(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val obj = JSONObject(trimmed)
        return obj.optJSONArray("data")
            ?: obj.optJSONArray("items")
            ?: obj.optJSONArray("rows")
            ?: JSONArray()
    }

    private fun parseProducts(raw: String): List<JubelioRemoteProduct> {
        val arr = extractArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sku = obj.optString("item_code")
                    .ifBlank { obj.optString("sku") }
                    .ifBlank { "JBL-${obj.optLong("item_id", i.toLong())}" }
                add(
                    JubelioRemoteProduct(
                        itemId = obj.optLong("item_id", obj.optLong("id", 0L)),
                        sku = sku,
                        name = obj.optString("item_name").ifBlank { obj.optString("name", sku) },
                        category = obj.optString("item_group_name").ifBlank { "Jubelio" },
                        availableQty = obj.optLong("available_qty", obj.optLong("qty_on_hand", 0L)),
                        reservedQty = obj.optLong("reserved_qty", 0L),
                        unitPrice = obj.optDouble("sell_price", obj.optDouble("price", 0.0))
                    )
                )
            }
        }
    }

    private fun parseOrders(raw: String): List<JubelioRemoteOrder> {
        val arr = extractArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val itemsJson = obj.optJSONArray("items") ?: JSONArray()
                val items = buildList {
                    for (j in 0 until itemsJson.length()) {
                        val itemObj = itemsJson.optJSONObject(j) ?: continue
                        add(
                            JubelioRemoteOrderItem(
                                sku = itemObj.optString("item_code").ifBlank { itemObj.optString("sku", "SKU") },
                                name = itemObj.optString("item_name").ifBlank { itemObj.optString("description", "Produk") },
                                qty = itemObj.optLong("qty_in_base", itemObj.optLong("qty", 1L)).coerceAtLeast(1L),
                                unitPrice = itemObj.optDouble("price", 0.0)
                            )
                        )
                    }
                }
                add(
                    JubelioRemoteOrder(
                        orderId = obj.optLong("salesorder_id", obj.optLong("id", 0L)),
                        orderNumber = obj.optString("salesorder_no").ifBlank { "JBL-ORD-${i + 1}" },
                        customerName = obj.optString("customer_name").ifBlank { "Pelanggan Jubelio" },
                        channel = obj.optString("source_name").ifBlank { obj.optString("channel", "Jubelio") },
                        totalAmount = obj.optDouble("grand_total", obj.optDouble("total", 0.0)),
                        status = obj.optString("status").ifBlank { "OPEN" },
                        items = items
                    )
                )
            }
        }
    }
}

class JubelioTokenExpiredException(message: String) : IllegalStateException(message)
