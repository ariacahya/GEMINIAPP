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
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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

data class JubelioLoginResult(
    val token: String,
    val resolvedBaseUrl: String
)

data class JubelioCallResult<T>(
    val data: T,
    val usedRawAuthHeader: Boolean
)

interface JubelioRetrofitApi {
    @Headers("Accept: application/json", "Content-Type: application/json")
    @POST
    suspend fun loginAt(
        @Url url: String,
        @Body request: JubelioLoginRequest
    ): Response<ResponseBody>

    @Headers("Accept: application/json")
    @GET
    suspend fun getAt(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50
    ): Response<ResponseBody>

    @Headers("Accept: application/json", "Content-Type: application/json")
    @POST("inventory/adjustments")
    suspend fun postStockAdjustment(
        @Header("Authorization") authorization: String,
        @Body request: JubelioAdjustmentRequest
    ): Response<ResponseBody>

    @Headers("Accept: application/json", "Content-Type: application/json")
    @POST("sales/orders/fulfill")
    suspend fun updateOrderFulfillment(
        @Header("Authorization") authorization: String,
        @Body request: JubelioFulfillmentRequest
    ): Response<ResponseBody>
}

class JubelioApiClient {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    private val apiCache = ConcurrentHashMap<String, JubelioRetrofitApi>()

    private fun apiFor(baseUrl: String): Pair<String, JubelioRetrofitApi> {
        val normalized = normalizeJubelioBaseUrl(baseUrl)
        val service = apiCache.getOrPut(normalized) {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(JubelioRetrofitApi::class.java)
        }
        return normalized to service
    }

    suspend fun login(
        email: String,
        password: String,
        baseUrl: String = DEFAULT_JUBELIO_BASE_URL
    ): Result<JubelioLoginResult> = runCatching {
        val (primaryBase, api) = apiFor(baseUrl)
        val candidateEndpoints = buildList {
            add("${primaryBase}login")
            add("${primaryBase}auth/login")
            if (primaryBase == DEFAULT_JUBELIO_BASE_URL) {
                add("${ALT_JUBELIO_BASE_URL}login")
            }
        }

        val req = JubelioLoginRequest(email.trim(), password)
        var lastError: Throwable? = null

        for (endpoint in candidateEndpoints) {
            val response = try {
                api.loginAt(endpoint, req)
            } catch (e: IOException) {
                lastError = IllegalStateException(mapNetworkError(e, endpoint), e)
                continue
            }

            val rawBody = response.body()?.string().orEmpty().ifBlank {
                response.errorBody()?.string().orEmpty()
            }

            if (response.isSuccessful) {
                val token = extractJubelioToken(
                    rawBody = rawBody,
                    headerAuth = response.headers()["Authorization"]
                )
                if (!token.isNullOrBlank()) {
                    val resolvedBase = if (endpoint.startsWith(ALT_JUBELIO_BASE_URL)) {
                        ALT_JUBELIO_BASE_URL
                    } else {
                        primaryBase
                    }
                    return@runCatching JubelioLoginResult(
                        token = token,
                        resolvedBaseUrl = resolvedBase
                    )
                }
                lastError = IllegalStateException(
                    "Server Jubelio merespons HTTP ${response.code()}, tetapi token tidak ditemukan pada respons."
                )
                continue
            }

            val serverMsg = parseErrorMessage(rawBody)
            val code = response.code()
            // 404/405 means try next login route candidate; 401/403/500 is an actual auth/server rejection
            lastError = IllegalStateException(formatLoginHttpError(code, serverMsg, endpoint))
            if (code != 404 && code != 405) {
                throw lastError
            }
        }

        throw lastError ?: IllegalStateException("Tidak dapat terhubung ke server REST API Jubelio.")
    }

    suspend fun fetchInventory(
        token: String,
        baseUrl: String = DEFAULT_JUBELIO_BASE_URL,
        preferRawAuth: Boolean = false
    ): Result<JubelioCallResult<List<JubelioRemoteProduct>>> = runCatching {
        val (normalizedBase, api) = apiFor(baseUrl)
        val paths = listOf("inventory/items", "inventory/items/", "inventory/")
        val (rawBody, usedRaw) = executeGetWithFallback(
            api = api,
            baseUrl = normalizedBase,
            paths = paths,
            token = token,
            preferRawAuth = preferRawAuth,
            actionLabel = "mengambil stok Jubelio"
        )
        JubelioCallResult(data = parseProducts(rawBody), usedRawAuthHeader = usedRaw)
    }

    suspend fun fetchSalesOrders(
        token: String,
        baseUrl: String = DEFAULT_JUBELIO_BASE_URL,
        preferRawAuth: Boolean = false
    ): Result<JubelioCallResult<List<JubelioRemoteOrder>>> = runCatching {
        val (normalizedBase, api) = apiFor(baseUrl)
        val paths = listOf("sales/orders", "sales/orders/", "sales/orders/completed/")
        val (rawBody, usedRaw) = executeGetWithFallback(
            api = api,
            baseUrl = normalizedBase,
            paths = paths,
            token = token,
            preferRawAuth = preferRawAuth,
            actionLabel = "mengambil order Jubelio"
        )
        JubelioCallResult(data = parseOrders(rawBody), usedRawAuthHeader = usedRaw)
    }

    suspend fun pushStockAdjustment(
        token: String,
        dateIso: String,
        note: String,
        items: List<JubelioStockAdjustmentItem>,
        baseUrl: String = DEFAULT_JUBELIO_BASE_URL,
        preferRawAuth: Boolean = false
    ): Result<Boolean> = runCatching {
        if (items.isEmpty()) return@runCatching preferRawAuth
        val (_, api) = apiFor(baseUrl)
        val req = JubelioAdjustmentRequest(
            transaction_date = dateIso,
            note = note,
            items = items
        )
        val modes = if (preferRawAuth) listOf(true, false) else listOf(false, true)
        var lastCode = 0
        var lastRaw = ""
        for (rawMode in modes) {
            val response = try {
                api.postStockAdjustment(authorization = formatAuthHeader(token, rawMode), request = req)
            } catch (e: IOException) {
                throw IllegalStateException(mapNetworkError(e, baseUrl), e)
            }
            if (response.isSuccessful) {
                return@runCatching rawMode
            }
            lastCode = response.code()
            lastRaw = response.errorBody()?.string().orEmpty()
            if (lastCode != 401) break
        }
        if (lastCode == 401) {
            throw JubelioTokenExpiredException("Token sesi Jubelio kedaluwarsa atau ditolak (HTTP 401). Silakan login ulang.")
        }
        val message = parseErrorMessage(lastRaw) ?: "Gagal kirim penyesuaian stok ke Jubelio (HTTP $lastCode)"
        throw IllegalStateException(message)
    }

    suspend fun pushOrderFulfillment(
        token: String,
        jubelioOrderId: Long,
        status: String,
        courier: String,
        trackingNumber: String,
        baseUrl: String = DEFAULT_JUBELIO_BASE_URL,
        preferRawAuth: Boolean = false
    ): Result<Boolean> = runCatching {
        val (_, api) = apiFor(baseUrl)
        val req = JubelioFulfillmentRequest(
            salesorder_id = jubelioOrderId,
            status = status,
            courier = courier,
            tracking_number = trackingNumber
        )
        val modes = if (preferRawAuth) listOf(true, false) else listOf(false, true)
        var lastCode = 0
        var lastRaw = ""
        for (rawMode in modes) {
            val response = try {
                api.updateOrderFulfillment(authorization = formatAuthHeader(token, rawMode), request = req)
            } catch (e: IOException) {
                throw IllegalStateException(mapNetworkError(e, baseUrl), e)
            }
            if (response.isSuccessful) {
                return@runCatching rawMode
            }
            lastCode = response.code()
            lastRaw = response.errorBody()?.string().orEmpty()
            if (lastCode != 401) break
        }
        if (lastCode == 401) {
            throw JubelioTokenExpiredException("Token sesi Jubelio kedaluwarsa atau ditolak (HTTP 401). Silakan login ulang.")
        }
        val message = parseErrorMessage(lastRaw) ?: "Gagal update status order Jubelio (HTTP $lastCode)"
        throw IllegalStateException(message)
    }

    private suspend fun executeGetWithFallback(
        api: JubelioRetrofitApi,
        baseUrl: String,
        paths: List<String>,
        token: String,
        preferRawAuth: Boolean,
        actionLabel: String
    ): Pair<String, Boolean> {
        val authModes = if (preferRawAuth) listOf(true, false) else listOf(false, true)
        var lastCode = 0
        var lastBody = ""

        for (path in paths) {
            val fullUrl = "$baseUrl$path"
            var got404 = false
            for (rawMode in authModes) {
                val response = try {
                    api.getAt(
                        url = fullUrl,
                        authorization = formatAuthHeader(token, rawMode)
                    )
                } catch (e: IOException) {
                    throw IllegalStateException(mapNetworkError(e, fullUrl), e)
                }

                val rawBody = response.body()?.string().orEmpty().ifBlank {
                    response.errorBody()?.string().orEmpty()
                }
                if (response.isSuccessful) {
                    return rawBody to rawMode
                }

                lastCode = response.code()
                lastBody = rawBody
                if (lastCode == 401) {
                    // Retry with alternate auth format (Bearer vs raw token)
                    continue
                }
                if (lastCode == 404 || lastCode == 405) {
                    got404 = true
                    break
                }
                val message = parseErrorMessage(rawBody) ?: "Gagal $actionLabel (HTTP $lastCode)"
                throw IllegalStateException(message)
            }
            if (!got404 && lastCode == 401) {
                throw JubelioTokenExpiredException(
                    "Token Jubelio ditolak server (HTTP 401). Silakan login ulang atau cek akses Open API."
                )
            }
        }

        if (lastCode == 401) {
            throw JubelioTokenExpiredException(
                "Token Jubelio ditolak server (HTTP 401). Silakan login ulang atau cek akses Open API."
            )
        }
        val message = parseErrorMessage(lastBody) ?: "Gagal $actionLabel (HTTP $lastCode)"
        throw IllegalStateException(message)
    }

    private fun formatAuthHeader(token: String, rawMode: Boolean): String {
        val cleaned = token.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        return if (rawMode) cleaned else "Bearer $cleaned"
    }

    private fun formatLoginHttpError(code: Int, serverMsg: String?, endpoint: String): String {
        val detail = serverMsg?.let { " ($it)" }.orEmpty()
        return when (code) {
            400 -> "Format permintaan login ditolak Jubelio (HTTP 400)$detail."
            401 -> "Email/password salah atau akun belum diizinkan akses REST API (HTTP 401)$detail."
            403 -> "Akses REST API diblokir untuk akun ini (HTTP 403). Pastikan fitur Open API aktif di akun Jubelio Anda$detail."
            404 -> "Endpoint login tidak ditemukan di $endpoint (HTTP 404). Coba ganti Base URL API."
            429 -> "Terlalu banyak percobaan login ke Jubelio (HTTP 429). Tunggu sebentar lalu coba lagi."
            in 500..599 -> {
                val hint = if (serverMsg?.contains("invalid", ignoreCase = true) == true ||
                    serverMsg?.contains("password", ignoreCase = true) == true ||
                    serverMsg.isNullOrBlank()
                ) {
                    "Email/password tidak cocok atau akun belum terdaftar untuk Open API Jubelio"
                } else {
                    serverMsg
                }
                "Login Jubelio ditolak server (HTTP $code): $hint."
            }
            else -> "Login Jubelio gagal (HTTP $code)$detail."
        }
    }

    private fun mapNetworkError(e: IOException, target: String): String = when (e) {
        is UnknownHostException ->
            "Tidak dapat menjangkau server Jubelio ($target). Periksa koneksi internet/DNS HP Anda atau Base URL."
        is SocketTimeoutException ->
            "Koneksi ke server Jubelio timeout. Periksa sinyal internet atau coba lagi."
        is SSLException ->
            "Koneksi aman HTTPS/SSL ke Jubelio gagal pada perangkat ini (${e.localizedMessage ?: "SSL error"})."
        else ->
            "Gagal terhubung ke jaringan Jubelio: ${e.localizedMessage ?: e.javaClass.simpleName}"
    }

    private fun parseErrorMessage(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            obj.optString("message")
                .ifBlank { obj.optString("error_description") }
                .ifBlank { obj.optString("error") }
                .ifBlank { obj.optString("msg") }
                .ifBlank { null }
        }.getOrNull() ?: raw.trim().take(180).takeIf { !it.startsWith("<") }
    }

    private fun extractArray(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return JSONArray()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val obj = JSONObject(trimmed)
        return obj.optJSONArray("data")
            ?: obj.optJSONArray("items")
            ?: obj.optJSONArray("rows")
            ?: obj.optJSONObject("data")?.optJSONArray("items")
            ?: obj.optJSONObject("data")?.optJSONArray("rows")
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

fun normalizeJubelioBaseUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return DEFAULT_JUBELIO_BASE_URL
    val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
}

fun extractJubelioToken(rawBody: String, headerAuth: String? = null): String? {
    val trimmed = rawBody.trim()
    if (trimmed.startsWith("{")) {
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        if (json != null) {
            val dataObj = json.optJSONObject("data")
            val resultObj = json.optJSONObject("result")
            val candidate = json.optString("token")
                .ifBlank { json.optString("access_token") }
                .ifBlank { json.optString("jwt") }
                .ifBlank { json.optString("id_token") }
                .ifBlank { dataObj?.optString("token").orEmpty() }
                .ifBlank { dataObj?.optString("access_token").orEmpty() }
                .ifBlank { resultObj?.optString("token").orEmpty() }
            if (candidate.isNotBlank()) {
                return candidate.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
            }
        }
    } else if (trimmed.isNotBlank() && !trimmed.startsWith("<")) {
        val unquoted = trimmed.removeSurrounding("\"").trim()
        if (unquoted.length >= 16 && !unquoted.contains(" ")) {
            return unquoted.removePrefix("Bearer ").removePrefix("bearer ").trim()
        }
    }
    val fromHeader = headerAuth?.trim()?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
    return fromHeader?.takeIf { it.isNotBlank() }
}

class JubelioTokenExpiredException(message: String) : IllegalStateException(message)
