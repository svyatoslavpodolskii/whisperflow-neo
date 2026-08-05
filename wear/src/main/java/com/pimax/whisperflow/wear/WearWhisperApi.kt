package com.pimax.whisperflow.wear

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private const val TAG = "🌐 WearWhisperApi"

/**
 * Direct GPU server API client for the watch.
 * Bypasses phone relay and calls the Whisper GPU server directly.
 *
 * This is useful when:
 * - Phone relay isn't working (emulator pairing issues)
 * - Watch has direct WiFi access to the server
 * - Lower latency is desired
 */
class WearWhisperApi(
    private val serverUrl: String = DEFAULT_SERVER_URL
) {
    companion object {
        const val DEFAULT_SERVER_URL = ""

        // Hallucination phrases to filter out
        private val HALLUCINATION_PATTERNS = listOf(
            "thank you",
            "thanks for watching",
            "please subscribe",
            "like and subscribe",
            "see you next time",
            "bye bye",
            "goodbye",
            "subtitles by",
            "translated by",
            "captioned by",
            "music",
            "applause",
            "laughter",
            "silence",
            "you"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe WAV audio bytes directly via the GPU server.
     *
     * @param wavBytes Complete WAV file as byte array
     * @param language Language code ("Auto", "English", "German", etc.)
     * @param task "transcribe" or "translate"
     * @return TranscriptionResponse with text on success
     */
    suspend fun transcribe(
        wavBytes: ByteArray,
        language: String = "Auto",
        task: String = "transcribe"
    ): TranscriptionResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Transcribing ${wavBytes.size} bytes → $serverUrl")

            // Step 1: Upload the audio file
            val uploadedPath = uploadAudio(wavBytes)
            if (uploadedPath == null) {
                return@withContext TranscriptionResponse.error("Upload failed")
            }
            Log.d(TAG, "Uploaded → $uploadedPath")

            // Step 2: Call the transcription API
            val eventId = callTranscribe(uploadedPath, task, language)
            if (eventId == null) {
                return@withContext TranscriptionResponse.error("API call failed")
            }
            Log.d(TAG, "Event ID → $eventId")

            // Step 3: Poll for result via SSE
            val text = pollResult(eventId)
            if (text == null) {
                return@withContext TranscriptionResponse.error("No result received")
            }

            // Step 4: Filter hallucinations
            val filteredText = filterHallucinations(text)
            if (filteredText.isBlank()) {
                Log.w(TAG, "Filtered as hallucination: $text")
                return@withContext TranscriptionResponse.error("No speech detected")
            }

            Log.d(TAG, "✅ Transcribed: ${filteredText.take(100)}")
            TranscriptionResponse.success(filteredText)

        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}", e)
            TranscriptionResponse.error(e.message ?: "Unknown error")
        }
    }

    /**
     * Check if the GPU server is reachable and healthy.
     */
    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/")
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed: ${e.message}")
            false
        }
    }

    // ── Internal Methods ────────────────────────────────────────────────────────

    private fun uploadAudio(wavBytes: ByteArray): String? {
        val filename = "watch_audio_${System.currentTimeMillis()}.wav"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files",
                filename,
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$serverUrl/gradio_api/upload")
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Upload failed: ${response.code}")
                    return null
                }

                val body = response.body?.string() ?: return null
                Log.d(TAG, "Upload response: $body")

                // Response is a JSON array of uploaded file paths
                val jsonArray = JSONArray(body)
                if (jsonArray.length() > 0) {
                    jsonArray.getString(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            null
        }
    }

    private fun callTranscribe(filePath: String, task: String, language: String): String? {
        // Build Gradio 6.x API payload
        val fileData = JSONObject().apply {
            put("path", filePath)
            put("meta", JSONObject().put("_type", "gradio.FileData"))
            put("orig_name", filePath.substringAfterLast("/"))
        }

        val payload = JSONObject().apply {
            put("data", JSONArray().apply {
                put(fileData)
                put(task)
                put(language)
            })
        }

        val request = Request.Builder()
            .url("$serverUrl/gradio_api/call/transcribe")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Call failed: ${response.code}")
                    return null
                }

                val body = response.body?.string() ?: return null
                Log.d(TAG, "Call response: $body")

                val json = JSONObject(body)
                json.optString("event_id", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call error: ${e.message}", e)
            null
        }
    }

    private fun pollResult(eventId: String): String? {
        val request = Request.Builder()
            .url("$serverUrl/gradio_api/call/transcribe/$eventId")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Poll failed: ${response.code}")
                    return null
                }

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
                var transcriptionText: String? = null

                // Parse SSE stream
                reader.forEachLine { line ->
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        try {
                            val jsonArray = JSONArray(data)
                            if (jsonArray.length() > 0) {
                                transcriptionText = jsonArray.optString(0, null)
                            }
                        } catch (_: Exception) {
                            // Not valid JSON, skip
                        }
                    }
                }

                transcriptionText
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll error: ${e.message}", e)
            null
        }
    }

    private fun filterHallucinations(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // Check if the entire text is a hallucination pattern
        for (pattern in HALLUCINATION_PATTERNS) {
            if (lower == pattern ||
                lower == "$pattern." ||
                lower == "$pattern!" ||
                lower.matches(Regex("^$pattern[.!?,]*$"))) {
                return ""
            }
        }

        // Check for very short text that's likely noise
        if (trimmed.length < 3) {
            return ""
        }

        // Check for text that's just punctuation or whitespace
        if (trimmed.replace(Regex("[\\s.,!?;:'\"()-]"), "").isEmpty()) {
            return ""
        }

        return trimmed
    }
}
