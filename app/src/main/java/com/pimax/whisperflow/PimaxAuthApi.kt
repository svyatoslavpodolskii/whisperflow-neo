package com.pimax.whisperflow

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * PimaxAuthApi — Identity client for pimax-auth service.
 *
 * Calls GET /api/whoami to resolve the current Tailscale IP → registered user.
 * The auth service lives on the same Pi host as Tasks/Streak, port 8100.
 */
class PimaxAuthApi(private val authUrl: String) {
    companion object {
        private const val TAG = "🔐 PimaxAuth"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
    }

    data class WhoAmIResult(
        val authenticated: Boolean,
        val id: String = "",
        val name: String = "",
        val email: String = "",
        val role: String = "",
        val error: String? = null
    )

    /**
     * Resolve the calling device's Tailscale IP to a registered pimax user.
     * Returns unauthenticated result on any network error.
     */
    suspend fun whoami(): WhoAmIResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$authUrl/api/whoami")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            try {
                val responseCode = connection.responseCode
                val body = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
                val json = JSONObject(body)
                val authenticated = json.optBoolean("authenticated", false)
                Log.d(TAG, "✅ whoami: authenticated=$authenticated, id=${json.optString("id")}")
                return@withContext WhoAmIResult(
                    authenticated = authenticated,
                    id = json.optString("id", ""),
                    name = json.optString("name", ""),
                    email = json.optString("email", ""),
                    role = json.optString("role", "")
                )
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ whoami failed: ${e.message}")
            return@withContext WhoAmIResult(authenticated = false, error = e.message ?: "Connection failed")
        }
    }
}
