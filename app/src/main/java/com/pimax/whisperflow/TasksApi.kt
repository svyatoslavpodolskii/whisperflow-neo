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

/**
 * TasksApi - Client for PiMax Tasks service
 *
 * Sends transcribed text to the Tasks webapp running on the Pi.
 * Default URL: configurable via SettingsDataStore
 */
class TasksApi(
    private val baseUrl: String = DEFAULT_TASKS_URL
) {
    companion object {
        private const val TAG = "📋 TasksApi"
        const val DEFAULT_TASKS_URL = ""
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    data class TaskResult(
        val success: Boolean,
        val taskId: Int? = null,
        val categoryName: String? = null,
        val error: String? = null
    )

    /**
     * Create a new task from transcribed text
     * @param title The task title (transcribed text)
     * @param source Source identifier (default: "whisper")
     */
    suspend fun createTask(title: String, source: String = "whisper"): TaskResult = withContext(Dispatchers.IO) {
        try {
            val trimmedTitle = title.trim()
            if (trimmedTitle.isEmpty()) {
                Log.w(TAG, "⚠️ Empty title, skipping task creation")
                return@withContext TaskResult(success = false, error = "Empty title")
            }

            val json = JSONObject().apply {
                put("title", trimmedTitle)
                put("source", source)
            }

            Log.d(TAG, "📤 Creating task: ${trimmedTitle.take(50)}...")

            val url = URL("$baseUrl/api/tasks")
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
                    BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                        reader.readText()
                    }
                } else {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream, "UTF-8")).use { reader ->
                        reader.readText()
                    }
                }

                if (responseCode in 200..299) {
                    val responseJson = JSONObject(responseBody)
                    if (responseJson.optBoolean("success", false)) {
                        val task = responseJson.optJSONObject("task")
                            val taskId = task?.optInt("id")
                            val categoryName = task?.optString("category_name")
                            Log.d(TAG, "✅ Task created: id=$taskId, category=$categoryName")
                            return@withContext TaskResult(success = true, taskId = taskId, categoryName = categoryName)
                    } else {
                        val error = responseJson.optString("error", "Unknown error")
                        Log.e(TAG, "❌ Task creation failed: $error")
                        return@withContext TaskResult(success = false, error = error)
                    }
                } else {
                    Log.e(TAG, "❌ HTTP $responseCode: $responseBody")
                    return@withContext TaskResult(success = false, error = "HTTP $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Task creation error: ${e.message}", e)
            return@withContext TaskResult(success = false, error = e.message ?: "Network error")
        }
    }

    /**
     * Check if the Tasks service is reachable
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
                Log.d(TAG, "🔍 Tasks service health: ${if (success) "online" else "offline (HTTP $responseCode)"}")
                return@withContext success
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "🔍 Tasks service unreachable: ${e.message}")
            return@withContext false
        }
    }
}
