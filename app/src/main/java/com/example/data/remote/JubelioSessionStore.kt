package com.example.data.remote

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.jubelioDataStore by preferencesDataStore(name = "jubelio_session_prefs")

const val DEFAULT_JUBELIO_BASE_URL = "https://api2.jubelio.com/"
const val ALT_JUBELIO_BASE_URL = "https://api.jubelio.com/"

enum class SyncConflictPolicy(val label: String) {
    WAREHOUSE_PRIORITY("Prioritas Stok Gudang"),
    JUBELIO_PRIORITY("Prioritas Stok Jubelio")
}

data class JubelioSessionState(
    val email: String = "",
    val token: String = "",
    val baseUrl: String = DEFAULT_JUBELIO_BASE_URL,
    val useRawAuthHeader: Boolean = false,
    val tokenAcquiredAtMillis: Long = 0L,
    val autoSyncEnabled: Boolean = true,
    val conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.WAREHOUSE_PRIORITY,
    val lastSyncMessage: String = "Belum ada sinkronisasi Jubelio",
    val lastErrorMessage: String = ""
) {
    val isConnected: Boolean
        get() = token.isNotBlank()

    val isTokenLikelyExpired: Boolean
        get() {
            if (token.isBlank() || tokenAcquiredAtMillis <= 0L) return false
            val twelveHoursMs = 12L * 60L * 60L * 1000L
            return (System.currentTimeMillis() - tokenAcquiredAtMillis) >= twelveHoursMs
        }
}

class JubelioSessionStore(private val context: Context) {
    private val emailKey = stringPreferencesKey("jubelio_email")
    private val tokenKey = stringPreferencesKey("jubelio_token")
    private val baseUrlKey = stringPreferencesKey("jubelio_base_url")
    private val rawAuthKey = booleanPreferencesKey("jubelio_raw_auth")
    private val acquiredAtKey = longPreferencesKey("jubelio_token_acquired_at")
    private val autoSyncKey = booleanPreferencesKey("jubelio_auto_sync")
    private val policyKey = stringPreferencesKey("jubelio_conflict_policy")
    private val lastSyncMsgKey = stringPreferencesKey("jubelio_last_sync_msg")
    private val lastErrorMsgKey = stringPreferencesKey("jubelio_last_error_msg")

    val sessionFlow: Flow<JubelioSessionState> = context.jubelioDataStore.data.map { prefs ->
        val policyName = prefs[policyKey] ?: SyncConflictPolicy.WAREHOUSE_PRIORITY.name
        JubelioSessionState(
            email = prefs[emailKey].orEmpty(),
            token = prefs[tokenKey].orEmpty(),
            baseUrl = prefs[baseUrlKey].orEmpty().ifBlank { DEFAULT_JUBELIO_BASE_URL },
            useRawAuthHeader = prefs[rawAuthKey] ?: false,
            tokenAcquiredAtMillis = prefs[acquiredAtKey] ?: 0L,
            autoSyncEnabled = prefs[autoSyncKey] ?: true,
            conflictPolicy = runCatching { SyncConflictPolicy.valueOf(policyName) }
                .getOrDefault(SyncConflictPolicy.WAREHOUSE_PRIORITY),
            lastSyncMessage = prefs[lastSyncMsgKey] ?: "Belum ada sinkronisasi Jubelio",
            lastErrorMessage = prefs[lastErrorMsgKey].orEmpty()
        )
    }

    suspend fun saveSession(
        email: String,
        token: String,
        baseUrl: String = DEFAULT_JUBELIO_BASE_URL,
        useRawAuthHeader: Boolean = false
    ) {
        context.jubelioDataStore.edit { prefs ->
            prefs[emailKey] = email
            prefs[tokenKey] = token
            prefs[baseUrlKey] = baseUrl
            prefs[rawAuthKey] = useRawAuthHeader
            prefs[acquiredAtKey] = System.currentTimeMillis()
            prefs[lastSyncMsgKey] = "Terhubung ke Jubelio ($email)"
            prefs[lastErrorMsgKey] = ""
        }
    }

    suspend fun updateAuthHeaderMode(useRawAuthHeader: Boolean) {
        context.jubelioDataStore.edit { prefs ->
            prefs[rawAuthKey] = useRawAuthHeader
        }
    }

    suspend fun updateSyncPreferences(
        autoSyncEnabled: Boolean,
        conflictPolicy: SyncConflictPolicy
    ) {
        context.jubelioDataStore.edit { prefs ->
            prefs[autoSyncKey] = autoSyncEnabled
            prefs[policyKey] = conflictPolicy.name
        }
    }

    suspend fun updateLastSyncMessage(message: String) {
        context.jubelioDataStore.edit { prefs ->
            prefs[lastSyncMsgKey] = message
            prefs[lastErrorMsgKey] = ""
        }
    }

    suspend fun recordError(errorMessage: String) {
        context.jubelioDataStore.edit { prefs ->
            prefs[lastErrorMsgKey] = errorMessage
            prefs[lastSyncMsgKey] = errorMessage
        }
    }

    suspend fun clearSession() {
        context.jubelioDataStore.edit { prefs ->
            prefs.remove(tokenKey)
            prefs.remove(acquiredAtKey)
            prefs[lastSyncMsgKey] = "Sesi Jubelio terputus"
            prefs[lastErrorMsgKey] = ""
        }
    }
}
