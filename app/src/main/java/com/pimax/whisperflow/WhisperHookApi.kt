package com.pimax.whisperflow

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WhisperHookApi - Generic webhook client for PiMax services
 *
 * Sends transcribed text to any Pi service that implements the standard
 * /api/whisper-hook endpoint. Each service interprets the text and takes
 * appropriate action (create task, complete habit, save card, etc.)
 *
 * Standard Request:
 * {
 *   "text": "the transcribed text",
 *   "source": "whisper",
 *   "timestamp": "2026-03-18T16:30:00Z"
 * }
 *
 * Standard Response:
 * {
 *   "success": true,
 *   "action": "completed_habit" | "created_task" | "status" | "ignored",
 *   "message": "Human-readable result for toast",
 *   "data": { ... service-specific ... }
 * }
 */
class WhisperHookApi(
    private val baseUrl: String,
    private val serviceName: String = "Unknown"
) {
    companion object {
        private const val TAG = "🔗 WhisperHook"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    data class HookResult(
        val success: Boolean,
        val action: String,      // "completed_habit", "created_task", "status", "ignored", "error"
        val message: String,     // Human-readable message for toast
        val serviceName: String, // Which service this result is from
        val data: JSONObject? = null
    ) {
        companion object {
            fun error(serviceName: String, error: String) = HookResult(
                success = false,
                action = "error",
                message = error,
                serviceName = serviceName,
                data = null
            )

            fun ignored(serviceName: String, message: String = "Ignored") = HookResult(
                success = false,
                action = "ignored",
                message = message,
                serviceName = serviceName,
                data = null
            )
        }
    }

    /**
     * Send transcribed text to the service's whisper-hook endpoint
     */
    suspend fun send(text: String): HookResult = withContext(Dispatchers.IO) {
        try {
            val trimmedText = text.trim()
            if (trimmedText.isEmpty()) {
                return@withContext HookResult.ignored(serviceName, "Empty text")
            }

            // Build request JSON
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val json = JSONObject().apply {
                put("text", trimmedText)
                put("source", "whisper")
                put("timestamp", timestamp)
            }

            Log.d(TAG, "📤 [$serviceName] Sending: ${trimmedText.take(50)}...")

            val url = URL("$baseUrl/api/whisper-hook")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            try {
                // Write request body
                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(json.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val responseBody = if (responseCode in 200..299) {
                    BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
                } else {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream, "UTF-8")).use { it.readText() }
                }

                val responseJson = JSONObject(responseBody)
                val success = responseJson.optBoolean("success", false)
                val action = responseJson.optString("action", "unknown")
                val message = responseJson.optString("message", "No message")
                val data = responseJson.optJSONObject("data")

                Log.d(TAG, "📥 [$serviceName] Response: action=$action, message=${message.take(50)}")

                return@withContext HookResult(
                    success = success,
                    action = action,
                    message = message,
                    serviceName = serviceName,
                    data = data
                )
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [$serviceName] Error: ${e.message}", e)
            return@withContext HookResult.error(serviceName, e.message ?: "Connection failed")
        }
    }

    /**
     * Check if the service is reachable (uses /api/health endpoint)
     */
    suspend fun checkConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/health")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
            }

            try {
                val responseCode = connection.responseCode
                val success = responseCode == 200
                Log.d(TAG, "🔍 [$serviceName] Health: ${if (success) "online" else "offline (HTTP $responseCode)"}")
                return@withContext success
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "🔍 [$serviceName] Unreachable: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Get service info for display
     */
    fun getServiceInfo() = mapOf(
        "name" to serviceName,
        "url" to baseUrl,
        "hookEndpoint" to "$baseUrl/api/whisper-hook",
        "healthEndpoint" to "$baseUrl/api/health"
    )
}
